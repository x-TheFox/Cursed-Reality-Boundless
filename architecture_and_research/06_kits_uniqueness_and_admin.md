# 06 — Character Kits, Technique Uniqueness and Admin Tools

Added after a design review: Gojo must be the strongest character, Six Eyes must be real, iconic techniques should be one-per-server, and operators need a one-command way to make a player a full canon character.

## 1. What JJA actually does today (research)

### Six Eyes (`attributes/sixeyes.mcfunction`)

Only four effects:

| Effect | Detail |
|---|---|
| CE regen | +5 Energy every 2 ticks while idle; +7 while charging (sneak ≥ 5). |
| Vision | Permanent night vision. |
| Look | Entity properties `sixeyes` / `sixeyesb` switch on the glowing-eye texture. |
| Gating | Unlocks Enchanted Lapse Blue (`form2e` → Wisdom Blue), Enchanted Reversal Red (`form3e` → Paramita), and Limitless's 5th upgrade. |

Sources: rolled as the Honored trait (Gojo clan only), `cheats/givesixeyes`, and the Strange Vial. Canon perception (seeing cursed energy, flow, techniques, efficiency so high Limitless barely costs anything) is **missing**.

### Gojo's teleportation and mobility

| Move | What it really does |
|---|---|
| Spatial Blitz (`limitless4`) | Short dash punch. |
| Spatial Blitz, sneak (`limitless4b`) | Teleports next to the nearest target within 30 blocks for 6 ticks of strikes. |
| Spatial Blitz, airborne (`limitless4c` → `warp`) | Grabs a target within 5 blocks, then warps both. |
| Flash Step / Infinity Dash | Dash item variants. |

There is no free teleport, no sustained flight, and no passive Infinity. Infinity (`limitless1`) is a timed move with a 1,200-tick cooldown.

### Cut or NPC-only Limitless content

| Function | State |
|---|---|
| `twilightblue`, `phaseblue`, `phasered` | Projectile handlers exist; **nothing spawns them**. Cut content. |
| `levitate_purple` | Aerial Hollow Purple used only by the Shinjuku Gojo NPC (`mobs/shinjuku_gojo`). |
| `gojo_domain_quick` | 0.2 s Unlimited Void; players get it only through `form6b`, the NPC uses it freely. |

### Sukuna, Megumi and Yuji

* **Vessel rules** (`relics/sukuna_finger`): a non-curse player who eats a finger becomes `has_sukuna`, plays a 141-tick cutscene and starts counting `finger`. If the "only one Sukuna" setting is on and a vessel exists, the finger rejects everyone else ("This finger's vessel has already been reincarnated into another player."). Curses eat fingers for +20 invest points instead; Explosive CE users get +10.
* **Sukuna takeover** (`sukuna_use`): 1–5 fingers = short takeover (91-tick cutscene), 6–14 = longer (51-tick cutscene), 15+ = Fuga and permanent options; 16,000-tick cooldown between takeovers.
* **"Meguna"** (`sukuna_cmds` line 106, `activate_meguna_cutscene`, `meguna_cutscene`): with 15+ fingers, holding the Sukuna technique while in Yuji's body (`yuji_fists`), sneak for 30 ticks and punch a nearby player without Sukuna, or the Megumi NPC. A cutscene moves Sukuna into Megumi's body, and Sukuna gains **Ten Shadows** in addition to Shrine.
* **Heian form** (`heian_transformation`): Sukuna's original four-armed body, with very high speed.

## 2. Unique techniques (one user per server)

New server config `uniqueTechniques`:

| Value | Behaviour |
|---|---|
| `OFF` | Anyone can have anything (JJA default except Sukuna). |
| `CANON` (**default**) | Only "named" techniques are unique: Limitless, Six Eyes, Shrine (Sukuna vessel), Ten Shadows, Mythical Beast Amber, Private Pure Love Train, Idle Transfiguration, Disaster Flames, Judgeman, Cursed Speech, Boogie Woogie, Soul Shrine, Blood Manipulation, Queen of Curses + Copy. Generic ones (Explosive CE, Absorption, Wind-Up, Miracles, Sight, Pain-Killer, Heavenly Restriction) stay shared. |
| `ALL` | Every technique and every Honored trait is unique. |

Rules:

1. **First claim is permanent.** The first player to obtain a unique technique (by roll, finger, kit or command) owns it. It is removed from everyone else's roll pool, fingers reject other players, and kits for it refuse to apply to someone else.
2. **Ownership survives death, logout and Reset Tickets.** "Once someone has it, that's it."
3. **Only an operator can release or transfer** (`/crb owner release|transfer`).
4. **Linked claims**: Limitless and Six Eyes are claimed together by the Gojo kit, but stay separately claimable by rolls (a Six Eyes user without Limitless is still possible, as in JJA).
5. **Sukuna's vessel counts as owning Shrine.** When Sukuna moves into Megumi through the Meguna event, he **also claims Ten Shadows**. If Ten Shadows is already owned by the Megumi player he possesses, ownership moves to the Sukuna player. Megumi's player keeps the body and loses the technique, as in canon.
6. Ownership lives in world `SavedData` (`crb_technique_owners`) keyed by player UUID, so it is server-wide across dimensions.

## 3. Kits (admin-friendly)

A **kit** is a data file (`data/<namespace>/crb_kits/<id>.json`, datapack-overridable) describing a whole character:

```json
{
  "display": "Satoru Gojo",
  "technique": "crb:limitless",
  "forms": "max",
  "traits": ["crb:six_eyes", "crb:rct_master"],
  "unlocks": ["crb:rct", "crb:domain_amplification", "crb:simple_domain", "crb:black_flash_mastery", "crb:blue_teleport", "crb:infinity_passive"],
  "grade": "special",
  "stats": { "strength": 60, "endurance": 70, "agility": 60, "energy_cap": 10000 },
  "combat_styles": ["crb:limitless_fists"],
  "items": [ { "id": "crb:gojo_cloak" }, { "id": "crb:gojo_torso" } ],
  "vessel": null,
  "claims": ["crb:limitless", "crb:six_eyes"]
}
```

Shipped kits:

| Kit | Gets |
|---|---|
| `gojo` | Limitless at max mastery, **Six Eyes**, RCT master, passive Infinity, Blue teleport, Domain Amplification, Simple Domain, Gojo fists, Gojo outfits, Special Grade. The strongest kit. |
| `gojo_student` | Pre-awakening Gojo: Limitless without Hollow Purple 200 % or passive Infinity, no RCT. |
| `sukuna` | Shrine at max, 20 fingers, Fuga, Malevolent Shrine (all three), Kamutoke, Sukuna robes, **Ten Shadows already absorbed** (post-Meguna), Special Grade. |
| `sukuna_heian` | `sukuna` + Heian four-armed form unlocked. |
| `yuji` | Soul Shrine, Prince of Black Sparks, **Sukuna vessel with 1 finger (force-fed)**, Perfect Vessel trait, Divergent Fist, Black Flash affinity, Yuji uniform, Grade 1. |
| `megumi` | Ten Shadows (all shikigami unlocked; Mahoraga still needs the taming ritual), Shadow Swim/Storage, Grade 2. |
| `toji` | Heavenly Restriction (Fushiguro), Inverted Spear of Heaven, Playful Cloud, Split-Soul Katana, Glock, Toji outfit. |
| `maki` | Heavenly Restriction (Zenin, awakened), Split-Soul Katana, Slaughter Demon, Playful Cloud. |
| `kashimo`, `hakari`, `mahito`, `jogo`, `higuruma`, `inumaki`, `todo`, `choso`, `yuta`, `kusakabe`, `finger_bearer` | Their technique at max with matching outfit, tools and traits. |

Applying a kit:

1. Checks uniqueness for every entry in `claims` (fails with a clear message unless `--force`, which transfers ownership).
2. Releases unique claims the player held that the new kit does not include (an operator is re-assigning them), and replaces their technique and traits. Inventory items are never removed.
3. Applies everything above and syncs the client. (The technique-switch flash arrives with the cinematic engine.)

**Yuji vs. Sukuna**: the `yuji` kit makes the player Sukuna's vessel, which claims the Sukuna slot (`crb:shrine`). Under `CANON` a separate `sukuna` player cannot exist at the same time, because Sukuna lives inside Yuji. Re-kitting Yuji (or `/crb owner release crb:shrine`) frees the slot.

### Commands (permission level 2)

```
/crb kit list
/crb kit give <players> <kit> [--force]
/crb technique set <player> <technique> [forms]
/crb technique clear <player>
/crb trait add|remove <player> <trait>
/crb stats set <player> <stat> <value>
/crb grade set <player> <grade>
/crb fingers set <player> <count>
/crb energy set|fill <player> [value]
/crb owner list
/crb owner release <technique>
/crb owner transfer <technique> <player>
/crb cooldowns reset <player>
```

## 4. Making Gojo the strongest

New and restored content, all built from existing JJA Limitless assets (Blue particles, warp/blitz animations, Purple effects):

| # | Addition | Design |
|---|---|---|
| GJ1 | **Six Eyes: perception** | Toggle (Combat Mode `Q` page). Living things within 48 blocks get an outline tinted by cursed-energy level (grade colours); invisible entities and hidden curses are revealed; an indicator shows enemy techniques that are winding up (direction + time to impact) for 1 s before they land. |
| GJ2 | **Six Eyes: efficiency** | Limitless costs −90 % CE (canon: atom-level precision); JJA's regen bonus kept. |
| GJ3 | **Passive Infinity** | With Six Eyes + RCT master, Infinity is always on at a small drain: projectiles stop 0.6 blocks short and melee is slowed to nothing unless the attacker uses Domain Amplification, Simple Domain, a domain's sure-hit, Black Flash, or is a Heavenly Restriction user with the Inverted Spear (canon counters). Taking damage through Infinity still shows the impact. |
| GJ4 | **Blue Teleport** | New ability on Spatial Blitz: plain = blink to the crosshair (up to 32 blocks, line-of-sight); sneak = blink behind the targeted enemy; airborne = three chained blinks within 1.5 s. Uses the `warp` animation, Lapse Blue implosion particles at both ends, and a short white fade. 3 s cooldown. |
| GJ5 | **Blue Flight** | Hold jump while airborne to hover / fly slowly using Lapse Blue (canon levitation). CE drain per second; cancelled by stun. |
| GJ6 | **Restored cut moves** | Twilight Blue (six orbiting Blue orbs that home in), Phase Blue (two Blues that pull from both sides), Phase Red (two Reds that push outward) become new variants of Lapse Blue / Reversal Red, using their existing handler logic as the spec. |
| GJ7 | **Levitate Purple** for players | Aerial Hollow Purple variant (airborne + sneak on Purple), taken from the Shinjuku Gojo NPC. |
| GJ8 | **Quick Unlimited Void** at max mastery | The 0.2 s domain from the Shinjuku fight as a sneak variant once Gojo has full mastery and Six Eyes. |

Balance stays configurable (`gojoOverpowered`, default `true`) for servers that want JJA's original numbers.
