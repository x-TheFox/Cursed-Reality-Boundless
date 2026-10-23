# Architecture & Research

Phase 1 reverse-engineering of the Jujutsu Awakening Bedrock addon. Start with [MASTER_PLAN.md](../MASTER_PLAN.md).

1. [Workspace & dependency audit](01_workspace_audit.md)
2. [How JJA works](02_addon_architecture.md)
3. [Why JJA feels cinematic](03_cinematics.md)
4. [Roster & gap analysis](04_roster_and_gap_analysis.md)
5. [Networking & authority](05_networking_and_authority.md)
6. [Kits, uniqueness & admin tools](06_kits_uniqueness_and_admin.md)

`catalog/` is generated. Regenerate with:

```bash
python3 -I tools/jja/catalog.py && python3 -I tools/jja/movesets.py
```

The roll tables (`catalog/roll_tables.md`) were extracted once from `menu_traitshow`, `menu_techniqueshow` and `ce_item`.
