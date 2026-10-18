package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FrontendRenderPass.class, remap = false)
public interface FrontendRenderPassAccessor {
    @Accessor("backend")
    RenderPassBackend jbpLod$getBackend();
}
