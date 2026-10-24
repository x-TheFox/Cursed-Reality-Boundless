"""Extract presentation timelines ("move scripts") from JJA's move mcfunctions.

A JJA move is a tick-indexed function: every line is gated on a countdown objective
(`scores={move=N}` or a range `move=a..b`). The countdown starts at the move's duration D and
decreases by one per tick, so a line keyed on value v runs at elapsed tick `D - v`.

For every timeline function this writes `assets/crb/move_scripts/<folder>/<name>.json`:

    {
      "source": "limitless/limitless5",
      "key": "move",                 # countdown objective
      "duration": 101,               # D (from movesets.json when known, else max key + 1)
      "cues": [ {"t": 0, "until": 0, "type": "anim", "anim": "animation.imaginary_purple", ...}, ... ]
    }

Cue types: anim, sound, stopsound, particle, camera_set, camera_fade, camera_clear, shake,
impact (full-screen impact frame by glyph codepoint), impact_clear, effect. Positions are a list of
ops applied from the caster's feet: {"anchor": "eyes"}, {"local": [x, y, z]} (^ coordinates),
{"rel": [x, y, z]} (~ coordinates). Selector conditions other than the timeline key are kept in
"cond" (e.g. "tag=sixeyes", "score:chance=29") for the Java side to evaluate.

Only commands whose execution context is the caster are extracted; lines that run as or at
other entities (projectiles, victims) belong to the Java move logic.

Run:  python3 tools/jja/timelines.py
"""
from __future__ import annotations

import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import BP, REPO, RESEARCH, load_json  # noqa: E402

FN = BP / "functions"
OUT = REPO / "src" / "main" / "resources" / "assets" / "crb" / "move_scripts"
TIMELINE_KEYS = ("move", "domain", "domainstart", "cutscene", "sukunacutscene", "blackflashing", "scene", "Dash", "Evasive")
IGNORED_SCORE_CONDS = {"Stun=0", "Disabled=0", "Disabled2=0", "nocts=0"}
IGNORED_TAG_CONDS = {"tag=!impactframesoff", "tag=!arenaboss"}

STATS = Counter()
SIM_TYPES = {"hit", "stun", "impulse", "hover", "self_move", "move_toward", "proj_tp", "summon", "shoot", "proj_deleter",
             "proj_kill", "self_deleter", "carve", "counter", "lock", "hold"}


# ---------------------------------------------------------------------------------------------
# tokenising

def tokenize(line: str) -> list[str]:
    """Split a command into tokens, keeping selectors, JSON and quoted strings intact."""
    toks, i, n = [], 0, len(line)
    while i < n:
        c = line[i]
        if c.isspace():
            i += 1
            continue
        start = i
        depth = 0
        in_str = False
        while i < n:
            ch = line[i]
            if in_str:
                if ch == "\\":
                    i += 2
                    continue
                if ch == '"':
                    in_str = False
            elif ch == '"':
                in_str = True
            elif ch in "[{":
                depth += 1
            elif ch in "]}":
                depth -= 1
            elif ch.isspace() and depth <= 0:
                break
            i += 1
        toks.append(line[start:i])
    return toks


_COORD = re.compile(r"([~^])(-?\d*\.?\d*)")


def split_coords(tokens: list[str], i: int):
    """Read a Bedrock position starting at tokens[i]; handles '^^^1' and '~ ~2 ~'.
    Returns (kind, [x, y, z], next_index) or (None, None, i) if no position is there."""
    parts: list[tuple[str, float]] = []
    j = i
    while j < len(tokens) and len(parts) < 3:
        t = tokens[j]
        if t and t[0] in "~^":
            for m in _COORD.finditer(t):
                parts.append((m.group(1), float(m.group(2)) if m.group(2) not in ("", "-", ".") else 0.0))
            j += 1
        elif re.fullmatch(r"-?\d+(\.\d+)?", t) and parts == [] and False:
            break
        else:
            break
    if len(parts) != 3:
        return None, None, i
    kinds = {k for k, _ in parts}
    if kinds == {"^"}:
        return "local", [v for _, v in parts], j
    if kinds == {"~"}:
        return "rel", [v for _, v in parts], j
    return "mixed", parts, j


def parse_selector(tok: str):
    """'@s[scores={move=5..9},tag=!x,type=player]' -> ('s', {'scores': {...}, 'tags': [...], 'other': [...]})."""
    m = re.match(r"@([sapre])(?:\[(.*)\])?$", tok)
    if not m:
        return None, None
    kind, body = m.group(1), m.group(2) or ""
    args = {"scores": {}, "tags": [], "other": [], "r": None}
    depth, cur, parts = 0, "", []
    for ch in body:
        if ch in "{[":
            depth += 1
        elif ch in "}]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur:
        parts.append(cur)
    for p in parts:
        p = p.strip()
        if p.startswith("scores="):
            for kv in p[len("scores={"):-1].split(","):
                if "=" in kv:
                    k, v = kv.split("=", 1)
                    args["scores"][k.strip()] = v.strip()
        elif p.startswith("tag="):
            args["tags"].append(p)
        elif p.startswith("r="):
            try:
                args["r"] = float(p[2:])
            except ValueError:
                args["other"].append(p)
        else:
            args["other"].append(p)
    return kind, args


# ---------------------------------------------------------------------------------------------
# timeline gating

def parse_range(v: str):
    m = re.fullmatch(r"(-?\d+)?(\.\.)?(-?\d+)?", v)
    if not m:
        return None
    lo, dots, hi = m.group(1), m.group(2), m.group(3)
    if not dots:
        return (int(lo), int(lo)) if lo is not None else None
    return (int(lo) if lo is not None else None, int(hi) if hi is not None else None)


class Context:
    def __init__(self):
        self.gate = None          # (lo, hi) on the timeline key
        self.cond: list[str] = []
        self.pos: list[dict] = []
        self.self_ctx = True       # executing as / at the caster
        self.facing = None
        self.runner = False        # extracting a projectile's own tick function
        self.victims = None        # `as @e[r=R,...]` from the caster: who a later `damage @s` hits
        self.pending_anchor = None # `as @e[...]`: becomes the position anchor at the next `at @s`
        self.anchor = None         # presentation runs at another entity (projectile, victim)


def _intersect(a, b):
    lows = [x for x in (a[0], b[0]) if x is not None]
    highs = [x for x in (a[1], b[1]) if x is not None]
    return (max(lows) if lows else None, min(highs) if highs else None)


def absorb_selector(ctx: Context, sel_kind, args, key: str, role: str):
    """Fold a selector into the context. role: 'as', 'at', 'target'."""
    if args is None:
        return
    if sel_kind != "s":
        if role in ("as", "at"):
            ctx.self_ctx = False
        return
    for k, v in args["scores"].items():
        if k == key:
            r = parse_range(v)
            if r:
                ctx.gate = r if ctx.gate is None else _intersect(ctx.gate, r)
        else:
            c = f"{k}={v}"
            if c not in IGNORED_SCORE_CONDS:
                ctx.cond.append("score:" + c)
    for t in args["tags"]:
        if t not in IGNORED_TAG_CONDS:
            ctx.cond.append(t)
    for o in args["other"]:
        if o.startswith("type=") or o.startswith("c=") or o.startswith("m="):
            if o in ("type=!player",):
                ctx.cond.append(o)
            continue
        if o.startswith("family=") or o.startswith("hasitem=") or o.startswith("name="):
            ctx.cond.append(o)


def scope_of(tok: str):
    """Audience/target of an effect: 'self', 'radius:N', 'all', or None (other entity)."""
    kind, args = parse_selector(tok)
    if kind is None:
        return None
    if kind == "s":
        return "self"
    if kind in ("a", "p", "e"):
        if args and any(o.startswith(("name=", "type=", "family=")) for o in args["other"]) and kind != "a":
            return "other:" + tok[:80]
        if args and args["r"] is not None:
            return f"radius:{args['r']:g}"
        if kind == "a" and args and not args["tags"] and not args["scores"]:
            return "all"
        if kind == "p":
            return "self"
        return "other:" + tok[:80]
    return None


# ---------------------------------------------------------------------------------------------
# command parsing

def glyph_of(text: str):
    for ch in text:
        if 0xE000 <= ord(ch) < 0xF900:
            return f"{ord(ch):04X}"
    return None


def audience_filters(tok: str):
    """Tag/score filters on a non-@s audience selector, e.g. @a[r=33,tag=limitless] -> ['tag=limitless']."""
    kind, args = parse_selector(tok)
    if kind in (None, "s") or args is None:
        return []
    out = list(args["tags"]) + [f"score:{k}={v}" for k, v in args["scores"].items()]
    return [c for c in out if c not in IGNORED_TAG_CONDS]


def parse_command(toks: list[str], ctx: Context, key: str):
    """Returns a cue dict (without lo/hi) or None."""
    if not toks:
        return None
    if (not ctx.runner and toks[0] == "scoreboard" and len(toks) >= 6 and toks[1] == "players"
            and toks[2] in ("set", "add", "remove") and toks[3].startswith("@s") and toks[4] == key):
        try:
            # JJA moves jump their own countdown: holds, re-press launches, early exits
            return {"type": "counter", "op": toks[2], "value": int(toks[5]), "at": list(ctx.pos)}
        except ValueError:
            return None
    cue = _parse_command(toks, ctx, key)
    if cue is None:
        cue = _parse_sim(toks, ctx)
    if cue is not None:
        sel = next((t for t in toks[1:4] if t.startswith("@") and not t.startswith("@s")), None)
        if sel:
            f = audience_filters(sel)
            if f:
                cue["audience_cond"] = f
    return cue


SELF_IMPULSES = {
    # Bedrock applyKnockback(horizontal strength along view, vertical) from index.js
    "ds:leapforwards": (7, 2), "ds:leapforwards2": (3, 0.85), "ds:leapforwards3": (3, 0.8),
    "ds:upslightly2": (0, 0.25), "ds:dasheast": ("side", -9), "ds:dashwest": ("side", 9),
}


def target_radius(tok: str):
    """Radius of a non-self target selector (@e[r=..] / @a[r=..]); None if it targets self or has no radius."""
    kind, args = parse_selector(tok)
    if kind in (None, "s") or args is None:
        return None
    return args["r"] if args["r"] is not None else None


# JJA's terrain carving functions: structure loads of an air sphere -> (radius, centre offset)
CARVES = {"2x2sphere": (1.5, 0.0), "4x4sphere": (4.5, 0.5), "23x23sphere": (11.5, 11.5), "32x32sphere": (16.0, 16.0)}


def _parse_sim(toks: list[str], ctx: Context):
    """Simulation cues: radius hits, stun, strength bonus, caster impulses and self teleports."""
    cmd = toks[0]
    if cmd == "function" and len(toks) >= 2 and toks[1] in CARVES:
        r, off = CARVES[toks[1]]
        at = list(ctx.pos) + ([{"rel": [off, off, off]}] if off else [])
        return {"type": "carve", "radius": r, "at": at}
    if cmd == "damage" and len(toks) >= 3:
        r = target_radius(toks[1])
        if r is None:
            return None
        try:
            amount = float(toks[2])
        except ValueError:
            return None
        return {"type": "hit", "amount": amount, "radius": r, "at": list(ctx.pos)}
    if cmd == "scoreboard" and len(toks) >= 6 and toks[1] == "players" and toks[2] == "set" and toks[4] in ("Move", "Camera") \
            and toks[3].startswith("@s"):
        try:
            # JJA: inputpermission movement/camera disabled while the score is 3+
            return {"type": "lock", "what": toks[4].lower(), "ticks": int(toks[5])}
        except ValueError:
            return None
    if cmd == "scoreboard" and len(toks) >= 6 and toks[1] == "players" and toks[2] == "set" and toks[4] == "Deleter" \
            and toks[3].startswith("@s"):
        try:
            return {"type": "self_deleter", "value": int(toks[5])}
        except ValueError:
            return None
    if cmd == "scoreboard" and len(toks) >= 6 and toks[1] == "players" and toks[2] == "set" and toks[4] == "Deleter":
        name = proj_name(toks[3])
        if name:
            try:
                return {"type": "proj_deleter", "name": name, "value": int(toks[5])}
            except ValueError:
                return None
    if cmd == "scoreboard" and len(toks) >= 6 and toks[1] == "players":
        op, sel, obj = toks[2], toks[3], toks[4]
        r = target_radius(sel)
        if r is None:
            return None
        if obj == "damage" and op == "add":
            try:
                return {"type": "hit", "amount": float(toks[5]), "radius": r, "at": list(ctx.pos)}
            except ValueError:
                return None
        if obj == "damage" and op == "operation" and toks[5] == "+=" and len(toks) >= 8 and toks[7] == "damageadd":
            return {"type": "hit", "amount": 0, "strength": True, "radius": r, "at": list(ctx.pos)}
        if obj == "Stun" and op in ("add", "set"):
            try:
                return {"type": "stun", "ticks": int(float(toks[5])), "radius": r, "at": list(ctx.pos)}
            except ValueError:
                return None
        return None
    if cmd == "scriptevent" and len(toks) >= 4 and toks[1].lower() == "ds:shootprojectile":
        cue = {"type": "shoot", "entity": toks[2].lower(), "name": toks[3], "velocity": 3.0, "deleter": None, "at": list(ctx.pos)}
        for i, a in enumerate(toks):
            if a.startswith("velocity:"):
                try: cue["velocity"] = float(a.split(":")[1])
                except ValueError: pass
            if a == "scores:" and i + 2 < len(toks) and toks[i + 1] == "Deleter":
                try: cue["deleter"] = int(toks[i + 2])
                except ValueError: pass
            # JJA's parser only knows "scores: Deleter N"; "scores:Deleter N" sets nothing
            if a == "nearestTypeApply":
                cue["existing"] = True  # launch the nearest projectile with this name instead of spawning
        return cue
    if cmd == "scriptevent" and len(toks) >= 2:
        ev = toks[1].lower()
        if ev == "ds:knockback":
            nums = [float(x) for x in toks[2:4] if re.fullmatch(r"-?\d*\.?\d+", x)]
            return {"type": "impulse", "forward": nums[0] if nums else 0, "up": nums[1] if len(nums) > 1 else 0}
        if ev in SELF_IMPULSES:
            a, b = SELF_IMPULSES[ev]
            if a == "side":
                return {"type": "impulse", "side": b, "forward": 0, "up": 0}
            return {"type": "impulse", "forward": a, "up": b}
        if ev == "ds:antiair":
            return {"type": "hover"}
        if ev == "ds:downfall":
            return {"type": "impulse", "forward": 0, "up": -1.5}
        return None
    if cmd == "tp" and len(toks) >= 3 and toks[1].startswith("@s") and toks[2].startswith("@s") and not ctx.runner:
        return {"type": "hold"}  # `tp @s @s`: pinned in place
    if cmd == "tp" and len(toks) >= 3 and toks[1].startswith("@s") and (len(toks) <= 4 or toks[3] in ("facing", "true", "false")):
        k, xyz, j = split_coords(toks, 2)
        if k == "local" and any(abs(v) > 1e-6 for v in xyz):
            return {"type": "self_move", "local": xyz}
        if k == "rel" and any(abs(v) > 1e-6 for v in xyz):
            return {"type": "self_move", "rel": xyz}
        if k == "rel" and not ctx.runner:
            return {"type": "hold"}  # `tp @s ~~~` every tick: no falling, no drifting
    if cmd == "tp" and len(toks) >= 5 and toks[1].startswith("@s") and toks[3] == "facing" and toks[4].startswith("@e"):
        # tp @s ^^^0.111 facing @e[name=ImagineTRG,...]: step toward a named projectile
        k, xyz, j = split_coords(toks, 2)
        target = proj_name(toks[4])
        if k == "local" and target:
            return {"type": "move_toward", "target": target, "step": xyz[2]}
    if cmd == "tp" and len(toks) >= 3 and toks[1].startswith("@e"):
        name = proj_name(toks[1])
        if name:
            k, xyz, j = split_coords(toks, 2)
            if k in ("local", "rel"):
                return {"type": "proj_tp", "name": name, "at": list(ctx.pos) + [{k: xyz}]}
    if cmd == "summon" and len(toks) >= 2:
        ent = toks[1].lower()
        name = None
        i = 2
        if ent == "ds:projectile" and len(toks) > 2 and toks[2][0] not in "~^":
            name = toks[2].strip('"')
            i = 3
        k, xyz, j = split_coords(toks, i)
        at = list(ctx.pos) + ([{k: xyz}] if k in ("local", "rel") else [])
        cue = {"type": "summon", "entity": ent, "at": at}
        rest = toks[j:] if j > i else toks[i:]
        if rest and rest[0] == "facing":
            fk, fxyz, fj = split_coords(rest, 1)
            if fk in ("local", "rel"):
                cue["facing_at"] = list(ctx.pos) + [{fk: fxyz}]
            rest = rest[fj:] if fj > 1 else rest[2:]
        if ent == "ds:projectile" and name is None and rest:
            # summon <type> <pos> [facing ...] <spawnEvent> <name>
            name = rest[-1].strip('"') if len(rest) >= 2 else rest[0].strip('"')
        if name:
            cue["name"] = name
        return cue
    if cmd == "kill" and len(toks) >= 2 and toks[1].startswith("@e"):
        name = proj_name(toks[1])
        if name:
            return {"type": "proj_kill", "name": name}
    if cmd == "scoreboard" and len(toks) >= 6 and toks[1] == "players" and toks[2] == "set" and toks[4] == "Deleter":
        name = proj_name(toks[3])
        if name:
            try:
                return {"type": "proj_deleter", "name": name, "value": int(toks[5])}
            except ValueError:
                return None
    return None


def victim_command(toks: list[str], ctx: Context):
    """`execute as @e[r=R] ... run damage @s N` / `scoreboard players add @s Stun N` as radius cues."""
    v = ctx.victims
    if len(toks) >= 3 and toks[0] == "damage" and toks[1].startswith("@s"):
        try:
            amount = float(toks[2])
        except ValueError:
            return None
        return {"type": "hit", "amount": amount, "radius": v["radius"], "at": v["at"], "audience_cond": v["cond"]}
    if len(toks) >= 6 and toks[0] == "scoreboard" and toks[3].startswith("@s") and toks[4] == "Stun" and toks[2] in ("add", "set"):
        try:
            return {"type": "stun", "ticks": int(float(toks[5])), "radius": v["radius"], "at": v["at"], "audience_cond": v["cond"]}
        except ValueError:
            return None
    return None


ANCHORABLE = {"particle", "sound", "shake"}


def anchor_of(tok: str):
    """Where presentation runs when JJA executes it at another entity."""
    kind, args = parse_selector(tok)
    if args is None:
        return {"near": 16, "c": 1}
    name = next((o[5:].strip('"') for o in args["other"] if o.startswith("name=") and not o.startswith("name=!")), None)
    typ = next((o[5:] for o in args["other"] if o.startswith("type=") and not o.startswith("type=!")), None)
    if name and "ds:projectile" in tok:
        return {"projectile": name}
    if name:
        return {"name": name}
    if typ:
        return {"entity_type": typ, "near": args["r"] or 64}
    c = next((int(o[2:]) for o in args["other"] if re.fullmatch(r"c=\d+", o)), None)
    out = {"near": args["r"] if args["r"] is not None else 16}
    if c:
        out["c"] = c
    if kind in ("a", "p"):
        out["players"] = True
    return out


def input_cond(c: str):
    """JJA reads a second press of the ability item (`tag=itemUse:ds:limitless3`) or a punch."""
    if c.startswith("tag=itemUse:"):
        return "tag=input"
    if c.startswith("score:punch="):
        return "tag=input:punch"
    if c.startswith("score:scroll="):
        return "tag=input"
    return c


def demote_fact(c: str):
    if c.startswith("w:near="):
        return "if:entity"
    if c.startswith("w:far="):
        return "unless:entity"
    if c == "w:solid":
        return "unless:block"
    if c == "w:air":
        return "if:block"
    return c


def world_fact(w: str, args: list[str]):
    """Runner world tests as facts the projectile runtime evaluates: 'w:solid' (block here is not
    air), 'w:air', 'w:near=R' (a valid target within R), 'w:far=R'. '' = no condition (fire checks)."""
    if args and args[0] == "block":
        k, xyz, j = split_coords(args, 1)
        blk = args[j - 0] if k and j < len(args) else (args[2] if len(args) > 2 else "")
        if k == "rel" and all(abs(v) < 1e-6 for v in xyz):
            if blk == "air":
                return "w:solid" if w == "unless" else "w:air"
            if blk in ("fire", "soul_fire") and w == "unless":
                return ""
    if args and args[0] == "entity" and len(args) > 1 and "tag=griefable" in args[1]:
        return ""  # the griefing setting is enforced by the Java carve itself
    if args and args[0] == "entity" and len(args) > 1 and args[1].startswith("@e"):
        m = re.search(r"\br=([\d.]+)", args[1])
        if m and "name=" not in args[1]:
            return ("w:near=" if w == "if" else "w:far=") + m.group(1)
    return None


def helper_entities():
    """BP helper entities that exist to explode or knock back once ({id: {explode|roar}})."""
    out = {}
    for p in (BP / "entities").rglob("*.json"):
        try:
            d = load_json(p)["minecraft:entity"]
        except Exception:
            continue
        ident = d.get("description", {}).get("identifier")
        comps = d.get("components", {})
        if not ident:
            continue
        e = {}
        if "minecraft:explode" in comps:
            x = comps["minecraft:explode"]
            e["explode"] = {"power": x.get("power", 3), "fire": bool(x.get("causes_fire", False)),
                            "breaks": bool(x.get("destroy_affected_by_griefing", x.get("breaks_blocks", True)))}
        roar = comps.get("minecraft:behavior.knockback_roar")
        if roar:
            e["roar"] = {"strength": roar.get("knockback_strength", 4), "range": roar.get("knockback_range", 4),
                         "damage": roar.get("knockback_damage", 0)}
        if e:
            out[ident] = e
    return dict(sorted(out.items()))


def proj_name(tok: str):
    """'@e[name=ImagineTRG,type=ds:projectile,r=5,c=1]' -> 'ImagineTRG'."""
    m = re.search(r'name="?([^",\]]+)"?', tok)
    return m.group(1) if m and "ds:projectile" in tok else None


def _parse_command(toks: list[str], ctx: Context, key: str):
    cmd = toks[0]
    if cmd == "playanimation" and len(toks) >= 3:
        target = toks[1]
        kind, args = parse_selector(target)
        absorb_selector(ctx, kind, args, key, "target")
        scope = scope_of(target)
        if scope is None or scope.startswith("other"):
            return None
        cue = {"type": "anim", "anim": toks[2], "target": scope}
        if len(toks) >= 5:
            try:
                cue["blend"] = float(toks[4])
            except ValueError:
                pass
        return cue
    if cmd == "playsound" and len(toks) >= 2:
        cue = {"type": "sound", "sound": toks[1], "audience": "all"}
        i = 2
        if i < len(toks) and toks[i].startswith("@"):
            cue["audience"] = scope_of(toks[i]) or "all"
            i += 1
        k, xyz, i = split_coords(toks, i)
        if k in ("local", "rel"):
            cue["at"] = ctx.pos + [{k: xyz}]
        else:
            cue["at"] = list(ctx.pos)
        nums = []
        for t in toks[i:]:
            try:
                nums.append(float(t))
            except ValueError:
                break
        if nums:
            cue["volume"] = nums[0]
        if len(nums) > 1:
            cue["pitch"] = nums[1]
        return cue
    if cmd == "stopsound" and len(toks) >= 2:
        return {"type": "stopsound", "sound": toks[2] if len(toks) > 2 else None}
    if cmd == "particle" and len(toks) >= 2:
        k, xyz, _ = split_coords(toks, 2)
        at = list(ctx.pos) + ([{k: xyz}] if k in ("local", "rel") else [])
        return {"type": "particle", "particle": toks[1], "at": at}
    if cmd == "camerashake" and len(toks) >= 3 and toks[1] == "add":
        cue = {"type": "shake", "audience": scope_of(toks[2]) or "self", "intensity": 0.5, "seconds": 1.0, "mode": "positional"}
        if cue["audience"].startswith("other"):
            return None
        rest = toks[3:]
        nums = [float(x) for x in rest if re.fullmatch(r"-?\d*\.?\d+", x)]
        if nums:
            cue["intensity"] = nums[0]
        if len(nums) > 1:
            cue["seconds"] = nums[1]
        if "rotational" in rest:
            cue["mode"] = "rotational"
        return cue
    if cmd == "camera" and len(toks) >= 3:
        target = toks[1]
        kind, args = parse_selector(target)
        absorb_selector(ctx, kind, args, key, "target")
        audience = scope_of(target) or "self"
        if audience.startswith("other"):
            return None
        sub = toks[2]
        if sub == "clear":
            return {"type": "camera_clear", "audience": audience}
        if sub == "fade":
            cue = {"type": "camera_fade", "audience": audience, "in": 0.0, "hold": 0.0, "out": 0.0, "color": [0, 0, 0]}
            rest = toks[3:]
            if "time" in rest:
                i = rest.index("time")
                try:
                    cue["in"], cue["hold"], cue["out"] = (float(x) for x in rest[i + 1:i + 4])
                except ValueError:
                    pass
            if "color" in rest:
                i = rest.index("color")
                try:
                    cue["color"] = [int(float(x)) for x in rest[i + 1:i + 4]]
                except ValueError:
                    pass
            return cue
        if sub == "set" and len(toks) >= 4:
            cue = {"type": "camera_set", "audience": audience, "preset": toks[3].replace("minecraft:", "")}
            rest = toks[4:]
            i = 0
            while i < len(rest):
                w = rest[i]
                if w == "ease" and i + 2 < len(rest):
                    try:
                        cue["ease"] = {"seconds": float(rest[i + 1]), "type": rest[i + 2]}
                    except ValueError:
                        pass
                    i += 3
                elif w == "pos":
                    k, xyz, j = split_coords(rest, i + 1)
                    if k in ("local", "rel"):
                        cue["pos"] = list(ctx.pos) + [{k: xyz}]
                    i = j if j > i + 1 else i + 1
                elif w == "facing":
                    if i + 1 < len(rest) and rest[i + 1].startswith("@"):
                        sc = scope_of(rest[i + 1])
                        cue["facing"] = {"entity": "self" if sc == "self" else rest[i + 1][:60]}
                        i += 2
                    else:
                        k, xyz, j = split_coords(rest, i + 1)
                        if k in ("local", "rel"):
                            cue["facing"] = {"at": list(ctx.pos) + [{k: xyz}]}
                        i = j if j > i + 1 else i + 1
                elif w == "rot" and i + 2 < len(rest):
                    cue["rot"] = [rest[i + 1], rest[i + 2]]
                    i += 3
                elif w == "default":
                    cue["default"] = True
                    i += 1
                else:
                    i += 1
            return cue
        return None
    if cmd in ("title", "titleraw") and len(toks) >= 3:
        target = toks[1]
        kind, args = parse_selector(target)
        absorb_selector(ctx, kind, args, key, "target")
        audience = scope_of(target) or "self"
        if audience.startswith("other") or toks[2] not in ("title",):
            return None
        text = " ".join(toks[3:])
        g = glyph_of(text)
        if g:
            return {"type": "impact", "glyph": g, "audience": audience}
        return {"type": "impact_clear", "audience": audience}
    if cmd == "effect" and len(toks) >= 3:
        audience = scope_of(toks[1])
        eff = toks[2].replace("minecraft:", "")
        if audience and not audience.startswith("other") and eff in (
                "night_vision", "blindness", "darkness", "slowness", "levitation", "slow_falling", "jump_boost", "invisibility", "speed"):
            cue = {"type": "effect", "effect": eff, "audience": audience}
            nums = [x for x in toks[3:] if re.fullmatch(r"\d+", x)]
            if nums:
                cue["seconds"] = int(nums[0])
                if cue["seconds"] == 0:
                    cue["clear"] = True  # Bedrock: a 0-second effect removes it
            if len(nums) > 1:
                cue["amplifier"] = int(nums[1])
            return cue
        if audience and not audience.startswith("other") and eff == "clear":
            return {"type": "effect", "effect": "*", "clear": True, "audience": audience}
    return None


def parse_line(line: str, key: str, allow_ungated: bool = False, runner: bool = False):
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    toks = tokenize(line)
    ctx = Context()
    ctx.runner = runner
    i = 0
    if toks and toks[0] == "execute":
        i = 1
        while i < len(toks):
            w = toks[i]
            if w == "run":
                if i + 1 < len(toks) and toks[i + 1] == "execute":
                    i += 2  # `run execute ...`: the same chain continues
                    continue
                i += 1
                break
            if w in ("as", "at") and i + 1 < len(toks):
                kind, args = parse_selector(toks[i + 1])
                if w == "as" and kind in ("e", "a") and args and args.get("r") is not None \
                        and ctx.self_ctx and ctx.victims is None:
                    ctx.victims = {"radius": args["r"], "at": list(ctx.pos), "cond": audience_filters(toks[i + 1])}
                if kind not in (None, "s") and ctx.anchor is None:
                    a = anchor_of(toks[i + 1])
                    if w == "as":
                        ctx.pending_anchor = a
                    elif a is not None:
                        ctx.anchor = a
                        ctx.pos = []
                absorb_selector(ctx, kind, args, key, w)
                if w == "at" and kind == "s":
                    ctx.pos = []  # re-anchored at the caster (or at the `as` entity)
                    if ctx.pending_anchor is not None and ctx.anchor is None:
                        ctx.anchor = ctx.pending_anchor
                i += 2
            elif w == "positioned" and i + 1 < len(toks):
                if toks[i + 1] == "as":
                    kind, args = parse_selector(toks[i + 2]) if i + 2 < len(toks) else (None, None)
                    if kind != "s":
                        ctx.self_ctx = False
                    i += 3
                else:
                    k, xyz, j = split_coords(toks, i + 1)
                    if k in ("local", "rel"):
                        ctx.pos.append({k: xyz})
                    elif k is None:
                        ctx.self_ctx = False  # absolute coordinates
                    i = j if j > i + 1 else i + 2
            elif w == "anchored" and i + 1 < len(toks):
                ctx.pos.append({"anchor": toks[i + 1]})
                i += 2
            elif w in ("if", "unless") and i + 1 < len(toks):
                # conditions on world state: keep a marker, skip its arguments up to the next keyword
                fact = world_fact(w, toks[i + 1:i + 5])
                if fact != "":
                    ctx.cond.append(fact or f"{w}:{toks[i + 1]}")
                i += 2
                while i < len(toks) and toks[i] not in ("as", "at", "positioned", "anchored", "if", "unless",
                                                        "run", "rotated", "facing", "align", "in"):
                    i += 1
            elif w in ("rotated", "facing"):
                i += 2 if i + 1 < len(toks) and toks[i + 1].startswith("@") else 4
                if i - 2 < len(toks) and toks[i - 1 if toks[i - 2] == w else i - 2:i][:1] == ["as"]:
                    pass
            elif w in ("align", "in"):
                i += 2
            else:
                i += 1
    cmd_toks = toks[i:]
    if not cmd_toks:
        return None
    # direct commands gate on their own target selector
    if len(cmd_toks) > 1 and cmd_toks[1].startswith("@s"):
        kind, args = parse_selector(cmd_toks[1])
        if cmd_toks[0] not in ("playanimation", "camera", "title", "titleraw"):
            absorb_selector(ctx, kind, args, key, "target")
    # `scoreboard players <op> @s[scores={move=N}] ...` gates on the 4th token
    if len(cmd_toks) > 3 and cmd_toks[0] == "scoreboard" and cmd_toks[3].startswith("@s"):
        kind, args = parse_selector(cmd_toks[3])
        absorb_selector(ctx, kind, args, key, "target")
    cue = victim_command(cmd_toks, ctx) if ctx.victims else None
    if cue is not None:
        ctx.self_ctx = True  # `as @e[r=..] run damage @s N` is the caster's radius hit
    else:
        cue = parse_command(cmd_toks, ctx, key)
    if cue is None:
        STATS["skipped:" + cmd_toks[0]] += 1
        return None
    if not ctx.self_ctx:
        if ctx.anchor is not None and cue["type"] in ANCHORABLE:
            cue["anchor"] = ctx.anchor  # e.g. particles on a projectile or on the victim
        else:
            STATS["skipped:other_context"] += 1
            return None
    if ctx.gate is None and not allow_ungated:
        STATS["skipped:ungated"] += 1
        return None
    cond = [input_cond(c) for c in ctx.cond]
    if not runner and cue["type"] not in ("counter",):
        cond = [demote_fact(c) for c in cond]  # world tests stay markers outside counter jumps
    if cond:
        cue["cond"] = sorted(set(cond))
    return ctx.gate, cue


# ---------------------------------------------------------------------------------------------

UNBOUNDED_LO, UNBOUNDED_HI = -1000000, 1000000


def find_runners():
    """Functions run each tick as a named ds:projectile: {function path: {names}}."""
    out = defaultdict(set)
    pat = re.compile(r'as @e\[name="?([^",\]]+)"?,type=ds:projectile[^\]]*\] at @s run function ([\w/]+)')
    for p in FN.rglob("*.mcfunction"):
        for line in p.open(encoding="utf-8", errors="ignore"):
            for m in pat.finditer(line):
                out[m.group(2)].add(m.group(1))
    return out


def extract_runner(path: Path, names: set):
    """A projectile's per-tick function: cues keyed on its remaining Deleter (lo/hi), 'proj=' name conditions."""
    text = path.read_text(encoding="utf-8", errors="ignore")
    rel = str(path.relative_to(FN).with_suffix(""))
    cues = []
    for line in text.splitlines():
        r = parse_line(line, "Deleter", allow_ungated=True, runner=True)
        if r is None:
            continue
        gate, cue = r
        lo, hi = gate if gate else (None, None)
        cue = {"lo": UNBOUNDED_LO if lo is None else lo, "hi": UNBOUNDED_HI if hi is None else hi, **cue}
        if "cond" in cue:
            cue["cond"] = ["proj=" + c[5:] if c.startswith("name=") else c for c in cue["cond"]]
        cues.append(cue)
    if not cues:
        return None
    return {"source": rel, "start": 0, "key": "Deleter", "mode": "remaining", "names": sorted(names), "cues": cues}


def dominant_key(text: str):
    counts = Counter()
    for k in TIMELINE_KEYS:
        counts[k] = len(re.findall(r"[{,]" + k + r"=\d", text))
    k, n = counts.most_common(1)[0]
    return k if n >= 3 else None


# Countdowns that are not set by a *_click: JJA's dashclick sets Dash 12 (28 for the charge dash),
# evade sets Evasive 21, Black Flash runs blackflashing from 9.
SPECIAL_DURATIONS = {"dash": 12, "evade": 21, "blackflash1": 10, "blackflash2": 10, "blackflash3": 10, "blackflash4": 10}


def known_durations():
    mv = json.loads((RESEARCH / "catalog" / "movesets.json").read_text())
    out = dict(SPECIAL_DURATIONS)
    for rows in mv.values():
        for r in rows:
            if r.get("function") and r.get("duration"):
                out.setdefault(r["function"], r["duration"][1])
    return out


def extract(path: Path, durations: dict):
    """Cues keyed on the countdown value itself: {"lo", "hi"} (inclusive), in file order."""
    text = path.read_text(encoding="utf-8", errors="ignore")
    key = dominant_key(text)
    if key is None:
        return None
    rel = str(path.relative_to(FN).with_suffix(""))
    cues = []
    for line in text.splitlines():
        r = parse_line(line, key)
        if r is None:
            continue
        (lo_v, hi_v), cue = r
        lo = 1 if lo_v is None else max(1, lo_v)  # the move function only runs while the key is 1+
        hi = UNBOUNDED_HI if hi_v is None else hi_v
        if hi < lo:
            STATS["skipped:empty_gate"] += 1
            continue
        cues.append({"lo": lo, "hi": hi, **cue})
    if not cues:
        return None
    start = durations.get(rel)
    if not start:
        jumps = [c["value"] for c in cues if c["type"] == "counter" and c["op"] == "set" and c["value"] > 0]
        tops = [c["hi"] for c in cues if c["hi"] < UNBOUNDED_HI and (not jumps or c["hi"] < min(jumps))]
        start = (max(tops) if tops else max(c["lo"] for c in cues)) + 1
    return {"source": rel, "key": key, "start": start, "cues": cues}


def main():
    durations = known_durations()
    if OUT.exists():
        import shutil
        shutil.rmtree(OUT)
    index = {}
    sim_index = {}
    type_counts = Counter()
    runners = find_runners()
    projectile_index = {}
    for fn, names in sorted(runners.items()):
        path = FN / (fn + ".mcfunction")
        if not path.exists():
            continue
        script = extract_runner(path, names)
        if not script:
            continue
        safe = re.sub(r"[^a-z0-9_/.-]", "_", fn.lower())
        sim = [c for c in script["cues"] if c["type"] in SIM_TYPES]
        script["cues"] = [c for c in script["cues"] if c["type"] not in SIM_TYPES]
        dst = OUT / "projectile" / f"{safe}.json"
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(json.dumps(script, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
        for n in names:
            projectile_index[n] = {"script": f"crb:projectile/{safe}", "sim": sim}
    (REPO / "src" / "main" / "resources" / "data" / "crb" / "projectiles.json").write_text(
        json.dumps(dict(sorted(projectile_index.items())), separators=(",", ":")), encoding="utf-8")
    print(f"projectile runners: {len(projectile_index)} names")
    helpers = helper_entities()
    (REPO / "src" / "main" / "resources" / "data" / "crb" / "helper_entities.json").write_text(
        json.dumps(helpers, separators=(",", ":")), encoding="utf-8")
    print(f"helper entities: {len(helpers)}")
    for path in sorted(FN.rglob("*.mcfunction")):
        if str(path.relative_to(FN).with_suffix("")) in runners:
            continue  # runner functions are projectile scripts, not caster moves
        script = extract(path, durations)
        if not script:
            continue
        rel = script["source"]
        safe = re.sub(r"[^a-z0-9_/.-]", "_", rel.lower())
        dst = OUT / f"{safe}.json"
        dst.parent.mkdir(parents=True, exist_ok=True)
        sim = [c for c in script["cues"] if c["type"] in SIM_TYPES]
        # clients follow unconditional counter jumps (holds, loops) themselves; the rest arrive from the server
        script["cues"] = [c for c in script["cues"] if c["type"] not in SIM_TYPES or (c["type"] == "counter" and not c.get("cond"))]
        if sim:
            sim_index["crb:" + safe] = sim
        dst.write_text(json.dumps(script, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
        index[rel] = {"id": f"crb:{safe}", "start": script["start"], "cues": len(script["cues"]), "key": script["key"]}
        type_counts.update(c["type"] for c in script["cues"])
        type_counts.update(c["type"] for c in sim_index.get("crb:" + safe, []))
    (OUT.parent / "jja" / "move_scripts.json").write_text(json.dumps(dict(sorted(index.items())), indent=1), encoding="utf-8")
    (REPO / "src" / "main" / "resources" / "data" / "crb" / "move_sim.json").write_text(
        json.dumps(dict(sorted(sim_index.items())), separators=(",", ":")), encoding="utf-8")
    print(f"simulation: {len(sim_index)} moves")
    # gameplay effects (slowness, levitation, blindness...) are applied by the server
    effects = {}
    for path in sorted(OUT.rglob("*.json")):
        sc = json.loads(path.read_text(encoding="utf-8"))
        ev = [{k: c[k] for k in ("lo", "hi", "effect", "seconds", "amplifier", "clear", "audience", "cond", "audience_cond") if k in c}
              for c in sc["cues"] if c["type"] == "effect" and c.get("effect") != "night_vision"]
        if ev:
            effects["crb:" + str(path.relative_to(OUT).with_suffix(""))] = ev
    (REPO / "src" / "main" / "resources" / "data" / "crb" / "move_effects.json").write_text(
        json.dumps(dict(sorted(effects.items())), separators=(",", ":")), encoding="utf-8")
    # the server cannot read client assets; it needs durations for /crb move play and NPC moves
    durations = {v["id"]: v["start"] for v in index.values()}
    data = REPO / "src" / "main" / "resources" / "data" / "crb" / "move_durations.json"
    data.write_text(json.dumps(dict(sorted(durations.items())), indent=0), encoding="utf-8")
    print(f"move scripts: {len(index)}  cues: {sum(type_counts.values())}")
    print("cue types:", dict(type_counts.most_common()))
    print("skipped:", dict(STATS.most_common(15)))


if __name__ == "__main__":
    main()
