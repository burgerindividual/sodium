package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.UniqueSectionRef;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.partition.UpdateStateUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RegionSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.ffi.NativeCull;
import net.caffeinemc.mods.sodium.ffi.NativeFrustum;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Queue;

public class NativeGraph implements Closeable {
    private static final int TILE_WIDTH = 8;
    private static final int TILE_HEIGHT = 8;
    private static final int TILE_LENGTH = 8;

    private final long nativePtr;
    private final RenderRegionManager regions;

    private final ObjectArrayList<ChunkRenderList> sortedRenderLists;
    private final EnumMap<ChunkUpdateType, ArrayDeque<UniqueSectionRef>> sortedRebuildLists;
    private int[] sortItems;

    public NativeGraph(
            RenderRegionManager regions,
            EnumMap<ChunkUpdateType, ArrayDeque<UniqueSectionRef>> sortedRebuildLists,
            byte renderDistance,
            byte minSectionY,
            byte maxSectionY
    ) {
        this.nativePtr = NativeCull.graphCreate(
                renderDistance,
                minSectionY,
                maxSectionY
        );
        this.regions = regions;

        this.sortedRenderLists = new ObjectArrayList<>();
        this.sortedRebuildLists = sortedRebuildLists;

        this.sortItems = new int[RenderRegion.REGION_SIZE];
    }

    private void clear() {
        this.sortedRenderLists.clear();
    }

    public void findVisible(
            NativeFrustum frustum,
            CameraTransform transform,
            float searchDistance,
            boolean useOcclusionCulling,
            int frame
    ) {
        this.clear();

        try (var stack = MemoryStack.stackPush()) {
            var resultsPtr = stack.ncalloc(8, 1, 16);
            var cameraPtr = NativeCull.frustumCreate(
                    stack,
                    frustum,
                    transform
            );

            NativeCull.graphSearch(
                    resultsPtr,
                    this.nativePtr,
                    cameraPtr,
                    searchDistance,
                    useOcclusionCulling
            );

            var tilesSlicePtr = MemoryUtil.memGetAddress(resultsPtr);
            var tileCount = MemoryUtil.memGetAddress(resultsPtr + Pointer.POINTER_SIZE);

            for (var tileIdx = 0L; tileIdx < tileCount; tileIdx++) {
                this.readTile(tilesSlicePtr + (tileIdx * 80), frame);
            }
        }
    }

    private void readTile(long tilePtr, int frame) {
        var tileSectionX = MemoryUtil.memGetInt(tilePtr);
        var tileSectionY = MemoryUtil.memGetInt(tilePtr + Integer.BYTES);
        var tileSectionZ = MemoryUtil.memGetInt(tilePtr + (Integer.BYTES * 2));
        var visibleSectionsPtr = tilePtr + 16;

        short currentY = (short) tileSectionY;
        short endY = (short) (tileSectionY + TILE_HEIGHT);
        byte processedYInTile = 0;

        while (currentY < endY) {
            short nextY = (short) Math.min((currentY + RenderRegion.REGION_HEIGHT) & ~RenderRegion.REGION_HEIGHT_M, endY);

            byte regionY = (byte) (currentY >> RenderRegion.REGION_HEIGHT_SH);
            byte minSectionYInRegion = (byte) (currentY & RenderRegion.REGION_HEIGHT_M);
            byte splitLength = (byte) (nextY - currentY);
            byte maxSectionYInRegion = (byte) (minSectionYInRegion + splitLength);

            currentY = nextY;

            int regionX = tileSectionX >> RenderRegion.REGION_WIDTH_SH;
            int regionZ = tileSectionZ >> RenderRegion.REGION_LENGTH_SH;
            var region = this.regions.get(regionX, regionY, regionZ);
            if (region == null) {
                return;
            }

            int regionSectionX = regionX << RenderRegion.REGION_WIDTH_SH;
            int regionSectionY = regionY << RenderRegion.REGION_HEIGHT_SH;
            int regionSectionZ = regionZ << RenderRegion.REGION_LENGTH_SH;
            var renderList = region.getRenderList();

//            var partition = region.getRenderList().getPartition();
            var partition = renderList.getPartition();
            if (partition.lastUpdatedFrame != frame) {
                partition.lastUpdatedFrame = frame;
                partition.resetCullingState();
            }
            var partitionRawVisibleSections = partition.visibleSections.getWords();
            var flagsArray = partition.flagsArray;
            var pUpdateStateArray = partition.pUpdateStateArray;
            var taskCancellationTokens = partition.taskCancellationTokens;
            byte regionYInPartition = (byte) (regionY & 1);

            byte sectionYInTile = processedYInTile;
            for (byte sectionYInRegion = minSectionYInRegion; sectionYInRegion < maxSectionYInRegion; sectionYInRegion++) {
                long bits = MemoryUtil.memGetLong(visibleSectionsPtr + ((long) sectionYInTile * Long.BYTES));

                byte sectionYInPartition = (byte) (sectionYInRegion + (regionYInPartition * RenderRegion.REGION_HEIGHT));
                partitionRawVisibleSections[sectionYInPartition] = bits;

//                this.readBits(
//                        bits,
//                        partition,
//                        sectionYInPartition,
//                        region,
//                        sectionYInRegion,
//                        frame
//                );
                while (bits != 0) {
                    byte bitIdx = (byte) Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;

                    // this can fit in an unsigned byte, but it's easier to keep it in a short
                    short regionSectionIndex = (short) ((sectionYInRegion * Long.SIZE) + bitIdx);
                    short partitionSectionIndex = (short) ((sectionYInPartition * Long.SIZE) + bitIdx);

                    var sectionFlags = flagsArray[partitionSectionIndex];

                    // only process section (and associated render list) if it has content that needs rendering
                    if (RenderSectionFlags.isBuilt(sectionFlags) && sectionFlags != RenderSectionFlags.EMPTY) {
                        if (renderList.getLastVisibleFrame() != frame) {
                            renderList.reset(frame);

                            this.sortedRenderLists.add(renderList);
                        }

                        renderList.add(regionSectionIndex, sectionFlags);
                    }

                    // always add to rebuild lists though, because it might just not be built yet
                    var pUpdateState = UpdateStateUnsafe.indexArray(pUpdateStateArray, partitionSectionIndex);
                    ChunkUpdateType type = UpdateStateUnsafe.getPendingUpdate(pUpdateState);

                    if (type != null && taskCancellationTokens[partitionSectionIndex] == null) {
                        Queue<UniqueSectionRef> queue = this.sortedRebuildLists.get(type);

                        if (queue.size() < type.getMaximumQueueSize()) {
                            var sectionPos = SectionPos.asLong(
                                    regionSectionX + RegionSectionIndex.unpackX(regionSectionIndex),
                                    regionSectionY + RegionSectionIndex.unpackY(regionSectionIndex),
                                    regionSectionZ + RegionSectionIndex.unpackZ(regionSectionIndex)
                            );
                            var sectionUid = UpdateStateUnsafe.getSectionUid(pUpdateState);

                            queue.add(new UniqueSectionRef(
                                    sectionPos,
                                    sectionUid,
                                    partition,
                                    partitionSectionIndex,
                                    region,
                                    regionSectionIndex
                            ));
                        }
                    }
                }

                sectionYInTile++;
            }

            processedYInTile += splitLength;
//            currentY = nextY;
        }
    }

//    private void readBits(
//            long bits,
//            WorldPartition partition,
//            int sectionYInPartition,
//            RenderRegion region,
//            int sectionYInRegion,
//            int frame
//    ) {
//        var renderList = region.getRenderList();
//
//        while (bits != 0) {
//            byte bitIdx = (byte) Long.numberOfTrailingZeros(bits);
//            bits &= bits - 1;
//
//            // this can fit in an unsigned byte, but it's easier to keep it in a short
//            short regionSectionIndex = (short) ((sectionYInRegion * Long.SIZE) + bitIdx);
//            short partitionSectionIndex = (short) ((sectionYInPartition * Long.SIZE) + bitIdx);
//
//            var sectionFlags = partition.flagsArray[partitionSectionIndex];
//
//            // only process section (and associated render list) if it has content that needs rendering
//            if (RenderSectionFlags.isBuilt(sectionFlags) && sectionFlags != RenderSectionFlags.EMPTY) {
//                if (renderList.getLastVisibleFrame() != frame) {
//                    renderList.reset(frame);
//
//                    this.sortedRenderLists.add(renderList);
//                }
//
//                renderList.add(regionSectionIndex, sectionFlags);
//            }
//
//            // always add to rebuild lists though, because it might just not be built yet
//            var pUpdateState = UpdateStateUnsafe.indexArray(partition.pUpdateStateArray, partitionSectionIndex);
//            ChunkUpdateType type = UpdateStateUnsafe.getPendingUpdate(pUpdateState);
//
//            if (type != null && partition.taskCancellationTokens[partitionSectionIndex] == null) {
//                this.tryAddToRebuildList(
//                        type,
//                        pUpdateState,
//                        partition,
//                        partitionSectionIndex,
//                        region,
//                        regionSectionIndex
//                );
////                Queue<UniqueSectionRef> queue = this.sortedRebuildLists.get(type);
////
////                if (queue.size() < type.getMaximumQueueSize()) {
////                    var sectionPos = SectionPos.asLong(
////                            region.getChunkX() + RegionSectionIndex.unpackX(regionSectionIndex),
////                            region.getChunkY() + RegionSectionIndex.unpackY(regionSectionIndex),
////                            region.getChunkZ() + RegionSectionIndex.unpackZ(regionSectionIndex)
////                    );
////                    var sectionUid = UpdateStateUnsafe.getSectionUid(pUpdateState);
////
////                    queue.add(new UniqueSectionRef(
////                            sectionPos,
////                            sectionUid,
////                            partition,
////                            partitionSectionIndex,
////                            region,
////                            regionSectionIndex
////                    ));
////                }
//            }
//        }
//    }

//    private void tryAddToRebuildList(
//            ChunkUpdateType type,
//            long pUpdateState,
//            WorldPartition partition,
//            short partitionSectionIndex,
//            RenderRegion region,
//            short regionSectionIndex
//    ) {
//        Queue<UniqueSectionRef> queue = this.sortedRebuildLists.get(type);
//
//        if (queue.size() < type.getMaximumQueueSize()) {
//            var sectionPos = SectionPos.asLong(
//                    region.getChunkX() + RegionSectionIndex.unpackX(regionSectionIndex),
//                    region.getChunkY() + RegionSectionIndex.unpackY(regionSectionIndex),
//                    region.getChunkZ() + RegionSectionIndex.unpackZ(regionSectionIndex)
//            );
//            var sectionUid = UpdateStateUnsafe.getSectionUid(pUpdateState);
//
//            queue.add(new UniqueSectionRef(
//                    sectionPos,
//                    sectionUid,
//                    partition,
//                    partitionSectionIndex,
//                    region,
//                    regionSectionIndex
//            ));
//        }
//    }

    public SortedRenderLists createRenderLists(Viewport viewport) {
        // sort the regions by distance to fix rare region ordering bugs
        var sectionPos = viewport.getChunkCoord();
        var cameraX = sectionPos.getX() >> RenderRegion.REGION_WIDTH_SH;
        var cameraY = sectionPos.getY() >> RenderRegion.REGION_HEIGHT_SH;
        var cameraZ = sectionPos.getZ() >> RenderRegion.REGION_LENGTH_SH;
        var size = this.sortedRenderLists.size();

        if (sortItems.length < size) {
            sortItems = new int[size];
        }

        for (var i = 0; i < size; i++) {
            var region = this.sortedRenderLists.get(i).getRegion();
            var x = Math.abs(region.getX() - cameraX);
            var y = Math.abs(region.getY() - cameraY);
            var z = Math.abs(region.getZ() - cameraZ);
            sortItems[i] = (x + y + z) << 16 | i;
        }

        IntArrays.unstableSort(sortItems, 0, size);

        var sorted = new ObjectArrayList<ChunkRenderList>(size);
        for (var i = 0; i < size; i++) {
            var key = sortItems[i];
            var renderList = this.sortedRenderLists.get(key & 0xFFFF);
            sorted.add(renderList);
        }

        // sort sections and invalidate batch caches if the render lists changed
        for (var list : sorted) {
            list.prepareForRender(sectionPos, sortItems);
        }

        return new SortedRenderLists(sorted);
    }

    public void setSection(int x, int y, int z, long visibilityData) {
        NativeCull.graphSetSection(
                this.nativePtr,
                x,
                y,
                z,
                visibilityData
        );
    }

    @Override
    public void close() {
        NativeCull.graphDelete(this.nativePtr);
    }
}
