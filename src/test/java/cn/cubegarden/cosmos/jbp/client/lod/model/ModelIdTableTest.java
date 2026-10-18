package cn.cubegarden.cosmos.jbp.client.lod.model;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class ModelIdTableTest {
    private static byte[] signature(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    @Test
    void airSignatureMapsToModelZeroAndNoArgConstructorUsesEmptySignature() {
        byte[] air = signature(0xA11);
        ModelIdTable table = new ModelIdTable(air);
        assertEquals(0, table.modelIdForBlockState(0));
        assertEquals(1, table.modelCount());
        assertEquals(0, table.register(5, air.clone()));
        assertEquals(0, table.modelIdForBlockState(5));
        assertEquals(1, table.register(6, signature(1)));
        assertEquals(1, table.modelCount() - 1);

        ModelIdTable defaults = new ModelIdTable();
        assertEquals(0, defaults.register(9, ModelSignature.empty()));
        assertEquals(1, defaults.register(10, signature(1)));
        assertEquals(0, defaults.register(0, signature(2)));
    }

    @Test
    void sameSignatureSharesAnIdAndIdsAreDenseFromOne() {
        ModelIdTable table = new ModelIdTable();
        assertEquals(1, table.register(10, signature(1)));
        assertEquals(2, table.register(11, signature(2)));
        assertEquals(1, table.register(12, signature(1)));
        assertEquals(3, table.register(13, signature(3)));
        assertEquals(4, table.modelCount());
        assertEquals(2, table.register(11, signature(99))); // already mapped state keeps its id
        assertEquals(-1, table.modelIdForBlockState(1));
        assertEquals(-1, table.modelIdForBlockState(1 << 19));
        assertEquals(-1, table.modelIdForBlockState(-3));
    }

    @Test
    void aliasMapsToExistingModelOnly() {
        ModelIdTable table = new ModelIdTable();
        table.register(10, signature(1));
        assertFalse(table.isMapped(20));
        assertEquals(0, table.alias(20, 0));
        assertTrue(table.isMapped(20));
        assertEquals(0, table.modelIdForBlockState(20));
        assertEquals(1, table.alias(21, 1));
        assertEquals(1, table.alias(21, 1));
        assertThrows(IllegalStateException.class, () -> table.alias(21, 0));
        assertThrows(IllegalArgumentException.class, () -> table.alias(22, 2));
        assertThrows(IllegalArgumentException.class, () -> table.alias(22, -1));
        assertTrue(table.isMapped(0));
        assertTrue(table.isMapped(10));
        assertFalse(table.isMapped(22));
        assertFalse(table.isMapped(1 << 19));
        assertEquals(2, table.modelCount());
    }

    @Test
    void overflowPastSixtyFiveThousandFiveHundredThirtyFiveThrowsAtomically() {
        ModelIdTable table = new ModelIdTable();
        for (int i = 1; i <= ModelAtlasLayout.MAX_MODEL_ID; i++) {
            assertEquals(i, table.register(i, signature(i)));
        }
        assertEquals(65_536, table.modelCount());
        int blockState = ModelAtlasLayout.MAX_MODEL_ID + 1;
        assertThrows(ModelAtlasFullException.class, () -> table.register(blockState, signature(-5)));
        assertFalse(table.isMapped(blockState));
        assertEquals(65_536, table.modelCount());
        assertInstanceOf(IllegalStateException.class, new ModelAtlasFullException());

        // known signatures still resolve, and overflowed states can be aliased to air
        assertEquals(7, table.register(blockState, signature(7)));
        assertEquals(0, table.alias(blockState + 1, 0));
        assertEquals(0, table.modelIdForBlockState(blockState + 1));
    }

    @Test
    void largeBlockStateIdsGrowTheTable() {
        ModelIdTable table = new ModelIdTable();
        int state = (1 << 20) - 1;
        assertEquals(1, table.register(state, signature(1)));
        assertEquals(1, table.modelIdForBlockState(state));
        assertEquals(-1, table.modelIdForBlockState(state - 1));
        assertThrows(IllegalArgumentException.class, () -> table.register(1 << 20, signature(2)));
        assertThrows(IllegalArgumentException.class, () -> table.register(-1, signature(2)));
    }
}
