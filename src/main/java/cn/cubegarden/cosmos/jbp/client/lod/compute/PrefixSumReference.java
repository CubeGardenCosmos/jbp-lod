package cn.cubegarden.cosmos.jbp.client.lod.compute;

/** CPU oracle for the Voxy util/prefixsum shaders' four-counter exclusive scan. */
public final class PrefixSumReference {
    private PrefixSumReference() {
    }

    /**
     * Converts each input uvec4 count to its per-component exclusive prefix, exactly as the
     * GPU shader does: output is (offset, offset+x, offset+x+y, offset+x+y+z), then the
     * next item's offset advances by x+y+z+w. Java int overflow intentionally matches uint32.
     */
    public static int[][] exclusiveScan(int[][] counts) {
        int[][] result = new int[counts.length][4];
        int offset = 0;
        for (int i = 0; i < counts.length; i++) {
            int[] count = counts[i];
            if (count == null || count.length != 4) {
                throw new IllegalArgumentException("every prefix-sum value must contain four uint32 words");
            }
            result[i][0] = offset;
            result[i][1] = offset + count[0];
            result[i][2] = result[i][1] + count[1];
            result[i][3] = result[i][2] + count[2];
            offset = result[i][3] + count[3];
        }
        return result;
    }
}
