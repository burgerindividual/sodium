package net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.UniqueSectionRef;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.Sorter;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Vector3dc;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;

public class ChunkBuilderSortingTask extends ChunkBuilderTask<ChunkSortOutput> {
    private final Sorter sorter;

    public ChunkBuilderSortingTask(UniqueSectionRef section, int frame, Vector3dc absoluteCameraPos, Sorter sorter) {
        super(section, frame, absoluteCameraPos);
        this.sorter = sorter;
    }

    @Override
    public ChunkSortOutput execute(ChunkBuildContext context, CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            return null;
        }

        ProfilerFiller profiler = Profiler.get();
        profiler.push("translucency sorting");

        this.sorter.writeIndexBuffer(this, false);

        profiler.pop();
        return new ChunkSortOutput(this.section, this.submitTime, this.sorter);
    }

    @Override
    public int getEffort() {
        return ChunkBuilder.LOW_EFFORT;
    }
}
