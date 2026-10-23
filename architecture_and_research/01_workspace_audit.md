# 01 — Workspace & Dependency Audit

_Audited 2026-10-23._

## Workspace layout

| Path | What it is |
|---|---|
| `CRB/` | This repository (`x-TheFox/Cursed-Reality-Boundless`). Was empty apart from a README. |
| `JJA RP AND BP/Behaviour/JujutsuAwa` | JJA behaviour pack — "The Domain Update", Heroic Productions. 1,935 mcfunctions (~91k lines), 2 JS scripts, 317 entities, 229 items, 93 structures, 58 loot tables, 52 blocks, 29 spawn rules, 41 animation controllers. |
| `JJA RP AND BP/Resource/JujutsuAwa` | JJA resource pack — 257 client entities, 357 geometries, 1,469 animations, 3,475 particle effects, 416 sound events / 414 sound files, 1,077 textures, 36 attachables, impact-frame font sheets. |
| `JJA RP AND BP/*.zip` | Zipped copies of the two packs (identical content). |
| `Suggested API/` | Ten pre-downloaded NeoForge 1.21.1 library jars (below). |

The addon is never copied into the repo. The converter in `tools/jja/` reads it from the workspace path and writes generated assets into `src/generated/resources` (see MASTER_PLAN §5). `JJA_ROOT` overrides the location.

## Toolchain on this machine

| Tool | Version | Notes |
|---|---|---|
| Java | 21.0.10 LTS | Required by MC 1.21.1. |
| Gradle (brew) | present | Build uses the Gradle wrapper, not the system install. |
| Python | 3 (system) | Asset/research tooling. Always run with `-I`. |
| Node | present | Not needed. |
| gh | logged in as `x-TheFox` | Used for branches/PRs/merges. |

## Suggested libraries — evaluation

| Jar | Mod id | Licence | Maven coordinate | Verdict |
|---|---|---|---|---|
| geckolib-neoforge-1.21.1-4.9.3 | `geckolib` | MIT | `software.bernie.geckolib:geckolib-neoforge-1.21.1:4.9.3` (cloudsmith) | **Use.** Consumes Bedrock `.geo.json` + `.animation.json` natively, so all 357 JJA geometries and 1,469 animations port with no reformatting. Also ships a Molang parser we reuse for particles. |
| PlayerAnimationLibNeoforge-1.1.6+mc.1.21.1 | `player_animation_library` | MIT | `com.zigythebird.playeranim:PlayerAnimationLibNeo:1.1.6+mc.1.21.1` (repo.redlance.org) | **Use.** Layered player animation with a universal loader that reads GeckoLib/Bedrock animation JSON. Replaces Bedrock `playanimation` on players (1,914 uses). |
| free_camera_api-neo-1.21.1-3.2.0 | `free_camera_api` | GPL-3.0 | `maven.modrinth:free-camera-api:YPlk2vkI` | **Use as camera backend.** Prioritised modifier stack: world/local position, YXZ rotation **including roll** (Dutch angles), FOV, obstacle handling, renders the local player while detached. Our Cinematic Director drives one HIGHEST-priority modifier. |
| veil-neoforge-1.21.1-4.5.2 | `veil` | LGPL-3.0 | `foundry.veil:veil-neoforge-1.21.1:4.5.2` (blamejared) | **Use.** Post-processing pipelines (impact-frame inversion, chromatic aberration, radial blur, domain colour grading), dynamic lights. Incompatible with Embeddium/Rubidium — documented. |
| SmartBrainLib-neoforge-1.21.1-1.16.11 | `smartbrainlib` | MPL-2.0 | `net.tslat.smartbrainlib:SmartBrainLib-neoforge-1.21.1:1.16.11` (cloudsmith) | **Use.** Brain-based AI for the ~60 NPC sorcerers, curses, shikigami and bosses. |
| lodestone-1.21.1-1.8.2 | `lodestone` | GPL-3.0 | — | **Skip.** Its `neoforge.mods.toml` declares Minecraft `[1.21,1.21.1)`, which excludes 1.21.1. It also overlaps with Veil (rendering) and our own shake/particle code. |
| photon-neoforge-1.21.1-2.2.7 + ldlib2-2.2.42 | `photon`, `ldlib2` | GPL-3.0 / LGPL-3.0 | — | **Skip for now.** Photon is an editor-driven VFX format; JJA's 3,475 effects are already in Bedrock (Snowstorm) format. A native Bedrock particle runtime reproduces them exactly, without a 12 MB dependency pair. Can be revisited for new, Java-only effects. |
| kilagraph-neoforge-21.1.0.16 | `kilagraph` | MIT | — | **Skip.** Node-graph editor that depends on LDLib2. Nothing in JJA needs it. |
| yet_another_config_lib_v3-3.8.2 | `yet_another_config_lib_v3` | LGPL-3.0 | — | **Skip.** NeoForge 21.1's built-in `ConfigurationScreen` covers JJA's ten settings toggles. |

### Version pins

* Minecraft **1.21.1**, NeoForge **21.1.219+** (LDLib-family jars need ≥ 21.1.216; Free Camera API needs ≥ 21.1.215). The build pins the newest 21.1.x available at the time of writing (21.1.264 seen on maven.neoforged.net).
* Build plugin: **ModDevGradle** (`net.neoforged.moddev`), the current NeoForge-recommended toolchain.
* Parchment mappings for readable parameter names.

### Things deliberately *not* added

* No Mixin-heavy rendering libraries beyond the above. Our own mixins are limited to small hooks (impact-frame overlay ordering, hit-stop render partial tick), and each is documented where it is defined.
* No Kotlin; plain Java 21.
