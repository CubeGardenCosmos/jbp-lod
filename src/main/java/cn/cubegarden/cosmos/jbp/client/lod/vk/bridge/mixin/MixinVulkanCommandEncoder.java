package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Locked RenderPearl boundaries; never intercepts JBP-LOD's own native queue submissions. */
@Mixin(value = VulkanCommandEncoder.class, remap = false)
public abstract class MixinVulkanCommandEncoder {
    @Inject(method = "submit", at = @At("RETURN"))
    private void jbpLod$afterGameSubmission(CallbackInfo ci) {
        GameVulkan.afterGameSubmission();
    }

    // destroy() closes its final submission, then already waits for the graphics queue.
    // Queue our markers before that existing wait, and poll after it, before VMA destruction.
    @Inject(method = "destroy", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanQueue;waitIdle()V"))
    private void jbpLod$beforeFinalQueueDrain(CallbackInfo ci) {
        GameVulkan.afterGameSubmission();
    }

    @Inject(method = "destroy", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanQueue;waitIdle()V", shift = At.Shift.AFTER))
    private void jbpLod$afterFinalQueueDrain(CallbackInfo ci) {
        GameVulkan.pollSubmissionRetirements();
    }
}
