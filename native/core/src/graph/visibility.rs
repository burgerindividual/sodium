use core::mem::transmute;
use core::ops::{BitAnd, BitAndAssign};

use core_simd::simd::Which::*;
use core_simd::simd::*;

#[derive(Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum GraphDirection {
    NegX = 0,
    NegY = 1,
    NegZ = 2,
    PosX = 3,
    PosY = 4,
    PosZ = 5,
}

impl GraphDirection {
    pub const ORDERED: [GraphDirection; 6] = [
        GraphDirection::NegX,
        GraphDirection::NegY,
        GraphDirection::NegZ,
        GraphDirection::PosX,
        GraphDirection::PosY,
        GraphDirection::PosZ,
    ];

    pub const fn opposite(&self) -> GraphDirection {
        match self {
            GraphDirection::NegX => GraphDirection::PosX,
            GraphDirection::NegY => GraphDirection::PosY,
            GraphDirection::NegZ => GraphDirection::PosZ,
            GraphDirection::PosX => GraphDirection::NegX,
            GraphDirection::PosY => GraphDirection::NegY,
            GraphDirection::PosZ => GraphDirection::NegZ,
        }
    }

    /// SAFETY: if out of bounds, this will fail to assert in debug mode
    pub unsafe fn from_int_unchecked(val: u8) -> Self {
        debug_assert!(val <= 5);
        transmute(val)
    }
}

#[derive(Clone, Copy)]
#[repr(transparent)]
pub struct GraphDirectionSet(u8);

impl GraphDirectionSet {
    pub const NONE: Self = Self(0);

    pub const ALL: Self = {
        let mut set = 0_u8;

        let mut i = 0;
        while i < GraphDirection::ORDERED.len() {
            set |= 1 << GraphDirection::ORDERED[i] as u8;
            i += 1;
        }

        Self(set)
    };

    pub const fn from(packed: u8) -> Self {
        Self(packed)
    }

    pub const fn single(direction: GraphDirection) -> GraphDirectionSet {
        Self(1 << direction as u8)
    }

    pub fn add(&mut self, dir: GraphDirection) {
        self.0 |= 1 << dir as u8;
    }

    pub fn add_all(&mut self, set: GraphDirectionSet) {
        self.0 |= set.0;
    }

    pub fn remove(&mut self, dir: GraphDirection) {
        self.0 &= !(1 << dir as u8);
    }

    pub const fn contains(&self, dir: GraphDirection) -> bool {
        (self.0 & (1 << dir as u8)) != 0
    }

    pub const fn is_empty(&self) -> bool {
        self.0 == 0
    }
}

impl Default for GraphDirectionSet {
    fn default() -> Self {
        Self::NONE
    }
}

impl BitAnd for GraphDirectionSet {
    type Output = GraphDirectionSet;

    fn bitand(self, rhs: Self) -> Self::Output {
        Self(self.0 & rhs.0)
    }
}

impl BitAndAssign for GraphDirectionSet {
    fn bitand_assign(&mut self, rhs: Self) {
        *self = *self & rhs;
    }
}

impl IntoIterator for GraphDirectionSet {
    type Item = GraphDirection;
    type IntoIter = GraphDirectionSetIter;

    fn into_iter(self) -> Self::IntoIter {
        GraphDirectionSetIter(self.0)
    }
}

#[repr(transparent)]
pub struct GraphDirectionSetIter(u8);

impl Iterator for GraphDirectionSetIter {
    type Item = GraphDirection;

    fn next(&mut self) -> Option<Self::Item> {
        // Description of the iteration approach on daniel lemire's blog
        // https://lemire.me/blog/2018/02/21/iterating-over-set-bits-quickly/
        if self.0 != 0 {
            // SAFETY: the result from a valid GraphDirectionSet value should never be out
            // of bounds
            let direction =
                unsafe { GraphDirection::from_int_unchecked(self.0.trailing_zeros() as u8) };
            self.0 &= self.0 - 1;
            Some(direction)
        } else {
            None
        }
    }
}

/**
The "Triangle" visibility format uses symmetrical properties in the
visibility data to shrink its representation. This cuts memory usage
taken by visibility data into a 4th of what it was.

This is possible by relying on the following:
If an incoming direction can "see" a particular outgoing direction, then
the reverse is also true. If that outgoing direction were to be an
incoming diretion, then the

Old format: 36 bits, 6 bits per direction. Fits in a u64.
New format: 15 bits, 1, 2, 3, 4, and 5 bits per respective direction.
Fits in a u16.

The layout can be seen here, where each number in the grid represents the
bit location: http://tinyurl.com/sodium-vis-triangle
*/
#[derive(Clone, Copy)]
#[repr(transparent)]
pub struct VisibilityData(u16);

impl VisibilityData {
    pub const ALL_OUTGOING: Self = Self(0b0111111111111111);

    pub fn pack(mut raw: u64) -> Self {
        raw >>= 6;
        let mut packed = (raw & 0b1) as u16;
        raw >>= 5;
        packed |= (raw & 0b110) as u16;
        raw >>= 4;
        packed |= (raw & 0b111000) as u16;
        raw >>= 3;
        packed |= (raw & 0b1111000000) as u16;
        raw >>= 2;
        packed |= (raw & 0b111110000000000) as u16;

        VisibilityData(packed)
    }

    pub fn get_outgoing_directions(&self, incoming: GraphDirectionSet) -> GraphDirectionSet {
        // extend everything to u32s because we can shift them faster on x86 without
        // avx512
        let vis_bits = Simd::<u32, 5>::splat(self.0 as u32);
        let in_bits = Simd::<u32, 5>::splat(incoming.0 as u32);

        // Split visibility bits so each lane is associated with a direction, along with
        // which directions that direction can see. The rows and columns both represent
        // incoming direction -> outgoing directions.
        let visibility_triangle = (vis_bits >> Simd::from_array([0, 1, 3, 6, 10]))
            & Simd::from_array([0b1, 0b11, 0b111, 0b1111, 0b11111]);

        // Row-wise comparison between the triangle visibility structure and the
        // incoming directions.
        //
        // The rows are already formatted horizontally in the same way that the incoming
        // bits are, so we can simply do a bitwise AND between the direction.
        //
        // If any of the directions in a given row are set, and that direction is part
        // of the incoming directions, this will consider that direction outgoing.
        //
        // The reason the 0th bit isn't worked on or possible in the true_values
        // vector is because it will be handled by the column-wise comparison.
        let row_comparison = (visibility_triangle & in_bits)
            .simd_ne(Simd::splat(0))
            .select(
                Simd::from_array([0b10, 0b100, 0b1000, 0b10000, 0b100000]),
                Simd::splat(0),
            );

        // Column-wise comparison between the triangle visibility structure and the
        // incoming directions.
        //
        // This operation fills the lanes with the value of bits
        // [1-5] (assuming 0-based indexing), where each lane is associated with
        // one bit.
        //
        // After that, the bitwise AND is done to create a mask where the visibility
        // data and the incoming directions are both valid.
        //
        // The reason bit 0 is excluded is because it is already completely accounted
        // for by the contens of the other columns, due to the symmetry of the data
        // structure. The bitwise AND will set the 0th bit when necessary.
        //
        // The casting from i32 and back to u32 is to force the shift right to be an
        // arithmetic shift right, because rust will always do arithmetic shifts for
        // signed ints.
        let col_comparison = ((in_bits
            << Simd::from_array([
                u32::BITS - 2,
                u32::BITS - 3,
                u32::BITS - 4,
                u32::BITS - 5,
                u32::BITS - 6,
            ]))
        .cast::<i32>()
            >> Simd::splat(31))
        .cast::<u32>()
            & visibility_triangle;

        // Combine the row comparison output and the column comparison output to get the
        // final outgoing directions value.
        //
        // The extension to power-of-2 vectors makes the reduction produce better
        // codegen.
        let outgoing_bits = simd_swizzle!(
            row_comparison,
            col_comparison,
            [
                First(0),
                First(1),
                First(2),
                First(3),
                First(4),
                First(4),
                First(4),
                First(4),
                Second(0),
                Second(1),
                Second(2),
                Second(3),
                Second(4),
                Second(4),
                Second(4),
                Second(4),
            ]
        )
        .reduce_or() as u8; // & !incoming // the bitwise and here doesn't seem to be necessary

        GraphDirectionSet(outgoing_bits)
    }
}

impl Default for VisibilityData {
    fn default() -> Self {
        Self::ALL_OUTGOING
    }
}
