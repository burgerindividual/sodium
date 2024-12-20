package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.ffi.NativeCull;
import org.lwjgl.system.MemoryStack;

import java.io.Closeable;
import java.util.concurrent.locks.ReentrantLock;

public class NativeGraph implements Closeable {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final long nativePtr;

    public NativeGraph(byte renderDistance, byte minSectionY, byte maxSectionY) {
        this.nativePtr = NativeCull.graphCreate(
                renderDistance,
                minSectionY,
                maxSectionY
        );
    }

    public long search(MemoryStack stack, Viewport viewport, float searchDistance, boolean useOcclusionCulling) {
        var returnValuePtr = stack.ncalloc(8, 16, 1);

        // we push the stack again because we want to deallocate the camera info right after
        // the function is run.
        try (var argsStack = stack.push()) {
            var cameraPtr = NativeCull.frustumCreate(
                    argsStack,
                    viewport.getFrustumIntersection(),
                    viewport.getTransform()
            );

            lock.lock();
            try {
                NativeCull.graphSearch(
                        returnValuePtr,
                        this.nativePtr,
                        cameraPtr,
                        searchDistance,
                        useOcclusionCulling
                );
            } finally {
                lock.unlock();
            }
        }

        return returnValuePtr;
    }

    public void setSection(int x, int y, int z, long opaqueBlocksBuffer) {
        lock.lock();
        try {
            NativeCull.graphSetSection(
                    this.nativePtr,
                    x,
                    y,
                    z,
                    opaqueBlocksBuffer
            );
        } finally {
            lock.unlock();
        }
    }

    public void removeSection(int x, int y, int z) {
        lock.lock();
        try {
            NativeCull.graphRemoveSection(
                    this.nativePtr,
                    x,
                    y,
                    z
            );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            NativeCull.graphDelete(this.nativePtr);
        } finally {
            lock.unlock();
        }
    }
}
