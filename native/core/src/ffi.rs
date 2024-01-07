#![allow(non_snake_case)]

use crate::graph::*;
use crate::jni::types::*;
use crate::math::*;

#[repr(C)]
struct CFrustum {
    planes: [[f32; 6]; 4],
    offset: [f64; 3],
}

#[allow(non_snake_case)]
mod java {
    use alloc::boxed::Box;

    use crate::ffi::*;
    use crate::graph::flags::SectionFlagSet;
    use crate::graph::local::LocalCoordContext;
    use crate::graph::visibility::VisibilityData;
    use crate::mem::LibcAllocVtable;
    use crate::panic::PanicHandlerFn;

    #[no_mangle]
    pub unsafe extern "C" fn Java_me_jellysquid_mods_sodium_ffi_core_CoreLib_setAllocator(
        _: *mut JEnv,
        _: *mut JClass,
        vtable: JPtr<LibcAllocVtable>,
    ) {
        let vtable = vtable.as_ref();

        crate::mem::set_allocator(vtable);
    }

    #[no_mangle]
    pub unsafe extern "C" fn Java_me_jellysquid_mods_sodium_ffi_core_CoreLib_setPanicHandler(
        _: *mut JEnv,
        _: *mut JClass,
        pfn: JPtr<PanicHandlerFn>,
    ) {
        let pfn = *pfn.as_ref();

        crate::panic::set_panic_handler(pfn);
    }

    #[no_mangle]
    pub unsafe extern "C" fn Java_me_jellysquid_mods_sodium_ffi_core_CoreLib_graphCreate(
        _: *mut JEnv,
        _: *mut JClass,
    ) -> Jlong {
        let graph = Box::new(Graph::new());

        Box::into_raw(graph) as usize as Jlong
    }

    #[no_mangle]
    pub unsafe extern "C" fn Java_me_jellysquid_mods_sodium_ffi_core_CoreLib_graphSetSection(
        _: *mut JEnv,
        _: *mut JClass,
        graph: JPtrMut<Graph>,
        x: Jint,
        y: Jint,
        z: Jint,
        visibility_data: Jlong,
        flags: Jbyte,
    ) {
        let graph = graph.into_mut_ref();
        graph.set_section(
            i32x3::from_xyz(x, y, z),
            VisibilityData::pack(visibility_data as u64),
            SectionFlagSet::from(flags as u8),
        );
    }

    #[no_mangle]
    pub unsafe extern "C" fn Java_me_jellysquid_mods_sodium_ffi_core_CoreLib_graphRemoveSection(
        _: *mut JEnv,
        _: *mut JClass,
        graph: JPtrMut<Graph>,
        x: Jint,
        y: Jint,
        z: Jint,
    ) {
        let graph = graph.into_mut_ref();
        graph.remove_section(i32x3::from_xyz(x, y, z));
    }

    #[no_mangle]
    pub unsafe extern "C" fn Java_me_jellysquid_mods_sodium_ffi_core_CoreLib_graphSearch(
        _: *mut JEnv,
        _: *mut JClass,
        graph: JPtrMut<Graph>,
        frustum: JPtr<CFrustum>,
        section_view_distance: Jshort,
        fog_distance: Jfloat,
        world_bottom_section_y: Jbyte,
        world_top_section_y: Jbyte,
        disable_occlusion_culling: Jboolean,
    ) -> Jlong {
        let graph = graph.into_mut_ref();
        let frustum = frustum.as_ref();

        let simd_camera_world_pos = f64x3::from_array(frustum.offset);
        let simd_frustum_planes = [
            f32x6::from_array(frustum.planes[0]),
            f32x6::from_array(frustum.planes[1]),
            f32x6::from_array(frustum.planes[2]),
            f32x6::from_array(frustum.planes[3]),
        ];

        let coord_context = LocalCoordContext::new(
            simd_frustum_planes,
            simd_camera_world_pos,
            section_view_distance as u8,
            fog_distance,
            world_bottom_section_y,
            world_top_section_y,
        );

        graph.cull_and_sort(&coord_context, disable_occlusion_culling) as *const _ as usize as Jlong
    }

    #[no_mangle]
    pub unsafe extern "C" fn Java_me_jellysquid_mods_sodium_ffi_core_CoreLib_graphDelete(
        _: *mut JEnv,
        _: *mut JClass,
        graph: JPtrMut<Graph>,
    ) {
        let graph_box = Box::from_raw(graph.into_mut_ref());
        drop(graph_box);
    }
}