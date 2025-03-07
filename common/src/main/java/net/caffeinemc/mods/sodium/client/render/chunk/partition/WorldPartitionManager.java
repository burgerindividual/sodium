package net.caffeinemc.mods.sodium.client.render.chunk.partition;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;

public class WorldPartitionManager {
    private final Long2ReferenceOpenHashMap<WorldPartition> partitions = new Long2ReferenceOpenHashMap<>();

    public WorldPartitionManager() {
    }

    @NotNull
    public WorldPartition getOrCreate(int sectionX, int sectionY, int sectionZ) {
        var partitionKey = WorldPartition.keyFromSection(sectionX, sectionY, sectionZ);

        return this.partitions.computeIfAbsent(
                partitionKey,
                (unused) -> new WorldPartition()
        );
    }

    public WorldPartition get(int sectionX, int sectionY, int sectionZ) {
        var partitionKey = WorldPartition.keyFromSection(sectionX, sectionY, sectionZ);
        return this.partitions.get(partitionKey);
    }

    // TODO: should we do this, or should be use the lastVisibleFrame in WorldPartition?
    //  alternative idea: have a branch based on lastVisibleFrame in this method to skip partitions.
    public void resetCullingState() {
        for (var partition : this.partitions.values()) {
            partition.resetCullingState();
        }
    }

    public void cleanup() {
        Iterator<WorldPartition> it = this.partitions.values().iterator();

        while (it.hasNext()) {
            var partition = it.next();

            if (partition.getSectionCount() == 0) {
                partition.delete();
                it.remove();
            }
        }
    }

    public void delete() {
        for (var partition : this.partitions.values()) {
            partition.delete();
        }
    }

    public int getTotalSections() {
        int count = 0;

        for (var partition : this.partitions.values()) {
            count += partition.getSectionCount();
        }

        return count;
    }
}
