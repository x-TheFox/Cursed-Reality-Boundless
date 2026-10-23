package io.github.xthefox.crb.progression.kit;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.xthefox.crb.progression.Grade;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A whole character loadout, loaded from {@code data/<ns>/crb_kits/<id>.json} and applied with
 * {@code /crb kit give}. See research doc 06.
 *
 * @param forms     technique mastery; {@link #MAX_FORMS} means the technique's maximum
 * @param fingers   if present, the player becomes Sukuna's vessel with this many fingers
 * @param claims    unique ids (techniques/traits) this kit takes; checked before applying
 */
public record Kit(
        String display,
        Optional<ResourceLocation> technique,
        int forms,
        List<ResourceLocation> traits,
        List<ResourceLocation> unlocks,
        Optional<Grade> grade,
        Map<String, Integer> stats,
        List<ResourceLocation> combatStyles,
        List<ItemEntry> items,
        Optional<Integer> fingers,
        List<ResourceLocation> claims
) {
    public static final int MAX_FORMS = -1;

    public record ItemEntry(ResourceLocation id, int count) {
        public static final Codec<ItemEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(ItemEntry::id),
                Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(ItemEntry::count)
        ).apply(i, ItemEntry::new));
    }

    private static final Codec<Integer> FORMS = Codec.either(Codec.INT, Codec.STRING).comapFlatMap(
            e -> e.map(DataResult::success,
                    s -> "max".equals(s) ? DataResult.success(MAX_FORMS) : DataResult.error(() -> "forms must be a number or \"max\"")),
            v -> v == MAX_FORMS ? Either.right("max") : Either.left(v));

    private static final Codec<Grade> GRADE = Codec.STRING.comapFlatMap(s -> {
        try { return DataResult.success(Grade.parse(s)); }
        catch (IllegalArgumentException e) { return DataResult.error(e::getMessage); }
    }, Grade::serializedName);

    public static final Codec<Kit> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("display").forGetter(Kit::display),
            ResourceLocation.CODEC.optionalFieldOf("technique").forGetter(Kit::technique),
            FORMS.optionalFieldOf("forms", MAX_FORMS).forGetter(Kit::forms),
            ResourceLocation.CODEC.listOf().optionalFieldOf("traits", List.of()).forGetter(Kit::traits),
            ResourceLocation.CODEC.listOf().optionalFieldOf("unlocks", List.of()).forGetter(Kit::unlocks),
            GRADE.optionalFieldOf("grade").forGetter(Kit::grade),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("stats", Map.of()).forGetter(Kit::stats),
            ResourceLocation.CODEC.listOf().optionalFieldOf("combat_styles", List.of()).forGetter(Kit::combatStyles),
            ItemEntry.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(Kit::items),
            Codec.intRange(0, 20).optionalFieldOf("fingers").forGetter(Kit::fingers),
            ResourceLocation.CODEC.listOf().optionalFieldOf("claims", List.of()).forGetter(Kit::claims)
    ).apply(i, Kit::new));
}
