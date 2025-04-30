package net.caffeinemc.mods.sodium.client.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.UniqueSectionRef;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderSortingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.NativeGraph;
import net.caffeinemc.mods.sodium.client.render.chunk.partition.*;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RegionSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior.DeferMode;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior.PriorityMode;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.DynamicData;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.DynamicTopoData;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.NoData;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.TranslucentData;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.trigger.CameraMovement;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.trigger.SortTriggering;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.util.RenderAsserts;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.MathUtil;
import net.caffeinemc.mods.sodium.client.util.SectionPosUtil;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.caffeinemc.mods.sodium.ffi.NativeCull;
import net.caffeinemc.mods.sodium.ffi.NativeFrustum;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class RenderSectionManager {
    private final ChunkBuilder builder;

    private final Long2ReferenceOpenHashMap<BlockEntity[]> globalBlockEntities = new Long2ReferenceOpenHashMap<>();

    private final WorldPartitionManager partitions;
    private final RenderRegionManager regions;
    private final ClonedChunkSectionCache sectionCache;

    private final ConcurrentLinkedDeque<ChunkJobResult<? extends BuilderTaskOutput>> buildResults = new ConcurrentLinkedDeque<>();

    private final ChunkRenderer chunkRenderer;

    private final ClientLevel level;

    private final int renderDistance;

    private final SortTriggering sortTriggering;

    private final NativeGraph nativeGraph;

    @NotNull
    private final EnumMap<ChunkUpdateType, ArrayDeque<UniqueSectionRef>> taskLists;

    @NotNull
    private SortedRenderLists renderLists;

    private ChunkJobCollector lastBlockingCollector;

    private int lastUpdatedFrame;
    private int nextSectionUid;

    private boolean needsGraphUpdate;

    private @Nullable BlockPos cameraBlockPos;
    private @Nullable Vector3dc cameraPosition;

    public RenderSectionManager(ClientLevel level, int renderDistance, CommandList commandList) {
        this.chunkRenderer = new DefaultChunkRenderer(RenderDevice.INSTANCE, ChunkMeshFormats.COMPACT);

        this.level = level;
        this.builder = new ChunkBuilder(level, ChunkMeshFormats.COMPACT);

        this.needsGraphUpdate = true;
        this.renderDistance = renderDistance;

        this.sortTriggering = new SortTriggering();

        this.partitions = new WorldPartitionManager();
        this.regions = new RenderRegionManager(commandList);

        this.taskLists = new EnumMap<>(ChunkUpdateType.class);

        for (var type : ChunkUpdateType.values()) {
            this.taskLists.put(type, new ArrayDeque<>());
        }

        this.renderLists = SortedRenderLists.empty();

        NativeGraph nativeGraph = null;
        if (NativeCull.SUPPORTED) {
            nativeGraph = new NativeGraph(
                    this.regions,
                    this.taskLists,
                    (byte) renderDistance,
                    (byte) level.getMinSectionY(),
                    (byte) level.getMaxSectionY()
            );
        }
        this.nativeGraph = nativeGraph;

        this.sectionCache = new ClonedChunkSectionCache(level);
    }

    public void updateCameraState(Vector3dc cameraPosition, Camera camera) {
        this.cameraBlockPos = camera.getBlockPosition();
        this.cameraPosition = cameraPosition;
    }

    public void update(Camera camera, Viewport viewport, FogParameters fogParameters, boolean spectator) {
        this.lastUpdatedFrame += 1;

        this.createTerrainRenderList(camera, viewport, fogParameters, this.lastUpdatedFrame, spectator);

        this.needsGraphUpdate = false;
    }

    private void createTerrainRenderList(Camera camera, Viewport viewport, FogParameters fogParameters, int frame, boolean spectator) {
        this.resetRenderLists();

        final var searchDistance = this.getSearchDistance(fogParameters);
        final var useOcclusionCulling = this.shouldUseOcclusionCulling(camera, spectator);

//        var player = Minecraft.getInstance().player;
        if (NativeCull.SUPPORTED
                && this.nativeGraph != null
                && viewport.getFrustum() instanceof NativeFrustum nativeFrustum
//                && player != null
//                && player.isHolding(Items.DEBUG_STICK)
//                && (frame & 1) != 0) {
        ) {
            // the rebuild lists will also be updated in this call
            this.nativeGraph.findVisible(nativeFrustum, viewport.getTransform(), searchDistance, useOcclusionCulling, frame);
            this.renderLists = this.nativeGraph.createRenderLists(viewport);
        } else {
            throw new UnsupportedOperationException("Java occlusion culling unimplemented");
        }
    }

    private float getSearchDistance(FogParameters fogParameters) {
        float distance;

        if (SodiumClientMod.options().performance.useFogOcclusion) {
            distance = this.getEffectiveRenderDistance(fogParameters);
        } else {
            distance = this.getRenderDistance();
        }

        return distance;
    }

    private boolean shouldUseOcclusionCulling(Camera camera, boolean spectator) {
        final boolean useOcclusionCulling;
        BlockPos origin = camera.getBlockPosition();

        if (spectator && this.level.getBlockState(origin)
                .isSolidRender())
        {
            useOcclusionCulling = false;
        } else {
            useOcclusionCulling = Minecraft.getInstance().smartCull;
        }
        return useOcclusionCulling;
    }

    private void resetRenderLists() {
        this.renderLists = SortedRenderLists.empty();

        for (var list : this.taskLists.values()) {
            list.clear();
        }
    }

    public void onSectionAdded(int x, int y, int z) {
        var partition = this.partitions.getOrCreateFromSection(x, y, z);
        var partitionSectionIndex = PartitionSectionIndex.pack(x, y, z);

//        var rsi = RegionSectionIndex.fromPartition(partitionSectionIndex);
//        if (rsi != RegionSectionIndex.pack(x, y, z)) {
//            throw new RuntimeException("RSI Validation Failed");
//        }
//
//        var psi2 = PartitionSectionIndex.fromRegion(
//                rsi,
//                PartitionSectionIndex.getRegionOffset(y >> RenderRegion.REGION_HEIGHT_SH)
//        );
//        if (psi2 != partitionSectionIndex) {
//            throw new RuntimeException("PSI Validation Failed");
//        }

        var sectionFlags = partition.flagsArray[partitionSectionIndex];

        if (sectionFlags != RenderSectionFlags.UNINITIALIZED) {
            return;
        }

        partition.createSection(partitionSectionIndex);

        var pUpdateState = UpdateStateUnsafe.indexArray(partition.pUpdateStateArray, partitionSectionIndex);
        UpdateStateUnsafe.setSectionUid(pUpdateState, this.nextSectionUid);
        this.nextSectionUid++;

        RenderRegion region = this.regions.createForChunk(x, y, z, partition);
        region.addSection();

        ChunkAccess chunk = this.level.getChunk(x, z);
        LevelChunkSection section = chunk.getSections()[this.level.getSectionIndexFromSectionY(y)];

        if (section.hasOnlyAir()) {
            var sectionPos = SectionPos.asLong(x, y, z);
            this.updateSectionInfo(sectionPos, partition, partitionSectionIndex, BuiltSectionInfo.EMPTY);
        } else {
            UpdateStateUnsafe.setPendingUpdate(pUpdateState, ChunkUpdateType.INITIAL_BUILD);
        }

        // force update to schedule build task
        this.needsGraphUpdate = true;
    }

    public void onSectionRemoved(int x, int y, int z) {
        var partition = this.partitions.getFromSection(x, y, z);
        if (partition == null) {
            return;
        }

        var partitionSectionIndex = PartitionSectionIndex.pack(x, y, z);

        var sectionFlags = partition.flagsArray[partitionSectionIndex];
        if (sectionFlags == RenderSectionFlags.UNINITIALIZED) {
            return;
        }

        var sectionPos = SectionPos.asLong(x, y, z);

        var translucentData = partition.translucentDataArray[partitionSectionIndex];
        if (translucentData != null) {
            this.sortTriggering.removeSection(translucentData, sectionPos);
        }

        RenderRegion region = this.regions.get(x, y, z);
        if (region != null) {
            var regionSectionIndex = RegionSectionIndex.fromPartition(partitionSectionIndex);
            region.removeSection(regionSectionIndex);
        }

        this.globalBlockEntities.remove(sectionPos);
        partition.deleteSection(partitionSectionIndex);

        // force update to remove section from render lists
        this.needsGraphUpdate = true;
    }

    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z) {
        RenderDevice device = RenderDevice.INSTANCE;
        CommandList commandList = device.createCommandList();

        this.chunkRenderer.render(matrices, commandList, this.renderLists, pass, new CameraTransform(x, y, z));

        commandList.flush();
    }

    public void tickVisibleRenders() {
        Iterator<ChunkRenderList> it = this.renderLists.iterator();

        while (it.hasNext()) {
            ChunkRenderList renderList = it.next();

            var iterator = renderList.sectionsWithSpritesIterator();
            if (iterator == null) {
                continue;
            }

            var partition = renderList.getPartition();
            var partitionRegionOffset = renderList.getPartitionRegionOffset();

            while (iterator.hasNext()) {
                var regionSectionIndex = iterator.nextByteAsInt();
                var partitionSectionIndex = PartitionSectionIndex.fromRegion(regionSectionIndex, partitionRegionOffset);

                // checking if the section is uninitialized should be unnecessary, because when a section is
                // uninitialized, its animated sprites array should be null.

                var sprites = partition.animatedSpritesArray[partitionSectionIndex];
                if (sprites == null) {
                    continue;
                }

                for (TextureAtlasSprite sprite : sprites) {
                    SpriteUtil.INSTANCE.markSpriteActive(sprite);
                }
            }
        }
    }

    public boolean isSectionVisible(int x, int y, int z) {
        // TODO: speed this up by hoisting out the partition lookup.
        //  Perhaps make a SectionIterator class that allows partitions to be iterated from a bounding box of sections.

        var partition = this.partitions.getFromSection(x, y, z);
        if (partition == null || partition.lastUpdatedFrame != this.lastUpdatedFrame) {
            return false;
        }

        var partitionSectionIndex = PartitionSectionIndex.pack(x, y, z);
        return partition.visibleSections.get(partitionSectionIndex);
    }

    public void uploadChunks() {
        var results = this.collectChunkBuildResults();

        if (results.isEmpty()) {
            return;
        }

        // only mark as needing a graph update if the uploads could have changed the graph
        // (sort results never change the graph)
        // generally there's no sort results without a camera movement, which would also trigger
        // a graph update, but it can sometimes happen because of async task execution
        this.needsGraphUpdate |= this.processChunkBuildResults(results);

        for (var result : results) {
            result.destroy();
        }
    }

    private boolean processChunkBuildResults(ArrayList<BuilderTaskOutput> results) {
        var filtered = filterChunkBuildResults(results);

        this.regions.uploadResults(RenderDevice.INSTANCE.createCommandList(), filtered);

        boolean touchedSectionInfo = false;
        for (var result : filtered) {
            var section = result.section;
            var sectionPos = section.pos();
            var partition = section.partition();
            var partitionSectionIndex = section.partitionSectionIndex();

            TranslucentData oldData = partition.translucentDataArray[partitionSectionIndex];
            if (result instanceof ChunkBuildOutput chunkBuildOutput) {
                touchedSectionInfo |= this.updateSectionInfo(sectionPos, partition, partitionSectionIndex, chunkBuildOutput.info);

                if (chunkBuildOutput.translucentData != null) {
                    this.sortTriggering.integrateTranslucentData(
                            oldData,
                            chunkBuildOutput.translucentData,
                            this.cameraPosition,
                            this::scheduleSort
                    );

                    // a rebuild always generates new translucent data which means applyTriggerChanges isn't necessary
                    partition.translucentDataArray[partitionSectionIndex] = chunkBuildOutput.translucentData;
                }
            } else if (result instanceof ChunkSortOutput sortOutput
                    && sortOutput.getDynamicSorter() != null
                    && partition.translucentDataArray[partitionSectionIndex] instanceof DynamicTopoData data) {
                this.sortTriggering.applyTriggerChanges(
                        data,
                        sortOutput.getDynamicSorter(),
                        SectionPos.of(sectionPos),
                        this.cameraPosition
                );
            }

            var pUpdateState = UpdateStateUnsafe.indexArray(partition.pUpdateStateArray, partitionSectionIndex);
            var job = partition.taskCancellationTokens[partitionSectionIndex];

            // clear the cancellation token (thereby marking the section as not having an
            // active task) if this job is the most recent submitted job for this section
            if (job != null && result.submitTime >= UpdateStateUnsafe.getLastSubmittedFrame(pUpdateState)) {
                partition.taskCancellationTokens[partitionSectionIndex] = null;
            }

            UpdateStateUnsafe.setLastUploadFrame(pUpdateState, result.submitTime);
        }

        return touchedSectionInfo;
    }

    private boolean updateSectionInfo(
            long sectionPos,
            WorldPartition partition,
            int partitionSectionIndex,
            @NotNull BuiltSectionInfo info
    ) {
        var pOcclusionData = OcclusionDataUnsafe.indexArray(partition.pOcclusionDataArray, partitionSectionIndex);
        var oldVisibilityData = OcclusionDataUnsafe.getVisibilityData(pOcclusionData);
        var renderStateChanged = partition.setRenderState(partitionSectionIndex, info);
        var newVisibilityData = OcclusionDataUnsafe.getVisibilityData(pOcclusionData);

        if (NativeCull.SUPPORTED && this.nativeGraph != null && oldVisibilityData != newVisibilityData) {
            this.nativeGraph.setSection(
                    SectionPos.x(sectionPos),
                    SectionPos.y(sectionPos),
                    SectionPos.z(sectionPos),
                    newVisibilityData
            );
        }

        if (ArrayUtils.isEmpty(info.globalBlockEntities)) {
            var prevGlobalBlockEntities = this.globalBlockEntities.remove(sectionPos);
            return (prevGlobalBlockEntities != null) || renderStateChanged;
        } else {
            var prevGlobalBlockEntities = this.globalBlockEntities.put(sectionPos, info.globalBlockEntities);
            return (prevGlobalBlockEntities == null) || renderStateChanged;
        }
    }

    private List<BuilderTaskOutput> filterChunkBuildResults(ArrayList<BuilderTaskOutput> outputs) {
        var map = new Long2ReferenceLinkedOpenHashMap<BuilderTaskOutput>();

        for (var output : outputs) {
            var section = output.section;
            var partition = section.partition();

            var pUpdateState = UpdateStateUnsafe.indexArray(
                    partition.pUpdateStateArray,
                    section.partitionSectionIndex()
            );
            var lastSubmittedFrame = UpdateStateUnsafe.getLastSubmittedFrame(pUpdateState);
            var lastUploadFrame = UpdateStateUnsafe.getLastUploadFrame(pUpdateState);

            // throw out outdated or duplicate outputs
            if (lastSubmittedFrame != output.submitTime || lastUploadFrame > output.submitTime) {
                continue;
            }

            var sectionPos = section.pos();
            var previous = map.get(sectionPos);
            if (previous == null || previous.submitTime < output.submitTime) {
                map.put(sectionPos, output);
            }
        }

        return new ArrayList<>(map.values());
    }

    private ArrayList<BuilderTaskOutput> collectChunkBuildResults() {
        ArrayList<BuilderTaskOutput> results = new ArrayList<>();
        ChunkJobResult<? extends BuilderTaskOutput> result;

        while ((result = this.buildResults.poll()) != null) {
            results.add(result.unwrap());
        }

        return results;
    }

    public void cleanupAndFlip() {
        this.sectionCache.cleanup();
        this.partitions.cleanup();
        this.regions.update();
    }

    public void updateChunks(boolean updateImmediately) {
        var thisFrameBlockingCollector = this.lastBlockingCollector;
        this.lastBlockingCollector = null;
        if (thisFrameBlockingCollector == null) {
            thisFrameBlockingCollector = new ChunkJobCollector(this.buildResults::add);
        }

        if (updateImmediately) {
            // for a perfect frame where everything is finished use the last frame's blocking collector
            // and add all tasks to it so that they're waited on
            this.submitSectionTasks(thisFrameBlockingCollector, thisFrameBlockingCollector, thisFrameBlockingCollector);

            thisFrameBlockingCollector.awaitCompletion(this.builder);
        } else {
            var nextFrameBlockingCollector = new ChunkJobCollector(this.buildResults::add);
            var deferredCollector = new ChunkJobCollector(
                this.builder.getHighEffortSchedulingBudget(),
                this.builder.getLowEffortSchedulingBudget(),
                this.buildResults::add);

            // if zero frame delay is allowed, submit important sorts with the current frame blocking collector.
            // otherwise submit with the collector that the next frame is blocking on.
            if (SodiumClientMod.options().debug.getSortBehavior().getDeferMode() == DeferMode.ZERO_FRAMES) {
                this.submitSectionTasks(thisFrameBlockingCollector, nextFrameBlockingCollector, deferredCollector);
            } else {
                this.submitSectionTasks(nextFrameBlockingCollector, nextFrameBlockingCollector, deferredCollector);
            }

            // wait on this frame's blocking collector which contains the important tasks from this frame
            // and semi-important tasks from the last frame
            thisFrameBlockingCollector.awaitCompletion(this.builder);

            // store the semi-important collector to wait on it in the next frame
            this.lastBlockingCollector = nextFrameBlockingCollector;
        }
    }

    private void submitSectionTasks(
        ChunkJobCollector importantCollector,
        ChunkJobCollector semiImportantCollector,
        ChunkJobCollector deferredCollector) {
            this.submitSectionTasks(importantCollector, ChunkUpdateType.IMPORTANT_SORT, true);
            this.submitSectionTasks(semiImportantCollector, ChunkUpdateType.IMPORTANT_REBUILD, true);

            // since the sort tasks are run last, the effort category can be ignored and
            // simply fills up the remaining budget. Splitting effort categories is still
            // important to prevent high effort tasks from using up the entire budget if it
            // happens to divide evenly.
            this.submitSectionTasks(deferredCollector, ChunkUpdateType.REBUILD, false);
            this.submitSectionTasks(deferredCollector, ChunkUpdateType.INITIAL_BUILD, false);
            this.submitSectionTasks(deferredCollector, ChunkUpdateType.SORT, true);
    }

    private void submitSectionTasks(ChunkJobCollector collector, ChunkUpdateType type, boolean ignoreEffortCategory) {
        var queue = this.taskLists.get(type);

        while (!queue.isEmpty() && collector.hasBudgetFor(type.getTaskEffort(), ignoreEffortCategory)) {
            var section = queue.pop();

            var partition = section.partition();
            var partitionSectionIndex = section.partitionSectionIndex();

            var pUpdateState = UpdateStateUnsafe.indexArray(partition.pUpdateStateArray, partitionSectionIndex);
            var currentSectionUid = UpdateStateUnsafe.getSectionUid(pUpdateState);

            // skip if section was disposed or replaced
            if (section.uid() != currentSectionUid) {
                continue;
            }

            // stop if the section is in this list but doesn't have this update type
            var pendingUpdate = UpdateStateUnsafe.getPendingUpdate(pUpdateState);
            if (pendingUpdate != null && pendingUpdate != type) {
                continue;
            }

            var translucentData = partition.translucentDataArray[partitionSectionIndex];
            int frame = this.lastUpdatedFrame;
            ChunkBuilderTask<? extends BuilderTaskOutput> task;
            if (type == ChunkUpdateType.SORT || type == ChunkUpdateType.IMPORTANT_SORT) {
                task = this.createSortTask(section, translucentData, frame);

                if (task == null) {
                    // when a sort task is null it means the render section has no dynamic data and
                    // doesn't need to be sorted. Nothing needs to be done.
                    continue;
                }
            } else {
                task = this.createRebuildTask(section, translucentData, frame);

                if (task == null) {
                    // if the section is empty or doesn't exist submit this null-task to set the
                    // built flag on the render section.
                    // It's important to use a NoData instead of null translucency data here in
                    // order for it to clear the old data from the translucency sorting system.
                    // This doesn't apply to sorting tasks as that would result in the section being
                    // marked as empty just because it was scheduled to be sorted and its dynamic
                    // data has since been removed. In that case simply nothing is done as the
                    // rebuild that must have happened in the meantime includes new non-dynamic
                    // index data.
                    var result = ChunkJobResult.successfully(new ChunkBuildOutput(
                            section,
                            frame,
                            NoData.forEmptySection(SectionPos.of(section.pos())),
                            BuiltSectionInfo.EMPTY,
                            Collections.emptyMap()
                    ));
                    this.buildResults.add(result);

                    partition.taskCancellationTokens[partitionSectionIndex] = null;
                }
            }

            if (task != null) {
                var job = this.builder.scheduleTask(task, type.isImportant(), collector::onJobFinished);
                collector.addSubmittedJob(job);

                partition.taskCancellationTokens[partitionSectionIndex] = job;
            }

            UpdateStateUnsafe.setLastSubmittedFrame(pUpdateState, frame);
            UpdateStateUnsafe.setPendingUpdate(pUpdateState, null);
        }
    }

    public @Nullable ChunkBuilderMeshingTask createRebuildTask(UniqueSectionRef section, TranslucentData translucentData, int frame) {
        ChunkRenderContext context = LevelSlice.prepare(
                this.level,
                SectionPos.of(section.pos()),
                this.sectionCache
        );

        if (context == null) {
            return null;
        }

        return new ChunkBuilderMeshingTask(
                section,
                translucentData,
                frame,
                this.cameraPosition,
                context
        );
    }

    public @Nullable ChunkBuilderSortingTask createSortTask(UniqueSectionRef section, TranslucentData translucentData, int frame) {
        if (translucentData instanceof DynamicData dynamicData) {
            return new ChunkBuilderSortingTask(section, frame, this.cameraPosition, dynamicData.getSorter());
        }
        return null;
    }

    public void processGFNIMovement(CameraMovement movement) {
        this.sortTriggering.triggerSections(this::scheduleSort, movement);
    }

    public void markGraphDirty() {
        this.needsGraphUpdate = true;
    }

    public boolean needsUpdate() {
        return true;
    }

    public ChunkBuilder getBuilder() {
        return this.builder;
    }

    public void destroy() {
        this.builder.shutdown(); // stop all the workers, and cancel any tasks

        for (var result : this.collectChunkBuildResults()) {
            result.destroy(); // delete resources for any pending tasks (including those that were cancelled)
        }

        this.partitions.delete();

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.regions.delete(commandList);
            this.chunkRenderer.delete(commandList);
        }

        if (NativeCull.SUPPORTED && this.nativeGraph != null) {
            this.nativeGraph.close();
        }
    }

    public int getTotalSections() {
        return this.partitions.getTotalSections();
    }

    public int getVisibleChunkCount() {
        var sections = 0;
        var iterator = this.renderLists.iterator();

        while (iterator.hasNext()) {
            var renderList = iterator.next();
            sections += renderList.getSectionsWithGeometryCount();
        }

        return sections;
    }

    public void scheduleSort(long sectionPos, boolean isDirectTrigger) {
        int x = SectionPos.x(sectionPos);
        int y = SectionPos.y(sectionPos);
        int z = SectionPos.z(sectionPos);

        var partition = this.partitions.getFromSection(x, y, z);
        if (partition == null) {
            return;
        }

        var partitionSectionIndex = PartitionSectionIndex.pack(x, y, z);

        var sectionFlags = partition.flagsArray[partitionSectionIndex];

        if (sectionFlags == RenderSectionFlags.UNINITIALIZED) {
            // This really shouldn't ever hit? I'm not sure if this is necessary
            throw new IllegalStateException("section must be initialized to schedule sort");
//            return;
        }

        var pUpdateState = UpdateStateUnsafe.indexArray(partition.pUpdateStateArray, partitionSectionIndex);
        var currentUpdate = UpdateStateUnsafe.getPendingUpdate(pUpdateState);

        var pendingUpdate = ChunkUpdateType.SORT;

        var priorityMode = SodiumClientMod.options().debug.getSortBehavior().getPriorityMode();
        if ((priorityMode == PriorityMode.ALL)
                || ((priorityMode == PriorityMode.NEARBY) && this.shouldPrioritizeTask(x, y, z, NEARBY_SORT_DISTANCE))) {
            pendingUpdate = ChunkUpdateType.IMPORTANT_SORT;
        }

        pendingUpdate = ChunkUpdateType.getPromotionUpdateType(currentUpdate, pendingUpdate);
        if (pendingUpdate != null) {
            UpdateStateUnsafe.setPendingUpdate(pUpdateState, pendingUpdate);

            var translucentData = partition.translucentDataArray[partitionSectionIndex];
            if (translucentData != null) {
                translucentData.prepareTrigger(isDirectTrigger);
            }
        }
    }

    public void scheduleRebuild(int x, int y, int z, boolean important) {
        RenderAsserts.validateCurrentThread();

        this.sectionCache.invalidate(x, y, z);

        var partition = this.partitions.getFromSection(x, y, z);
        if (partition == null) {
            return;
        }

        var partitionSectionIndex = PartitionSectionIndex.pack(x, y, z);

        var sectionFlags = partition.flagsArray[partitionSectionIndex];

        if (!RenderSectionFlags.isBuilt(sectionFlags)) {
            return;
        }

        var pUpdateState = UpdateStateUnsafe.indexArray(partition.pUpdateStateArray, partitionSectionIndex);
        var currentUpdate = UpdateStateUnsafe.getPendingUpdate(pUpdateState);

        ChunkUpdateType pendingUpdate;

        if (allowImportantRebuilds() && (important || this.shouldPrioritizeTask(x, y, z, NEARBY_REBUILD_DISTANCE))) {
            pendingUpdate = ChunkUpdateType.IMPORTANT_REBUILD;
        } else {
            pendingUpdate = ChunkUpdateType.REBUILD;
        }

        pendingUpdate = ChunkUpdateType.getPromotionUpdateType(currentUpdate, pendingUpdate);
        if (pendingUpdate != null) {
            UpdateStateUnsafe.setPendingUpdate(pUpdateState, pendingUpdate);

            // force update to schedule rebuild task on this section
            this.needsGraphUpdate = true;
        }
    }

    private static final float NEARBY_REBUILD_DISTANCE = Mth.square(16.0f);
    private static final float NEARBY_SORT_DISTANCE = Mth.square(25.0f);

    private boolean shouldPrioritizeTask(int x, int y, int z, float distance) {
        return this.cameraBlockPos != null
                && SectionPosUtil.getSquaredDistance(x, y, z, this.cameraBlockPos) < distance;
    }

    private static boolean allowImportantRebuilds() {
        return !SodiumClientMod.options().performance.alwaysDeferChunkUpdates;
    }

    private float getEffectiveRenderDistance(FogParameters fogParameters) {
        var alpha = fogParameters.alpha();
        var distance = fogParameters.end();

        var renderDistance = this.getRenderDistance();

        // The fog must be fully opaque in order to skip rendering of chunks behind it
        if (!Mth.equal(alpha, 1.0f)) {
            return renderDistance;
        }

        return Math.min(renderDistance, distance + 0.5f);
    }

    private float getRenderDistance() {
        return this.renderDistance * 16.0f;
    }

    public Collection<String> getDebugStrings() {
        List<String> list = new ArrayList<>();

        int count = 0;

        long geometryDeviceUsed = 0;
        long geometryDeviceAllocated = 0;
        long indexDeviceUsed = 0;
        long indexDeviceAllocated = 0;

        for (var region : this.regions.getLoadedRegions()) {
            var resources = region.getResources();

            if (resources == null) {
                continue;
            }

            var geometryArena = resources.getGeometryArena();
            geometryDeviceUsed += geometryArena.getDeviceUsedMemory();
            geometryDeviceAllocated += geometryArena.getDeviceAllocatedMemory();

            var indexArena = resources.getIndexArena();
            indexDeviceUsed += indexArena.getDeviceUsedMemory();
            indexDeviceAllocated += indexArena.getDeviceAllocatedMemory();

            count++;
        }

        list.add(String.format("Pools: Geometry %d/%d MiB, Index %d/%d MiB (%d buffers)",
                MathUtil.toMib(geometryDeviceUsed), MathUtil.toMib(geometryDeviceAllocated),
                MathUtil.toMib(indexDeviceUsed), MathUtil.toMib(indexDeviceAllocated), count));
        list.add(String.format("Transfer Queue: %s", this.regions.getStagingBuffer().toString()));

        list.add(String.format("Chunk Builder: Permits=%02d (E %03d) | Busy=%02d | Total=%02d",
                this.builder.getScheduledJobCount(), this.builder.getScheduledEffort(), this.builder.getBusyThreadCount(), this.builder.getTotalThreadCount())
        );

        list.add(String.format("Chunk Queues: U=%02d (P0=%03d | P1=%03d | P2=%03d)",
                this.buildResults.size(),
                this.taskLists.get(ChunkUpdateType.IMPORTANT_REBUILD).size() + this.taskLists.get(ChunkUpdateType.IMPORTANT_SORT).size(),
                this.taskLists.get(ChunkUpdateType.REBUILD).size() + this.taskLists.get(ChunkUpdateType.SORT).size(),
                this.taskLists.get(ChunkUpdateType.INITIAL_BUILD).size())
        );

        this.sortTriggering.addDebugStrings(list);

        return list;
    }

    public @NotNull SortedRenderLists getRenderLists() {
        return this.renderLists;
    }

    public boolean isSectionBuilt(int x, int y, int z) {
        var partition = this.partitions.getFromSection(x, y, z);

        if (partition == null) {
            return false;
        }

        var partitionSectionIndex = PartitionSectionIndex.pack(x, y, z);
        return RenderSectionFlags.isBuilt(partition.flagsArray[partitionSectionIndex]);
    }

    public void onChunkAdded(int x, int z) {
        // TODO: hoist some of the variables in onSectionAdded out of this loop
        for (int y = this.level.getMinSectionY(); y <= this.level.getMaxSectionY(); y++) {
            this.onSectionAdded(x, y, z);
        }
    }

    public void onChunkRemoved(int x, int z) {
        // TODO: hoist some of the variables in onSectionRemoved out of this loop
        for (int y = this.level.getMinSectionY(); y <= this.level.getMaxSectionY(); y++) {
            this.onSectionRemoved(x, y, z);
        }
    }

    public Iterable<BlockEntity[]> getGlobalBlockEntities() {
        return this.globalBlockEntities.values();
    }
}
