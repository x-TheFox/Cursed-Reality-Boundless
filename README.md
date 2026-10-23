# Cursed Reality: Boundless

A Minecraft Java Edition (NeoForge 1.21.1) port of the **Jujutsu Awakening** Bedrock addon: the same techniques, domains, bosses and cinematic timing, rebuilt on Java systems (Combat Mode controls instead of hotbar items, server-authoritative networking, a real cinematic camera, impact-frame shaders and hit-stop).

* Plan and progress: [MASTER_PLAN.md](MASTER_PLAN.md)
* Research: [architecture_and_research/](architecture_and_research/)
* Asset and research tooling: [tools/jja/](tools/jja/)

The original addon is expected next to this repository at `../JJA RP AND BP` (override with `JJA_ROOT`) when regenerating assets.

## Building

```bash
./gradlew build
```

Runs the unit tests and the server GameTests. `./gradlew runClient` starts a dev client.

## Admin quick start

Operators (permission level 2) can turn any player into a full canon character:

```
/crb kit give <player> crb:gojo        # Limitless (max) + Six Eyes + RCT + outfit
/crb kit give <player> crb:sukuna      # Shrine, 20 fingers, Ten Shadows (post-Meguna)
/crb kit give <player> crb:yuji        # Soul Shrine, Sukuna's vessel with 1 finger
/crb kit list
/crb owner list                         # who owns which unique technique
/crb owner release crb:limitless
```

By default (`uniqueTechniques = CANON` in the server config) each named character's technique belongs to the first player who gets it. See [research doc 06](architecture_and_research/06_kits_uniqueness_and_admin.md) for every command and kit.

## Visual checks (dev harness)

Scripted screenshots of any move, for checking cinematics without playing by hand:

```bash
./gradlew runHarnessServer
```

```bash
./gradlew runHarnessClient -PcrbHarness=$PWD/tools/harness/hollow_purple.txt
```

Scripts (`tools/harness/*.txt`) run commands, wait on a move's own clock (`waitmove 56`), aim the camera and take screenshots into `run/screenshots/harness/`. Examples from the current build are in [docs/progress](docs/progress/).
