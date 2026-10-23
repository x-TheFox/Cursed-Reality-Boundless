"""Shared helpers for reading the Jujutsu Awakening (JJA) Bedrock addon.

The addon lives next to this repo in the workspace:
    ../JJA RP AND BP/Behaviour/JujutsuAwa   (behaviour pack)
    ../JJA RP AND BP/Resource/JujutsuAwa    (resource pack)

Override with the JJA_ROOT environment variable if it moves.
"""
from __future__ import annotations

import json
import os
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
JJA_ROOT = Path(os.environ.get("JJA_ROOT", REPO.parent / "JJA RP AND BP"))
BP = JJA_ROOT / "Behaviour" / "JujutsuAwa"
RP = JJA_ROOT / "Resource" / "JujutsuAwa"
RESEARCH = REPO / "architecture_and_research"

_COMMENT_RE = re.compile(r'("(?:\\.|[^"\\])*")|//[^\n]*|/\*.*?\*/', re.S)
_TRAILING_COMMA_RE = re.compile(r",(\s*[}\]])")
# stray tokens Bedrock's parser tolerates, e.g. `"minecraft:particle_initial_speed": 175,s`
_STRAY_TOKEN_RE = re.compile(r",[ \t]*[A-Za-z_]+[ \t]*(?=\r?\n\s*\")")


def load_json(path: Path):
    """Load Bedrock-flavoured JSON (comments, trailing commas, BOM)."""
    text = path.read_text(encoding="utf-8-sig", errors="replace")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    text = _COMMENT_RE.sub(lambda m: m.group(1) or "", text)
    text = _TRAILING_COMMA_RE.sub(r"\1", text)
    text = _STRAY_TOKEN_RE.sub(",", text)
    return json.loads(text, strict=False)


def try_load(path: Path):
    try:
        return load_json(path)
    except Exception as exc:  # noqa: BLE001 - catalog must not die on one bad file
        return {"__error__": f"{type(exc).__name__}: {exc}"}


def files(root: Path, suffix: str):
    return sorted(p for p in root.rglob(f"*{suffix}") if p.is_file())


def rel(p: Path, base: Path) -> str:
    return str(p.relative_to(base))


def load_lang() -> dict[str, str]:
    out: dict[str, str] = {}
    for lang in [RP / "texts" / "en_US.lang", BP / "texts" / "en_US.lang"]:
        if not lang.exists():
            continue
        for line in lang.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.split("\t#")[0].strip()
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


_FMT_RE = re.compile("\u00a7.")


def strip_fmt(s: str) -> str:
    return _FMT_RE.sub("", s or "")
