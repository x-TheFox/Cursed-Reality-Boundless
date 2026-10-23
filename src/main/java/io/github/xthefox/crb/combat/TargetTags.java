package io.github.xthefox.crb.combat;

import io.github.xthefox.crb.CRB;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * JJA target filters on hits ({@code damage @e[tag=Big] 30}, {@code @e[tag=!deletion_adapted]}).
 *
 * <p>Only facts we can compute are checked; everything else passes. Many JJA target tags are marks
 * a move puts on its own victims mid-combo ({@code dempseyrolled}, {@code filtered_target}), and
 * treating those as unmet would silently drop the hit.
 */
public final class TargetTags {
    public static final TagKey<EntityType<?>> BIG = TagKey.create(Registries.ENTITY_TYPE, CRB.id("big"));

    /** Answers whether a target has a JJA tag. */
    public interface Provider {
        /** @return TRUE/FALSE when this provider knows the tag (or "score:k=range"), null otherwise */
        Boolean has(LivingEntity target, String tag);
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    static {
        PROVIDERS.add((t, tag) -> tag.equals("Big") ? isBig(t) : null);
        PROVIDERS.add((t, tag) -> tag.equals("player") ? t instanceof net.minecraft.world.entity.player.Player : null);
    }

    private TargetTags() {}

    /** Adaptation, Infinity, shikigami ownership and curses register here as they are implemented. */
    public static void register(Provider p) {
        PROVIDERS.add(p);
    }

    public static boolean isBig(LivingEntity e) {
        return e.getType().is(BIG) || e.getBbHeight() >= 3.0f || e.getBbWidth() >= 2.0f;
    }

    /**
     * {@code conds} are "tag=x" / "tag=!x" / "score:k=range" strings. Unknown tags pass; unknown
     * scores fail (they gate bonus hits such as domain amplification), except always-true
     * {@code k=0..} checks.
     */
    public static boolean test(List<String> conds, LivingEntity target) {
        for (String c : conds) {
            boolean negate;
            String tag;
            if (c.startsWith("tag=!")) { negate = true; tag = c.substring(5); }
            else if (c.startsWith("tag=")) { negate = false; tag = c.substring(4); }
            else if (c.startsWith("score:")) {
                Boolean known = lookup(target, c);
                if (known == null ? !c.endsWith("=0..") : !known) return false;
                continue;
            }
            else continue;
            Boolean known = lookup(target, tag);
            if (known != null && known == negate) return false;
        }
        return true;
    }

    static Boolean lookup(LivingEntity target, String tag) {
        for (Provider p : PROVIDERS) {
            Boolean b = p.has(target, tag);
            if (b != null) return b;
        }
        return null;
    }
}
