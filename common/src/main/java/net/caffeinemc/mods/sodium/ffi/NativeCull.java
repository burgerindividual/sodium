package net.caffeinemc.mods.sodium.ffi;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.lwjgl.system.*;
import oshi.SystemInfo;

import static org.joml.FrustumIntersection.*;

public class NativeCull {
    public static final boolean SUPPORTED;

    private static final PanicCallback PANIC_CALLBACK;

    static {
        var errorLoading = false;
        PanicCallback panicCallback = null;

        try {
            var architecture = Platform.getArchitecture();
            var systemType = String.format(
                    "%s-%s%s",
                    Platform.get().getName().toLowerCase(),
                    architecture.name().toLowerCase(),
                    getCPUFeatures(architecture)
            );
            var nativePath = String.format(
                    "assets/sodium/natives/%s/%s",
                    systemType,
                    System.mapLibraryName("native_cull")
            );

            Library.loadSystem(
                    System::load,
                    System::loadLibrary,
                    NativeCull.class,
                    "",
                    nativePath
            );

            initAllocator();
            panicCallback = initPanicHandler();
        } catch (Throwable t) {
            SodiumClientMod.logger().error("Error loading native culling library", t);
            errorLoading = true;
        }

        SUPPORTED = !errorLoading;
        PANIC_CALLBACK = panicCallback;
    }

    private static String getCPUFeatures(Platform.Architecture architecture) {
        if (architecture.equals(Platform.Architecture.X64)) {
            var cpuFeatureStrings = new SystemInfo().getHardware().getProcessor().getFeatureFlags();

            // Windows does not let us check for the presence of FMA in its API, so we'll just assume it's present if
            // AVX2 is present. I don't know of any CPUs where this isn't the case
            var hasAVX2 = false;
            var hasSSE41 = false;
            var hasSSSE3 = false;

            for (var cpuFeatureString : cpuFeatureStrings) {
                var lowercaseFeatureString = cpuFeatureString.toLowerCase();
                hasAVX2 |= lowercaseFeatureString.contains("avx2");
                hasSSE41 |= lowercaseFeatureString.contains("sse4_1") || lowercaseFeatureString.contains("sse4.1");
                hasSSSE3 |= lowercaseFeatureString.contains("ssse3");
            }

            if (hasAVX2) {
                return "-avx2+fma";
            } else if (hasSSE41 && hasSSSE3) {
                return "-sse4_1+ssse3";
            }
        }

        return "";
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

    public static long frustumCreate(MemoryStack stack, NativeFrustum frustum, CameraTransform transform) {
        // alignment and size obtained from rust
        long pFrustum = stack.nmalloc(8, 120);

        try {
            // should be faster than normal reflection
            var planes = frustum.getPlanes();

            // the order of the planes in memory matches the direction order used in the native code
            // (NEG_X, NEG_Y, NEG_Z, POS_X, POS_Y, POS_Z)
            MemoryUtil.memPutFloat(pFrustum, planes[PLANE_NX].x);
            MemoryUtil.memPutFloat(pFrustum + 4, planes[PLANE_NX].y);
            MemoryUtil.memPutFloat(pFrustum + 8, planes[PLANE_NX].z);
            MemoryUtil.memPutFloat(pFrustum + 12, planes[PLANE_NX].w);

            MemoryUtil.memPutFloat(pFrustum + 16, planes[PLANE_NY].x);
            MemoryUtil.memPutFloat(pFrustum + 20, planes[PLANE_NY].y);
            MemoryUtil.memPutFloat(pFrustum + 24, planes[PLANE_NY].z);
            MemoryUtil.memPutFloat(pFrustum + 28, planes[PLANE_NY].w);

            MemoryUtil.memPutFloat(pFrustum + 32, planes[PLANE_NZ].x);
            MemoryUtil.memPutFloat(pFrustum + 36, planes[PLANE_NZ].y);
            MemoryUtil.memPutFloat(pFrustum + 40, planes[PLANE_NZ].z);
            MemoryUtil.memPutFloat(pFrustum + 44, planes[PLANE_NZ].w);

            MemoryUtil.memPutFloat(pFrustum + 48, planes[PLANE_PX].x);
            MemoryUtil.memPutFloat(pFrustum + 52, planes[PLANE_PX].y);
            MemoryUtil.memPutFloat(pFrustum + 56, planes[PLANE_PX].z);
            MemoryUtil.memPutFloat(pFrustum + 60, planes[PLANE_PX].w);

            MemoryUtil.memPutFloat(pFrustum + 64, planes[PLANE_PY].x);
            MemoryUtil.memPutFloat(pFrustum + 68, planes[PLANE_PY].y);
            MemoryUtil.memPutFloat(pFrustum + 72, planes[PLANE_PY].z);
            MemoryUtil.memPutFloat(pFrustum + 76, planes[PLANE_PY].w);

            MemoryUtil.memPutFloat(pFrustum + 80, planes[PLANE_PZ].x);
            MemoryUtil.memPutFloat(pFrustum + 84, planes[PLANE_PZ].y);
            MemoryUtil.memPutFloat(pFrustum + 88, planes[PLANE_PZ].z);
            MemoryUtil.memPutFloat(pFrustum + 92, planes[PLANE_PZ].w);

            MemoryUtil.memPutDouble(pFrustum + 96, transform.x);
            MemoryUtil.memPutDouble(pFrustum + 104, transform.y);
            MemoryUtil.memPutDouble(pFrustum + 112, transform.z);
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
     * @param visibility_bitmask     Rust Type: {@code u64}
     */
    public static native void graphSetSection(long graph_ptr, int x, int y, int z, long visibility_bitmask);

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
