package io.github.xthefox.crb.core;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Mirrors JJA's "Addon Settings" item (ds:settings). Gameplay rules are server-side;
 * presentation preferences are per client.
 */
public final class CRBConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;
    public static final ModConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        var server = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();
        var client = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
    }

    private CRBConfig() {}

    public enum Uniqueness { OFF, CANON, ALL }

    public static final class Server {
        public final ModConfigSpec.BooleanValue griefing;
        public final ModConfigSpec.BooleanValue mobEvents;
        public final ModConfigSpec.BooleanValue cooldowns;
        public final ModConfigSpec.BooleanValue missionCooldowns;
        public final ModConfigSpec.BooleanValue onlyOneSukunaVessel;
        public final ModConfigSpec.BooleanValue mahitoInstaKill;
        public final ModConfigSpec.DoubleValue expMultiplier;
        public final ModConfigSpec.IntValue lagCompensationTicks;
        public final ModConfigSpec.EnumValue<Uniqueness> uniqueTechniques;
        public final ModConfigSpec.BooleanValue gojoOverpowered;

        Server(ModConfigSpec.Builder b) {
            b.push("gameplay");
            griefing = b.comment("Techniques destroy blocks (JJA: Block Destruction/Griefing).")
                    .define("griefing", true);
            mobEvents = b.comment("Curse raids and custom mob events spawn (JJA: Custom Mob Event Spawning).")
                    .define("mobEvents", true);
            cooldowns = b.comment("Ability cooldowns are enforced (JJA: Toggle Cooldowns).")
                    .define("cooldowns", true);
            missionCooldowns = b.comment("Mission cooldowns are enforced (JJA: Toggle Mission Cooldowns).")
                    .define("missionCooldowns", true);
            onlyOneSukunaVessel = b.comment("Only one player per world can become Sukuna's vessel.")
                    .define("onlyOneSukunaVessel", true);
            mahitoInstaKill = b.comment("Idle Transfiguration's soul touch can instantly kill weakened targets.")
                    .define("mahitoInstaKill", false);
            expMultiplier = b.comment("Experience gain multiplier.")
                    .defineInRange("expMultiplier", 1.0, 0.0, 100.0);
            uniqueTechniques = b.comment(
                            "One user per technique. OFF: anyone can have anything (JJA default, except Sukuna's vessel).",
                            "CANON: named characters' techniques (Limitless, Shrine, Ten Shadows ...) and Six Eyes are claimed by the first player to get them.",
                            "ALL: every technique and every honored trait is unique.",
                            "Claims are permanent; operators can release or transfer them with /crb owner.")
                    .defineEnum("uniqueTechniques", Uniqueness.CANON);
            gojoOverpowered = b.comment("Enable the Gojo upgrades beyond JJA's numbers (Six Eyes efficiency, passive Infinity, Blue teleport and flight).")
                    .define("gojoOverpowered", true);
            b.pop();
            b.push("network");
            lagCompensationTicks = b.comment("Maximum ticks melee hit tests rewind targets to compensate for attacker latency.")
                    .defineInRange("lagCompensationTicks", 3, 0, 10);
            b.pop();
        }
    }

    public static final class Client {
        public final ModConfigSpec.BooleanValue impactFrames;
        public final ModConfigSpec.BooleanValue cinematicCamera;
        public final ModConfigSpec.DoubleValue shakeScale;
        public final ModConfigSpec.DoubleValue flashOpacity;
        public final ModConfigSpec.BooleanValue hitStop;

        Client(ModConfigSpec.Builder b) {
            b.push("presentation");
            impactFrames = b.comment("Show full-screen impact frames (JJA: Impact frames).")
                    .define("impactFrames", true);
            cinematicCamera = b.comment("Let techniques take control of the camera for scripted shots.")
                    .define("cinematicCamera", true);
            shakeScale = b.comment("Screen shake multiplier (0 disables).")
                    .defineInRange("shakeScale", 1.0, 0.0, 2.0);
            flashOpacity = b.comment("Maximum opacity of screen flashes and fades.")
                    .defineInRange("flashOpacity", 1.0, 0.0, 1.0);
            hitStop = b.comment("Briefly freeze attacker and victim animation on heavy hits.")
                    .define("hitStop", true);
            b.pop();
        }
    }
}
