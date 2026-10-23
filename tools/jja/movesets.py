"""Extract every technique's moveset from the JJA *_click / *_moves mcfunctions.

For each move variant (formN / formNb / formNc / formNe / wepN ...) we recover:
    item       hotbar item that triggers it (ds:limitless2)
    modifier   plain | sneak | air | look-up | sneak+look-up ...
    energy     cursed-energy threshold / cost
    cooldown   cooldown objective + value in ticks
    duration   initial `move` countdown (ticks)
    name       name shown in the cooldown message
    function   timeline function run while `move` > 0

Writes architecture_and_research/catalog/movesets.{md,json}.
"""
from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import BP, RESEARCH, strip_fmt  # noqa: E402

FN = BP / "functions"
SEL_RE = re.compile(r"@[sapre]\[([^\]]*(?:\{[^}]*\}[^\]]*)*)\]")
SCORES_RE = re.compile(r"scores=\{([^}]*)\}")
ITEM_RE = re.compile(r"item=([a-z0-9_:]+)")
TAG_RE = re.compile(r"(?<![a-z_])tag=(!?[A-Za-z0-9_:.]+)")
CD_NAME_RE = re.compile(r'"text":"([^"]*?) (?:is|are) on cooldown')
SET_RE = re.compile(r"scoreboard players set (@s\[.*\]) (\w+) (-?\d+)\s*$")
REMOVE_RE = re.compile(r"scoreboard players remove (@s\[.*\]) Energy (\d+)\s*$")
ADD_FORM_RE = re.compile(r"^tag (@s\[.*\]) add (\w+)\s*$")
MOVES_RE = re.compile(r"execute as @s\[([^\]]*(?:\{[^}]*\}[^\]]*)*)\] run function ([\w/]+)")
MOVE_VAL_RE = re.compile(r"(?:move|domain|domainstart)=(\d+)(?:\.\.(\d+))?")

IGNORED_SCORES = {"Stun", "Disabled", "Disabled2", "nocts"}


def parse_sel(sel: str):
    scores = {}
    m = SCORES_RE.search(sel)
    if m:
        for part in m.group(1).split(","):
            if "=" in part:
                k, v = part.split("=", 1)
                scores[k.strip()] = v.strip()
    item = ITEM_RE.search(sel)
    tags = [t for t in TAG_RE.findall(sel) if not t.endswith("click")]
    return {"item": item.group(1) if item else None, "scores": scores, "tags": tags}


def signature(p):
    s = p["scores"]
    sneak = s.get("Sneaking")
    jump = s.get("detect_jump")
    cd = sorted(k for k, v in s.items() if v == "0" and re.match(r"(ability|domainCD|wep|ab)\w*", k))
    tags = tuple(sorted(t for t in p["tags"] if t.lstrip("!") in {"uplook", "sixeyes", "reversecursed", "heian_era"}))
    return (p["item"], sneak, jump, tags, tuple(cd))


def modifier_label(sig):
    _, sneak, jump, tags, _ = sig
    bits = []
    if sneak and sneak != "0":
        bits.append("sneak")
    if jump and jump != "0":
        bits.append("airborne")
    for t in tags:
        bits.append(("not " if t.startswith("!") else "") + {"uplook": "look-up"}.get(t.lstrip("!"), t.lstrip("!")))
    return " + ".join(bits) or "plain"


def energy_of(p):
    e = p["scores"].get("Energy", "")
    m = re.match(r"(\d+)\.\.", e)
    return int(m.group(1)) if m else None


def timeline_length(func: str):
    path = FN / (func + ".mcfunction")
    if not path.exists():
        return None, 0
    hi, lines = 0, 0
    for line in path.open(encoding="utf-8", errors="ignore"):
        lines += 1
        for a, b in MOVE_VAL_RE.findall(line):
            hi = max(hi, int(a), int(b or 0))
    return hi, lines


def moves_map(folder: Path):
    out = {}
    for p in folder.glob("*moves*.mcfunction"):
        for line in p.open(encoding="utf-8", errors="ignore"):
            m = MOVES_RE.search(line)
            if not m:
                continue
            tags = [t for t in TAG_RE.findall(m.group(1)) if not t.startswith("!")]
            for t in tags:
                if re.match(r"(form|wep)\w+$", t) or t not in {"limitless", "shikigami", "lovetrain", "lightning"}:
                    out.setdefault(t, m.group(2))
    return out


def extract(click_file: Path):
    folder = click_file.parent
    mm = moves_map(folder)
    forms = []
    durations, cooldowns, costs, names = {}, {}, {}, {}
    for line in click_file.open(encoding="utf-8", errors="ignore"):
        line = line.rstrip("\r\n")
        m = ADD_FORM_RE.match(line)
        if m:
            p = parse_sel(m.group(1))
            forms.append((m.group(2), signature(p), p))
            continue
        m = SET_RE.match(line)
        if m:
            p = parse_sel(m.group(1))
            sig = signature(p)
            if m.group(2) in ("move", "domainstart", "domain"):
                durations.setdefault(sig, (m.group(2), int(m.group(3))))
            elif re.match(r"(ability|domainCD|wep)\w*", m.group(2)):
                cooldowns.setdefault(sig[:4], (m.group(2), int(m.group(3))))
            continue
        m = REMOVE_RE.match(line)
        if m:
            costs.setdefault(signature(parse_sel(m.group(1)))[:4], int(m.group(2)))
            continue
        if "on cooldown" in line:
            sel = SEL_RE.search(line)
            n = CD_NAME_RE.search(line)
            if sel and n:
                p = parse_sel(sel.group(1))
                names.setdefault(signature(p)[:4], strip_fmt(n.group(1)).strip())
    rows = []
    for form, sig, p in forms:
        dur = durations.get(sig)
        if dur is None:  # durations sometimes omit the cooldown=0 guard
            dur = next((v for k, v in durations.items() if k[:4] == sig[:4]), None)
        func = mm.get(form)
        hi, n = timeline_length(func) if func else (None, 0)
        rows.append({
            "form": form,
            "item": p["item"],
            "modifier": modifier_label(sig),
            "energy": costs.get(sig[:4], energy_of(p)),
            "energy_req": energy_of(p),
            "cooldown": cooldowns.get(sig[:4]),
            "duration": dur,
            "name": names.get(sig[:4]),
            "function": func,
            "timeline_max_tick": hi,
            "timeline_lines": n,
        })
    return rows


def main():
    result = defaultdict(list)
    for click in sorted(FN.rglob("*_click.mcfunction")):
        key = str(click.relative_to(FN).with_suffix(""))
        result[key] = extract(click)
    out = RESEARCH / "catalog"
    (out / "movesets.json").write_text(json.dumps(result, indent=1, ensure_ascii=False))
    md = ["# Extracted movesets", "",
          "Auto-generated by `tools/jja/movesets.py` from the addon's `*_click` and `*_moves` functions.",
          "Durations and cooldowns are in ticks (20 = 1 s). `energy` is the cost removed (or the minimum required).", ""]
    for key, rows in result.items():
        md += [f"## `{key}`", "", "| form | item | modifier | name | energy | cooldown | duration | timeline fn | max tick |",
               "|---|---|---|---|---|---|---|---|---|"]
        for r in rows:
            cd = f"{r['cooldown'][0]}={r['cooldown'][1]}" if r["cooldown"] else ""
            du = f"{r['duration'][0]}={r['duration'][1]}" if r["duration"] else ""
            md.append(f"| {r['form']} | `{r['item']}` | {r['modifier']} | {r['name'] or ''} | {r['energy'] or ''} | "
                      f"{cd} | {du} | `{r['function'] or ''}` | {r['timeline_max_tick'] or ''} |")
        md.append("")
    (out / "movesets.md").write_text("\n".join(md))
    print({k: len(v) for k, v in result.items()})


if __name__ == "__main__":
    main()
