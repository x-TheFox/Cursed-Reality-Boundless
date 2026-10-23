# 02 — How Jujutsu Awakening Works (Reverse-Engineered)

JJA is a command-driven state machine. Almost all gameplay lives in mcfunctions that run every tick and communicate through **scoreboard objectives** and **entity tags**. The two JS files (`scripts/index.js`, `scripts/domain_clashing.js`) are a helper layer for things commands cannot do (projectile velocity, knockback vectors, ray casts, HUD bars, domain-clash arithmetic).

## 1. Tick loop

`functions/tick.json` runs six functions every tick:

| Function | Role |
|---|---|
| `_scores` (416 lines) | Decrement every timer objective; apply locks (`Move` → movement input off, `Camera` → look input off, `Frames` → i-frames via resistance/instant-health); stun handling. |
| `_self_cmds` (509 lines) | The **dispatcher**. Routes held items and item-use tags to technique functions (below). |
| `_missions` | Mission board / phone / quest timers. |
| `health_thingy` | Copies health into `detect_health` for selectors. |
| `detect_sneak2` | Sneak-duration counter (`Sneaking`), used for "hold sneak" variants and CE recharge. |
| `remove_scores` | Cleanup. |

`index.js` adds a per-tick `moveAngle` objective (movement direction relative to view, for directional dashes), an energy-regen loop (full cap in 90 s, paused while moving, in a domain or clashing), agility → speed/jump-boost mapping, curse/Heavenly Restriction passive regen, and the Sukuna Fuga charge bar (`a:fuga_barN` title → custom HUD element).

## 2. Core state objectives

| Objective | Meaning (ticks unless noted) |
|---|---|
| `move` | **Active move timeline.** Set to the move's length when it starts, counts **down** to 0. Every move function keys effects on `scores={move=N}`, so `elapsed = start − move`. |
| `domainstart` / `domain` / `domainactive` | Same idea for domain cast → domain running → domain alive. |
| `ab`, `ability1..12`, `wep1..6`, `domainCD` | Cooldowns per hotbar slot / weapon art / domain. |
| `Energy`, `EnergyCap`, `energystat` | Cursed energy and its cap. Negative energy deals damage (burnout). |
| `Stun` | Stunned; cancels any move whose function checks `Stun=1..` (see `cancel_attack`). |
| `Frames` | Invulnerability frames. Tag `Frames` excludes an entity from every hit selector. |
| `Move` / `Camera` | Movement / camera input lock (`inputpermission`). |
| `cutscene`, `sukunacutscene`, `scene` | Cutscene timers (Black Flash, Sukuna, story). |
| `BasicM1`, `BasicM1s`, `M1Reset`, `AfterAnim`, `Disabled` | M1 combo state (see §5). |
| `damage`, `damageadd`, `damageinsurance` | Pending damage, attacker bonus, defender reduction. |
| `strength`, `endurance`, `agility`, `invest`, `grade`, `exp` | RPG stats. |
| `forms` | How many skills of the current technique are unlocked (1–6+). |
| `domain_resistance`, `domain_resistancecap` | Domain-clash barrier HP. |
| `blackflashchance`, `thezone`, `blackflash_amount` | Black Flash roll, "The Zone" buff, counter. |
| `fuga`, `finger` | Sukuna Fuga charge, finger count. |
| `wheelactive`, `adapttimer`, `*_adapted` tags | Mahoraga wheel adaptation per damage type. |

## 3. Technique skeleton

Every innate technique (`limitless`, `shikigami`, `lovetrain`, `kashimo`/`lightning`, `explosive`, `hr`, `judgeman`, `speech`, `absorption`, `flames`, `sukuna`, `boogie_woogie`, `idle_transfig`) uses the same files:

| File | Purpose |
|---|---|
| `<t>_technique` | Run on using the technique scroll. Clears the previous technique, tags the player (`limitless`), grants hotbar items `ds:<t>1..N` (locked in inventory, kept on death). **Sneak-use** spends 10 invest points to unlock the next skill (`forms += 1`); some upgrades require Six Eyes or RCT. |
| `<t>_clicks` | On item-use: tags `<t>click`, calls `<t>_click`. |
| `<t>_click` | **Variant selector.** For the held item picks a variant by modifier (plain / sneak / airborne / look-up / Six-Eyes) and tags `formN`, `formNb`, `formNc`, `formNe`. Checks cooldown + energy, prints a cooldown message, sets `move` (duration), the cooldown objective and removes energy. |
| `<t>_moves` | Per tick while `abilitying`: dispatches each `formX` tag to its timeline function. |
| `<t>N[b|c|e]` | **The timeline.** Hundreds of `execute … [scores={move=K}] run …` lines. |
| `<t>_clear` | Sneak + punch with the scroll: removes the technique. |

`tools/jja/movesets.py` extracts every variant into `catalog/movesets.md` (name, modifier, energy, cooldown, duration, timeline function).

**Combat styles** (fists, cursed tools) work the same way through `fighting/<style>/<style>_m1s` (M1 combo while held) and `<style>_attacks` (special arts triggered by item-use → `wepN`).

## 4. The move timeline format (key to porting)

A timeline line looks like:

```
execute as @s[scores={move=98}] at @s run summon ds:projectile ImagineBlue ^-2.5^1.5^1
camera @s[scores={move=85}] fade time 0.1 0 0.2 color 255 255 255
title @s[scores={move=43},tag=!impactframesoff,type=player] title <glyph U+E3xx>
```

Each line is (tick or tick range) × (selector filters) × (command). Commands fall into two groups:

* **Presentation**: `playanimation`, `playsound`/`stopsound`, `particle`, `camera … set/fade/clear`, `camerashake`, `title` with an impact-frame glyph, `effect night_vision/slowness` (hit-stop feel), `fog`, `hud`.
* **Simulation**: `summon`, `tp`, `damage`, `scoreboard players …` on targets (damage, stun, knockback), `scriptevent ds:*` (JS projectiles/dashes/raycasts), `fill` (destruction), `structure load` (domain interiors).

Presentation lines are regular enough to **extract automatically** into data (`MASTER_PLAN §5.3`), which keeps the original timing exactly. Simulation is reimplemented by hand per move in Java.

## 5. Melee core

* **M1 combo** (`fighting/basic/basic_m1s`): 4 hits. Each click adds 8/8/9/10 ticks to `BasicM1`; the hit lands when `BasicM1 = 3`. Box hit at `^^^1.85` radius 1.8 (large mobs: `^^^3.85` r 4.85). Fourth hit → `Disabled 15` (end-lag), `M1Reset 25` resets the chain.
* **M1 clash**: two players swinging into each other inside 2.25 blocks trigger `m1_clash`.
* **Parry** (`parry`): punch during an enemy attack window → 8 i-frames, damage and stun to the attacker, heals the defender vs. Heroic Prime.
* **Black Flash** (`blackflash1..4`): rolled 1/269 on the impact tick (`BasicM1 = 7`); rigged by `blackflashrig`. Result: damage ×7, 30-tick stun, 7-frame impact-frame burst, shake (4, 0.25 s), night-vision flash, then **The Zone** for 200 ticks (higher Black Flash rate). Yuji and Sukuna get special finishers (`blackflash_finisher_gojo`, soul damage when Yuji is awakened).
* **Damage types** (`damages/*`): blunt, slash, cleave, fire, electric, explosion, poison, pull, water, soul, speech, sight, deletion, infinity and one per domain. Each type feeds Mahoraga's adaptation wheel.
* **Movement**: dash (8 directions via `moveAngle`), double jump, flash step, wall-climb (JS), auto-jump-over-obstacle leaps (JS), evade, knockdown, downslam.

## 6. Domains

* `<t>6` casts (`domainstart`), plays the hand-sign animation and orbiting camera, summons `ds:domain_barrier` (black) or `ds:domain_barrier_white`, loads the domain interior (`structure` or summoned set-piece entities), then runs `<t>_domain` for the active period with sure-hit effects.
* **Domain clash**: if two casts overlap, a `ds:domain_clash_main` entity is tagged `domain_clashing_commence`. Each clasher gets `domain_resistance` (barrier HP). Damage taken while within 50 blocks of another clasher drains it 1:1. Sneaking for 5 s starts "strengthening" (+1 every 5 ticks). Highest resistance wins (`ds:clash_winner`, ties go to health). Each domain has a `*_clashing` entity (11 variants) for the visual.
* Counters: Simple Domain, Domain Amplification, Hollow Wicker Basket, Mahoraga adaptation, and `domainloser` / `domain_break`.

## 7. Progression

* **Character creation menu** (`_menu`, `menu_*`): rolls race (human / curse), clan (Gojo, Zenin, Kamo, Inumaki, Fushiguro…), innate technique and traits with weighted rarity tiers (`common`, `uncommon`, `rare`, `exotic`, `honored`).
* **Stats book**: invest points into Strength / Endurance / Agility / Cursed Energy limit. Level-ups grant points and CE; grades go Grade 4 → 3 → 2 → 1 → Special Grade.
* **Traits** include Six Eyes, Heavenly Restriction (Zenin / Fushiguro / Mechamaru variants), Reverse Cursed Technique mastery, 120 %, Undying, Insanity, Satiated, Exoskeleton, Gambler, Lone Wolf, Tactical IQ, 530k IQ, Prodigy, Rough/Faster CE, Blessed Strength, Enhanced Str/Agi, Delusional (idol trait) and Prince of Black Sparks (Yuji).
* **RCT** is learned by surviving at ≤ 10 HP with endurance ≥ 50 and CE cap ≥ 300 (cutscene `ce/rct_obtain`).
* **Sukuna vessel**: eating fingers (`relics/sukuna_finger`) → Sukuna's technique; 15+ fingers unlock Fuga; Heian transformation; only one vessel per world (setting).
* **Missions**: phone item, credit currency, vessel mission, capture-point events, curse raids (`mobevents`), dungeons/structures (cursed cavern, graveyard, haunted apartment, school, sewers, gas station, factory, library, plane, TVA, rifts).

## 8. JS helper events (to be absorbed into Java)

`ds:dismantle*`, `ds:pebble`, `ds:transfigured_*`, `ds:body_repel_*` (projectile spawn + velocity), `ds:knockback`, `ds:dash*`, `ds:leapforwards*`, `ds:autoJump2`, `ds:antiair`, `ds:downfall`, `ds:upslightly2` (motion), `ds:shockwave` (spherical block removal job), `ds:lightning` (procedural bolt via line particles), `ds:raycast` (entity-then-block ray → run function there), `ds:delusional*` / `ds:styleswap` / `*_background1` (particles shown **only to tagged viewers** — per-viewer VFX), `ds:spawn_middle` (spawn at centroid of tagged entities), `ds:human_count` / `ds:remove_humans` (Mahito inventory), `ds:wall_climb`, `ds:shootprojectile`, `ds:regeneration`, `ds:clash_winner`.

Per-viewer particles (`delusional`, `cutscene_vision`) matter for networking: they are effects that only some clients should see.

## 9. Pain points the port fixes

* **Hotbar clutter**: each technique fills up to eight locked slots; variants depend on sneaking/jumping/looking up at the moment of the click, which is error-prone.
* **Desync**: hits are radius checks on server-side positions with no lag compensation; animations are fire-and-forget `playanimation`, so late joiners and distant players see broken states; camera is forced per player with no way to opt out.
* **Performance**: thousands of `@e` selectors per tick (the `_self_cmds` dispatcher alone evaluates ~150 item checks per player per tick), ticking areas, `fill`-based destruction.
* **Engine limits**: impact frames are font glyphs in `/title` (no blending, causes crashes, hence the "Impact frames (Fixes Crashes)" setting), no post-processing, no real hit-stop, camera eases limited to presets.
