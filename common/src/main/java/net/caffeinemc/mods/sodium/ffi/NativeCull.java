package net.caffeinemc.mods.sodium.ffi;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.FrustumIntersection;
import org.joml.Vector4f;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class NativeCull {
    public static final boolean SUPPORTED;

    private static final PanicCallback PANIC_CALLBACK;

    private static final MethodHandle FRUSTUM_PLANES_HANDLE;

    static {
        var errorLoading = false;
        PanicCallback panicCallback = null;
        MethodHandle frustumPlanesHandle = null;

        try {
            Library.loadSystem(
                    System::load,
                    System::loadLibrary,
                    NativeCull.class,
                    "",
                    "assets/sodium/natives/libnative_cull.so"
            );

            initAllocator();
            panicCallback = initPanicHandler();

            var field = FrustumIntersection.class.getDeclaredField("planes");
            field.setAccessible(true);
            frustumPlanesHandle = MethodHandles.lookup().unreflectGetter(field);
        } catch (Throwable t) {
            SodiumClientMod.logger().error("Error loading native culling library", t);
            errorLoading = true;
        }

        SUPPORTED = !errorLoading;
        PANIC_CALLBACK = panicCallback;
        FRUSTUM_PLANES_HANDLE = frustumPlanesHandle;
    }

    private static void initAllocator() {
        var allocator = MemoryUtil.getAllocator();

        var alignedAllocFnPtr = allocator.getAlignedAlloc();
        var alignedFreeFnPtr = allocator.getAlignedFree();
        var reallocFnPtr = allocator.getRealloc();
        var callocFnPtr = allocator.getCalloc();

        if (alignedAllocFnPtr == 0 || alignedFreeFnPtr == 0 || reallocFnPtr == 0 || callocFnPtr == 0) {
            throw new NullPointerException(String.format(
                    "Function pointers may not be null."
                            + " aligned_alloc: %s, aligned_free: %s, realloc: %s, calloc: %s",
                    alignedAllocFnPtr,
                    alignedFreeFnPtr,
                    reallocFnPtr,
                    callocFnPtr
            ));
        }

        NativeCull.setAllocator(
                alignedAllocFnPtr,
                alignedFreeFnPtr,
                reallocFnPtr,
                callocFnPtr
        );
    }

    private static PanicCallback initPanicHandler() {
        var panicCallback = PanicCallback.defaultHandler();
        NativeCull.setPanicHandler(panicCallback.address());
        return panicCallback;
    }

    public static void freePanicHandler() {
        if (PANIC_CALLBACK != null) {
            PANIC_CALLBACK.free();
        }
    }

    public static long frustumCreate(MemoryStack stack, FrustumIntersection frustum, CameraTransform offset) {
        // alignment and size obtained from rust
        long pFrustum = stack.nmalloc(8, 120);

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

        return pFrustum;
    }

    /**
     * @param aligned_alloc_fn_ptr Rust Type: {@code AlignedAllocFn}
     * @param aligned_free_fn_ptr  Rust Type: {@code AlignedFreeFn}
     * @param realloc_fn_ptr       Rust Type: {@code ReallocFn}
     * @param calloc_fn_ptr        Rust Type: {@code CallocFn}
     */
    private static native void setAllocator(long aligned_alloc_fn_ptr, long aligned_free_fn_ptr, long realloc_fn_ptr, long calloc_fn_ptr);

    /**
     * @param panic_handler_fn_ptr Rust Type: {@code PanicHandlerFn}
     */
    private static native void setPanicHandler(long panic_handler_fn_ptr);

    /**
     * @param render_distance        Rust Type: {@code u8}
     * @param world_bottom_section_y Rust Type: {@code i8}
     * @param world_top_section_y    Rust Type: {@code i8}
     * @return Rust Type: {@code }
     */
    public static native long graphCreate(byte render_distance, byte world_bottom_section_y, byte world_top_section_y);

    /**
     * @param graph_ptr              Rust Type: {@code *mut Graph}
     * @param x                      Rust Type: {@code i32}
     * @param y                      Rust Type: {@code i32}
     * @param z                      Rust Type: {@code i32}
     * @param traversable_blocks_ptr Rust Type: {@code *const FFISectionOpaqueBlocks}
     */
    public static native void graphSetSection(long graph_ptr, int x, int y, int z, long traversable_blocks_ptr);

    /**
     * @param graph_ptr Rust Type: {@code *mut Graph}
     * @param x         Rust Type: {@code i32}
     * @param y         Rust Type: {@code i32}
     * @param z         Rust Type: {@code i32}
     */
    public static native void graphRemoveSection(long graph_ptr, int x, int y, int z);

    /**
     * @param return_value_ptr      Rust Type: {@code *mut FFISlice<FFIVisibleSectionsTile>}
     * @param graph_ptr             Rust Type: {@code *mut Graph}
     * @param camera_ptr            Rust Type: {@code *const FFICamera}
     * @param search_distance       Rust Type: {@code f32}
     * @param use_occlusion_culling Rust Type: {@code bool}
     */
    public static native void graphSearch(long return_value_ptr, long graph_ptr, long camera_ptr, float search_distance, boolean use_occlusion_culling);

    /**
     * @param graph_ptr Rust Type: {@code *mut Graph}
     */
    public static native void graphDelete(long graph_ptr);
}
