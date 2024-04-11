#![cfg_attr(not(test), no_std)]
#![feature(portable_simd)]
#![feature(maybe_uninit_slice)]
#![feature(fn_ptr_trait)]
// get rid of this when StdFloat doesn't rely on std
#![feature(core_intrinsics)]
#![feature(link_llvm_intrinsics)]
#![feature(simd_ffi)]
// In most cases, we want to have functions and structs laying around
// that may come in handy in the future
#![allow(dead_code)]

use core_simd::simd::num::SimdUint;
use core_simd::simd::Simd;

extern crate alloc;

mod collections;
mod ffi;
mod graph;
mod jni;
mod math;
mod mem;
#[macro_use]
mod panic;
mod region;
mod tests;

#[no_mangle]
pub fn bfs_bitmask_step(bit_array: u64) -> u64 {
    let bit_array_vec = Simd::<u64, 13>::splat(bit_array);
    let shifted_bit_arrays = ((bit_array_vec
        & Simd::from_array([
            // -X
            0x0f0f0f0f00000000,
            0xf0f0f0f0f0f0f0f0,
            // -Y
            0x3333000033330000,
            0xcccccccccccccccc,
            // -Z
            0x5500550055005500,
            0xaaaaaaaaaaaaaaaa,
            // +X
            0x0f0f0f0f0f0f0f0f,
            0x00000000f0f0f0f0,
            // +Y
            0x3333333333333333,
            0x0000cccc0000cccc,
            // +Z
            0x5555555555555555,
            0x00aa00aa00aa00aa,
            // Base
            0,
        ]))
        >> Simd::from_array([
            28, 4, // -X
            14, 2, // -Y
            7, 1, // -Z
            0, 0, // +X
            0, 0, // +Y
            0, 0, // +Z
            0, // Base
        ]))
        << Simd::from_array([
            0, 0, // -X
            0, 0, // -Y
            0, 0, // -Z
            4, 28, // +X
            2, 14, // +Y
            1, 7, // +Z
            0, // Base
        ]);

    shifted_bit_arrays.reduce_or()
}
