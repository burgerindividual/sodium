package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.SectionIteration;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.ffi.NativeCull;
import net.caffeinemc.mods.sodium.ffi.NativeFrustum;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Queue;

public class NativeGraph implements Closeable {
    private static final int TILE_WIDTH = 8;
    private static final int TILE_HEIGHT = 8;
    private static final int TILE_LENGTH = 8;

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
            NativeFrustum frustum,
            CameraTransform transform,
            float searchDistance,
            boolean useOcclusionCulling,
            int frame
    ) {
        this.clear();

        try (var stack = MemoryStack.stackPush()) {
            var resultsPtr = stack.ncalloc(NativeCull.FFISLICE_ALIGNMENT, 1, NativeCull.FFISLICE_SIZE);
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

            var tilesDataPtr = MemoryUtil.memGetAddress(resultsPtr + NativeCull.FFISLICE_DATA_PTR_OFFSET);
            var tileCount = MemoryUtil.memGetAddress(resultsPtr + NativeCull.FFISLICE_COUNT_OFFSET);

            for (var tileIdx = 0L; tileIdx < tileCount; tileIdx++) {
                this.readTile(tilesDataPtr + (tileIdx * NativeCull.FFITILE_SIZE), frame);
            }
        }
    }

    private void readTile(long tilePtr, int frame) {
        var tileSectionX = MemoryUtil.memGetInt(tilePtr + NativeCull.FFITILE_ORIGIN_SECTION_X_OFFSET);
        var tileSectionY = MemoryUtil.memGetInt(tilePtr + NativeCull.FFITILE_ORIGIN_SECTION_Y_OFFSET);
        var tileSectionZ = MemoryUtil.memGetInt(tilePtr + NativeCull.FFITILE_ORIGIN_SECTION_Z_OFFSET);
        var visibleSectionsPtr = tilePtr + NativeCull.FFITILE_VISIBLE_SECTIONS_OFFSET;

        // We assume that tile X and Z coordinates line up with region X and Z coordinates.
        int regionX = tileSectionX >> RenderRegion.REGION_WIDTH_SH;
        int regionZ = tileSectionZ >> RenderRegion.REGION_LENGTH_SH;

        // Iterate regions on Y axis inside current tile. Regions and Tiles aren't guaranteed to align on the Y axis.
        SectionIteration.iterateSplitsOnAxis(
                tileSectionY,
                tileSectionY + TILE_HEIGHT,
                RenderRegion.REGION_HEIGHT,
                (regionY, minSectionYInRegion, maxSectionYInRegion, nextYInTile) -> {
                    var region = this.regions.get(regionX, regionY, regionZ);
                    if (region == null) {
                        return;
                    }

                    var renderList = region.getRenderList();

                    // Iterate over section Y levels in the region, while also keeping track of the Y level in the tile
                    // to fetch the visible section data.
                    long sectionYInTile = nextYInTile;
                    for (int sectionYInRegion = minSectionYInRegion; sectionYInRegion < maxSectionYInRegion; sectionYInRegion++) {
                        // Get 64 bits of visible sections at a time, which represents a full slice on the X and Z axes.
                        long visibleSectionsSlice =
                                MemoryUtil.memGetLong(visibleSectionsPtr + (sectionYInTile * Long.BYTES));
                        sectionYInTile++;

                        // Each 1-bit represents a section that is visible. Use Lemire-style set-bit iteration approach,
                        // found here: https://lemire.me/blog/2018/02/21/iterating-over-set-bits-quickly/. This will
                        // quickly skip over 0-bits.
                        while (visibleSectionsSlice != 0) {
                            var bitIdx = Long.numberOfTrailingZeros(visibleSectionsSlice);
                            visibleSectionsSlice &= visibleSectionsSlice - 1;

                            // Bit indices are ordered as YZX in both Tiles and Regions. The ZX part of the index has an
                            // identical representation between Tile and Region indices, and the index of a bit in the
                            // 64-bit slice conveniently represents that exactly. We only need to adjust for the Y part
                            // of the index.
                            var sectionIndex = (sectionYInRegion * Long.SIZE) + bitIdx;
                            RenderSection section = region.getSection(sectionIndex);
                            if (section != null) {
                                this.visitSection(section, renderList, frame);
                            }
                        }
                    }
                }
        );
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
