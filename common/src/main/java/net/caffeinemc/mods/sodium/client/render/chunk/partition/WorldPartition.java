package net.caffeinemc.mods.sodium.client.render.chunk.partition;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.GraphDirectionSet;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.TranslucentData;
import net.caffeinemc.mods.sodium.client.util.collections.BitArray;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public class WorldPartition {
    public static final int PARTITION_WIDTH  = 8;
    public static final int PARTITION_HEIGHT = 8;
    public static final int PARTITION_LENGTH = 8;

    public static final int PARTITION_WIDTH_M  = PARTITION_WIDTH - 1;
    public static final int PARTITION_HEIGHT_M = PARTITION_HEIGHT - 1;
    public static final int PARTITION_LENGTH_M = PARTITION_LENGTH - 1;

    public static final int PARTITION_WIDTH_SH  = Integer.bitCount(PARTITION_WIDTH_M);
    public static final int PARTITION_HEIGHT_SH = Integer.bitCount(PARTITION_HEIGHT_M);
    public static final int PARTITION_LENGTH_SH = Integer.bitCount(PARTITION_LENGTH_M);

    public static final int PARTITION_SIZE = PARTITION_WIDTH * PARTITION_HEIGHT * PARTITION_LENGTH;

    // A bit array of sections that are initialized and visible. Sections marked as visible
    // are allowed to be unbuilt or empty.
    public final BitArray visibleSections;

    public final long pOcclusionDataArray;

    public final byte[] flagsArray;

    public final BlockEntity [] @Nullable [] cullableBlockEntitiesArray;
    public final TextureAtlasSprite [] @Nullable [] animatedSpritesArray;

    public final @Nullable TranslucentData[] translucentDataArray;

    public final @Nullable CancellationToken[] taskCancellationTokens;
    // TODO: if it's possible for duplicate section updates to be submitted in the same frame, this needs to have
    //  some sort of key to identify a specific update submission.
    public final long pUpdateStateArray;

//    private int lastVisibleFrame = -1;
    private int sectionCount;

    public WorldPartition() {
        this.visibleSections = new BitArray(PARTITION_SIZE);

        this.pOcclusionDataArray = OcclusionDataUnsafe.allocateArray(PARTITION_SIZE);

        this.flagsArray = new byte[PARTITION_SIZE];
        Arrays.fill(this.flagsArray, RenderSectionFlags.UNINITIALIZED);

        this.cullableBlockEntitiesArray = new BlockEntity[PARTITION_SIZE][];
        this.animatedSpritesArray = new TextureAtlasSprite[PARTITION_SIZE][];

        this.translucentDataArray = new TranslucentData[PARTITION_SIZE];

        this.taskCancellationTokens = new CancellationToken[PARTITION_SIZE];
        this.pUpdateStateArray = UpdateStateUnsafe.allocateArray(PARTITION_SIZE);
    }

    public static long key(int x, int y, int z) {
        return SectionPos.asLong(x, y, z);
    }

    public static long keyFromSection(int sectionX, int sectionY, int sectionZ) {
        return key(
                sectionX >> PARTITION_WIDTH_SH,
                sectionY >> PARTITION_HEIGHT_SH,
                sectionZ >> PARTITION_LENGTH_SH
        );
    }

    public void createSection(int sectionIndex) {
        this.flagsArray[sectionIndex] = RenderSectionFlags.UNBUILT;

        this.sectionCount++;
    }

    public void deleteSection(int sectionIndex) {
        var cancellationToken = this.taskCancellationTokens[sectionIndex];
        if (cancellationToken != null) {
            cancellationToken.setCancelled();
        }
        this.taskCancellationTokens[sectionIndex] = null;
        this.cullableBlockEntitiesArray[sectionIndex] = null;
        this.animatedSpritesArray[sectionIndex] = null;
        this.translucentDataArray[sectionIndex] = null;

        this.visibleSections.unset(sectionIndex);
        this.flagsArray[sectionIndex] = RenderSectionFlags.UNINITIALIZED;

        var pOcclusionData = OcclusionDataUnsafe.indexArray(this.pOcclusionDataArray, sectionIndex);
        OcclusionDataUnsafe.clearEntry(pOcclusionData);

        var pUpdateState = UpdateStateUnsafe.indexArray(this.pUpdateStateArray, sectionIndex);
        UpdateStateUnsafe.clearEntry(pUpdateState);

        this.sectionCount--;
    }

    public boolean setRenderState(int sectionIndex, @NotNull BuiltSectionInfo info) {
        // build state contained in flags
        var prevFlags = this.flagsArray[sectionIndex];
        this.flagsArray[sectionIndex] = info.flags;

        var pOcclusionData = OcclusionDataUnsafe.indexArray(this.pOcclusionDataArray, sectionIndex);
        var prevVisibilityData = OcclusionDataUnsafe.getVisibilityData(pOcclusionData);
        OcclusionDataUnsafe.setVisibilityData(pOcclusionData, info.visibilityData);

        this.cullableBlockEntitiesArray[sectionIndex] = info.culledBlockEntities;
        this.animatedSpritesArray[sectionIndex] = info.animatedSprites;

        // the section is marked as having received graph-relevant changes if it's build state, flags, or connectedness has changed.
        // the entities and sprites don't need to be checked since whether they exist is encoded in the flags.
        return prevFlags != info.flags || prevVisibilityData != info.visibilityData;
    }

//    private boolean emptyRenderState(int sectionIndex) {
//        this.visibleSections.unset(sectionIndex);
//
//        var wasBuilt = RenderSectionFlags.isBuilt(this.flagsArray[sectionIndex]);
//        this.flagsArray[sectionIndex] = RenderSectionFlags.UNBUILT;
//
//        var pOcclusionData = OcclusionDataUnsafe.indexArray(this.pOcclusionDataArray, sectionIndex);
//        OcclusionDataUnsafe.setVisibilityData(pOcclusionData, VisibilityEncoding.NULL);
//
//        this.cullableBlockEntitiesArray[sectionIndex] = null;
//        this.animatedSpritesArray[sectionIndex] = null;
//
//        return wasBuilt;
//    }

    // TODO: replace this with using lastVisibleFrame
    public void resetCullingState() {
        this.visibleSections.unsetAll();

        for (int sectionIndex = 0; sectionIndex < PARTITION_SIZE; sectionIndex++) {
            var pOcclusionData = OcclusionDataUnsafe.indexArray(this.pOcclusionDataArray, sectionIndex);
            OcclusionDataUnsafe.setIncomingDirections(pOcclusionData, GraphDirectionSet.NONE);
        }
    }

    public void delete() {
        OcclusionDataUnsafe.freeArray(this.pOcclusionDataArray);
        UpdateStateUnsafe.freeArray(this.pUpdateStateArray);

        for (var cancellationToken : this.taskCancellationTokens) {
            if (cancellationToken != null) {
                cancellationToken.setCancelled();
            }
        }
    }

    public int getSectionCount() {
        return this.sectionCount;
    }
}
