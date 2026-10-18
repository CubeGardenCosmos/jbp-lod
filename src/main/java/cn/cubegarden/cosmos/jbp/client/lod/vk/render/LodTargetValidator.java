package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameTargets;
import cn.cubegarden.cosmos.jbp.client.lod.vk.caps.DeviceCaps;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

/** Pure metadata checks for the borrowed V1 dynamic-rendering target. */
public final class LodTargetValidator {
    private LodTargetValidator() {
    }

    public static Result validate(boolean dynamicRenderingActive, long deviceAddress,
                                  long renderScopeIdentity, int renderAreaX, int renderAreaY,
                                  int renderAreaWidth, int renderAreaHeight,
                                  LodFrameTargets targets) {
        if (targets == null) {
            return Result.invalid("targets are required");
        }
        if (!dynamicRenderingActive) {
            return Result.invalid("CUTOUT dynamic-rendering scope is not active");
        }
        if (deviceAddress == 0 || renderScopeIdentity == 0) {
            return Result.invalid("device and render-scope identities must be nonzero");
        }
        if (targets.renderScopeIdentity() != renderScopeIdentity) {
            return Result.invalid("provider returned targets from a different render scope");
        }

        LodFrameTargets.ImageTarget color = targets.color();
        LodFrameTargets.ImageTarget depth = targets.depth();
        if (color.image() == depth.image()) {
            return Result.invalid("color and depth images must be distinct");
        }
        if (!isColorFormat(color.vkFormat()) || color.aspectMask() != VK_IMAGE_ASPECT_COLOR_BIT) {
            return Result.invalid("color target format/aspect is unsupported");
        }
        if (!isDepthFormat(depth.vkFormat()) || depth.aspectMask() != VK_IMAGE_ASPECT_DEPTH_BIT) {
            return Result.invalid("depth target format/aspect is unsupported");
        }
        if (color.width() <= 0 || color.height() <= 0
                || color.width() != depth.width() || color.height() != depth.height()) {
            return Result.invalid("color and depth extents must be positive and match");
        }
        if (color.sampleCount() != 1 || depth.sampleCount() != 1) {
            return Result.invalid("the frozen V1 CUTOUT path requires single-sample targets");
        }
        if (!depth.sampled()) {
            return Result.invalid("the depth target needs sampled-image usage for LOD occlusion culling");
        }
        if (!isUnchangedGeneralLayout(color) || !isUnchangedGeneralLayout(depth)) {
            return Result.invalid("borrowed V1 targets must remain in GENERAL layout during CUTOUT");
        }
        if (renderAreaX < 0 || renderAreaY < 0 || renderAreaWidth <= 0 || renderAreaHeight <= 0
                || renderAreaX > color.width() - renderAreaWidth
                || renderAreaY > color.height() - renderAreaHeight) {
            return Result.invalid("CUTOUT render area is outside the target extent");
        }
        if (!targets.matrices().isFinite()) {
            return Result.invalid("projection/model-view matrices must be finite 4x4 matrices");
        }
        return Result.success();
    }

    /** Also verifies the selected physical device can attach the supplied color/depth formats. */
    public static Result validate(boolean dynamicRenderingActive, long deviceAddress,
                                  long renderScopeIdentity, int renderAreaX, int renderAreaY,
                                  int renderAreaWidth, int renderAreaHeight,
                                  LodFrameTargets targets, DeviceCaps caps) {
        Result metadata = validate(dynamicRenderingActive, deviceAddress, renderScopeIdentity,
                renderAreaX, renderAreaY, renderAreaWidth, renderAreaHeight, targets);
        if (!metadata.valid() || caps == null) {
            return caps == null && metadata.valid() ? Result.invalid("device capabilities are required") : metadata;
        }
        if (!caps.supportsFormat(targets.color().vkFormat(),
                org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT)) {
            return Result.invalid("physical device cannot use the color target format as an attachment");
        }
        if (!caps.supportsFormat(targets.depth().vkFormat(),
                org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)) {
            return Result.invalid("physical device cannot use the depth target format as an attachment");
        }
        return Result.success();
    }

    private static boolean isUnchangedGeneralLayout(LodFrameTargets.ImageTarget image) {
        return image.currentLayout() == VK_IMAGE_LAYOUT_GENERAL
                && image.layoutAfterDraw() == VK_IMAGE_LAYOUT_GENERAL;
    }

    private static boolean isColorFormat(int format) {
        return format == VK_FORMAT_R8G8B8A8_UNORM
                || format == VK_FORMAT_R8G8B8A8_SRGB
                || format == VK_FORMAT_B8G8R8A8_UNORM
                || format == VK_FORMAT_B8G8R8A8_SRGB
                || format == VK_FORMAT_R16G16B16A16_SFLOAT;
    }

    private static boolean isDepthFormat(int format) {
        return format == VK_FORMAT_D16_UNORM
                || format == VK_FORMAT_D24_UNORM_S8_UINT
                || format == VK_FORMAT_D32_SFLOAT
                || format == VK_FORMAT_D32_SFLOAT_S8_UINT;
    }

    public record Result(boolean valid, String reason) {
        private static Result success() {
            return new Result(true, "");
        }

        private static Result invalid(String reason) {
            return new Result(false, reason);
        }
    }
}
