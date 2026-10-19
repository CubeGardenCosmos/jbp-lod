package cn.cubegarden.cosmos.jbp.client.lod.importing;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** Reads one compressed chunk payload out of an Anvil region file without loading the whole file. */
public final class RegionChunkReader {
    private RegionChunkReader() {
    }

    /** Compressed chunk data and its {@code RegionFileVersion} id. */
    public record ChunkPayload(byte compression, byte[] data) {
    }

    /** Reads the 8 KiB header, or null when the file is too small to contain one. */
    public static byte[] readHeader(FileChannel channel, long fileSize) throws IOException {
        if (fileSize < RegionFileLayout.HEADER_BYTES) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(RegionFileLayout.HEADER_BYTES);
        readFully(channel, buffer, 0L);
        return buffer.array();
    }

    /**
     * Reads and validates one chunk's compressed stream.
     *
     * @return null when the entry is empty, points outside the file or exceeds {@code maxPayloadBytes}
     */
    public static ChunkPayload readPayload(FileChannel channel, long fileSize,
                                           RegionFileLayout.ChunkLocation location, int maxPayloadBytes)
            throws IOException {
        long start = (long) location.sectorOffset() * RegionFileLayout.SECTOR_BYTES;
        if (start + RegionFileLayout.CHUNK_PREFIX_BYTES > fileSize) {
            return null;
        }
        ByteBuffer prefix = ByteBuffer.allocate(RegionFileLayout.CHUNK_PREFIX_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        readFully(channel, prefix, start);
        int declaredLength = prefix.getInt(0);
        byte compression = prefix.get(4);
        int payloadSize = RegionFileLayout.payloadSize(declaredLength, location.sectorCount(), fileSize,
                location.sectorOffset(), maxPayloadBytes);
        if (payloadSize < 0) {
            return null;
        }
        ByteBuffer payload = ByteBuffer.allocate(payloadSize);
        readFully(channel, payload, start + RegionFileLayout.CHUNK_PREFIX_BYTES);
        return new ChunkPayload(compression, payload.array());
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        int read = 0;
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position + read);
            if (count < 0) {
                throw new EOFException("Unexpected end of region file at " + (position + read));
            }
            read += count;
        }
    }
}
