package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.ffi.NativeCull;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Queue;

public class NativeGraph implements Closeable {
    private final long nativePtr;
    private final RenderRegionManager regions;

    private final ObjectArrayList<ChunkRenderList> sortedRenderLists;
    private final EnumMap<ChunkUpdateType, ArrayDeque<RenderSection>> sortedRebuildLists;
    private int[] sortItems;

    public NativeGraph(RenderRegionManager regions, byte renderDistance, byte minSectionY, byte maxSectionY) {
        this.nativePtr = NativeCull.graphCreate(
                renderDistance,
                minSectionY,
                maxSectionY
        );
        this.regions = regions;

        this.sortedRenderLists = new ObjectArrayList<>();
        this.sortedRebuildLists = new EnumMap<>(ChunkUpdateType.class);

        for (var type : ChunkUpdateType.values()) {
            this.sortedRebuildLists.put(type, new ArrayDeque<>());
        }

        this.sortItems = new int[RenderRegion.REGION_SIZE];
    }

    private void clear() {
        this.sortedRenderLists.clear();

        for (var rebuildList : this.sortedRebuildLists.values()) {
            rebuildList.clear();
        }
    }

    public void findVisible(
            Viewport viewport,
            float searchDistance,
            boolean useOcclusionCulling,
            int frame
    ) {
        this.clear();

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

        RenderRegion[] regionPair = {
                this.regions.get(originRegionX, originRegionY, originRegionZ),
                this.regions.get(originRegionX, originRegionY + 1, originRegionZ)
        };

        if (regionPair[0] == null && regionPair[1] == null) {
            return;
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

                var regionIdx = y >> RenderRegion.REGION_HEIGHT_SH;
                var region = regionPair[regionIdx];

                if (region != null) {
                    var sectionIdx = LocalSectionIndex.pack(x, y & RenderRegion.REGION_HEIGHT_M, z);
                    RenderSection section = region.getSection(sectionIdx);
                    if (section != null) {
                        this.visitSection(section, region.getRenderList(), frame);
                    }
                }
            }
        }
    }

    private void visitSection(RenderSection section, ChunkRenderList renderList, int frame) {
        section.setLastVisibleFrame(frame);

        // only process section (and associated render list) if it has content that needs rendering
        if (section.getFlags() != 0) {
            if (renderList.getLastVisibleFrame() != frame) {
                renderList.reset(frame);

                this.sortedRenderLists.add(renderList);
            }

            renderList.add(section);
        }

        // always add to rebuild lists though, because it might just not be built yet
        this.addToRebuildLists(section);
    }

    private void addToRebuildLists(RenderSection section) {
        ChunkUpdateType type = section.getPendingUpdate();

        if (type != null && section.getTaskCancellationToken() == null) {
            Queue<RenderSection> queue = this.sortedRebuildLists.get(type);

            if (queue.size() < type.getMaximumQueueSize()) {
                queue.add(section);
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

        if (this.sortItems.length < size) {
            this.sortItems = new int[size];
        }

        for (var i = 0; i < size; i++) {
            var region = this.sortedRenderLists.get(i).getRegion();
            var x = Math.abs(region.getX() - cameraX);
            var y = Math.abs(region.getY() - cameraY);
            var z = Math.abs(region.getZ() - cameraZ);
            this.sortItems[i] = (x + y + z) << 16 | i;
        }

        IntArrays.unstableSort(this.sortItems, 0, size);

        var sorted = new ObjectArrayList<ChunkRenderList>(size);
        for (var i = 0; i < size; i++) {
            var key = this.sortItems[i];
            var renderList = this.sortedRenderLists.get(key & 0xFFFF);
            sorted.add(renderList);
        }

        for (var list : sorted) {
            list.sortSections(sectionPos, this.sortItems);
        }

        return new SortedRenderLists(sorted);
    }

    public Map<ChunkUpdateType, ArrayDeque<RenderSection>> getRebuildLists() {
        return this.sortedRebuildLists;
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
