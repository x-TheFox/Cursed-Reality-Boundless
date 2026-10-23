# Cursed Reality: Boundless — Master Plan

A NeoForge 1.21.1 port of the **Jujutsu Awakening (JJA)** Bedrock addon ("The Domain Update", Heroic Productions), rebuilt on Java systems: same moves, same timing, same assets, no hotbar clutter, no desync.

Research that backs every decision here is in [`architecture_and_research/`](architecture_and_research/):

| Doc | Contents |
|---|---|
| [01 Workspace audit](architecture_and_research/01_workspace_audit.md) | Toolchain, library evaluation, version pins |
| [02 Addon architecture](architecture_and_research/02_addon_architecture.md) | How JJA's tick loop, scoreboards, techniques and timelines work |
| [03 Cinematics](architecture_and_research/03_cinematics.md) | Measured camera / shake / fade / impact-frame language, beat-by-beat breakdowns |
| [04 Roster & gaps](architecture_and_research/04_roster_and_gap_analysis.md) | Every technique, tool, NPC, boss; stubbed and missing abilities |
| [05 Networking](architecture_and_research/05_networking_and_authority.md) | Payloads, authority, lag compensation, binds, spectating |
| [06 Kits & uniqueness](architecture_and_research/06_kits_uniqueness_and_admin.md) | One-user-per-technique rule, data-driven character kits, OP commands, Gojo/Six Eyes upgrades, Sukuna→Megumi and Yuji's vessel start |
| [`catalog/`](architecture_and_research/catalog/) | Auto-generated inventories (entities, items, particles, sounds, animations, geometry, functions, movesets, roll tables) |

---

## 1. Identity

| | |
|---|---|
| Mod id | `crb` |
| Name | Cursed Reality: Boundless |
| Package | `io.github.xthefox.crb` |
| Minecraft / Loader | 1.21.1 / NeoForge 21.1.x (≥ 21.1.219) |
| Java | 21 |
| Build | Gradle wrapper + ModDevGradle, Parchment mappings |
| JJA namespace mapping | `ds:foo` → `crb:foo` (ids otherwise unchanged, so research tables map 1:1) |

## 2. Dependency stack

| Library | Version | Use |
|---|---|---|
| GeckoLib | 4.9.3 | Entity/block/item models and animations straight from JJA's Bedrock `.geo.json` / `.animation.json`; its Molang parser powers the particle runtime. |
| Player Animation Library | 1.1.6+mc.1.21.1 | Layered player body animation from the same Bedrock animation files. |
| Free Camera API | 3.2.0 (`maven.modrinth:free-camera-api:YPlk2vkI`) | Low-level camera modifier (position, YXZ rotation incl. roll, FOV, obstacle handling). Our director is the only writer. |
| Veil | 4.5.2 | Post-processing (impact frames, chromatic split, radial blur, domain grading), dynamic lights. |
| SmartBrainLib | 1.16.11 | NPC/boss AI. |

Rejected with reasons in audit doc: Lodestone (declares MC `[1.21,1.21.1)`), Photon + LDLib2 + KilaGraph (format mismatch, size), YACL (built-in config screen suffices).

## 3. Architecture overview

```
            ┌───────────────────────── server (authoritative) ─────────────────────────┐
 input ──►  │ AbilityInput ─► VariantResolver ─► MoveInstance (elapsed tick)          │
 payloads   │                     │                 ├─ MoveLogic.tick(t)  (Java: damage,│
            │                     │                 │   projectiles, binds, domains)   │
            │                     ▼                 └─ hit tests (lag-compensated)     │
            │              SorcererData / CombatState attachments (energy, cd, stun…)  │
            └──────────┬───────────────────────────────────────────────┬──────────────┘
                MoveStart / MoveEnd / ImpactEvent / BindSync / DomainSync / *Sync
            ┌──────────▼───────────────────── clients ──────────────────▼──────────────┐
            │ MovePresenter: plays MoveScript cues at elapsed t                       │
            │   ├─ PlayerAnimationLib / GeckoLib animations                           │
            │   ├─ Sounds (layered, distance-scoped)                                  │
            │   ├─ BedrockFX particle runtime                                         │
            │   └─ CinematicDirector: shots (splines, roll, FOV), trauma shake,       │
            │      fades, impact-frame overlay (Veil), hit-stop                       │
            │ HUD: CE bar, slot cooldowns w/ variant preview, Fuga, clash bar         │
            └─────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Code layout

```
io.github.xthefox.crb
 ├─ CRB                      mod entrypoint, registries
 ├─ core/                    config, registries (custom: TECHNIQUE, ABILITY, DAMAGE_KIND, DOMAIN)
 ├─ data/                    attachments: SorcererData, CombatState, (later) MissionData
 ├─ net/                     payload records + handlers
 ├─ ability/                 Technique, Ability, Variant, Modifiers, AbilityUser, VariantResolver
 ├─ move/                    MoveScript (data), MoveInstance, MoveLogic, MoveRegistry
 ├─ combat/                  hit tests, lag-comp history, DamageKind, stun/i-frames, M1 combos,
 │                            Black Flash, parry, evade/dash, binds, knockback helpers
 ├─ domain/                  DomainManager, barrier entity, sure-hit, clash
 ├─ technique/<name>/        one package per technique (logic + entities)
 ├─ entity/                  shared projectiles, set-piece entities, shikigami, NPCs, bosses
 ├─ progression/             stats, grades, exp, traits, roll tables, creation screen
 ├─ world/                   structures, spawns, missions, events, blocks (plushies, crates)
 └─ client/
     ├─ cinematic/           CinematicDirector, Shot, Spline, Ease, Shake, Fade, ImpactFrames, HitStop
     ├─ fx/                  BedrockFX (Snowstorm-compatible particle runtime)
     ├─ input/               Combat Mode, keymappings, radial menu
     ├─ hud/                 overlays
     └─ render/              GeckoLib renderers, barrier/domain rendering
```

### 3.2 Moves = data + logic

Every JJA move is a tick-indexed timeline. The port splits each one in two:

* **`MoveScript`** (JSON, `assets/crb/move_scripts/<technique>/<move>.json`, client resources so resource packs can retime moves), **extracted automatically** from the mcfunction by `tools/jja/timelines.py`: animation, sound, particle, camera shot, fade, shake, impact frame, FOV, slowdown cues with their exact elapsed tick, target scope (`self`, `radius:40`, `victims`) and local-space offsets.
* **`MoveLogic`** (Java): the simulation — summons, projectiles, hitboxes, damage, stun, knockback, binds, block destruction, domain lifecycle. Hand-written per move, using the JJA function as the spec.

Both run on `elapsed = now − startTick`. The script can be hand-edited after extraction to add Java-only upgrades (spline shots, roll, FOV punches) without touching the logic.

### 3.3 Abilities and variants

`Technique` → ordered `Ability` slots → `Variant`s keyed by `Modifiers` (plain, sneak, airborne, look-up, sneak+airborne, Six-Eyes-gated). Each variant: energy cost, cooldown key + ticks, move id, unlock requirement (`forms ≥ n`, trait, RCT). Values come from `catalog/movesets.json`. One `AbilityUser` interface covers players and mobs, so NPC bosses cast the same moves.

## 4. Controls — Combat Mode (no hotbar clutter)

Toggle **Combat Mode** with `` ` `` (rebindable). Vanilla controls are untouched when it is off.

| Input (Combat Mode on) | Action |
|---|---|
| `1`–`5` | Technique skills of the current page |
| `6` | Domain Expansion / ultimate (hold 0.5 s to confirm) |
| Mouse wheel | Next/previous technique page (Ten Shadows has 11 summons) |
| LMB | M1 combo with the current combat style or cursed tool |
| RMB | Combat-style / cursed-tool art |
| `Q` | Selected Cursed Energy art (RCT, Divergent Fist, Simple Domain, Domain Amplification, Hollow Wicker Basket, Falling Blossom Emotion) |
| `Left Alt` | Dash (direction from WASD, as JJA's `moveAngle`) |
| `F` | Evade |
| Hold `Sneak` while idle | Charge cursed energy |
| Hold `H` | Radial menu: combat style, CE art, shikigami, technique page, outfit |
| `Sneak` / airborne / look up | Variant modifiers, exactly as JJA; the HUD previews which variant each key will fire |

The hotbar stays the player's own: cursed tools are real items; techniques, styles and CE arts are not items at all.

## 5. Asset conversion pipeline (`tools/jja/`)

All scripts are Python 3 (`python3 -I tools/jja/<script>.py`), deterministic and idempotent. They read the addon from `JJA_ROOT` and write into `src/main/resources` (under `assets/crb/` and `data/crb/`). Converted assets are committed (the project owner holds the rights to use them), so the repo builds without the addon present; re-run the scripts after changing them.

| Script | Input | Output |
|---|---|---|
| `catalog.py` ✅ | both packs | research catalog |
| `movesets.py` ✅ | `*_click`, `*_moves` | `catalog/movesets.{md,json}` |
| `convert.py` ✅ | resource pack | `assets/crb/`: `textures/**` (1,065), `geo/*.geo.json` (364 GeckoLib models, one per geometry), `animations/*.animation.json` (1,464 for GeckoLib), `player_animations/*.json` (1,317 re-boned for Player Animation Library), `sounds/**` + `sounds.json` (395 files, 399 events), `bedrock_particles/<ns>/*.json` (3,468), `textures/glyph/<page>/*.png` (725 impact-frame/UI cells), `models/item/*.json` (216), `lang/en_us.json`, and `jja/*.json` indexes (entities, geometry, animations, particles, sounds, glyphs, attachables, render/animation controllers, poly meshes) |
| `timelines.py` ✅ | move mcfunctions | `assets/crb/move_scripts/**` (701 scripts, 15,280 cues) + `data/crb/move_durations.json` |
| `convert_structures.py` | `structures/*.mcstructure` | `data/crb/structure/…` (world branch) |
| `convert_loot.py` | `loot_tables/**` | `data/crb/loot_table/…` (world branch) |

Run `python3 tools/jja/convert.py` (needs Pillow). It is deterministic, and everything it skipped or could not resolve is listed in [`catalog/conversion_report.md`](architecture_and_research/catalog/conversion_report.md).

`./gradlew build` validates the converted assets in JUnit (`ConvertedAssetsTest`: legal paths, parseable JSON, every reference resolves; `LibraryLoadingTest`: every model parses and bakes in GeckoLib, every animation parses in GeckoLib and Player Animation Library), so Python is only needed to regenerate.

## 6. Cinematic engine

* **Shots**: keyframes in caster-local or world space, Catmull-Rom position spline, slerped rotation including roll, per-segment easing (JJA's `in_sine`, `in_out_circ`, `out_back`… are implemented exactly), FOV curve. JJA's single-segment eases become one-segment splines, so extracted shots look identical before any upgrade.
* **Trauma shake**: intensity and duration from JJA values; trauma² with Perlin noise on yaw/pitch/roll; distance attenuation; additive.
* **Fades**: in/hold/out + RGB, exactly JJA's `camera fade` semantics, drawn as a HUD layer.
* **Impact frames**: sliced frames drawn full-screen through a Veil post shader (blend modes: replace, invert, additive), one frame per tick like JJA.
* **Hit-stop**: on `ImpactEvent`, freeze render partial-tick and animation time of attacker and victims for N ticks; camera keeps a micro-shake.
* **Locks**: `Move` / `Camera` flags from `CombatState` suppress input client-side; the server ignores movement while locked.

## 7. Implementation tracker

Each line is one feature branch → PR → merge after a green `./gradlew build`.

### Foundation

| # | Branch | Scope | Status |
|---|---|---|---|
| 1 | `docs/phase1-research` | Research docs, catalog tools, this plan | ✅ |
| 2 | `feat/core-architecture` | Gradle/MDG, deps, entrypoint, config, custom registries, attachments, payload registration skeleton, technique-ownership SavedData, kit format + loader, `/crb` admin commands, 19 shipped kits, JUnit + GameTests | ✅ |
| 3 | `feat/asset-pipeline` | Model/animation/texture/sound/lang/particle/impact-frame converters + converted assets | ✅ |
| 4 | `feat/move-engine` | Timeline extractor, `MoveScript` loader, server `MoveEngine`/`MoveInstance`, client cue player (sounds, player animations), MoveStart/End/State sync, tracking catch-up, `AbilityInput`, `/crb move play` | ✅ |
| 5 | `feat/camera-engine` | Director (Bedrock free-camera semantics, all eases, presets, ownership/release), shakes, fades, impact frames, flash, FOV punch, HUD hiding, server-side move effects, dev harness | ✅ (hit-stop moves to combat-core) |
| 6 | `feat/bedrock-particles` | BedrockFX runtime (emitters, curves, Molang, flipbooks, collision) | ☐ |
| 7 | `feat/input-hud` | Combat Mode, keybinds, radial menu, HUD | ☐ |
| 8 | `feat/combat-core` | M1 combos + clash, lag-comp hit tests, DamageKind, stun/i-frames, dash/evade/double jump/flash step, parry, Black Flash + The Zone, binds, CE regen/charge/burnout | ☐ |

### Vertical slice

| 9 | `feat/char-gojo` | Limitless complete (all 23 variants + fists), **Six Eyes perception + efficiency (GJ1–2)**, **passive Infinity (GJ3)**, **Blue Teleport (GJ4)**, **Blue Flight (GJ5)**, restored Twilight/Phase Blue + Phase Red (GJ6), Levitate Purple (GJ7), quick Unlimited Void (GJ8), domain system + barrier + Unlimited Void, domain clash, Student/Shinjuku Gojo NPCs, `gojo` + `gojo_student` kits | ☐ |

### Full roster (one branch each)

| # | Branch | Scope |
|---|---|---|
| 10 | `feat/char-sukuna` | Shrine kit, fingers/vessel (uniqueness-aware), takeovers, **Meguna transfer (gains Ten Shadows)**, Fuga, Heian form, Malevolent Shrine ×3, Kamutoke/Hiten, Mahoraga interplay, `sukuna` / `sukuna_heian` kits |
| 11 | `feat/char-megumi` | Ten Shadows: all shikigami + taming rituals, Shadow Wheel, Mahoraga adaptation, Agito, Chimera Shadow Garden, **Tiger Funeral (G1)**, **Shadow Swim/Storage (G8)** |
| 12 | `feat/char-yuji` | Soul Shrine, Black Sparks style, Shrine of Reverie, awakened NPC, rage cutscene, `yuji` kit (vessel with 1 force-fed finger, Perfect Vessel) |
| 13 | `feat/char-kashimo` | Mythical Beast Amber, Unforgiving Tempest |
| 14 | `feat/char-hakari` | Love Train, jackpot, Idle Death Gamble |
| 15 | `feat/char-mahito` | Idle Transfiguration, transfigured humans, Instant Spirit Body, Self-Embodiment of Perfection |
| 16 | `feat/char-jogo` | Disaster Flames, Coffin of the Iron Mountain |
| 17 | `feat/char-higuruma` | Judgeman, Deadly Sentencing, Executioner's Sword |
| 18 | `feat/char-inumaki` | Cursed Speech, Chamber of Resonance, megaphone |
| 19 | `feat/char-todo` | Boogie Woogie, brother combos, **Vibraslap (G7)** |
| 20 | `feat/tech-misc` | Explosive CE, Absorption, Heavenly Restriction (+ variants), Miracles, Wind-Up, Immobilizing Sight, Pain-Killer, Delusional trait |
| 21 | `feat/ce-arts` | RCT (+ obtain cutscene), Divergent Fist, Simple Domain, Domain Amplification, Hollow Wicker Basket, **Falling Blossom Emotion (G4)** |
| 22 | `feat/cursed-tools` | All tools and their arts, outfits |
| 23 | `feat/progression` | Creation menu + roll tables, stats book, grades, exp, traits, **Perfect Vessel trait (G6)**, settings |
| 24 | `feat/npcs-curses` | Sorcerers, curses, shikigami bosses, canon NPC kits via SmartBrainLib |
| 25 | `feat/world` | Blocks (plushies, crates, ores), structures, spawns, missions, phone, mob events, capture points, dungeons |
| 26 | `feat/bosses-rift` | Astaroth, Benedictus, Duke, Kaiguy, Void Eye/Servant, Chaos, Finger Bearer, Toji v2 |
| 27 | `feat/boss-heroic-prime` | Secret boss: phases, stances, minions, arena, Rift Prison, dialogue, Architect, Core of Creation, all boss weapons, **Omega Death Scythe (G5)** |
| 28 | `feat/char-choso` | **Blood Manipulation (G2)** |
| 29 | `feat/char-yuta` | **Queen of Curses + Copy (G3)** |
| 30 | `feat/hinted-roster` | Tier-3 stretch kits (Nobara, Nanami, Geto, Kenjaku, Uraume, Yuki, Mei Mei) |

## 7b. Asset gaps found during conversion

Missing from the JJA pack itself (broken or silent in Bedrock too). Each is synthesised in the branch that first needs it:

* **Poly meshes** (15 models incl. domain barriers, Instant Spirit Body, Mahito transfigurations, Iron Mountain): GeckoLib cannot read them; meshes are split into `jja/poly_mesh/` for a custom render layer (camera/rendering branch).
* **Vanilla Bedrock animations** JJA entities inherit (`animation.humanoid.*`, `animation.piglin.*`, 80 refs): replaced by generic Java biped/quadruped animation controllers.
* **Never-defined animations** (29, mostly idle loops such as `animation.simple_domain.idle`, `animation.okarunmask.*`), **geometries** (`geometry.tsunami`, `geometry.simple_domain`), **textures** (e.g. `fat_sukuna`, `body_transfigure`), and **19 sounds** (e.g. `kashimodomain`, `oisatoru`, `whoosh4/5`): designed from neighbouring assets.
* **19 FMOD `.fsb` sounds**: vanilla overrides (player attack/hurt, bat takeoff) in a proprietary codec; not needed because combat code controls those sounds.

## 8. Gap designs (summary)

Full rationale in research doc 04. Assets for new abilities are derived from existing JJA assets (recoloured textures, re-timed animations, existing particle effects with new tints) so the style matches.

* **G1 Tiger Funeral** — pounce (dash-grab), two shadow-claw slashes, roar (radius stun 20 ticks). Fusion part of Agito.
* **G2 Blood Manipulation** — Blood meter (costs HP above 30 %). Convergence (charge), Piercing Blood (beam, line hit), Supernova (5 orbiting orbs → burst), Slicing Exorcism (2 returning discs), Flowing Red Scale (speed/strength buff), Stack (ultimate overdrive), Blood Edge style.
* **G3 Queen of Curses + Copy** — Rika (follower), Rika Grab / Arm Slam, Copy (store one variant of a technique Rika hit within 5 s; cast it at 1.5× cost), Pure Love beam, Domain: Authentic Mutual Love.
* **G4 Falling Blossom Emotion** — 10 s anti-sure-hit stance; −80 % domain sure-hit damage, auto-counter lash.
* **G5 Omega Death Scythe** — Reaper Sweep, Soul Harvest leap, Death's Verdict.
* **G6 Perfect Vessel** — survive fingers, resist takeover, +25 % finger buffs. Yuji's canon trait; part of the `yuji` kit.
* **GJ1–GJ8 Gojo** — Six Eyes perception and efficiency, passive Infinity, Blue Teleport, Blue Flight, restored cut moves; see research doc 06.
* **G7 Vibraslap** — swap without clapping (shorter wind-up, sound cue).
* **G8 Shadow Swim / Storage** — sink into shadow for fast traversal; store one item in shadow.
* **G9 Universal adaptation** — every `DamageKind` feeds Mahoraga.

## 8b. Uniqueness and kits

* `uniqueTechniques` = `OFF` / `CANON` (default) / `ALL`. First claim is permanent; only operators release or transfer. Sukuna's vessel owns Shrine; the Meguna transfer also claims Ten Shadows.
* Kits are datapack JSON (`data/<ns>/crb_kits/*.json`) applied with `/crb kit give`; the `gojo` kit includes both Limitless and Six Eyes.
* Full spec and command list: research doc 06.

## 9. Verification

* `./gradlew build` after every module: compile, JUnit (variant resolution, timeline parsing, spline/ease math, Molang, roll tables), asset validation.
* NeoForge GameTests for server behaviour (cooldowns, energy, hit/rewind, domain clash outcome).
* `runClient` / `runServer` smoke checks for registration and resource loading before each merge.

## 10. Git workflow

* `main` is always buildable. One feature branch per tracker row, semantic commits (`feat:`, `fix:`, `docs:`, `build:`, `chore:`), PR documenting the Bedrock behaviour and the Java implementation, merge after a green build.
