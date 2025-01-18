package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.ffi.NativeCull;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.io.Closeable;

public class NativeGraph implements Closeable {
    private final long nativePtr;
    private final RenderRegionManager regions;

    public NativeGraph(RenderRegionManager regions, byte renderDistance, byte minSectionY, byte maxSectionY) {
        this.nativePtr = NativeCull.graphCreate(
                renderDistance,
                minSectionY,
                maxSectionY
        );
        this.regions = regions;
    }

    // TODO: inline the visit function to get rid of some indirection if this method is too slow
    public void findVisible(
            OcclusionCuller.Visitor visitor,
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
                var originRegionX = MemoryUtil.memGetInt(tileSlicePtr);
                var originRegionY = MemoryUtil.memGetInt(tileSlicePtr + Integer.BYTES);
                var originRegionZ = MemoryUtil.memGetInt(tileSlicePtr + (Integer.BYTES * 2));
                var visibleSectionsPtr = MemoryUtil.memGetAddress(tileSlicePtr + 16);

                var lowerRegion = regions.get(originRegionX, originRegionY, originRegionZ);
                var upperRegion = regions.get(originRegionX, originRegionY + 1, originRegionZ);

                if (lowerRegion == null && upperRegion == null) {
                    continue;
                }

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
//                        if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
//                            y = 7 - y;
//                        }

                        RenderSection section = null;

                        if (y < RenderRegion.REGION_HEIGHT) {
                            if (lowerRegion != null) {
                                var sectionIdx = LocalSectionIndex.pack(x, y, z);
                                section = lowerRegion.getSection(sectionIdx);
                            }
                        } else {
                            if (upperRegion != null) {
                                var sectionIdx = LocalSectionIndex.pack(x, y - 4, z);
                                section = upperRegion.getSection(sectionIdx);
                            }
                        }

                        if (section != null) {
                            section.setLastVisibleFrame(frame);
                            visitor.visit(section);
                        }
                    }
                }

                tileSlicePtr += 24;
            }
        }
    }

    public void setSection(int x, int y, int z, long traversableBlocksBuffer) {
        NativeCull.graphSetSection(
                this.nativePtr,
                x,
                y,
                z,
                traversableBlocksBuffer
        );
    }

    @Override
    public void close() {
        NativeCull.graphDelete(this.nativePtr);
    }
}
