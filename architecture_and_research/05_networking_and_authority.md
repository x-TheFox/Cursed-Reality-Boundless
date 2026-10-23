# 05 — Networking, Authority and Sync Design

Target: NeoForge 1.21.1 payload API (`RegisterPayloadHandlersEvent`, `CustomPacketPayload` + `StreamCodec`), versioned registrar `"1"`.

## Principles

1. **The server owns outcomes.** Cursed energy, cooldowns, stun, i-frames, damage, hit confirmation, domain state, clash resistance, progression. The client never sends "I hit X"; it sends "I pressed ability slot 3 with Sneak held, looking along V, at my tick T".
2. **One packet per move, not per tick.** A move is a deterministic timeline (`MoveScript` data + Java logic keyed by elapsed tick). The server broadcasts `MoveStart` once to everyone tracking the caster; each client plays the presentation locally from the same data. Per-tick traffic is only vanilla entity movement.
3. **Late joiners catch up.** When a player starts tracking an entity (`PlayerEvent.StartTracking`) the server re-sends any active move with its current elapsed tick, any active domain and any bind. A player who walks into a domain mid-cast sees it correctly.
4. **Prediction only where the player already owns the result.** Vanilla gives the client authority over its own movement, so dashes, double jumps, leaps and flash steps are applied instantly on the client and also run server-side for validation. Everything else waits for the server (~1 RTT), masked by an instant local "wind-up" animation and sound.
5. **Lag-compensated hits.** The server keeps a 20-tick ring buffer of every living entity's bounding box. Melee and short-range hitboxes are tested against target boxes rewound by the attacker's measured latency (capped at 150 ms / 3 ticks). Long-range projectiles are simulated server-side without rewind.

## Payloads

| Direction | Payload | Contents | Notes |
|---|---|---|---|
| C→S | `AbilityInput` | slot (0–8), modifier bits (sneak, airborne, look-up, alt), look vector, client tick | Server resolves the variant with the same rules as JJA's `*_click`. Rate-limited (max 10/s). |
| C→S | `ComboInput` | M1 / heavy / block press-release | Feeds the M1 state machine. |
| C→S | `RadialSelect` | technique page, slot | Sets the active loadout page. |
| C→S | `ClashInput` | strengthen held | Domain-clash sneak-to-strengthen. |
| S→C | `SorcererSync` | full or delta of the player's attachment (energy, cap, stats, technique, unlocked forms, cooldowns, traits, grade, exp) | Owner only. Delta-encoded by dirty flags. |
| S→C | `CombatStateSync` | stun, i-frames, locks, Black Flash zone, guard | Owner + trackers (needed for animations and HUD of others). |
| S→C | `MoveStart` | caster id, move id (registry id), variant, start game tick, elapsed offset, RNG seed, target ids | Clients run the move's presentation timeline. |
| S→C | `MoveEnd` | caster id, move id, reason (finished / cancelled / stunned) | Clients stop animation, camera, looping sounds. |
| S→C | `ImpactEvent` | attacker, victims, position, hit-stop ticks, shake profile, impact-frame sequence id, flash colour | Fired at the server-confirmed hit tick. Drives hit-stop + VFX. |
| S→C | `BindSync` | anchor entity, bound entity, local offset/rotation, until tick | Grabs, carries, cinematic finishers. |
| S→C | `DomainSync` | domain id, owner, centre, radius, phase, clash participants + resistance | Barrier render, post-processing, clash HUD. |
| S→C | `CinematicCue` | ad-hoc camera/fade/shake/title for scripted story events | Bosses, cutscenes, The Architect. |
| S→C | `ViewerFx` | effect id, position, viewer filter | JJA's per-viewer particles (delusional, cutscene vision). |

All entity references are network ids resolved on the client; a missing id is ignored (not crashed on).

## Authority table

| Thing | Authority | Client role |
|---|---|---|
| Ability start, cost, cooldown | Server | Predicts wind-up visuals only |
| Own movement (dashes, leaps) | Client (vanilla) | Applies instantly; server validates speed and position (anti-cheat clamp) |
| Forced movement of others (knockback, pulls, Blue) | Server | Interpolates |
| Hit detection | Server with rewind | — |
| Damage numbers, stun, i-frames | Server | Displays |
| Camera shots | Client (caster; nearby players for scoped shakes/fades) | Runs director from move data |
| Animations | Client from `MoveStart` | Deterministic; resynced on tracking start |
| Domains and clashes | Server | Renders barrier and post-FX |
| Cutscene locks (`Move` / `Camera`) | Server sets lock flags | Client suppresses input; server ignores movement packets while locked |

## Multi-entity synchronised moves (grabs, finishers, Black Flash cutscenes)

Problem in Bedrock: victims are `tp`'d every tick on the server, so on other clients they stutter between interpolation steps and fight their own movement.

Solution: **binds**.

1. Server creates a bind (anchor = attacker, local offset, rotation, duration) and sends `BindSync`.
2. Server sets the victim's position from the anchor every tick; for a player victim it also locks input.
3. Clients do not interpolate the victim from server positions while a bind is active. They place the victim at `anchor.interpolatedPose × offset` every frame, so attacker and victim move as one rigid pair with no rubber-banding.
4. The bind ends with a final authoritative position packet; clients blend over 3 ticks back to normal interpolation.

## Spectating someone else's cinematic

* **Shakes and fades** keep JJA's radius scoping (`@a[r=40]`) and attenuate with distance.
* **Impact frames** are shown to the caster, the victims, and anyone within the move's `impact_radius` who is looking within 60° of the event. Everyone else only gets shake and flash, so a fight across the map does not black out your screen.
* **Camera shots** are only forced on the caster and on locked victims, as in JJA. Nearby players get a HUD prompt ("Hold [V] to watch") that temporarily borrows the shot.
* **Domains**: anyone inside the barrier gets the domain's post-processing and fog; outside sees the barrier shell.

## Timeline determinism

* Move presentation and server logic both index on `elapsed = serverTick − startTick`. The client's start tick is shifted by the packet's elapsed offset, so effects land on the same world tick within one frame.
* Random choices inside a move (particle spreads, Black Flash rolls on the client side for VFX only) use the seed from `MoveStart`.
* Hit-stop freezes **visual** time on the client for the involved entities only; server ticks never pause, so the world stays in sync.

## Bandwidth budget

A Hollow Purple (100 ticks, ~150 presentation cues) costs one `MoveStart` (~40 bytes), one `ImpactEvent` per hit tick and a few `SorcererSync` deltas. The Bedrock version streams every `particle`, `playsound` and `camera` command as its own packet to every client in range.
