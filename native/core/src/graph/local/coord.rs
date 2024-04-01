use core_simd::simd::prelude::*;
use derive_more::{Add, AddAssign, Div, DivAssign, Mul, MulAssign, Sub, SubAssign};

use super::Coords3;
use crate::graph::{GraphDirection, SECTIONS_IN_GRAPH};
use crate::math::u8x3;
use crate::unwrap_debug;

#[derive(
    Clone,
    Copy,
    PartialEq,
    Eq,
    Debug,
    Add,
    AddAssign,
    Sub,
    SubAssign,
    Mul,
    MulAssign,
    Div,
    DivAssign,
)]
#[repr(transparent)]
pub struct LocalNodeCoords<const LEVEL: u8>(u8x3);

impl<const LEVEL: u8> LocalNodeCoords<LEVEL> {
    pub fn from_raw(raw: u8x3) -> LocalNodeCoords<LEVEL> {
        Self(raw)
    }

    pub fn into_raw(self) -> u8x3 {
        self.0
    }

    pub fn into_level<const OTHER_LEVEL: u8>(self) -> LocalNodeCoords<OTHER_LEVEL> {
        if OTHER_LEVEL > LEVEL {
            LocalNodeCoords::<OTHER_LEVEL>(self.0 >> Simd::splat(OTHER_LEVEL - LEVEL))
        } else {
            LocalNodeCoords::<OTHER_LEVEL>(self.0 << Simd::splat(LEVEL - OTHER_LEVEL))
        }
    }

    pub fn length() -> u8 {
        1 << LEVEL
    }

    pub fn size() -> u32 {
        1 << (LEVEL * 3)
    }
}

impl<const LEVEL: u8> Coords3<u8> for LocalNodeCoords<LEVEL> {
    fn from_xyz(x: u8, y: u8, z: u8) -> Self {
        Self(u8x3::from_xyz(x, y, z))
    }

    fn into_tuple(self) -> (u8, u8, u8) {
        self.0.into_tuple()
    }

    fn x(&self) -> u8 {
        self.0.x()
    }

    fn y(&self) -> u8 {
        self.0.y()
    }

    fn z(&self) -> u8 {
        self.0.z()
    }
}

#[derive(Clone, Copy, PartialEq, Debug)]
#[repr(transparent)]
pub struct LocalNodeIndex<const LEVEL: u8>(pub u32);

// XYZXYZXYZXYZXYZXYZXYZXYZ
const MORTON_X_MASK: u32 = 0b10010010_01001001_00100100;
const MORTON_Y_MASK: u32 = 0b01001001_00100100_10010010;
const MORTON_Z_MASK: u32 = 0b00100100_10010010_01001001;

impl<const LEVEL: u8> LocalNodeIndex<LEVEL> {
    pub fn pack(unpacked: LocalNodeCoords<LEVEL>) -> Self {
        let section_coords = unpacked.into_level::<0>();

        // allocate one byte per bit for each element.
        // each element is still has its individual bits in linear ordering, but the
        // bytes in the vector are in morton ordering.
        #[rustfmt::skip]
        let expanded_linear_bits = simd_swizzle!(
            section_coords.0,
            [
            //  X, Y, Z
                2, 1, 0,
                2, 1, 0,
                2, 1, 0,
                2, 1, 0,
                2, 1, 0,
                2, 1, 0,
                2, 1, 0,
                2, 1, 0, // LSB
            ]
        );

        // shift each bit into the sign bit for morton ordering
        #[rustfmt::skip]
        let expanded_morton_bits = expanded_linear_bits << Simd::<u8, 24>::from_array(
            [
                7, 7, 7,
                6, 6, 6,
                5, 5, 5,
                4, 4, 4,
                3, 3, 3,
                2, 2, 2,
                1, 1, 1,
                0, 0, 0, // LSB
            ],
        );

        // arithmetic shift to set each whole lane to its sign bit, then shrinking all
        // lanes to bitmask
        let morton_packed = unsafe {
            Mask::<i8, 24>::from_int_unchecked(expanded_morton_bits.cast::<i8>() >> Simd::splat(7))
        }
        .to_bitmask() as u32;

        Self(morton_packed)
    }

    pub fn unpack_section(&self) -> LocalNodeCoords<0> {
        // allocate one byte per bit for each element.
        // each element is still has its individual bits in morton ordering, but the
        // bytes in the vector are in linear ordering.
        #[rustfmt::skip]
        let expanded_linear_bits = simd_swizzle!(
            u8x4::from_array(self.0.to_le_bytes()),
            [
                // X
                // LSB
                0, 0,
                1, 1, 1,
                2, 2, 2,
                // MSB

                // Y
                // LSB
                0, 0, 0,
                1, 1,
                2, 2, 2,
                // MSB

                // Z
                // LSB
                0, 0, 0,
                1, 1, 1,
                2, 2,
                // MSB
            ]
        );

        // shift each bit into the sign bit for morton ordering
        #[rustfmt::skip]
        let expanded_morton_bits = expanded_linear_bits << Simd::<u8, 24>::from_array(
            [
                // X
                // LSB
                5, 2,
                7, 4, 1,
                6, 3, 0,
                // MSB

                // Y
                // LSB
                6, 3, 0,
                5, 2,
                7, 4, 1,
                // MSB

                // Z
                // LSB
                7, 4, 1,
                6, 3, 0,
                5, 2,
                // MSB
            ],
        );

        // arithmetic shift to set each whole lane to its sign bit, then shrinking all
        // lanes to bitmask
        let linear_packed = unsafe {
            Mask::<i8, 24>::from_int_unchecked(expanded_morton_bits.cast::<i8>() >> Simd::splat(7))
        }
        .to_bitmask();

        let section_coords = u8x3::from_slice(&linear_packed.to_le_bytes()[0..3]);

        LocalNodeCoords::<0>::from_raw(section_coords)
    }

    pub fn unpack(&self) -> LocalNodeCoords<LEVEL> {
        self.unpack_section().into_level()
    }

    pub fn inc_x(self) -> Self {
        self.inc::<{ MORTON_X_MASK }>()
    }

    pub fn inc_y(self) -> Self {
        self.inc::<{ MORTON_Y_MASK }>()
    }

    pub fn inc_z(self) -> Self {
        self.inc::<{ MORTON_Z_MASK }>()
    }

    pub fn dec_x(self) -> Self {
        self.dec::<{ MORTON_X_MASK }>()
    }

    pub fn dec_y(self) -> Self {
        self.dec::<{ MORTON_Y_MASK }>()
    }

    pub fn dec_z(self) -> Self {
        self.dec::<{ MORTON_Z_MASK }>()
    }

    pub fn inc<const AXIS_MASK: u32>(self) -> Self {
        // get the value of 1 in the current axis representation
        let axis_one = AXIS_MASK & 0b111;
        // scale it to the current size of the level
        let axis_level_one = axis_one << (LEVEL * 3);

        // make the other bits in the number 1
        let mut masked = self.0 | !AXIS_MASK;

        // do the thing
        masked = masked.wrapping_add(axis_level_one);

        // modify only the masked bits in the original number
        let current_axis_bits = masked & AXIS_MASK;
        let other_axis_bits = self.0 & !AXIS_MASK;

        // combine bits from all axis
        Self(other_axis_bits | current_axis_bits)
    }

    pub fn dec<const AXIS_MASK: u32>(self) -> Self {
        // get the value of 1 in the current axis representation
        let axis_one = AXIS_MASK & 0b111;
        // scale it to the current size of the level
        let axis_level_one = axis_one << (LEVEL * 3);

        // make the other bits in the number 0
        let mut masked = self.0 & AXIS_MASK;

        // do the thing
        masked = masked.wrapping_sub(axis_level_one);

        // modify only the masked bits in the original number
        let current_axis_bits = masked & AXIS_MASK;
        let other_axis_bits = self.0 & !AXIS_MASK;

        // combine bits from all axis
        Self(other_axis_bits | current_axis_bits)
    }

    pub fn as_array_index(&self) -> usize {
        self.0 as usize
    }

    pub fn index_array_unchecked<'array, T>(
        &self,
        array: &'array [T; SECTIONS_IN_GRAPH],
    ) -> &'array T {
        // SAFETY: Using unsafe gets are okay because the internal representation will
        // never have the top 8 bits set, and the arrays are exactly the length
        // of what we can represent with 24 bits.
        unsafe { unwrap_debug!(array.get(self.as_array_index())) }
    }

    pub fn index_array_unchecked_mut<'array, T>(
        &self,
        array: &'array mut [T; SECTIONS_IN_GRAPH],
    ) -> &'array mut T {
        // SAFETY: see documentation in index_array_unchecked
        unsafe { unwrap_debug!(array.get_mut(self.as_array_index())) }
    }

    pub fn iter_lower_nodes<const LOWER_LEVEL: u8>(&self) -> LowerNodeIter<LEVEL, LOWER_LEVEL> {
        LowerNodeIter::new(self)
    }

    pub fn get_all_neighbors(&self) -> NeighborNodes<LEVEL> {
        const DEC_MASK: Simd<u32, 6> = Simd::from_array([
            MORTON_X_MASK,
            MORTON_Y_MASK,
            MORTON_Z_MASK,
            u32::MAX,
            u32::MAX,
            u32::MAX,
        ]);

        const INC_MASK: Simd<u32, 6> = Simd::from_array([
            u32::MAX,
            u32::MAX,
            u32::MAX,
            MORTON_X_MASK,
            MORTON_Y_MASK,
            MORTON_Z_MASK,
        ]);

        const FINAL_MASK: Simd<u32, 6> = Simd::from_array([
            MORTON_X_MASK,
            MORTON_Y_MASK,
            MORTON_Z_MASK,
            MORTON_X_MASK,
            MORTON_Y_MASK,
            MORTON_Z_MASK,
        ]);

        let additive = Simd::from_array([
            -(((MORTON_X_MASK & 0b111) << (LEVEL * 3)) as i32),
            -(((MORTON_Y_MASK & 0b111) << (LEVEL * 3)) as i32),
            -(((MORTON_Z_MASK & 0b111) << (LEVEL * 3)) as i32),
            ((MORTON_X_MASK & 0b111) << (LEVEL * 3)) as i32,
            ((MORTON_Y_MASK & 0b111) << (LEVEL * 3)) as i32,
            ((MORTON_Z_MASK & 0b111) << (LEVEL * 3)) as i32,
        ]);

        let vec = Simd::<u32, 6>::splat(self.0);
        // make the other bits in the number 0 for dec, 1 for inc
        let mut masked = (vec & DEC_MASK) | !INC_MASK;

        // inc/dec
        masked = (masked.cast::<i32>() + additive).cast::<u32>();

        // modify only the masked bits in the original number
        NeighborNodes::new((vec & !FINAL_MASK) | (masked & FINAL_MASK))
    }
}

pub struct LowerNodeIter<const LEVEL: u8, const LOWER_LEVEL: u8> {
    current: LocalNodeIndex<LOWER_LEVEL>,
    end: u32,
}

impl<const LEVEL: u8, const LOWER_LEVEL: u8> LowerNodeIter<LEVEL, LOWER_LEVEL> {
    fn new(index: &LocalNodeIndex<LEVEL>) -> Self {
        assert_eq!(LOWER_LEVEL, LEVEL - 1);

        // the representation is the same
        let lower_index = LocalNodeIndex::<LOWER_LEVEL>(index.0);

        Self {
            current: lower_index,
            end: lower_index.0 + LocalNodeCoords::<LEVEL>::size(),
        }
    }
}

impl<const LEVEL: u8, const LOWER_LEVEL: u8> Iterator for LowerNodeIter<LEVEL, LOWER_LEVEL> {
    type Item = LocalNodeIndex<LOWER_LEVEL>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.current.0 >= self.end {
            None
        } else {
            let current = self.current;

            self.current.0 += LocalNodeCoords::<LOWER_LEVEL>::size();

            Some(current)
        }
    }
}

#[repr(transparent)]
pub struct NeighborNodes<const LEVEL: u8>(Simd<u32, 6>);

impl<const LEVEL: u8> NeighborNodes<LEVEL> {
    fn new(raw_indices: Simd<u32, 6>) -> NeighborNodes<LEVEL> {
        NeighborNodes(raw_indices)
    }

    pub fn get(&self, direction: GraphDirection) -> LocalNodeIndex<LEVEL> {
        LocalNodeIndex(self.0[direction as usize])
    }
}
