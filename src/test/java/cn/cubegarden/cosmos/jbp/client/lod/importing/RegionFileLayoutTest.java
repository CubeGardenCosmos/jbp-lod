package cn.cubegarden.cosmos.jbp.client.lod.importing;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFileLayoutTest {
    private static byte[] header(int... entries) {
        byte[] header = new byte[RegionFileLayout.HEADER_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        for (int index = 0; index < entries.length; index++) {
            buffer.putInt(index * Integer.BYTES, entries[index]);
        }
        return header;
    }

    @Test void namesSelectOnlyRegionFiles() {
        assertTrue(RegionFileLayout.isRegionFile("r.0.0.mca"));
        assertTrue(RegionFileLayout.isRegionFile("r.-1.2.mca"));
        assertFalse(RegionFileLayout.isRegionFile("r.0.0.mca_old"));
        assertFalse(RegionFileLayout.isRegionFile("r.0.0.mcc"));
        assertFalse(RegionFileLayout.isRegionFile("r.0.0.mca.tmp"));
        assertFalse(RegionFileLayout.isRegionFile("region"));
    }

    @Test void coordinatesKeepSign() {
        assertEquals(0, RegionFileLayout.regionCoordinates("r.0.0.mca")[0]);
        assertEquals(12, RegionFileLayout.regionCoordinates("r.12.-3.mca")[0]);
        assertEquals(-3, RegionFileLayout.regionCoordinates("r.12.-3.mca")[1]);
        assertNull(RegionFileLayout.regionCoordinates("r.999999999999.0.mca"));
    }

    @Test void headerYieldsWorldChunkCoordinatesAndSkipsEmptySlots() {
        int sector = (5 << 8) | 3;
        var chunks = RegionFileLayout.parseHeader(
                header(sector, 0, (7 << 8) | 1, (0 << 8) | 9), 4, -2);
        assertEquals(2, chunks.size());
        var first = chunks.get(0);
        assertEquals(4 << 5, first.chunkX());
        assertEquals((-2 << 5), first.chunkZ());
        assertEquals(5, first.sectorOffset());
        assertEquals(3, first.sectorCount());
        var second = chunks.get(1);
        assertEquals((4 << 5) + 2, second.chunkX());
        assertEquals((-2 << 5) + 0, second.chunkZ());
        assertEquals(7, second.sectorOffset());
        assertEquals(1, second.sectorCount());
    }

    @Test void shortHeaderIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RegionFileLayout.parseHeader(new byte[RegionFileLayout.HEADER_BYTES - 1], 0, 0));
    }

    @Test void payloadSizeRejectsUnsafeEntries() {
        long fileSize = 3L * RegionFileLayout.SECTOR_BYTES;
        int sectorOffset = 2;
        // payload = 6-1 = 5 bytes, fits one sector and the file
        assertEquals(5, RegionFileLayout.payloadSize(6, 1, fileSize, sectorOffset, 1024));
        // exceeds the sector allocation: declaredLength > sectorCount*4096 - 4
        assertEquals(-1, RegionFileLayout.payloadSize(4093, 1, fileSize, sectorOffset, 1 << 20));
        // extends past the file
        assertEquals(-1, RegionFileLayout.payloadSize(4090, 1, fileSize, 5, 1 << 20));
        // zero/negative declarations
        assertEquals(-1, RegionFileLayout.payloadSize(0, 1, fileSize, sectorOffset, 1024));
        assertEquals(-1, RegionFileLayout.payloadSize(1, 1, fileSize, sectorOffset, 1024));
        // configured allocation bound
        assertEquals(-1, RegionFileLayout.payloadSize(6, 1, fileSize, sectorOffset, 4));
        // no sectors / no offset
        assertEquals(-1, RegionFileLayout.payloadSize(6, 0, fileSize, sectorOffset, 1024));
        assertEquals(-1, RegionFileLayout.payloadSize(6, 1, fileSize, 0, 1024));
    }
}
