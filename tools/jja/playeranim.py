"""Bake JJA's Bedrock player animations into flat Player Animation Library (PAL) bones.

JJA's player model (`geometry.playermodel`) is a hierarchy: root -> body -> head, arms, legs, and
rightItem under rightArm. Bedrock applies a parent's transform to its children, so turning `root`
spins the whole player and leaning `body` carries the arms, head and legs with it. PAL animates
the vanilla player parts independently, plus `body` (the whole model, pivot y=12) and `torso`.

Copying JJA's bones one-to-one (the old conversion) put `root` on the torso alone, so whole-body
motion left the limbs behind. Here every animation is sampled once per tick, the hierarchy is
composed, and each PAL part gets the rotation and offset that puts it where Bedrock would:

    PAL body       <- root                       (about PAL's body pivot, y=12)
    PAL torso      <- body
    PAL head/arms  <- body * head / body * arm
    PAL legs       <- body * leg
    items          <- copied (PAL attaches them to the arms already)

Math happens in GeckoLib space (x mirrored from Bedrock; a bone turns as Rz * Ry * Rx with
Bedrock's x and y rotations negated), which is how GeckoLib and PAL read Bedrock files.
"""
from __future__ import annotations

import math
import re

FPS = 20
DT = 1.0 / FPS

# JJA geometry.playermodel (Bedrock coordinates)
PIVOTS = {
    "root": (0, 0, 0), "body": (0, 24, 0), "head": (0, 24, 0),
    "rightArm": (-5, 22, 0), "leftArm": (5, 22, 0), "rightLeg": (-1.9, 12, 0), "leftLeg": (1.9, 12, 0),
}
CHILDREN_OF_BODY = ("head", "rightArm", "leftArm", "rightLeg", "leftLeg")
# PAL part -> (JJA bone, PAL pivot in GeckoLib space)
PAL_PARTS = {
    "torso": ("body", (0, 24, 0)), "head": ("head", (0, 24, 0)),
    "right_arm": ("rightArm", (5, 22, 0)), "left_arm": ("leftArm", (-5, 22, 0)),
    "right_leg": ("rightLeg", (2, 12, 0)), "left_leg": ("leftLeg", (-2, 12, 0)),
}
PAL_BODY_PIVOT = (0, 12, 0)
ITEM_BONES = {"rightItem": "right_item", "rightitem": "right_item", "leftItem": "left_item", "leftitem": "left_item"}

# ------------------------------------------------------------------ molang

_MOLANG_ZERO = re.compile(r"\b(?:query|q|variable|v|temp|t|context|c)\.\w+")
_TIME = re.compile(r"\b(?:query|q)\.(?:anim_time|life_time)\b")


def _molang(expr: str, time: float) -> float:
    """Tiny evaluator for the Molang JJA uses in player animations (mostly look rotations -> 0)."""
    s = expr.strip().lower()
    if not s:
        return 0.0
    try:
        return float(s)
    except ValueError:
        pass
    s = _TIME.sub(f"({time})", s)
    s = re.sub(r"\bthis\b", "0", s)
    s = _MOLANG_ZERO.sub("0", s)
    s = s.replace("math.", "m_")
    env = {
        "m_sin": lambda d: math.sin(math.radians(d)), "m_cos": lambda d: math.cos(math.radians(d)),
        "m_abs": abs, "m_clamp": lambda x, a, b: max(a, min(b, x)), "m_lerp": lambda a, b, t: a + (b - a) * t,
        "m_pi": math.pi, "m_floor": math.floor, "m_ceil": math.ceil, "m_round": round, "m_trunc": math.trunc,
        "m_sqrt": math.sqrt, "m_pow": math.pow, "m_min": min, "m_max": max, "m_mod": math.fmod, "m_exp": math.exp,
        "m_ln": math.log, "m_random": lambda a, b: (a + b) / 2, "m_random_integer": lambda a, b: (a + b) // 2,
        "m_atan2": lambda y, x: math.degrees(math.atan2(y, x)), "m_atan": lambda x: math.degrees(math.atan(x)),
        "m_asin": lambda x: math.degrees(math.asin(x)), "m_acos": lambda x: math.degrees(math.acos(x)),
    }
    if "?" in s or ";" in s or "=" in s.replace("==", "").replace("<=", "").replace(">=", "").replace("!=", ""):
        return 0.0
    try:
        return float(eval(s, {"__builtins__": {}}, env))  # noqa: S307 - our own asset text, no builtins
    except Exception:  # noqa: BLE001
        return 0.0


def _vec(v, time: float):
    if isinstance(v, (int, float)):
        return [float(v)] * 3
    if isinstance(v, str):
        x = _molang(v, time)
        return [x, x, x]
    if isinstance(v, list):
        out = [(_molang(x, time) if isinstance(x, str) else float(x)) for x in v[:3]]
        while len(out) < 3:
            out.append(0.0)
        return out
    return [0.0, 0.0, 0.0]


def _is_look_only(channel) -> bool:
    """`["query.target_x_rotation", "query.target_y_rotation", 0]`: the head follows the camera."""
    text = str(channel)
    return "target_x_rotation" in text or "target_y_rotation" in text


# ------------------------------------------------------------------ keyframe sampling

def _keyframes(channel):
    """Sorted [(time, pre, post, catmull)] or None for a constant channel."""
    if not isinstance(channel, dict):
        return None
    frames = []
    for k, v in channel.items():
        try:
            t = float(k)
        except ValueError:
            continue
        if isinstance(v, dict):
            post = v.get("post", v.get("vector", v.get("pre")))
            pre = v.get("pre", post)
            frames.append((t, pre, post, v.get("lerp_mode") == "catmullrom"))
        else:
            frames.append((t, v, v, False))
    frames.sort(key=lambda f: f[0])
    return frames or None


def sample(channel, time: float):
    if channel is None:
        return None
    frames = _keyframes(channel)
    if frames is None:
        return _vec(channel, time)
    if time <= frames[0][0]:
        return _vec(frames[0][1], time)
    if time >= frames[-1][0]:
        return _vec(frames[-1][2], time)
    for i in range(len(frames) - 1):
        t0, _, post0, cm0 = frames[i]
        t1, pre1, _, cm1 = frames[i + 1]
        if t0 <= time <= t1:
            u = 0.0 if t1 <= t0 else (time - t0) / (t1 - t0)
            p1, p2 = _vec(post0, time), _vec(pre1, time)
            if cm0 or cm1:
                p0 = _vec(frames[i - 1][2], time) if i > 0 else p1
                p3 = _vec(frames[i + 2][1], time) if i + 2 < len(frames) else p2
                return [_catmull(p0[k], p1[k], p2[k], p3[k], u) for k in range(3)]
            return [p1[k] + (p2[k] - p1[k]) * u for k in range(3)]
    return _vec(frames[-1][2], time)


def _catmull(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)


def channel_end(channel) -> float:
    frames = _keyframes(channel)
    return frames[-1][0] if frames else 0.0


# ------------------------------------------------------------------ affine math (GeckoLib space)

def _mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _mat_vec(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def _rot(rx_b, ry_b, rz_b):
    """A Bedrock rotation as GeckoLib applies it: Rz(z) * Ry(-y) * Rx(-x)."""
    x, y, z = math.radians(-rx_b), math.radians(-ry_b), math.radians(rz_b)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return _mat_mul(rz, _mat_mul(ry, rx))


def _gl(v):
    """Bedrock -> GeckoLib space (x mirrored)."""
    return [-v[0], v[1], v[2]]


def bone_affine(rot, pos, pivot_b):
    """v -> R (v - p) + p + pos, as (M, t)."""
    m = _rot(*(rot or (0, 0, 0)))
    p = _gl(pivot_b)
    d = _gl(pos or (0, 0, 0))
    mp = _mat_vec(m, p)
    return m, [p[i] + d[i] - mp[i] for i in range(3)]


def compose(a, b):
    """a after b."""
    ma, ta = a
    mb, tb = b
    return _mat_mul(ma, mb), [x + y for x, y in zip(_mat_vec(ma, tb), ta)]


IDENTITY = ([[1, 0, 0], [0, 1, 0], [0, 0, 1]], [0.0, 0.0, 0.0])


def decompose(aff, pivot_g, prev=None):
    """(M, t) -> Bedrock rotation [x, y, z] and position [x, y, z] about a PAL pivot (GeckoLib space)."""
    m, t = aff
    b = math.asin(max(-1.0, min(1.0, -m[2][0])))
    if abs(math.cos(b)) > 1e-6:
        a = math.atan2(m[1][0], m[0][0])
        c = math.atan2(m[2][1], m[2][2])
    else:  # gimbal lock: put everything on z
        a = math.atan2(-m[0][1], m[1][1])
        c = 0.0
    sols = [(a, b, c), (a + math.pi, math.pi - b, c + math.pi)]
    if prev is not None:
        def near(s):
            return tuple(v + 2 * math.pi * round((p - v) / (2 * math.pi)) for v, p in zip(s, prev))
        sols = [near(s) for s in sols]
        a, b, c = min(sols, key=lambda s: sum((v - p) ** 2 for v, p in zip(s, prev)))
    rad = (a, b, c)
    rot_b = [-math.degrees(c), -math.degrees(b), math.degrees(a)]
    mp = _mat_vec(m, pivot_g)
    pos_g = [t[i] + mp[i] - pivot_g[i] for i in range(3)]
    return rot_b, [-pos_g[0], pos_g[1], pos_g[2]], rad


# ------------------------------------------------------------------ baking

def _simplify(times, values, eps):
    """Drops samples that linear interpolation of their neighbours reproduces."""
    keep = [0]
    for i in range(1, len(times) - 1):
        a, b = keep[-1], i + 1
        u = (times[i] - times[a]) / (times[b] - times[a])
        if any(abs(values[a][k] + (values[b][k] - values[a][k]) * u - values[i][k]) > eps for k in range(3)):
            keep.append(i)
    if len(times) > 1:
        keep.append(len(times) - 1)
    return keep


def _emit(times, values, eps):
    if all(max(abs(x) for x in v) < 1e-4 for v in values):
        return None
    idx = _simplify(times, values, eps)
    return {f"{times[i]:.4g}": [round(x, 4) for x in values[i]] for i in idx}


def bake(anim: dict) -> dict | None:
    """A JJA player animation as flat PAL bones, or None if it does not move the player model."""
    bones = anim.get("bones") or {}
    core = {k: v for k, v in bones.items() if k in PIVOTS}
    if not core:
        return None
    length = anim.get("animation_length")
    if not isinstance(length, (int, float)):
        length = max([channel_end(ch) for b in core.values() for ch in (b.get("rotation"), b.get("position")) if ch is not None] + [0.0])
    length = max(float(length), DT)
    n = int(round(length * FPS))
    times = [min(i * DT, length) for i in range(n + 1)]
    body_moves = "body" in core

    def local(name, time):
        b = core.get(name)
        if not b:
            return IDENTITY
        rot = sample(b.get("rotation"), time)
        pos = sample(b.get("position"), time)
        return bone_affine(rot, pos, PIVOTS[name])

    tracks: dict[str, dict[str, list]] = {}
    prev: dict[str, tuple] = {}

    def put(part, time, aff, pivot_g):
        rot, pos, rad = decompose(aff, list(pivot_g), prev.get(part))
        prev[part] = rad
        tr = tracks.setdefault(part, {"rotation": [], "position": []})
        tr["rotation"].append(rot)
        tr["position"].append(pos)

    for time in times:
        if "root" in core:
            put("body", time, local("root", time), PAL_BODY_PIVOT)
        body = local("body", time)
        if body_moves:
            put("torso", time, body, PAL_PARTS["torso"][1])
        for part, (bone, pivot) in PAL_PARTS.items():
            if part == "torso":
                continue
            if bone not in core and not body_moves:
                continue
            if bone == "head" and bone in core and _is_look_only(core[bone].get("rotation")) and not body_moves:
                continue  # the head keeps following the camera
            put(part, time, compose(body, local(bone, time)), pivot)

    out_bones = {}
    for part, tr in tracks.items():
        data = {}
        rot = _emit(times, tr["rotation"], 0.05)
        pos = _emit(times, tr["position"], 0.005)
        if rot:
            data["rotation"] = rot
        if pos:
            data["position"] = pos
        if data:
            out_bones[part] = data
    for src, dst in ITEM_BONES.items():
        if src in bones and dst not in out_bones:
            out_bones[dst] = {k: v for k, v in bones[src].items() if k in ("rotation", "position", "scale")}
    if not out_bones:
        return None
    out = {k: v for k, v in anim.items() if k in ("loop", "override_previous_animation", "start_delay", "loop_delay")}
    out["animation_length"] = round(length, 4)
    out["bones"] = out_bones
    return out


# ------------------------------------------------------------------ animation keyframe effects

def geometry_bones(geo: dict) -> dict:
    """{bone: (parent, pivot, {locator: pos})} from a Bedrock geometry (new or legacy format)."""
    out = {}
    for b in geo.get("bones", []):
        locs = {}
        for k, v in (b.get("locators") or {}).items():
            if isinstance(v, dict):
                v = v.get("offset", [0, 0, 0])
            locs[k] = v
        out[b["name"]] = (b.get("parent"), b.get("pivot", [0, 0, 0]), locs)
    return out


def locator_table(geometries: list[dict]) -> dict:
    """locator -> (geometry bones, bone, rest position); the first geometry that defines it wins."""
    table = {}
    for g in geometries:
        bones = geometry_bones(g)
        for name, (_, _, locs) in bones.items():
            for loc, pos in locs.items():
                table.setdefault(loc, (bones, name, pos))
    return table


def locator_position(anim: dict, bones: dict, bone: str, rest, time: float):
    """Where a locator is at {@code time} in the animation, in GeckoLib model space (pixels)."""
    chain = []
    b = bone
    seen = set()
    while b and b in bones and b not in seen:
        seen.add(b)
        chain.append(b)
        b = bones[b][0]
    channels = anim.get("bones") or {}
    aff = IDENTITY
    for name in reversed(chain):  # root first
        ch = channels.get(name) or {}
        rot = sample(ch.get("rotation"), time) if ch else None
        pos = sample(ch.get("position"), time) if ch else None
        aff = compose(aff, bone_affine(rot, pos, bones[name][1]))
    m, t = aff
    p = _mat_vec(m, _gl(rest))
    return [round(p[i] + t[i], 3) for i in range(3)]


def bake_effects(name: str, raw: dict, anim: dict | None, locators: dict, particle_ids: dict, sound_ids: dict) -> list:
    """JJA keyframe effects as [{t, particle|sound, at?}] with locator positions resolved."""
    out = []

    def entries(block):
        for k, v in (block or {}).items():
            try:
                t = float(k)
            except ValueError:
                continue
            for e in (v if isinstance(v, list) else [v]):
                if isinstance(e, dict):
                    yield t, e

    for t, e in entries(raw.get("particle_effects")):
        short = e.get("effect")
        pid = particle_ids.get(short, short if short and ":" in short else None)
        if not pid:
            continue
        item = {"t": round(t, 4), "particle": pid}
        loc = e.get("locator")
        if loc and loc in locators and anim is not None:
            bones, bone, rest = locators[loc]
            item["at"] = locator_position(anim, bones, bone, rest, t)
        elif loc:
            item["locator"] = loc
        out.append(item)
    for t, e in entries(raw.get("sound_effects")):
        short = e.get("effect")
        sid = sound_ids.get(short, short)
        if sid:
            out.append({"t": round(t, 4), "sound": sid})
    out.sort(key=lambda x: x["t"])
    return out
