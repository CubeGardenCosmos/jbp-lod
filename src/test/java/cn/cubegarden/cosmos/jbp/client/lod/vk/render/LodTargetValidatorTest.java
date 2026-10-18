package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameTargets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

class LodTargetValidatorTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final long SCOPE = 0x55;

    @Test
    void acceptsFrozenVanillaCutoutTarget() {
        assertTrue(LodTargetValidator.validate(true, 0x10, SCOPE, 0, 0, WIDTH, HEIGHT,
                targets(color(VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT, 1,
                                VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x20),
                        depth(VK_FORMAT_D32_SFLOAT, WIDTH, HEIGHT, 1,
                                VK_IMAGE_ASPECT_DEPTH_BIT, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x30)))
                .valid());
    }

    @Test
    void rejectsUnsupportedColorFormat() {
        assertInvalid(targets(color(999, WIDTH, HEIGHT, 1, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x20),
                depth(VK_FORMAT_D32_SFLOAT, WIDTH, HEIGHT, 1, VK_IMAGE_ASPECT_DEPTH_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x30)));
    }

    @Test
    void rejectsMismatchedExtent() {
        assertInvalid(targets(color(VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT, 1, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x20),
                depth(VK_FORMAT_D32_SFLOAT, WIDTH - 1, HEIGHT, 1, VK_IMAGE_ASPECT_DEPTH_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x30)));
    }

    @Test
    void rejectsMultisampleTargetsAndLayoutMismatch() {
        assertInvalid(targets(color(VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT, 4, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x20),
                depth(VK_FORMAT_D32_SFLOAT, WIDTH, HEIGHT, 4, VK_IMAGE_ASPECT_DEPTH_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x30)));
        assertInvalid(targets(color(VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT, 1, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, 0, 0x20),
                depth(VK_FORMAT_D32_SFLOAT, WIDTH, HEIGHT, 1, VK_IMAGE_ASPECT_DEPTH_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x30)));
    }

    @Test
    void rejectsOutOfBoundsRenderAreaAndWrongScope() {
        LodFrameTargets frame = targets(color(VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT, 1,
                        VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x20),
                depth(VK_FORMAT_D32_SFLOAT, WIDTH, HEIGHT, 1, VK_IMAGE_ASPECT_DEPTH_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x30));
        assertFalse(LodTargetValidator.validate(true, 0x10, SCOPE, WIDTH - 4, 0, 8, 1, frame).valid());
        LodFrameTargets wrongScope = new LodFrameTargets(frame.color(), frame.depth(), frame.matrices(), SCOPE + 1);
        assertFalse(LodTargetValidator.validate(true, 0x10, SCOPE, 0, 0, WIDTH, HEIGHT, wrongScope).valid());
    }

    private static void assertInvalid(LodFrameTargets targets) {
        assertFalse(LodTargetValidator.validate(true, 0x10, SCOPE, 0, 0, WIDTH, HEIGHT, targets).valid());
    }

    @Test
    void rejectsDepthWithoutSampledUsage() {
        LodFrameTargets.ImageTarget unsampled = new LodFrameTargets.ImageTarget(0x30, 0x130,
                VK_FORMAT_D32_SFLOAT, WIDTH, HEIGHT, 1, VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, false);
        assertInvalid(targets(color(VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT, 1, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0x20), unsampled));
    }

    private static LodFrameTargets targets(LodFrameTargets.ImageTarget color, LodFrameTargets.ImageTarget depth) {
        return new LodFrameTargets(color, depth,
                new LodFrameTargets.Matrices(identity(), identity(), identity()), SCOPE);
    }

    private static LodFrameTargets.ImageTarget color(int format, int width, int height, int samples,
                                                      int aspect, int layout, int after, long image) {
        return new LodFrameTargets.ImageTarget(image, image + 0x100, format, width, height, samples,
                aspect, layout, after, true);
    }

    private static LodFrameTargets.ImageTarget depth(int format, int width, int height, int samples,
                                                      int aspect, int layout, int after, long image) {
        return new LodFrameTargets.ImageTarget(image, image + 0x100, format, width, height, samples,
                aspect, layout, after, true);
    }

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }
}
