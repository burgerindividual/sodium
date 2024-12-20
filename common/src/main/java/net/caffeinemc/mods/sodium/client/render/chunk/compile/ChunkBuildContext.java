package net.caffeinemc.mods.sodium.client.render.chunk.compile;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.NativeGraph;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public class ChunkBuildContext {
    public final ChunkBuildBuffers buffers;
    public final BlockRenderCache cache;
    public final NativeGraph nativeGraph;

    public ChunkBuildContext(ClientLevel level, ChunkVertexType vertexType, NativeGraph nativeGraph) {
        this.buffers = new ChunkBuildBuffers(vertexType);
        this.cache = new BlockRenderCache(Minecraft.getInstance(), level);
        this.nativeGraph = nativeGraph;
    }

    public void cleanup() {
        this.buffers.destroy();
        this.cache.cleanup();
    }
}
