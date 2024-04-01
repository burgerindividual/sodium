#![cfg_attr(not(test), no_std)]
#![feature(portable_simd)]
#![feature(maybe_uninit_slice)]
#![feature(fn_ptr_trait)]
// get rid of this when StdFloat doesn't rely on std
#![feature(core_intrinsics)]
// In most cases, we want to have functions and structs laying around
// that may come in handy in the future
#![allow(dead_code)]

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
