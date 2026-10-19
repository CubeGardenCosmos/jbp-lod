package cn.cubegarden.cosmos.jbp.client.lod.importing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionChunkReaderTest {
    @TempDir
    Path directory;

    private Path regionFile(int sectorOffset, int sectorCount, byte compression, byte[] payload)
            throws Exception {
        Path path = directory.resolve("r.0.0.mca");
        ByteBuffer file = ByteBuffer.allocate(RegionFileLayout.HEADER_BYTES + 2 * RegionFileLayout.SECTOR_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        file.putInt(0, (sectorOffset << 8) | sectorCount);
        file.position(sectorOffset * RegionFileLayout.SECTOR_BYTES);
        file.putInt(payload.length + 1);
        file.put(compression);
        file.put(payload);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(file.flip());
        }
        return path;
    }

    @Test void readsHeaderAndCompressedPayload() throws Exception {
        byte[] payload = {10, 20, 30, 40, 50};
        Path path = regionFile(2, 1, (byte) 3, payload);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            byte[] header = RegionChunkReader.readHeader(channel, channel.size());
            assertEquals(RegionFileLayout.HEADER_BYTES, header.length);
            var chunks = RegionFileLayout.parseHeader(header, 0, 0);
            assertEquals(1, chunks.size());
            var chunk = RegionChunkReader.readPayload(channel, channel.size(), chunks.get(0), 1024);
            assertEquals(3, chunk.compression());
            assertArrayEquals(payload, chunk.data());
        }
    }

    @Test void oversizedPayloadIsRejectedWithoutAllocating() throws Exception {
        byte[] payload = new byte[300];
        Path path = regionFile(2, 1, (byte) 2, payload);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            byte[] header = RegionChunkReader.readHeader(channel, channel.size());
            var chunk = RegionChunkReader.readPayload(channel, channel.size(),
                    RegionFileLayout.parseHeader(header, 0, 0).get(0), 16);
            assertNull(chunk);
        }
    }

    @Test void tinyFileHasNoHeader() throws Exception {
        Path path = directory.resolve("r.0.0.mca");
        Files.write(path, new byte[100]);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            assertNull(RegionChunkReader.readHeader(channel, channel.size()));
        }
    }
}
