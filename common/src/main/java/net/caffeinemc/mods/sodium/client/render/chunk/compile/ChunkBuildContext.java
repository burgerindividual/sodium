package net.caffeinemc.mods.sodium.client.render.chunk.compile;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public class ChunkBuildContext {
    public final ChunkBuildBuffers buffers;
    public final BlockRenderCache cache;
    public final long nativeGraphPtr;

    public ChunkBuildContext(ClientLevel level, ChunkVertexType vertexType, long nativeGraphPtr) {
        this.buffers = new ChunkBuildBuffers(vertexType);
        this.cache = new BlockRenderCache(Minecraft.getInstance(), level);
        this.nativeGraphPtr = nativeGraphPtr;
    }

    public void cleanup() {
        this.buffers.destroy();
        this.cache.cleanup();
    }
}
