package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.UniqueSectionRef;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.partition.UpdateStateUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.partition.WorldPartitionManager;
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
    private final long nativePtr;
    private final RenderRegionManager regions;
    private final WorldPartitionManager partitions;

    private final ObjectArrayList<ChunkRenderList> sortedRenderLists;
    private final EnumMap<ChunkUpdateType, ArrayDeque<UniqueSectionRef>> sortedRebuildLists;
    private int[] sortItems;

    public NativeGraph(
            RenderRegionManager regions,
            WorldPartitionManager partitions,
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
        this.partitions = partitions;

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
                this.readTile(tilesSlicePtr + (tileIdx * 24), frame);
            }
        }
    }

    private void readTile(long tilePtr, int frame) {
        var originRegionX = MemoryUtil.memGetInt(tilePtr);
        var originRegionY = MemoryUtil.memGetInt(tilePtr + Integer.BYTES);
        var originRegionZ = MemoryUtil.memGetInt(tilePtr + (Integer.BYTES * 2));
        var visibleSectionsPtr = MemoryUtil.memGetAddress(tilePtr + 16);

        var partition = this.partitions.get(
                originRegionX,
                originRegionY >> 1,
                originRegionZ
        );
        if (partition == null) {
            return;
        }
        // If a tile is in the results queue, then we know that at least 1 section is visible in the tile. Because
        // tiles and partitions represent the same data at the moment, and because there shouldn't ever be duplicate
        // tile entries, we can go ahead and say that the partition is visible.
        partition.lastVisibleFrame = frame;
        partition.resetCullingState();
        var partitionRawVisibleSections = partition.visibleSections.getWords();

        for (int regionYInTile = 0; regionYInTile < 2; regionYInTile++) {
            var regionX = originRegionX;
            var regionY = originRegionY + regionYInTile;
            var regionZ = originRegionZ;
            var region = this.regions.get(regionX, regionY, regionZ);
            if (region == null) {
                continue;
            }

            var regionSectionX = regionX << RenderRegion.REGION_WIDTH_SH;
            var regionSectionY = regionY << RenderRegion.REGION_HEIGHT_SH;
            var regionSectionZ = regionZ << RenderRegion.REGION_LENGTH_SH;
            var renderList = region.getRenderList();

            for (int sectionYInRegion = 0; sectionYInRegion < 4; sectionYInRegion++) {
                var sectionYInPartition = sectionYInRegion + (regionYInTile * RenderRegion.REGION_HEIGHT);
                var bits = MemoryUtil.memGetLong(visibleSectionsPtr + (sectionYInPartition * Long.BYTES));
                partitionRawVisibleSections[sectionYInPartition] = bits;

                while (bits != 0) {
                    var bitIdx = Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;

                    var regionSectionIndex = (sectionYInRegion * Long.SIZE) + bitIdx;
                    var partitionSectionIndex = (sectionYInPartition * Long.SIZE) + bitIdx;

                    var sectionFlags = partition.flagsArray[partitionSectionIndex];

                    // only process section (and associated render list) if it has content that needs rendering
                    if (RenderSectionFlags.isBuilt(sectionFlags) && sectionFlags != RenderSectionFlags.EMPTY) {
                        if (renderList.getLastVisibleFrame() != frame) {
                            renderList.reset(frame);

                            this.sortedRenderLists.add(renderList);
                        }

                        renderList.add(regionSectionIndex, sectionFlags);
                    }

                    // always add to rebuild lists though, because it might just not be built yet
                    var pUpdateState = UpdateStateUnsafe.indexArray(partition.pUpdateStateArray, partitionSectionIndex);
                    ChunkUpdateType type = UpdateStateUnsafe.getPendingUpdate(pUpdateState);

                    if (type != null && partition.taskCancellationTokens[partitionSectionIndex] == null) {
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
            }
        }
    }

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
