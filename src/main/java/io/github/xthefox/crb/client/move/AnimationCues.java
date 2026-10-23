package io.github.xthefox.crb.client.move;

import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.enums.PlayState;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.move.MoveLogic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code anim} cues: JJA's {@code playanimation}. Players animate through a Player Animation
 * Library layer; other entities through {@link EntityAnimator}s registered by their renderers.
 */
public final class AnimationCues {
    /** Our move layer sits above vanilla and most other mods' layers. */
    public static final ResourceLocation LAYER = CRB.id("moves");
    public static final int LAYER_PRIORITY = 1500;

    /** Plays a Bedrock animation on a non-player entity (implemented by GeckoLib renderers). */
    @FunctionalInterface
    public interface EntityAnimator {
        void play(Entity entity, String animation, float startTick);
    }

    private static final Map<Class<?>, EntityAnimator> ANIMATORS = new ConcurrentHashMap<>();

    private AnimationCues() {}

    public static void register() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, LAYER_PRIORITY,
                player -> new PlayerAnimationController(player, (controller, data, setter) -> PlayState.STOP));
        ClientMoves.registerHandler("anim", (m, cue, elapsed) -> playFor(m, cue, 0));
        ClientMoves.registerHandler("anim_resume", (m, cue, offset) -> play(m.entity(), cue.str("anim", ""), offset));
        ClientMoves.onEnd(AnimationCues::onEnd);
    }

    public static void registerAnimator(Class<? extends Entity> type, EntityAnimator animator) {
        ANIMATORS.put(type, animator);
    }

    private static void playFor(ClientMoves.Playing m, MoveScript.Cue cue, float offset) {
        String anim = cue.str("anim", "");
        String target = cue.str("target", "self");
        if (target.equals("self")) {
            play(m.entity(), anim, offset);
        } else if (target.startsWith("radius:")) {
            // victims within the radius play the animation too (e.g. domain sure-hit "stunned" poses)
            Entity caster = m.entity();
            var level = Minecraft.getInstance().level;
            if (caster == null || level == null) return;
            double r = Double.parseDouble(target.substring(7));
            for (Entity e : level.getEntities(caster, caster.getBoundingBox().inflate(r), e -> e instanceof LivingEntity)) {
                if (e.distanceToSqr(caster) <= r * r) play(e, anim, offset);
            }
            play(caster, anim, offset);
        }
    }

    public static void play(Entity entity, String animation, float startTick) {
        if (entity == null || animation.isEmpty()) return;
        if (entity instanceof AbstractClientPlayer player) {
            controller(player).triggerAnimation(ResourceLocation.fromNamespaceAndPath(CRB.MOD_ID, animation), startTick);
            return;
        }
        for (var e : ANIMATORS.entrySet()) {
            if (e.getKey().isInstance(entity)) {
                e.getValue().play(entity, animation, startTick);
                return;
            }
        }
    }

    private static void onEnd(ClientMoves.Playing m, MoveLogic.EndReason reason) {
        // a finished move lets its last animation play out, as in Bedrock; cancelled moves snap back
        if (reason == MoveLogic.EndReason.FINISHED) return;
        if (m.entity() instanceof AbstractClientPlayer player) controller(player).stopTriggeredAnimation();
    }

    private static PlayerAnimationController controller(AbstractClientPlayer player) {
        return (PlayerAnimationController) PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER);
    }
}
