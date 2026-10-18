package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanRenderPass.class, remap = false)
public interface VulkanRenderPassAccessor {
    @Accessor("commandBuffer")
    VkCommandBuffer jbpLod$getCommandBuffer();

    @Accessor("renderArea")
    RenderPass.RenderArea jbpLod$getRenderArea();
}
