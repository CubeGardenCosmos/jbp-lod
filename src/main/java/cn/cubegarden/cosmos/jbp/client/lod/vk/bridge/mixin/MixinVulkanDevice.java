package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.VulkanBridgeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanDevice.class, remap = false)
public abstract class MixinVulkanDevice {
    @Inject(method = "close", at = @At("HEAD"))
    private void jbpLod$closeBridgeProbe(CallbackInfo ci) {
        VulkanBridgeProbe.close((VulkanDevice) (Object) this);
    }
}
