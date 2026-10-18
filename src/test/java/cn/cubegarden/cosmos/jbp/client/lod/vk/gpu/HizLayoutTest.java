package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HizLayoutTest {
    @Test
    void extentsRoundDownToPowerOfTwoLikeUpstream() {
        HizLayout layout = HizLayout.forSource(2560, 1440);

        assertEquals(2048, layout.width());
        assertEquals(1024, layout.height());
        assertEquals(HizLayout.MAX_LEVELS, layout.levels());
        assertTrue(layout.computeChain());
        assertEquals(2048 / 64, layout.dispatchGroupsX());
        assertEquals(1024 / 64, layout.dispatchGroupsY());
        assertEquals((2048 << 16) | 1024, layout.packedSize());
        assertEquals(1.0f / 2048, layout.invWidth());
        assertEquals(1.0f / 1024, layout.invHeight());
        assertEquals(32, layout.mipWidth(6));
        assertEquals(16, layout.mipHeight(6));
    }

    @Test
    void sameHighestPowerOfTwoReusesTheImage() {
        assertTrue(HizLayout.forSource(1920, 1080).sameImage(HizLayout.forSource(1025, 1024)));
        assertNotEquals(HizLayout.forSource(1920, 1080), HizLayout.forSource(2048, 1080));
    }

    @Test
    void minimumComputeSizeStillHasSevenLevelsSoMipSixExists() {
        HizLayout layout = HizLayout.forSource(64, 64);

        assertTrue(layout.computeChain());
        assertEquals(7, layout.levels());
        assertEquals(1, layout.mipWidth(6));
        assertEquals(1, layout.dispatchGroupsX());
    }

    @Test
    void smallOrThinExtentsUseBlitOnlyChain() {
        HizLayout thin = HizLayout.forSource(4096, 32);
        assertFalse(thin.computeChain());
        assertEquals(7, thin.levels());
        assertEquals(64, thin.mipWidth(6));
        assertEquals(1, thin.mipHeight(6));
        assertThrows(IllegalStateException.class, thin::dispatchGroupsX);

        HizLayout tiny = HizLayout.forSource(8, 4);
        assertEquals(3, tiny.levels());
        assertEquals(2, tiny.mipWidth(2));

        assertEquals(1, HizLayout.forSource(1, 1).levels());
        assertThrows(IndexOutOfBoundsException.class, () -> HizLayout.forSource(1, 1).mipWidth(1));
    }

    @Test
    void rejectsEmptyAndUnpackableExtents() {
        assertThrows(IllegalArgumentException.class, () -> HizLayout.forSource(0, 10));
        assertThrows(IllegalArgumentException.class, () -> HizLayout.forSource(10, -1));
        assertThrows(IllegalArgumentException.class, () -> HizLayout.forSource(40000, 1080));
        assertEquals(16384, HizLayout.forSource(16384, 16384).width());
    }

    @Test
    void computeScheduleOrdersBlitDispatchAndLayoutsAndEndsReadable() {
        HizLayout layout = HizLayout.forSource(1920, 1080);
        List<HizLayout.Command> commands = layout.schedule();

        assertEquals(List.of(HizLayout.Kind.BARRIER, HizLayout.Kind.BLIT, HizLayout.Kind.BARRIER,
                        HizLayout.Kind.DISPATCH, HizLayout.Kind.BARRIER),
                commands.stream().map(HizLayout.Command::kind).toList());
        assertEquals(0, commands.get(1).level());
        simulate(layout, commands);
    }

    @Test
    void blitOnlyScheduleProducesEveryLevelInOrder() {
        HizLayout layout = HizLayout.forSource(256, 16);
        List<HizLayout.Command> commands = layout.schedule();

        int[] blits = commands.stream().filter(c -> c.kind() == HizLayout.Kind.BLIT)
                .mapToInt(HizLayout.Command::level).toArray();
        assertEquals(layout.levels(), blits.length);
        assertTrue(Arrays.equals(java.util.stream.IntStream.range(0, layout.levels()).toArray(), blits));
        assertTrue(commands.stream().noneMatch(c -> c.kind() == HizLayout.Kind.DISPATCH));
        simulate(layout, commands);
    }

    @Test
    void everyScheduleVariantSimulatesCorrectly() {
        int[][] sizes = {{64, 64}, {64, 4096}, {4096, 4096}, {63, 63}, {2, 2}, {1, 1}, {640, 48}, {16384, 64}};
        for (int[] size : sizes) {
            HizLayout layout = HizLayout.forSource(size[0], size[1]);
            simulate(layout, layout.schedule());
        }
    }

    /** Tracks each level's layout through the commands and checks hazards against the shader ABI. */
    private static void simulate(HizLayout layout, List<HizLayout.Command> commands) {
        HizLayout.ImageLayout[] state = new HizLayout.ImageLayout[layout.levels()];
        Arrays.fill(state, HizLayout.ImageLayout.UNDEFINED);
        boolean[] written = new boolean[layout.levels()];
        for (HizLayout.Command command : commands) {
            switch (command.kind()) {
                case BARRIER -> {
                    boolean[] touched = new boolean[layout.levels()];
                    for (HizLayout.ImageBarrier barrier : command.barriers()) {
                        assertTrue(barrier.levelCount() > 0);
                        for (int level = barrier.baseLevel(); level < barrier.baseLevel() + barrier.levelCount(); level++) {
                            assertFalse(touched[level], "level " + level + " in one barrier twice");
                            touched[level] = true;
                            assertEquals(state[level], barrier.oldLayout(), "old layout of level " + level);
                            state[level] = barrier.newLayout();
                        }
                    }
                }
                case BLIT -> {
                    int level = command.level();
                    assertEquals(HizLayout.ImageLayout.COLOR_ATTACHMENT, state[level]);
                    if (level > 0) {
                        assertEquals(HizLayout.ImageLayout.SHADER_READ_ONLY, state[level - 1]);
                        assertTrue(written[level - 1]);
                    }
                    assertFalse(written[level]);
                    written[level] = true;
                }
                case DISPATCH -> {
                    assertEquals(HizLayout.ImageLayout.SHADER_READ_ONLY, state[0]);
                    for (int level = HizLayout.COMPUTE_FIRST_LEVEL; level <= HizLayout.COMPUTE_LAST_LEVEL; level++) {
                        assertEquals(HizLayout.ImageLayout.GENERAL, state[level]);
                        assertFalse(written[level]);
                        written[level] = true;
                    }
                }
            }
        }
        for (int level = 0; level < layout.levels(); level++) {
            assertTrue(written[level], "level " + level + " never written");
            assertEquals(HizLayout.ImageLayout.SHADER_READ_ONLY, state[level], "final layout of level " + level);
        }
    }
}
