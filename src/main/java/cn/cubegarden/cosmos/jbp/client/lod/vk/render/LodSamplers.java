package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

/** Samplers used by the LOD draw and depth-conversion descriptors. */
final class LodSamplers {
    private LodSamplers() {
    }

    /** Model atlas sampler: nearest texels, linear mips up to {@code maxLod}, repeat addressing (upstream). */
    static long atlas(VkDevice device, float maxLod) {
        if (!Float.isFinite(maxLod) || maxLod < 0.0f) {
            throw new IllegalArgumentException("maxLod must be finite and non-negative");
        }
        return create(device, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_LINEAR,
                VK_SAMPLER_ADDRESS_MODE_REPEAT, maxLod);
    }

    /** Lightmap sampler: bilinear, clamped, single level. */
    static long lightmap(VkDevice device) {
        return create(device, VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST,
                VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, 0.0f);
    }

    /** Depth sampler: exact texels, clamped, single level. */
    static long depth(VkDevice device) {
        return create(device, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST,
                VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, 0.0f);
    }

    private static long create(VkDevice device, int filter, int mipmapMode, int addressMode, float maxLod) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter).mipmapMode(mipmapMode)
                    .addressModeU(addressMode).addressModeV(addressMode).addressModeW(addressMode)
                    .mipLodBias(0.0f).anisotropyEnable(false).maxAnisotropy(1.0f)
                    .compareEnable(false).minLod(0.0f).maxLod(maxLod)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE).unnormalizedCoordinates(false);
            var handle = stack.mallocLong(1);
            int result = vkCreateSampler(device, info, null, handle);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("[JBP-LOD] vkCreateSampler failed with VkResult " + result);
            }
            return handle.get(0);
        }
    }
}
