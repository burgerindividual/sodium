package me.jellysquid.mods.sodium.ffi.core;

import org.joml.FrustumIntersection;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import me.jellysquid.mods.sodium.ffi.core.callback.PanicCallback;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class CoreLib {
    private static final PanicCallback CALLBACK = PanicCallback.defaultHandler();

    public static long frustumCreate(FrustumIntersection frustum, Vector3d offset) {
        long pFrustum = MemoryUtil.nmemAlloc(((6 * 4) * 4) + (3 * 8));
        copyFrustumPoints(pFrustum, frustum);

        return pFrustum;
    }

    public static void frustumDelete(long pFrustum) {
        MemoryUtil.nmemFree(pFrustum);
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

    private static void copyFrustumPoints(long pFrustum, FrustumIntersection frustum) {
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
        } catch (Throwable t) {
            throw new RuntimeException("Failed to extract planes from frustum", t);
        }
    }

    public static void init() {
        CoreLib.initAllocator(MemoryUtil.getAllocator());
        CoreLib.initPanicHandler();
    }

    private static void initPanicHandler() {
        CoreLib.setPanicHandler(CALLBACK.address());
    }

    private static void initAllocator(MemoryUtil.MemoryAllocator allocator) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pfn = stack.mallocPointer(4);
            pfn.put(0 /* aligned_alloc */, allocator.getAlignedAlloc());
            pfn.put(1 /* aligned_free */, allocator.getAlignedFree());
            pfn.put(2 /* realloc */, allocator.getRealloc());
            pfn.put(3 /* calloc */, allocator.getCalloc());

            CoreLib.setAllocator(pfn.address());
        }
    }

    static native void setAllocator(long pAllocatorPfns);

    static native void setPanicHandler(long pFnPanicHandler);

    /**
     * Returns a pointer to the created graph
     */
    static native long graphCreate();

    static native void graphSetSection(
            long pGraph,
            int x,
            int y,
            int z,
            boolean hasGeometry,
            long visibilityData);

    static native void graphRemoveSection(long pGraph, int x, int y, int z);

    /**
     * Returns a pointer to the search results
     */
    static native long graphSearch(
            long pGraph,
            long pFrustum,
            short viewDistance,
            float fogDistance,
            byte bottomSection,
            byte topSection,
            boolean disableOcclusionCulling);

    static native void graphDelete(long pGraph);

    static {
        System.loadLibrary("sodium_core");
    }
}
