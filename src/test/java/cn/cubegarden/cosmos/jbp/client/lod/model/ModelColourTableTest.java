package cn.cubegarden.cosmos.jbp.client.lod.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class ModelColourTableTest {
    @Test
    void constantAndMissingTintsAreOpaqueArgbOrMinusOne() {
        ModelColourTable table = new ModelColourTable();
        assertEquals(0xFF112233, table.registerModel(1, true, false, 0x00112233, null));
        assertEquals(0xFF112233, table.registerModel(1, true, false, 0x112233, null));
        assertEquals(-1, table.registerModel(2, false, false, 0x00ABCDEF, null));
        assertThrows(IllegalStateException.class, () -> table.registerModel(1, true, false, 0x998877, null));
        assertThrows(NullPointerException.class, () -> table.registerModel(3, true, true, 0, null));
        ModelColourTable.Snapshot snapshot = table.snapshot(4);
        assertEquals(0xFF112233, snapshot.colourTint(1));
        assertEquals(-1, snapshot.colourTint(2));
        assertEquals(-1, snapshot.colourTint(0));
        assertEquals(0, snapshot.colourWords().length);
        assertThrows(IllegalArgumentException.class, () -> table.snapshot(1));
    }

    @Test
    void rowsRebaseWhenBiomesAreAdded() {
        ModelColourTable table = new ModelColourTable();
        IntUnaryOperator resolver4 = biome -> 0x200 + biome;
        table.registerBiome(0);
        int first = table.registerModel(3, true, true, 0, biome -> 0x100 + biome);
        int second = table.registerModel(4, true, true, 0, resolver4);
        assertEquals(0, first);
        assertEquals(1, second);
        assertEquals(1, table.biomeCount());
        assertEquals(2, table.biomeRowCount());

        table.registerBiome(1);
        table.registerBiome(2);
        table.registerBiome(1); // idempotent
        assertEquals(3, table.biomeCount());

        ModelColourTable.Snapshot snapshot = table.snapshot(5);
        assertEquals(0, snapshot.colourTint(3));
        assertEquals(3, snapshot.colourTint(4));
        assertEquals(3, table.registerModel(4, true, true, 0, resolver4));
        assertArrayEquals(new int[]{
                0xFF000100, 0xFF000101, 0xFF000102,
                0xFF000200, 0xFF000201, 0xFF000202}, snapshot.colourWords());
        assertEquals(3, snapshot.biomeCount());
        assertEquals(24, snapshot.colourBytes().length);
    }

    @Test
    void missingBiomeIdsResolveToOpaqueWhiteWithoutShiftingLaterIndices() {
        ModelColourTable table = new ModelColourTable();
        table.registerBiome(2);
        table.registerBiome(4);
        table.registerModel(7, true, true, 0, biome -> {
            if (biome != 2 && biome != 4) {
                throw new AssertionError("resolver called for unregistered biome " + biome);
            }
            return biome == 2 ? 0x0000FF : 0x00FF00;
        });
        assertEquals(5, table.biomeCount());
        assertArrayEquals(new int[]{-1, -1, 0xFF0000FF, -1, 0xFF00FF00}, table.snapshot(8).colourWords());
    }

    @Test
    void registeredModelKeepsItsRowAndBaseFollowsBiomeCount() {
        ModelColourTable table = new ModelColourTable();
        table.registerBiome(0);
        table.registerBiome(1);
        assertEquals(0, table.registerModel(10, true, true, 0, b -> 1));
        assertEquals(2, table.registerModel(11, true, true, 0, b -> 2));
        table.registerBiome(3);
        assertEquals(4, table.biomeCount());
        ModelColourTable.Snapshot snapshot = table.snapshot(16);
        assertEquals(0, snapshot.colourTint(10));
        assertEquals(4, snapshot.colourTint(11));
        assertEquals(8, snapshot.colourWords().length);
        assertEquals(0xFFFFFFFF, snapshot.colourWords()[2]); // unregistered biome 2
        assertThrows(IllegalArgumentException.class, () -> table.registerBiome(-1));
        assertThrows(IllegalArgumentException.class, () -> table.registerBiome(0x10000));
    }

    @Test
    void rowAssignmentIsConstantTimePerRegistration() {
        ModelColourTable table = new ModelColourTable();
        table.registerBiome(0);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int model = 1; model <= 60_000; model++) {
                assertEquals(model - 1, table.registerModel(model, true, true, 0, b -> 0));
            }
        });
        assertEquals(60_000, table.biomeRowCount());
        assertEquals(60_000, table.snapshot(65_536).colourWords().length);
    }
}
