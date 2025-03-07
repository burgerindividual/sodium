package net.caffeinemc.mods.sodium.client.render.chunk.region;

public class RegionSectionIndex {
    // YZX order
    private static final int X_BITS = RenderRegion.REGION_WIDTH_M, X_OFFSET = 0, X_MASK = X_BITS << X_OFFSET;
    private static final int Y_BITS = RenderRegion.REGION_HEIGHT_M, Y_OFFSET = 6, Y_MASK = Y_BITS << Y_OFFSET;
    private static final int Z_BITS = RenderRegion.REGION_LENGTH_M, Z_OFFSET = 3, Z_MASK = Z_BITS << Z_OFFSET;

    // MUST BE CHANGED IF THE PARTITION SIZE CHANGES
    public static int fromPartition(int partitionSectionIndex) {
        return partitionSectionIndex & 0b011_111_111;
    }

    public static int pack(int x, int y, int z) {
        return ((x & X_BITS) << X_OFFSET) | ((y & Y_BITS) << Y_OFFSET) | ((z & Z_BITS) << Z_OFFSET);
    }

    public static int unpackX(int idx) {
        return (idx >> X_OFFSET) & X_BITS;
    }

    public static int unpackY(int idx) {
        return (idx >> Y_OFFSET) & Y_BITS;
    }

    public static int unpackZ(int idx) {
        return (idx >> Z_OFFSET) & Z_BITS;
    }
}