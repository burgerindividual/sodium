package net.caffeinemc.mods.sodium.ffi.core;

import org.joml.FrustumIntersection;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.ffi.core.callback.PanicCallback;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class CoreLib {
    private static final PanicCallback CALLBACK = PanicCallback.defaultHandler();

    public static long frustumCreate(MemoryStack stack, FrustumIntersection frustum, CameraTransform offset) {
        // alignment and size obtained from rust
        long pFrustum = stack.nmalloc(8, 120);
        copyFrustumPoints(pFrustum, frustum, offset);
        return pFrustum;
    }

    private static final MethodHandle FRUSTUM_PLANES_HANDLE = getFrustumPlanesHandle();

    private static MethodHandle getFrustumPlanesHandle() {
        try {
            var field = FrustumIntersection.class.getDeclaredField("planes");
            field.setAccessible(true);
            return MethodHandles.lookup().unreflectGetter(field);
        } catch (Exception e) {
            throw new RuntimeException("Unable to get frustum planes handle", e);
        }
    }

    private static void copyFrustumPoints(long pFrustum, FrustumIntersection frustum, CameraTransform offset) {
        try {
            // should be faster than normal reflection
            var planes = (Vector4f[]) FRUSTUM_PLANES_HANDLE.invokeExact(frustum);

            MemoryUtil.memPutFloat(pFrustum, planes[0].x);
            MemoryUtil.memPutFloat(pFrustum + 4, planes[1].x);
            MemoryUtil.memPutFloat(pFrustum + 8, planes[2].x);
            MemoryUtil.memPutFloat(pFrustum + 12, planes[3].x);
            MemoryUtil.memPutFloat(pFrustum + 16, planes[4].x);
            MemoryUtil.memPutFloat(pFrustum + 20, planes[5].x);

            MemoryUtil.memPutFloat(pFrustum + 24, planes[0].y);
            MemoryUtil.memPutFloat(pFrustum + 28, planes[1].y);
            MemoryUtil.memPutFloat(pFrustum + 32, planes[2].y);
            MemoryUtil.memPutFloat(pFrustum + 36, planes[3].y);
            MemoryUtil.memPutFloat(pFrustum + 40, planes[4].y);
            MemoryUtil.memPutFloat(pFrustum + 44, planes[5].y);

            MemoryUtil.memPutFloat(pFrustum + 48, planes[0].z);
            MemoryUtil.memPutFloat(pFrustum + 52, planes[1].z);
            MemoryUtil.memPutFloat(pFrustum + 56, planes[2].z);
            MemoryUtil.memPutFloat(pFrustum + 60, planes[3].z);
            MemoryUtil.memPutFloat(pFrustum + 64, planes[4].z);
            MemoryUtil.memPutFloat(pFrustum + 68, planes[5].z);

            MemoryUtil.memPutFloat(pFrustum + 72, planes[0].w);
            MemoryUtil.memPutFloat(pFrustum + 76, planes[1].w);
            MemoryUtil.memPutFloat(pFrustum + 80, planes[2].w);
            MemoryUtil.memPutFloat(pFrustum + 84, planes[3].w);
            MemoryUtil.memPutFloat(pFrustum + 88, planes[4].w);
            MemoryUtil.memPutFloat(pFrustum + 92, planes[5].w);

            MemoryUtil.memPutDouble(pFrustum + 96, offset.x);
            MemoryUtil.memPutDouble(pFrustum + 104, offset.y);
            MemoryUtil.memPutDouble(pFrustum + 112, offset.z);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to extract planes from frustum", t);
        }
    }

    public static void init() {
        Library.loadNative("me.jellysquid.mods.sodium", "natives/libsodium_core.so");

        CoreLib.initAllocator(MemoryUtil.getAllocator());
        CoreLib.initPanicHandler();
    }

    private static void initPanicHandler() {
        boolean error = CoreLib.setPanicHandler(CALLBACK.address());
        if (error) {
            throw new RuntimeException("Error setting panic handler for CoreLib");
        }
    }

    private static void initAllocator(MemoryUtil.MemoryAllocator allocator) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pfns = stack.mallocPointer(4);
            pfns.put(0 /* aligned_alloc */, allocator.getAlignedAlloc());
            pfns.put(1 /* aligned_free */, allocator.getAlignedFree());
            pfns.put(2 /* realloc */, allocator.getRealloc());
            pfns.put(3 /* calloc */, allocator.getCalloc());

            boolean error = CoreLib.setAllocator(pfns.address());
            if (error) {
                throw new RuntimeException("Error setting memory allocator for CoreLib");
            }
        }
    }

    public static native boolean setAllocator(long pAllocatorPfns);

    public static native boolean setPanicHandler(long pFnPanicHandler);

    /**
     * Returns a pointer to the created graph
     */
    public static native long graphCreate();

    public static native void graphSetSection(
            long pGraph, int x, int y, int z, long visibilityData);

    public static native void graphRemoveSection(long pGraph, int x, int y, int z);

    /**
     * Returns a pointer to the search results.
     * This pointer does not need to be freed or deleted, as it is cached as part of
     * the graph. If the graph is deleted, this pointer is invalid.
     */
    public static native long graphSearch(
            long pGraph,
            long pFrustum,
            float searchDistance,
            byte bottomSection,
            byte topSection,
            boolean useOcclusionCulling);

    /**
     * The pointer provided is invalid after calling this method.
     */
    public static native void graphDelete(long pGraph);
}
