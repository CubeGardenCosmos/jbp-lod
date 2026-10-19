package cn.cubegarden.cosmos.jbp.client.lod.importing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsing/validation of Minecraft Anvil region files ({@code r.X.Z.mca}).
 *
 * <p>Layout: 4096 bytes of chunk offsets, 4096 bytes of timestamps, then 4 KiB sectors. Each offset
 * entry is {@code sectorOffset << 8 | sectorCount} in big-endian; each chunk starts with a big-endian
 * int length (including the following one-byte compression id) and that compression id. All checks
 * that do not need the file contents live here so they can be unit-tested without Minecraft.
 */
public final class RegionFileLayout {
    public static final int HEADER_BYTES = 8192;
    public static final int SECTOR_BYTES = 4096;
    public static final int CHUNK_PREFIX_BYTES = 5;
    public static final int CHUNKS_PER_REGION = 1024;

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private RegionFileLayout() {
    }

    /** One chunk present in a region file. Coordinates are world chunk coordinates. */
    public record ChunkLocation(int chunkX, int chunkZ, int sectorOffset, int sectorCount) {
    }

    /** Region coordinates from an Anvil file name, or null when the name is not a region file. */
    public static int[] regionCoordinates(String fileName) {
        Matcher matcher = REGION_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    public static boolean isRegionFile(String fileName) {
        return regionCoordinates(fileName) != null;
    }

    /**
     * Parses the 8 KiB header into the chunks that declare a payload.
     *
     * @throws IllegalArgumentException when the header is shorter than {@link #HEADER_BYTES}
     */
    public static List<ChunkLocation> parseHeader(byte[] header, int regionX, int regionZ) {
        if (header.length < HEADER_BYTES) {
            throw new IllegalArgumentException("Region header shorter than " + HEADER_BYTES + " bytes: " + header.length);
        }
        List<ChunkLocation> chunks = new ArrayList<>();
        for (int index = 0; index < CHUNKS_PER_REGION; index++) {
            int entry = readIntBigEndian(header, index * Integer.BYTES);
            int sectorOffset = entry >>> 8;
            int sectorCount = entry & 0xFF;
            if (sectorOffset == 0 || sectorCount == 0) {
                continue;
            }
            int localX = index & 31;
            int localZ = index >>> 5;
            chunks.add(new ChunkLocation((regionX << 5) | localX, (regionZ << 5) | localZ,
                    sectorOffset, sectorCount));
        }
        return chunks;
    }

    /**
     * Validates and returns the compressed payload size ({@code declaredLength - 1}) for a chunk
     * header. Returns -1 when the entry cannot be read safely.
     *
     * @param declaredLength the big-endian int in front of the chunk data
     * @param sectorCount    sectors claimed by the header entry
     * @param fileSize       region file size in bytes
     * @param sectorOffset   first sector (in sectors) of the chunk
     * @param maxPayloadBytes hard allocation bound
     */
    public static int payloadSize(int declaredLength, int sectorCount, long fileSize, int sectorOffset,
                                  int maxPayloadBytes) {
        if (declaredLength <= 0 || sectorCount <= 0 || sectorOffset <= 0 || maxPayloadBytes <= 0) {
            return -1;
        }
        long payload = (long) declaredLength - 1;
        if (payload <= 0 || payload > maxPayloadBytes) {
            return -1;
        }
        // The declared stream must fit both its sector allocation and the file itself.
        if ((long) declaredLength > (long) sectorCount * SECTOR_BYTES - 4L) {
            return -1;
        }
        long start = (long) sectorOffset * SECTOR_BYTES + CHUNK_PREFIX_BYTES;
        if (start > fileSize || start + payload > fileSize) {
            return -1;
        }
        return (int) payload;
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }
}
