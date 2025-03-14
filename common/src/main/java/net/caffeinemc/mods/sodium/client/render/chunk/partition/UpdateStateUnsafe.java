package net.caffeinemc.mods.sodium.client.render.chunk.partition;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public class UpdateStateUnsafe {
    private static final long CHUNK_UPDATE_TYPE_OFFSET = 0;
    private static final long SECTION_UID_OFFSET = 4;
    private static final long LAST_UPLOAD_FRAME_OFFSET = 8;
    private static final long LAST_SUBMITTED_FRAME_OFFSET = 12;

    private static final long ALIGNMENT = 4;
    private static final long STRIDE = 16;

    public static long allocateArray(long count) {
        var bytes = count * STRIDE;

        var ptr = MemoryUtil.nmemAlignedAlloc(ALIGNMENT, bytes);
        // the default value for all the fields is -1, so we can use a memset
        MemoryUtil.memSet(ptr, -1, bytes);

        return ptr;
    }

    public static void freeArray(long pArray) {
        MemoryUtil.nmemAlignedFree(pArray);
    }

    public static long indexArray(long pArray, int sectionIndex) {
        return pArray + (sectionIndex * STRIDE);
    }

    public static void clearEntry(long pUpdateState) {
        MemoryUtil.memPutInt(pUpdateState + CHUNK_UPDATE_TYPE_OFFSET, -1);
        MemoryUtil.memPutInt(pUpdateState + SECTION_UID_OFFSET, -1);
        MemoryUtil.memPutInt(pUpdateState + LAST_UPLOAD_FRAME_OFFSET, -1);
        MemoryUtil.memPutInt(pUpdateState + LAST_SUBMITTED_FRAME_OFFSET, -1);
    }

    public static @Nullable ChunkUpdateType getPendingUpdate(long pUpdateState) {
        var ordinal = MemoryUtil.memGetInt(pUpdateState + CHUNK_UPDATE_TYPE_OFFSET);

        // Hotspot only consistently skips the array bounds check if we do this
        if (Integer.compareUnsigned(ordinal, ChunkUpdateType.VALUES.length) < 0) {
            return ChunkUpdateType.VALUES[ordinal];
        } else {
            return null;
        }
    }

    public static void setPendingUpdate(long pUpdateState, @Nullable ChunkUpdateType type) {
        var intValue = type == null ? -1 : type.ordinal();
        MemoryUtil.memPutInt(pUpdateState + CHUNK_UPDATE_TYPE_OFFSET, intValue);
    }

    public static int getSectionUid(long pUpdateState) {
        return MemoryUtil.memGetInt(pUpdateState + SECTION_UID_OFFSET);
    }

    public static void setSectionUid(long pUpdateState, int sectionUid) {
        MemoryUtil.memPutInt(pUpdateState + SECTION_UID_OFFSET, sectionUid);
    }

    public static int getLastUploadFrame(long pUpdateState) {
        return MemoryUtil.memGetInt(pUpdateState + LAST_UPLOAD_FRAME_OFFSET);
    }

    public static void setLastUploadFrame(long pUpdateState, int lastUploadFrame) {
        MemoryUtil.memPutInt(pUpdateState + LAST_UPLOAD_FRAME_OFFSET, lastUploadFrame);
    }

    public static int getLastSubmittedFrame(long pUpdateState) {
        return MemoryUtil.memGetInt(pUpdateState + LAST_SUBMITTED_FRAME_OFFSET);
    }

    public static void setLastSubmittedFrame(long pUpdateState, int lastSubmittedFrame) {
        MemoryUtil.memPutInt(pUpdateState + LAST_SUBMITTED_FRAME_OFFSET, lastSubmittedFrame);
    }
}
