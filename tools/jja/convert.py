"""Convert the JJA resource pack into NeoForge/GeckoLib/PlayerAnimationLib resources.

Run from the repo root (needs Pillow):

    python3 tools/jja/convert.py            # everything
    python3 tools/jja/convert.py textures models animations sounds particles lang glyphs items index

Outputs (committed):
    src/main/resources/assets/crb/textures/**               every PNG, paths sanitised
    src/main/resources/assets/crb/geo/<name>.geo.json       one GeckoLib model per Bedrock geometry
    src/main/resources/assets/crb/animations/<file>.animation.json      GeckoLib (entities, items)
    src/main/resources/assets/crb/player_animations/<file>.json         PlayerAnimationLib (players)
    src/main/resources/assets/crb/sounds/**, sounds.json
    src/main/resources/assets/crb/bedrock_particles/<ns>/<name>.json    for the BedrockFX runtime
    src/main/resources/assets/crb/textures/glyph/<page>/<cell>.png      impact frames and UI glyphs
    src/main/resources/assets/crb/models/item/<item>.json   flat item models from item_texture.json
    src/main/resources/assets/crb/lang/en_us.json           JJA strings merged under crb keys
    src/main/resources/assets/crb/jja/*.json                indexes (entities, geometry, animations,
                                                             particles, sounds, glyphs, render/anim controllers)

Everything is deterministic: rerunning produces identical files.
"""
from __future__ import annotations

import json
import re
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import BP, REPO, RP, files, load_json, try_load  # noqa: E402

ASSETS = REPO / "src" / "main" / "resources" / "assets" / "crb"
INDEX = ASSETS / "jja"
NS = "crb"
REPORT: dict[str, list] = defaultdict(list)

_VALID = re.compile(r"[^a-z0-9_./-]")


def sanitize(path: str) -> str:
    """Make a Bedrock file path a valid Minecraft resource path."""
    p = path.replace("\\", "/").lower()
    p = re.sub(r"\s*-\s*copy", "_copy", p)
    p = p.replace(" ", "_").replace("!", "_")
    p = _VALID.sub("_", p)
    p = re.sub(r"_+", "_", p)
    p = re.sub(r"_(?=[./])", "", p)
    return p.strip("_")


def strip_ns(identifier: str) -> tuple[str, str]:
    """`ds:foo` -> ('ds', 'foo'); `geometry.foo` -> ('', 'geometry.foo')."""
    if ":" in identifier:
        ns, name = identifier.split(":", 1)
        return ns, name
    return "", identifier


def dump(path: Path, data, pretty: bool = False):
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, indent=1 if pretty else None, separators=None if pretty else (",", ":"),
                      ensure_ascii=False, sort_keys=pretty)
    path.write_text(text + ("\n" if pretty else ""), encoding="utf-8")


def reset(path: Path):
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


# ---------------------------------------------------------------------------------------------
# textures

def convert_textures():
    out = ASSETS / "textures"
    glyph_dir = out / "glyph"  # owned by convert_glyphs
    for child in out.iterdir() if out.exists() else []:
        if child != glyph_dir:
            shutil.rmtree(child) if child.is_dir() else child.unlink()
    out.mkdir(parents=True, exist_ok=True)
    seen: dict[str, str] = {}
    mapping = {}
    for src in files(RP / "textures", ".png"):
        rel = str(src.relative_to(RP / "textures"))
        dst = sanitize(rel)
        if dst in seen and seen[dst] != rel:
            stem, ext = dst.rsplit(".", 1)
            n = 2
            while f"{stem}_{n}.{ext}" in seen:
                n += 1
            REPORT["texture_collisions"].append(f"{rel} -> {stem}_{n}.{ext} (clashes with {seen[dst]})")
            dst = f"{stem}_{n}.{ext}"
        seen[dst] = rel
        mapping["textures/" + rel[:-4]] = "textures/" + dst[:-4]
        target = out / dst
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, target)
        mcmeta = src.with_suffix(".png.mcmeta")
        if mcmeta.exists():
            shutil.copyfile(mcmeta, target.with_suffix(".png.mcmeta"))
    dump(INDEX / "textures.json", dict(sorted(mapping.items())), pretty=True)
    print(f"textures: {len(mapping)}")
    return mapping


def texture_ref(bedrock_path: str | None, mapping: dict) -> str | None:
    """Bedrock 'textures/entity/steve' -> 'crb:textures/entity/steve.png' (or None if absent)."""
    if not bedrock_path:
        return None
    key = bedrock_path.replace("\\", "/")
    if key.endswith(".png"):
        key = key[:-4]
    if key in mapping:
        return f"{NS}:{mapping[key]}.png"
    lower = {k.lower(): v for k, v in mapping.items()}
    if key.lower() in lower:
        return f"{NS}:{lower[key.lower()]}.png"
    REPORT["missing_textures"].append(bedrock_path)
    return None


# ---------------------------------------------------------------------------------------------
# geometry

def _legacy_geometries(data: dict):
    """1.8 / 1.10 format: {"geometry.name[:parent]": {...}} -> list of (identifier, parent, body)."""
    for key, body in data.items():
        if not key.startswith("geometry.") or not isinstance(body, dict):
            continue
        ident, _, parent = key.partition(":")
        yield ident, parent or None, body


def convert_models(tex_map: dict):
    out = ASSETS / "geo"
    reset(out)
    poly_out = INDEX / "poly_mesh"
    reset(poly_out)
    geos: dict[str, dict] = {}
    origin: dict[str, str] = {}
    legacy_parents: dict[str, str] = {}
    for src in files(RP / "models", ".json"):
        d = try_load(src)
        if "__error__" in d:
            REPORT["model_parse_errors"].append(f"{src.relative_to(RP)}: {d['__error__']}")
            continue
        rel = str(src.relative_to(RP))
        entries = []
        if "minecraft:geometry" in d:
            for g in d["minecraft:geometry"]:
                entries.append((g["description"]["identifier"], None, g))
        else:
            for ident, parent, body in _legacy_geometries(d):
                g = {
                    "description": {
                        "identifier": ident,
                        "texture_width": body.get("texturewidth", 64),
                        "texture_height": body.get("textureheight", 64),
                        "visible_bounds_width": body.get("visible_bounds_width", 2),
                        "visible_bounds_height": body.get("visible_bounds_height", 2),
                        "visible_bounds_offset": body.get("visible_bounds_offset", [0, 1, 0]),
                    },
                    "bones": body.get("bones", []),
                }
                if parent:
                    legacy_parents[ident] = parent
                entries.append((ident, parent, g))
        for ident, _, g in entries:
            if ident in geos:
                REPORT["duplicate_geometry"].append(f"{ident}: kept {origin[ident]}, ignored {rel}")
                continue
            geos[ident] = g
            origin[ident] = rel

    # resolve legacy inheritance: child bones override parent bones of the same name
    for ident, parent in legacy_parents.items():
        if parent not in geos:
            REPORT["missing_geometry_parent"].append(f"{ident} -> {parent}")
            continue
        own = {b["name"]: b for b in geos[ident].get("bones", [])}
        merged = [own.pop(b["name"], b) for b in geos[parent].get("bones", [])] + list(own.values())
        geos[ident]["bones"] = merged

    index = {}
    used_names: set[str] = set()
    for ident in sorted(geos):
        g = geos[ident]
        name = sanitize(ident[len("geometry."):] if ident.startswith("geometry.") else ident)
        base, n = name, 2
        while name in used_names:
            name = f"{base}_{n}"
            n += 1
        used_names.add(name)
        bones = g.get("bones", []) or []
        # GeckoLib cannot read Bedrock poly meshes; move them to a sidecar rendered by our own layer
        meshes = {b["name"]: b.pop("poly_mesh") for b in bones if "poly_mesh" in b}
        entry = {"file": f"{NS}:geo/{name}.geo.json", "source": origin[ident], "bones": len(bones)}
        if meshes:
            dump(poly_out / f"{name}.json", {"geometry": ident, "texture_width": g["description"].get("texture_width"),
                                             "texture_height": g["description"].get("texture_height"), "bones": meshes})
            entry["poly_mesh"] = f"{NS}:jja/poly_mesh/{name}.json"
            REPORT["poly_mesh_geometry"].append(f"{ident} (mesh moved to jja/poly_mesh/{name}.json)")
        dump(out / f"{name}.geo.json", {"format_version": "1.12.0", "minecraft:geometry": [g]})
        index[ident] = entry
    dump(INDEX / "geometry.json", index, pretty=True)
    print(f"models: {len(index)} geometries")
    return index


# ---------------------------------------------------------------------------------------------
# animations

PLAYER_BONES = {
    "head": "head", "body": "body", "rightArm": "right_arm", "leftArm": "left_arm",
    "rightLeg": "right_leg", "leftLeg": "left_leg", "rightItem": "right_item", "leftItem": "left_item",
    "cape": "cape", "root": "torso", "waist": "torso",
}
_PLAYER_CORE = {"head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg", "root", "waist"}


def _player_copy(anim: dict) -> dict | None:
    bones = anim.get("bones") or {}
    if not any(b in _PLAYER_CORE for b in bones):
        return None
    mapped = {}
    for bone, data in bones.items():
        target = PLAYER_BONES.get(bone)
        if target is None:
            continue
        if target == "torso" and "torso" in mapped and bone == "waist":
            continue  # root wins over waist
        mapped[target] = data
    out = {k: v for k, v in anim.items() if k in ("loop", "animation_length", "override_previous_animation",
                                                    "anim_time_update", "blend_weight", "start_delay", "loop_delay")}
    out["bones"] = mapped
    return out


def convert_animations():
    out = ASSETS / "animations"
    pout = ASSETS / "player_animations"
    reset(out)
    reset(pout)
    index = {}
    player_count = 0
    for src in files(RP / "animations", ".json"):
        d = try_load(src)
        if "__error__" in d:
            REPORT["animation_parse_errors"].append(f"{src.relative_to(RP)}: {d['__error__']}")
            continue
        stem = sanitize(src.name[: -len(".json")])
        if stem.endswith(".animation"):
            stem = stem[: -len(".animation")]
        anims = d.get("animations", {}) or {}
        dump(out / f"{stem}.animation.json", {"format_version": "1.8.0", "animations": anims})
        players = {}
        for name, a in anims.items():
            if not isinstance(a, dict):
                continue
            if name in index:
                REPORT["duplicate_animation"].append(f"{name}: kept {index[name]['file']}, also in {src.name}")
            else:
                index[name] = {"file": f"{NS}:animations/{stem}.animation.json", "length": a.get("animation_length"),
                               "loop": a.get("loop", False)}
            pc = _player_copy(a)
            if pc is not None:
                players[name] = pc
        if players:
            dump(pout / f"{stem}.json", {"format_version": "1.8.0", "animations": players})
            for name in players:
                index.setdefault(name, {})["player"] = f"{NS}:{name}"
            player_count += len(players)
    dump(INDEX / "animations.json", dict(sorted(index.items())), pretty=True)
    print(f"animations: {len(index)} ({player_count} also as player animations)")
    return index


# ---------------------------------------------------------------------------------------------
# sounds

def _java_vanilla_sounds() -> set[str] | None:
    """Sound paths shipped with Java 1.21.1, from the asset index ModDevGradle downloads (if present)."""
    idx = Path.home() / ".gradle" / "caches" / "neoformruntime" / "assets" / "indexes" / "17.json"
    if not idx.exists():
        return None
    objs = json.loads(idx.read_text())["objects"]
    return {k[len("minecraft/sounds/"):-len(".ogg")] for k in objs if k.startswith("minecraft/sounds/") and k.endswith(".ogg")}


def convert_sounds():
    out = ASSETS / "sounds"
    reset(out)
    available = {}
    for src in sorted((RP / "sounds").rglob("*")):
        if src.suffix.lower() == ".ogg":
            rel = str(src.relative_to(RP))[: -len(".ogg")]  # sounds/mob/foo
            dst = sanitize(rel[len("sounds/"):])
            target = out / f"{dst}.ogg"
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, target)
            available[rel.lower()] = dst
        elif src.suffix.lower() == ".fsb":
            REPORT["skipped_fsb"].append(f"{src.relative_to(RP)} (FMOD FADPCM; vanilla override, not convertible)")

    by_basename = defaultdict(list)
    for rel, dst in available.items():
        by_basename[rel.rsplit("/", 1)[-1]].append(dst)
    vanilla = _java_vanilla_sounds()

    defs = try_load(RP / "sounds" / "sound_definitions.json").get("sound_definitions", {})
    sounds_json = {}
    index = {}
    for event, body in sorted(defs.items()):
        if not isinstance(body, dict):
            continue
        key = sanitize(event)
        attenuation = body.get("max_distance")
        entries = []
        for s in body.get("sounds", []) or []:
            s = {"name": s} if isinstance(s, str) else dict(s)
            name = str(s.get("name", "")).replace("\\", "/")
            base = name.lower().rsplit("/", 1)[-1]
            vanilla_path = name[len("sounds/"):] if name.startswith("sounds/") else name
            if name.lower() in available:
                ref = f"{NS}:{available[name.lower()]}"
            elif len(by_basename.get(base, [])) == 1:
                ref = f"{NS}:{by_basename[base][0]}"
                REPORT["sounds_found_by_filename"].append(f"{event}: {name} -> {ref}")
            elif vanilla is not None and vanilla_path in vanilla:
                ref = f"minecraft:{vanilla_path}"
                REPORT["vanilla_sound_refs"].append(f"{event}: {name}")
            elif vanilla is None and name.startswith("sounds/"):
                ref = f"minecraft:{vanilla_path}"
                REPORT["vanilla_sound_refs_unverified"].append(f"{event}: {name}")
            else:
                REPORT["missing_sound_files"].append(f"{event}: {name} (missing from the JJA pack)")
                continue
            e = {"name": ref}
            if "volume" in s:
                e["volume"] = float(s["volume"])
            if "pitch" in s:
                e["pitch"] = float(s["pitch"])
            if "weight" in s:
                e["weight"] = int(s["weight"])
            if s.get("stream"):
                e["stream"] = True
            if attenuation is not None:
                e["attenuation_distance"] = max(1, int(min(float(attenuation), 256)))
            entries.append(e if len(e) > 1 else ref)
        if not entries:
            continue
        sounds_json[key] = {"sounds": entries}
        index[event] = {"event": f"{NS}:{key}", "category": body.get("category", "neutral")}
    dump(ASSETS / "sounds.json", sounds_json, pretty=True)
    dump(INDEX / "sounds.json", index, pretty=True)
    print(f"sounds: {len(available)} files, {len(sounds_json)} events")
    return index


# ---------------------------------------------------------------------------------------------
# particles

def convert_particles(tex_map: dict):
    out = ASSETS / "bedrock_particles"
    reset(out)
    index = {}
    for src in files(RP / "particles", ".json"):
        d = try_load(src)
        pe = d.get("particle_effect") if isinstance(d, dict) else None
        if not pe:
            REPORT["particle_parse_errors"].append(f"{src.relative_to(RP)}: {d.get('__error__', 'no particle_effect')}")
            continue
        ident = pe.get("description", {}).get("identifier", "")
        ns, name = strip_ns(ident)
        ns = sanitize(ns or "minecraft")
        path = f"{ns}/{sanitize(name)}"
        if ident in index:
            REPORT["duplicate_particle"].append(f"{ident}: kept {index[ident]['file']}, ignored {src.relative_to(RP)}")
            continue
        brp = pe.get("description", {}).get("basic_render_parameters", {})
        tex = texture_ref(brp.get("texture"), tex_map) if brp.get("texture") else None
        if tex:
            brp["texture"] = tex
        dump(out / f"{path}.json", d)
        index[ident] = {"file": f"{NS}:bedrock_particles/{path}.json", "texture": tex, "material": brp.get("material")}
    dump(INDEX / "particles.json", dict(sorted(index.items())), pretty=True)
    print(f"particles: {len(index)}")
    return index


# ---------------------------------------------------------------------------------------------
# glyph sheets (impact frames, UI)

TITLE_PAGES = None


def _title_glyph_pages() -> set[int]:
    pages = set()
    for p in files(BP / "functions", ".mcfunction"):
        for line in p.open(encoding="utf-8", errors="ignore"):
            if "title" not in line:
                continue
            for ch in line:
                o = ord(ch)
                if 0xE000 <= o < 0xF900:
                    pages.add(o >> 8)
    return pages


def convert_glyphs():
    from PIL import Image

    Image.MAX_IMAGE_PIXELS = None
    out = ASSETS / "textures" / "glyph"
    reset(out)
    used_in_titles = _title_glyph_pages()
    index = {}
    for sheet in sorted((RP / "font").glob("glyph_*.png")):
        page = sheet.stem.split("_")[1].upper()
        page_num = int(page, 16)
        im = Image.open(sheet).convert("RGBA")
        cell = im.size[0] / 16.0
        kept = 0
        for row in range(16):
            for col in range(16):
                box = (round(col * cell), round(row * cell), round((col + 1) * cell), round((row + 1) * cell))
                c = im.crop(box)
                alpha = c.getchannel("A").getextrema()
                if alpha[1] == 0:
                    continue  # fully transparent cell
                lo, hi = c.convert("L").getextrema()
                if hi == 0 and alpha[0] == 255:
                    pass  # solid black frame: still a valid (blackout) impact frame
                code = (page_num << 8) | (row << 4) | col
                name = f"{row:x}{col:x}"
                q = c.quantize(colors=128, method=Image.Quantize.FASTOCTREE)
                target = out / page.lower() / f"{name}.png"
                target.parent.mkdir(parents=True, exist_ok=True)
                q.save(target, "PNG", optimize=True)
                index[f"{code:04X}"] = {"texture": f"{NS}:textures/glyph/{page.lower()}/{name}.png",
                                        "size": c.size[0], "impact": page_num in used_in_titles}
                kept += 1
        print(f"glyphs: page {page} -> {kept} cells")
    dump(INDEX / "glyphs.json", index, pretty=True)
    return index


# ---------------------------------------------------------------------------------------------
# items, lang

def convert_items(tex_map: dict):
    out = ASSETS / "models" / "item"
    reset(out)
    atlas = try_load(RP / "textures" / "item_texture.json").get("texture_data", {})
    count = 0
    for src in files(BP / "items", ".json"):
        d = try_load(src)
        it = d.get("minecraft:item", {}) if isinstance(d, dict) else {}
        ident = it.get("description", {}).get("identifier", "")
        ns, name = strip_ns(ident)
        if not name:
            continue
        icon = (it.get("components", {}) or {}).get("minecraft:icon")
        if isinstance(icon, dict):
            icon = icon.get("texture") or (icon.get("textures") or {}).get("default")
        entry = atlas.get(icon or name) or atlas.get(name)
        tex_path = None
        if entry:
            t = entry.get("textures")
            tex_path = t[0] if isinstance(t, list) else (t.get("path") if isinstance(t, dict) else t)
        ref = texture_ref(tex_path, tex_map) if tex_path else None
        if ref is None:
            REPORT["items_without_icon"].append(ident)
            continue
        layer = ref[: -len(".png")].replace(f"{NS}:textures/", f"{NS}:")
        dump(out / f"{sanitize(name)}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": layer}}, pretty=True)
        count += 1
    print(f"item models: {count}")


def convert_lang():
    path = ASSETS / "lang" / "en_us.json"
    existing = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    generated = {}
    src = RP / "texts" / "en_US.lang"
    for raw in src.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.split("\t#")[0].strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k, v = k.strip(), v.strip().replace("\\n", "\n")
        m = re.match(r"^(item|entity|tile|action\.hint\.exit)\.(?:([a-z0-9_]+):)?([^.]+(?:\.[^.]+)*?)(?:\.name)?$", k)
        if m and m.group(2) in ("ds", None) and m.group(1) in ("item", "entity", "tile") and m.group(2) == "ds":
            kind = {"item": "item", "entity": "entity", "tile": "block"}[m.group(1)]
            generated[f"{kind}.{NS}.{sanitize(m.group(3))}"] = v
        else:
            generated[f"jja.{k}"] = v
    added = 0
    for k, v in generated.items():
        if k not in existing:
            existing[k] = v
            added += 1
    dump(path, dict(sorted(existing.items())), pretty=True)
    print(f"lang: {added} new keys ({len(existing)} total)")


# ---------------------------------------------------------------------------------------------
# entity index (drives the generic GeckoLib renderers later)

# Bedrock vanilla animation families JJA entities inherit; Java gets generic biped/quadruped
# animations for these instead (see conversion_report.md).
VANILLA_ANIMATION_FAMILIES = {
    "humanoid", "player", "piglin", "zombie", "skeleton", "armor_stand", "villager", "villager_v2", "wolf",
    "iron_golem", "pillager", "vindicator", "evoker", "witch", "enderman", "spider", "creeper", "pig",
    "cow", "sheep", "chicken", "horse", "llama", "fox", "cat", "ocelot", "bee", "warden", "wither",
    "ender_dragon", "blaze", "ghast", "slime", "phantom", "drowned", "husk", "stray", "vex", "allay",
    "axolotl", "goat", "frog", "camel", "sniffer", "agent", "npc", "parrot", "bat", "squid", "dolphin",
    "guardian", "elder_guardian", "shulker", "strider", "hoglin", "zoglin", "ravager", "panda", "polar_bear",
    "rabbit", "turtle", "trident", "fishing_hook", "arrow", "shield", "bow", "crossbow", "spyglass",
}


def build_entity_index(tex_map, geo_index, anim_index):
    entities = {}
    for src in files(RP / "entity", ".json"):
        d = try_load(src)
        ce = d.get("minecraft:client_entity") if isinstance(d, dict) else None
        if not ce:
            continue
        desc = ce.get("description", {})
        ident = desc.get("identifier")
        if not ident:
            continue
        if ident in entities:
            REPORT["duplicate_client_entity"].append(f"{ident}: kept {entities[ident]['source']}, ignored {src.relative_to(RP)}")
            continue
        geometry = {}
        for k, g in (desc.get("geometry") or {}).items():
            if g in geo_index:
                geometry[k] = geo_index[g]["file"]
            else:
                REPORT["entity_missing_geometry"].append(f"{ident}: {g}")
        textures = {}
        for k, t in (desc.get("textures") or {}).items():
            ref = texture_ref(t, tex_map)
            if ref:
                textures[k] = ref
        animations = {}
        for k, a in (desc.get("animations") or {}).items():
            if a.startswith("controller."):
                animations[k] = {"controller": a}
            elif a in anim_index:
                animations[k] = {"animation": a, "file": anim_index[a].get("file")}
            elif a.split(".")[1:2] and a.split(".")[1] in VANILLA_ANIMATION_FAMILIES:
                animations[k] = {"vanilla": a}
                REPORT["vanilla_animation_refs"].append(a)
            else:
                REPORT["entity_missing_animation"].append(f"{ident}: {a}")
        entities[ident] = {
            "source": str(src.relative_to(RP)),
            "geometry": geometry,
            "textures": textures,
            "materials": desc.get("materials", {}),
            "animations": animations,
            "scripts": desc.get("scripts", {}),
            "render_controllers": desc.get("render_controllers", []),
            "particle_effects": desc.get("particle_effects", {}),
            "sound_effects": desc.get("sound_effects", {}),
        }
    dump(INDEX / "entities.json", dict(sorted(entities.items())), pretty=True)

    for kind, folder in (("render_controllers", "render_controllers"), ("animation_controllers", "animation_controllers")):
        merged = {}
        for src in files(RP / folder, ".json"):
            merged.update((try_load(src).get(kind) or {}))
        dump(INDEX / f"{kind}.json", dict(sorted(merged.items())), pretty=True)

    attach = {}
    for src in files(RP / "attachables", ".json"):
        d = try_load(src).get("minecraft:attachable", {})
        desc = d.get("description", {})
        if desc.get("identifier"):
            attach[desc["identifier"]] = {
                "geometry": {k: geo_index.get(g, {}).get("file") for k, g in (desc.get("geometry") or {}).items()},
                "textures": {k: texture_ref(t, tex_map) for k, t in (desc.get("textures") or {}).items()},
                "animations": desc.get("animations", {}),
                "scripts": desc.get("scripts", {}),
            }
    dump(INDEX / "attachables.json", dict(sorted(attach.items())), pretty=True)
    print(f"index: {len(entities)} client entities, {len(attach)} attachables")


def write_report():
    lines = ["# Asset conversion report", "", "Generated by `tools/jja/convert.py`. Items listed here were not converted or need follow-up.", ""]
    for key in sorted(REPORT):
        vals = sorted(set(REPORT[key]))
        lines += [f"## {key} ({len(vals)})", ""] + [f"- {v}" for v in vals[:400]]
        if len(vals) > 400:
            lines.append(f"- … {len(vals) - 400} more")
        lines.append("")
    (REPO / "architecture_and_research" / "catalog" / "conversion_report.md").write_text("\n".join(lines), encoding="utf-8")
    print("report:", {k: len(set(v)) for k, v in sorted(REPORT.items())})


def main(argv):
    all_steps = {"textures", "models", "animations", "sounds", "particles", "lang", "glyphs", "items", "index"}
    steps = set(argv) or all_steps
    INDEX.mkdir(parents=True, exist_ok=True)
    tex_map = convert_textures() if "textures" in steps else json.loads((INDEX / "textures.json").read_text())
    geo = convert_models(tex_map) if "models" in steps else json.loads((INDEX / "geometry.json").read_text())
    anim = convert_animations() if "animations" in steps else json.loads((INDEX / "animations.json").read_text())
    if "sounds" in steps:
        convert_sounds()
    if "particles" in steps:
        convert_particles(tex_map)
    if "glyphs" in steps:
        convert_glyphs()
    if "items" in steps:
        convert_items(tex_map)
    if "lang" in steps:
        convert_lang()
    if "index" in steps:
        build_entity_index(tex_map, geo, anim)
    if steps == all_steps:
        write_report()  # partial runs would produce an incomplete report


if __name__ == "__main__":
    main(sys.argv[1:])
