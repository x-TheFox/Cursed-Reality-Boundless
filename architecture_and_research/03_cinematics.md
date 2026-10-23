# 03 — Why JJA Feels Cinematic

Measured across all 1,935 mcfunctions (counts are command occurrences).

| Device | Uses | How JJA uses it |
|---|---|---|
| `particle` | 9,927 | Layered bursts: 3–6 particle effects fired on the same tick at slightly different offsets (`hollow_blue_stars` left, `hollow_red_stars` right…). |
| `playsound` | 6,852 | Stacked at very high volume with distance `100000`, so the whole server hears ultimates; pitch-shifted vanilla sounds layered over custom stingers (`mob.hollow2`, `mob.infinitevoid1`, `music.*`). |
| `playanimation` | 1,914 | Full-body poses timed to the tick on the caster, targets and set-piece entities. |
| `camera … set minecraft:free` | 1,308 | **Scripted shots** in caster-local space (`pos ^1.5 ^2.25 ^1 facing ~~1.4~`). Eases: `in_sine` (289), `in_out_circ` (162), `linear` (28), `out_back` (10). Shots last 0.5–10 s. |
| `camera … fade` | 534 | **Smash-cut flashes.** Typical in/hold/out: `0 0 0.5`, `0 0 0.1`, `0.15 0.33 0.15` (technique switch). Colours: black 295, white 183, plus purple (200,0,200) for Hollow Purple, red for Sukuna, light blue for Six Eyes. |
| `camerashake add` | 1,596 | Short and violent. Most common (intensity, seconds): (2, 0.15) ×283, (2, 0.25) ×110, (4, 0.25) ×92, (1, 0.15) ×92. Radius-scoped to nearby players (`@a[r=10..40]`). `rotational` for heavy impacts. |
| `title` + glyph | 1,511 (`title` + `titleraw`) | **Impact frames.** 457 distinct full-screen images on six 16×16 glyph pages (`font/glyph_E3/E4/E6/E7/F2/F3.png`). Shown 1 frame per tick in 2–7 frame bursts, then cleared with `§l`. |
| `effect slowness 1 N` | ~130 | Brief slows at impact = poor man's hit-stop. |
| `effect night_vision` | many | Screen "pop" at the moment of impact. |
| `fog` / `hud` | 25 / 24 | Domain atmospheres; hide HUD during cutscenes. |
| `inputpermission` | via `Move`/`Camera` | Locks the player during a shot so the framing holds. |

## Anatomy of a hit — Hollow Purple (`limitless/limitless5`, 100 ticks)

Elapsed tick = 100 − `move`.

| t | Beat |
|---|---|
| 0 | Animation `imaginary_purple`; caster lifted 2 blocks; **black fade** 0.1/0/0.2; sting `mob.hollow2`. Stars particles left (blue) / right (red). |
| 2 | Summon Blue and Red orbs at ±2.5 blocks, plus the hidden merge target in front. Orbs locked to the caster's hands for 10 ticks. |
| 15–16 | Orbs collide (`imaginary_collide`), **white flash** 0.1/0/0.2 to everyone within 10 blocks, water-whoosh sting. |
| 25 | Camera lock starts (until the shot); caster hovers (`antiair`). |
| 40–60 | Slowness on the caster: the "charge" breath. |
| 55–61 | **Impact frames** — 6 frames, then clear. |
| 56 | **Shake** (2, 0.25) to everyone within 40 blocks, night-vision pop within 20, the purple projectile fires at velocity 4. |
| 64 | **Purple fade** (235,55,235) for everyone within 40 blocks. |

The pattern repeats across the addon: **anticipation pose → sting + flash → hold → impact-frame burst + shake + flash on the same tick → afterglow fade**.

## Anatomy of a domain — Unlimited Void (`limitless/gojo_domain`, 760 ticks)

* 0–20: clear camera, half-second black fade.
* 53–104: two orbit shots, `ease 2.5`, swinging the camera from `^-7` to `^7` around the caster during the hand sign.
* 300–420: barrier interior summoned, music starts, eye particles; three **impact-frame bursts** (t≈333–350, 372–380, 412–420) separated by long fades (4.5/3.5 s, 2.75/1.15 s).
* 430–549: slow push-in shots (`ease 6.65`, `ease 3`, `ease 1.5`).
* 550+: white barrier, white particle storm, then the victims' `stunned` pose and a final `ease 5` shot.

## What we keep, what we upgrade

| Keep exactly (data-driven, extracted) | Upgrade in Java |
|---|---|
| Tick timing of every beat | Camera: Catmull-Rom splines between shot keys instead of single-segment eases; continuous **roll** (Dutch angles); FOV punches (−8° → +12° over 4 ticks) on impacts. |
| Which sound/particle/animation/impact frame fires when | Impact frames: drawn with a shader overlay (additive/inverted blending, chromatic split) instead of a font glyph; no crash risk, so the "impact frames off" setting becomes an accessibility toggle. |
| Shake intensity/duration ratios | Shake: trauma-based (Perlin noise, squared falloff, distance attenuation) so overlapping shakes add up naturally. |
| Fade colours and timings | **True hit-stop**: freeze the attacker's and victim's animation and partial-tick interpolation for 2–6 ticks on heavy hits, client-side and synchronised by the server's impact event. |
| Per-viewer effects (delusional, cutscene vision) | Post-processing per domain (Unlimited Void: desaturation + star field; Malevolent Shrine: red grade + vignette). |
| | Spectators: players near a cinematic see it from their own camera, with an optional prompt to "watch" from the director's shot. |
