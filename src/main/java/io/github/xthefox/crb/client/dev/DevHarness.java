package io.github.xthefox.crb.client.dev;

import io.github.xthefox.crb.CRB;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Development-only scripted client used to verify cinematics visually. Enabled by the system
 * property {@code crb.devHarness=<script file>} (see the {@code runHarnessClient} Gradle run).
 *
 * <p>Script lines (blank lines and {@code #} comments ignored):
 * <pre>
 * wait 40                 # ticks
 * waitmove 56             # until the local player's active move reaches elapsed tick 56
 * cmd crb kit give @s crb:gojo
 * look 90 10              # yaw pitch
 * fx ds:hollow_blue_stars 3 1.5   # spawn a Bedrock particle effect 3 blocks ahead, 1.5 up
 * shot hollow_purple_t56  # screenshot into &lt;gameDir&gt;/screenshots/harness/
 * quit
 * </pre>
 * The script starts 60 ticks after joining a world.
 */
public final class DevHarness {
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static int waitTicks = -1;
    private static int waitMove = -1;
    private static boolean moveSeen;
    private static int moveWaitTimeout;
    private static boolean started;

    private DevHarness() {}

    public static void init() {
        String path = System.getProperty("crb.devHarness", "");
        if (path.isBlank()) return;
        try {
            for (String line : Files.readAllLines(Path.of(path))) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) LINES.add(l);
            }
        } catch (IOException e) {
            CRB.LOG.error("Dev harness: cannot read {}", path, e);
            return;
        }
        CRB.LOG.info("Dev harness loaded {} steps from {}", LINES.size(), path);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.pauseOnLostFocus = false;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            if (!started) {
                started = true;
                waitTicks = 60;
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!started || mc.player == null || mc.getConnection() == null) return;
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (waitMove >= 0) {
            var playing = io.github.xthefox.crb.client.move.ClientMoves.active().get(mc.player.getId());
            if (playing != null) {
                moveSeen = true;
                int elapsed = (int) (io.github.xthefox.crb.client.move.ClientMoves.ticks() - playing.localStart);
                if (elapsed < waitMove) return;
            } else if (!moveSeen && --moveWaitTimeout > 0) {
                return; // the move's start packet has not arrived yet
            }
            // reached the tick, or the move already ended
            waitMove = -1;
        }
        while (!LINES.isEmpty() && waitTicks <= 0 && waitMove < 0) {
            String line = LINES.poll();
            String[] p = line.split(" ", 2);
            String arg = p.length > 1 ? p[1] : "";
            CRB.LOG.info("Dev harness: {}", line);
            switch (p[0]) {
                case "wait" -> waitTicks = Integer.parseInt(arg.trim());
                case "waitmove" -> {
                    waitMove = Integer.parseInt(arg.trim());
                    moveWaitTimeout = 100;
                }
                case "cmd" -> {
                    if (arg.contains("move play")) moveSeen = false;
                    mc.getConnection().sendCommand(arg.startsWith("/") ? arg.substring(1) : arg);
                }
                case "fx" -> {
                    // fx <effect id> <forward> <up> [right]: spawn a particle effect in front of the player
                    String[] a = arg.trim().split("\\s+");
                    double fwd = a.length > 1 ? Double.parseDouble(a[1]) : 3, upOff = a.length > 2 ? Double.parseDouble(a[2]) : 1.5,
                            left = a.length > 3 ? Double.parseDouble(a[3]) : 0;
                    var pos = io.github.xthefox.crb.move.CuePositions.local(mc.player.position().add(0, upOff, 0), mc.player.getYRot(), 0, left, 0, fwd);
                    boolean ok = io.github.xthefox.crb.client.fx.BedrockFX.INSTANCE.spawn(a[0], pos, mc.player.getYRot(), null);
                    if (!ok) CRB.LOG.warn("Dev harness: unknown effect {}", a[0]);
                }
                case "look" -> {
                    String[] a = arg.trim().split("\\s+");
                    mc.player.setYRot(Float.parseFloat(a[0]));
                    mc.player.setXRot(Float.parseFloat(a[1]));
                }
                case "shot" -> {
                    try {
                        Files.createDirectories(mc.gameDirectory.toPath().resolve("screenshots/harness"));
                    } catch (IOException e) {
                        CRB.LOG.error("Dev harness: cannot create screenshot folder", e);
                    }
                    mc.getToasts().clear(); // dev-only "chat can't be verified" toasts from the offline server
                    Screenshot.grab(mc.gameDirectory, "harness/" + arg.trim() + ".png", mc.getMainRenderTarget(),
                            msg -> CRB.LOG.info("Dev harness screenshot: {}", msg.getString()));
                }
                case "quit" -> {
                    CRB.LOG.info("Dev harness finished");
                    mc.stop();
                    return;
                }
                default -> CRB.LOG.warn("Dev harness: unknown step {}", line);
            }
        }
    }
}
