package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FrontendGpuDevice.class, remap = false)
public interface FrontendGpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend jbpLod$getBackend();
}
