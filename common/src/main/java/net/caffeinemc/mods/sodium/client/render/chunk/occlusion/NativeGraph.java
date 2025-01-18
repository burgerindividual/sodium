package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.ffi.NativeCull;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.io.Closeable;
import java.util.concurrent.locks.ReentrantLock;

public class NativeGraph implements Closeable {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final long nativePtr;

    public NativeGraph(byte renderDistance, byte minSectionY, byte maxSectionY) {
        this.nativePtr = NativeCull.graphCreate(
                renderDistance,
                minSectionY,
                maxSectionY
        );
    }

    public void findVisible(
            OcclusionCuller.Visitor visitor,
            Long2ReferenceMap<RenderSection> sectionByPosition,
            Viewport viewport,
            float searchDistance,
            boolean useOcclusionCulling,
            int frame
    ) {
        try (var stack = MemoryStack.stackPush()) {
            var resultsPtr = stack.ncalloc(8, 16, 1);
            var cameraPtr = NativeCull.frustumCreate(
                    stack,
                    viewport.getFrustumIntersection(),
                    viewport.getTransform()
            );

            this.lock.lock();

            try {
                NativeCull.graphSearch(
                        resultsPtr,
                        this.nativePtr,
                        cameraPtr,
                        searchDistance,
                        useOcclusionCulling
                );

                var tileCount = MemoryUtil.memGetAddress(resultsPtr);
                var tileSlicePtr = MemoryUtil.memGetAddress(resultsPtr + Pointer.POINTER_SIZE);

                for (int tileIdx = 0; tileIdx < tileCount; tileIdx++) {
                    var originSectionX = MemoryUtil.memGetInt(tileSlicePtr);
                    var originSectionY = MemoryUtil.memGetInt(tileSlicePtr + Integer.BYTES);
                    var originSectionZ = MemoryUtil.memGetInt(tileSlicePtr + (Integer.BYTES * 2));
                    var visibleSectionsPtr = MemoryUtil.memGetAddress(tileSlicePtr + 16);

                    for (int z = 0; z < 8; z++) {
                        var bits = MemoryUtil.memGetLong(visibleSectionsPtr + (z * Long.BYTES));
                        while (bits != 0) {
                            var bitIdx = Long.numberOfTrailingZeros(bits);
                            bits &= bits - 1;

                            // bits are ordered with the bit pattern of "ZZZYYYXXX".
                            // we have to disassemble bitIdx to retrieve our x and y coordinate
                            // of the set bit.
                            var x = bitIdx & 0b111;
                            var y = (bitIdx >> 3) & 0b111;
//                            if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
//                                y = 7 - y;
//                            }
                            long key = SectionPos.asLong(originSectionX + x, originSectionY + y, originSectionZ + z);
                            var section = sectionByPosition.get(key);

                            if (section != null) {
                                section.setLastVisibleFrame(frame);
                                visitor.visit(section);
                            }
                        }
                    }

                    tileSlicePtr += 24;
                }
            } finally {
                this.lock.unlock();
            }
        }
    }

    public void setSection(int x, int y, int z, long traversableBlocksBuffer) {
        this.lock.lock();
        try {
            NativeCull.graphSetSection(
                    this.nativePtr,
                    x,
                    y,
                    z,
                    traversableBlocksBuffer
            );
        } finally {
            this.lock.unlock();
        }
    }

    public void removeSection(int x, int y, int z) {
        this.lock.lock();
        try {
            NativeCull.graphRemoveSection(
                    this.nativePtr,
                    x,
                    y,
                    z
            );
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void close() {
        this.lock.lock();
        try {
            NativeCull.graphDelete(this.nativePtr);
        } finally {
            this.lock.unlock();
        }
    }
}
