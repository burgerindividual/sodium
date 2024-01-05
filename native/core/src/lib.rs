#![feature(portable_simd)]
#![feature(cell_leak)]
#![feature(maybe_uninit_slice)]
// In most cases, we want to have functions and structs laying around
// that may come in handy in the future
#![allow(dead_code)]

mod collections;
mod ffi;
mod graph;
mod jni;
mod math;
mod mem;
mod panic;
mod region;
