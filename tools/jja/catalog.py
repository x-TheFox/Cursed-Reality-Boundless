"""Catalog every asset and logic file in the JJA addon.

Writes:
    architecture_and_research/catalog/*.md   human-readable inventories
    architecture_and_research/catalog/catalog.json   machine-readable index
        (consumed by the asset converter and the move-timeline extractor)

Run:  python3 -I tools/jja/catalog.py
"""
from __future__ import annotations

import collections
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import BP, RP, RESEARCH, files, load_lang, rel, strip_fmt, try_load  # noqa: E402

OUT = RESEARCH / "catalog"


def as_list(v):
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def bp_entities():
    rows = []
    for p in files(BP / "entities", ".json"):
        d = try_load(p)
        e = d.get("minecraft:entity", {}) if isinstance(d, dict) else {}
        desc = e.get("description", {})
        comps = e.get("components", {})
        fam = comps.get("minecraft:type_family", {}).get("family", [])
        hp = comps.get("minecraft:health", {})
        rows.append({
            "id": desc.get("identifier", "?"),
            "file": rel(p, BP),
            "families": fam,
            "health": hp.get("max", hp.get("value")),
            "spawnable": desc.get("is_spawnable"),
            "runtime": desc.get("runtime_identifier"),
            "properties": sorted((desc.get("properties") or {}).keys()),
            "component_groups": len(e.get("component_groups", {}) or {}),
            "events": sorted((e.get("events") or {}).keys()),
            "has_projectile": "minecraft:projectile" in comps,
            "error": d.get("__error__") if isinstance(d, dict) else None,
        })
    return rows


def rp_entities():
    rows = []
    for p in files(RP / "entity", ".json"):
        d = try_load(p)
        ce = d.get("minecraft:client_entity", {}) if isinstance(d, dict) else {}
        desc = ce.get("description", {})
        rows.append({
            "id": desc.get("identifier", "?"),
            "file": rel(p, RP),
            "geometry": desc.get("geometry", {}),
            "textures": desc.get("textures", {}),
            "materials": desc.get("materials", {}),
            "animations": desc.get("animations", {}),
            "render_controllers": as_list(desc.get("render_controllers")),
            "particle_effects": desc.get("particle_effects", {}),
            "sound_effects": desc.get("sound_effects", {}),
        })
    return rows


def items(lang):
    rows = []
    for p in files(BP / "items", ".json"):
        d = try_load(p)
        it = d.get("minecraft:item", {}) if isinstance(d, dict) else {}
        desc = it.get("description", {})
        comps = it.get("components", {})
        iid = desc.get("identifier", "?")
        name = comps.get("minecraft:display_name", {}).get("value")
        name = lang.get(name, name) if name else None
        name = name or lang.get(f"item.{iid}.name") or lang.get(f"item.{iid}")
        icon = comps.get("minecraft:icon")
        if isinstance(icon, dict):
            icon = icon.get("texture") or (icon.get("textures") or {}).get("default")
        rows.append({
            "id": iid,
            "file": rel(p, BP),
            "name": strip_fmt(name or ""),
            "icon": icon,
            "category": (desc.get("menu_category") or {}).get("category", desc.get("category")),
            "max_stack": comps.get("minecraft:max_stack_size"),
            "components": sorted(comps.keys()),
        })
    return rows


def particles():
    rows = []
    for p in files(RP / "particles", ".json"):
        d = try_load(p)
        pe = d.get("particle_effect", {}) if isinstance(d, dict) else {}
        desc = pe.get("description", {})
        brp = desc.get("basic_render_parameters", {})
        comps = pe.get("components", {}) or {}
        rows.append({
            "id": desc.get("identifier", "?"),
            "file": rel(p, RP),
            "texture": brp.get("texture"),
            "material": brp.get("material"),
            "emitter": [k for k in comps if k.startswith("minecraft:emitter_rate") or k.startswith("minecraft:emitter_lifetime")],
            "events": sorted((pe.get("events") or {}).keys()),
        })
    return rows


def sounds():
    path = RP / "sounds" / "sound_definitions.json"
    if not path.exists():
        path = RP / "sound_definitions.json"
    sd = try_load(path).get("sound_definitions", {})
    rows = []
    for key, v in sorted(sd.items()):
        srcs = []
        for s in as_list(v.get("sounds") if isinstance(v, dict) else None):
            srcs.append(s if isinstance(s, str) else s.get("name"))
        rows.append({"id": key, "category": v.get("category") if isinstance(v, dict) else None, "files": srcs})
    return rows


def animations():
    rows = []
    for p in files(RP / "animations", ".json"):
        d = try_load(p)
        for name, a in (d.get("animations", {}) if isinstance(d, dict) else {}).items():
            if not isinstance(a, dict):
                continue
            rows.append({
                "id": name,
                "file": rel(p, RP),
                "length": a.get("animation_length"),
                "loop": a.get("loop"),
                "bones": len(a.get("bones", {}) or {}),
                "sound_keys": len(a.get("sound_effects", {}) or {}),
                "particle_keys": len(a.get("particle_effects", {}) or {}),
                "timeline_keys": len(a.get("timeline", {}) or {}),
            })
    return rows


def controllers(root: Path, base: Path):
    rows = []
    for p in files(root, ".json"):
        d = try_load(p)
        for name, c in (d.get("animation_controllers", {}) if isinstance(d, dict) else {}).items():
            rows.append({"id": name, "file": rel(p, base), "states": len((c or {}).get("states", {}) or {})})
    return rows


def geometry():
    rows = []
    for p in files(RP / "models", ".json"):
        d = try_load(p)
        for g in as_list(d.get("minecraft:geometry") if isinstance(d, dict) else None):
            desc = g.get("description", {})
            rows.append({
                "id": desc.get("identifier", "?"),
                "file": rel(p, RP),
                "tex": [desc.get("texture_width"), desc.get("texture_height")],
                "bones": len(g.get("bones", []) or []),
            })
    return rows


def functions():
    folders = collections.defaultdict(lambda: {"files": 0, "lines": 0, "names": []})
    for p in files(BP / "functions", ".mcfunction"):
        r = p.relative_to(BP / "functions")
        key = str(r.parent) if str(r.parent) != "." else "(root)"
        n = sum(1 for _ in p.open(encoding="utf-8", errors="ignore"))
        folders[key]["files"] += 1
        folders[key]["lines"] += n
        folders[key]["names"].append(r.stem)
    return dict(sorted(folders.items()))


def simple_list(root: Path, suffix: str, base: Path):
    return [rel(p, base) for p in files(root, suffix)] if root.exists() else []


def md_table(rows, cols, title):
    out = [f"# {title}", "", f"Total: **{len(rows)}**", "", "| " + " | ".join(c for c, _ in cols) + " |",
           "|" + "---|" * len(cols)]
    for r in rows:
        cells = []
        for _, fn in cols:
            v = fn(r)
            if isinstance(v, (list, tuple)):
                v = ", ".join(str(x) for x in v)
            elif isinstance(v, dict):
                v = ", ".join(f"{k}={vv}" for k, vv in v.items())
            cells.append(str(v if v is not None else "").replace("|", "\\|").replace("\n", " ")[:220])
        out.append("| " + " | ".join(cells) + " |")
    return "\n".join(out) + "\n"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    lang = load_lang()
    cat = {
        "bp_entities": bp_entities(),
        "rp_entities": rp_entities(),
        "items": items(lang),
        "particles": particles(),
        "sounds": sounds(),
        "animations": animations(),
        "bp_controllers": controllers(BP / "animation_controllers", BP),
        "rp_controllers": controllers(RP / "animation_controllers", RP),
        "geometry": geometry(),
        "functions": functions(),
        "structures": simple_list(BP / "structures", ".mcstructure", BP),
        "loot_tables": simple_list(BP / "loot_tables", ".json", BP),
        "recipes": simple_list(BP / "recipes", ".json", BP),
        "blocks": simple_list(BP / "blocks", ".json", BP),
        "spawn_rules": simple_list(BP / "spawn_rules", ".json", BP),
        "features": simple_list(BP / "features", ".json", BP) + simple_list(BP / "feature_rules", ".json", BP),
        "textures": collections.Counter(str(p.relative_to(RP / "textures").parts[0]) for p in files(RP / "textures", ".png")),
        "sound_files": len(files(RP / "sounds", ".ogg")) + len(files(RP / "sounds", ".fsb")),
    }
    (OUT / "catalog.json").write_text(json.dumps(cat, indent=1, ensure_ascii=False))

    (OUT / "bp_entities.md").write_text(md_table(cat["bp_entities"], [
        ("id", lambda r: f"`{r['id']}`"), ("families", lambda r: r["families"]), ("hp", lambda r: r["health"]),
        ("projectile", lambda r: "yes" if r["has_projectile"] else ""), ("props", lambda r: len(r["properties"])),
        ("events", lambda r: len(r["events"])), ("file", lambda r: r["file"])], "Behaviour-pack entities"))
    (OUT / "rp_entities.md").write_text(md_table(cat["rp_entities"], [
        ("id", lambda r: f"`{r['id']}`"), ("geometry", lambda r: list(r["geometry"].values())),
        ("textures", lambda r: len(r["textures"])), ("animations", lambda r: len(r["animations"])),
        ("file", lambda r: r["file"])], "Client (resource-pack) entities"))
    (OUT / "items.md").write_text(md_table(cat["items"], [
        ("id", lambda r: f"`{r['id']}`"), ("name", lambda r: r["name"]), ("icon", lambda r: r["icon"]),
        ("category", lambda r: r["category"]), ("file", lambda r: r["file"])], "Items"))
    (OUT / "particles.md").write_text(md_table(cat["particles"], [
        ("id", lambda r: f"`{r['id']}`"), ("texture", lambda r: r["texture"]), ("material", lambda r: r["material"]),
        ("file", lambda r: r["file"])], "Particles"))
    (OUT / "sounds.md").write_text(md_table(cat["sounds"], [
        ("id", lambda r: f"`{r['id']}`"), ("category", lambda r: r["category"]), ("files", lambda r: r["files"])],
        "Sound events"))
    (OUT / "animations.md").write_text(md_table(cat["animations"], [
        ("id", lambda r: f"`{r['id']}`"), ("len (s)", lambda r: r["length"]), ("loop", lambda r: r["loop"]),
        ("bones", lambda r: r["bones"]), ("sfx", lambda r: r["sound_keys"]), ("pfx", lambda r: r["particle_keys"]),
        ("file", lambda r: r["file"])], "Animations"))
    (OUT / "geometry.md").write_text(md_table(cat["geometry"], [
        ("id", lambda r: f"`{r['id']}`"), ("tex", lambda r: "x".join(str(x) for x in r["tex"])),
        ("bones", lambda r: r["bones"]), ("file", lambda r: r["file"])], "Geometry models"))
    (OUT / "controllers.md").write_text(
        md_table(cat["bp_controllers"], [("id", lambda r: f"`{r['id']}`"), ("states", lambda r: r["states"]),
                                         ("file", lambda r: r["file"])], "Behaviour animation controllers")
        + "\n" + md_table(cat["rp_controllers"], [("id", lambda r: f"`{r['id']}`"), ("states", lambda r: r["states"]),
                                                   ("file", lambda r: r["file"])], "Resource animation controllers"))
    fn = ["# mcfunction folders", "", "| folder | files | lines | functions |", "|---|---|---|---|"]
    for k, v in cat["functions"].items():
        fn.append(f"| `{k}` | {v['files']} | {v['lines']} | {', '.join(sorted(v['names']))[:1800]} |")
    (OUT / "functions.md").write_text("\n".join(fn) + "\n")
    misc = ["# World content", ""]
    for key in ["structures", "loot_tables", "recipes", "blocks", "spawn_rules", "features"]:
        misc += [f"## {key} ({len(cat[key])})", "", ", ".join(f"`{x}`" for x in cat[key]), ""]
    misc += ["## textures by folder", ""] + [f"- `{k}`: {v}" for k, v in sorted(cat["textures"].items())]
    misc += ["", f"Sound files: {cat['sound_files']}"]
    (OUT / "world.md").write_text("\n".join(misc) + "\n")

    summary = {k: (len(v) if not isinstance(v, int) else v) for k, v in cat.items()}
    errors = [r for r in cat["bp_entities"] if r.get("error")]
    print(json.dumps(summary, indent=1))
    print("bp entity parse errors:", len(errors))


if __name__ == "__main__":
    main()
