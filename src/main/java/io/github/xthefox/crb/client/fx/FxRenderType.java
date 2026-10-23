package io.github.xthefox.crb.client.fx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One particle render type per (texture, Bedrock material). JJA's particle textures are bound
 * directly (never stitched into the vanilla particle atlas, which they would overflow).
 * {@code particles_add} → additive, {@code particles_blend} → alpha blend, {@code particles_alpha} → cutout.
 */
public record FxRenderType(ResourceLocation texture, FxDef.Material material) implements ParticleRenderType {
    private static final Map<FxRenderType, FxRenderType> CACHE = new ConcurrentHashMap<>();
    public static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public static FxRenderType of(ResourceLocation texture, FxDef.Material material) {
        FxRenderType key = new FxRenderType(texture == null ? WHITE : texture, material);
        return CACHE.computeIfAbsent(key, k -> k);
    }

    @Override
    public BufferBuilder begin(Tesselator tesselator, TextureManager textures) {
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderTexture(0, texture);
        switch (material) {
            case ADD -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                RenderSystem.depthMask(false);
            }
            case BLEND -> {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.depthMask(false);
            }
            case ALPHA -> {
                RenderSystem.disableBlend();
                RenderSystem.depthMask(true);
            }
        }
        return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
    }

    @Override
    public boolean isTranslucent() {
        return material != FxDef.Material.ALPHA;
    }

    @Override
    public String toString() {
        return "crb_fx[" + texture + "," + material + "]";
    }
}
