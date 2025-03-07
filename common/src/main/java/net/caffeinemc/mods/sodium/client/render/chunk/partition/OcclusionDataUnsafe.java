package net.caffeinemc.mods.sodium.client.render.chunk.partition;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.GraphDirectionSet;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.VisibilityEncoding;
import org.lwjgl.system.MemoryUtil;

public class OcclusionDataUnsafe {
    private static final long VISIBILITY_DATA_OFFSET = 0;
    private static final long INCOMING_DIRECTIONS_OFFSET = 8;

    private static final long ALIGNMENT = 8; // 8 byte (64 bit) alignment for reading longs
    private static final long STRIDE = 16;

    public static long allocateArray(long count) {
        var bytes = count * STRIDE;

        var ptr = MemoryUtil.nmemAlignedAlloc(ALIGNMENT, bytes);
        // the default value for all the fields is 0, so we can use a memset
        MemoryUtil.memSet(ptr, 0, bytes);

        return ptr;
    }

    public static void freeArray(long pointer) {
        MemoryUtil.nmemAlignedFree(pointer);
    }

    public static long indexArray(long pointer, int sectionIndex) {
        return pointer + (sectionIndex * STRIDE);
    }

    public static void clearEntry(long pOcclusionData) {
        MemoryUtil.memPutLong(pOcclusionData + VISIBILITY_DATA_OFFSET, VisibilityEncoding.NULL);
        MemoryUtil.memPutByte(pOcclusionData + INCOMING_DIRECTIONS_OFFSET, (byte) GraphDirectionSet.NONE);
    }

    public static long getVisibilityData(long pOcclusionData) {
        return MemoryUtil.memGetLong(pOcclusionData + VISIBILITY_DATA_OFFSET);
    }

    public static void setVisibilityData(long pOcclusionData, long visibilityData) {
        MemoryUtil.memPutLong(pOcclusionData + VISIBILITY_DATA_OFFSET, visibilityData);
    }

    // The incoming directions is stored in 1 byte, but due to alignment constraints,
    // we don't really save anything by doing this.

    public static int getIncomingDirections(long pOcclusionData) {
        return MemoryUtil.memGetByte(pOcclusionData + INCOMING_DIRECTIONS_OFFSET);
    }

    public static void setIncomingDirections(long pOcclusionData, int incomingDirections) {
        MemoryUtil.memPutByte(pOcclusionData + INCOMING_DIRECTIONS_OFFSET, (byte) incomingDirections);
    }
}
