package net.caffeinemc.mods.sodium.client.render.chunk.compile;

import net.caffeinemc.mods.sodium.client.render.chunk.partition.WorldPartition;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.util.SectionPosUtil;
import org.jetbrains.annotations.NotNull;

public record UniqueSectionRef(
        long pos,
        int uid,
        WorldPartition partition,
        int partitionSectionIndex,
        RenderRegion region,
        int regionSectionIndex
) {
    @Override
    public @NotNull String toString() {
        return "Section{" +
                SectionPosUtil.packedToString(this.pos) +
                ", UID:" + this.uid +
                '}';
    }

//    public int partitionSectionIndex() {
//        var x = SectionPos.x(this.pos);
//        var y = SectionPos.y(this.pos);
//        var z = SectionPos.z(this.pos);
//        return PartitionSectionIndex.pack(x, y, z);
//    }

//    public int regionSectionIndex() {
//        var x = SectionPos.x(this.pos);
//        var y = SectionPos.y(this.pos);
//        var z = SectionPos.z(this.pos);
//        return RegionSectionIndex.pack(x, y, z);
//    }
}