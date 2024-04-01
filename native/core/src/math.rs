#![allow(non_camel_case_types)]

use core::intrinsics::simd::*;
use core::ops::{Add, Mul};

use core_simd::simd::prelude::*;
use core_simd::simd::*;

pub const X: usize = 0;
pub const Y: usize = 1;
pub const Z: usize = 2;
pub const W: usize = 3;

// the most common non-po2 length we use is 3, so we create shorthands for it
pub type i8x3 = Simd<i8, 3>;
pub type i16x3 = Simd<i16, 3>;
pub type i32x3 = Simd<i32, 3>;
pub type i64x3 = Simd<i64, 3>;

pub type u8x3 = Simd<u8, 3>;
pub type u16x3 = Simd<u16, 3>;
pub type u32x3 = Simd<u32, 3>;
pub type u64x3 = Simd<u64, 3>;

pub type f32x3 = Simd<f32, 3>;
pub type f64x3 = Simd<f64, 3>;

// additional useful shorthands
pub type f32x6 = Simd<f32, 6>;

// additional declarations outside of traits for const usage
pub const fn from_xyz<T: SimdElement>(x: T, y: T, z: T) -> Simd<T, 3> {
    Simd::from_array([x, y, z])
}

pub const fn from_xyzw<T: SimdElement>(x: T, y: T, z: T, w: T) -> Simd<T, 4> {
    Simd::from_array([x, y, z, w])
}

pub trait Coords3<T> {
    fn from_xyz(x: T, y: T, z: T) -> Self;
    fn into_tuple(self) -> (T, T, T);
    fn x(&self) -> T;
    fn y(&self) -> T;
    fn z(&self) -> T;
}

impl<T> Coords3<T> for Simd<T, 3>
where
    T: SimdElement,
{
    fn from_xyz(x: T, y: T, z: T) -> Self {
        Simd::from_array([x, y, z])
    }

    fn into_tuple(self) -> (T, T, T) {
        self.to_array().into()
    }

    fn x(&self) -> T {
        self[X]
    }

    fn y(&self) -> T {
        self[Y]
    }

    fn z(&self) -> T {
        self[Z]
    }
}

pub trait Coords4<T> {
    fn from_xyzw(x: T, y: T, z: T, w: T) -> Self;
    fn into_tuple(self) -> (T, T, T, T);
    fn x(&self) -> T;
    fn y(&self) -> T;
    fn z(&self) -> T;
    fn w(&self) -> T;
}

impl<T> Coords4<T> for Simd<T, 4>
where
    T: SimdElement,
{
    fn from_xyzw(x: T, y: T, z: T, w: T) -> Self {
        Simd::from_array([x, y, z, w])
    }

    fn into_tuple(self) -> (T, T, T, T) {
        (self.x(), self.y(), self.z(), self.w())
    }

    fn x(&self) -> T {
        self[X]
    }

    fn y(&self) -> T {
        self[Y]
    }

    fn z(&self) -> T {
        self[Z]
    }

    fn w(&self) -> T {
        self[W]
    }
}

pub trait FastFma {
    fn fast_fma(self, a: Self, b: Self) -> Self;
}

impl<const LANES: usize, T: SimdElement> FastFma for Simd<T, LANES>
where
    LaneCount<LANES>: SupportedLaneCount,
    // Self: StdFloat,
    Self: Mul<Output = Self>,
    Self: Add<Output = Self>,
{
    fn fast_fma(self, a: Self, b: Self) -> Self {
        if cfg!(target_feature = "fma") {
            unsafe { simd_fma(self, a, b) }
        } else {
            self * a + b
        }
    }
}

pub trait RemEuclid {
    fn rem_euclid(self, rhs: Self) -> Self;
}

impl<const LANES: usize> RemEuclid for Simd<f64, LANES>
where
    LaneCount<LANES>: SupportedLaneCount,
{
    fn rem_euclid(self, rhs: Self) -> Self {
        let r = self % rhs;
        r + r
            .simd_lt(Simd::splat(0.0))
            .select(rhs.abs(), Simd::splat(0.0))
    }
}

impl<const LANES: usize> RemEuclid for Simd<f32, LANES>
where
    LaneCount<LANES>: SupportedLaneCount,
{
    fn rem_euclid(self, rhs: Self) -> Self {
        let r = self % rhs;
        r + r
            .simd_lt(Simd::splat(0.0))
            .select(rhs.abs(), Simd::splat(0.0))
    }
}

pub trait StdFloat: Sized {
    #[inline]
    fn mul_add(self, a: Self, b: Self) -> Self {
        unsafe { simd_fma(self, a, b) }
    }

    #[inline]
    fn floor(self) -> Self {
        unsafe { simd_floor(self) }
    }
}

impl<const LANES: usize> StdFloat for Simd<f32, LANES> where LaneCount<LANES>: SupportedLaneCount {}
impl<const LANES: usize> StdFloat for Simd<f64, LANES> where LaneCount<LANES>: SupportedLaneCount {}
