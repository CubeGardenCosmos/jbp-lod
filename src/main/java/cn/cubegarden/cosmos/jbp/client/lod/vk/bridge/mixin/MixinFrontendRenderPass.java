package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import cn.cubegarden.cosmos.jbp.client.lod.vk.render.VulkanLodRenderer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The frontend's onFinish callback closes the backend rendering scope before this hook runs. */
@Mixin(value = FrontendRenderPass.class, remap = false)
public abstract class MixinFrontendRenderPass {
    @Inject(method = "close", at = @At("RETURN"))
    private void jbpLod$afterPassClosed(CallbackInfo ci) {
        VulkanLodRenderer.afterPassClosed((RenderPass) (Object) this);
    }
}
