package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Upstream Voxy reads {@code SpriteContents.mipmapStrategy} through an access widener; JBP-LOD uses an accessor mixin. */
@Mixin(value = SpriteContents.class, remap = false)
public interface SpriteContentsAccessor {
    @Accessor("mipmapStrategy")
    MipmapStrategy jbpLod$getMipmapStrategy();
}
