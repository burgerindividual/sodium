use core::mem::size_of;

use core_simd::simd::*;

use crate::graph::*;
use crate::unwrap_debug;

// operations on u8x64 are faster in some cases compared to u64x8
pub type Level3Node = Simd<u8, 64>;
pub type Level2Node = u64;
pub type Level1Node = u8;
pub type Level0Node = bool;

pub union LinearBitOctree {
    // the divide by 8 is because there are 8 bits per byte
    level_3: [Level3Node; SECTIONS_IN_GRAPH / size_of::<Level3Node>() / 8],
    level_2: [Level2Node; SECTIONS_IN_GRAPH / size_of::<Level2Node>() / 8],
    level_1: [Level1Node; SECTIONS_IN_GRAPH / size_of::<Level1Node>() / 8],
}

impl InitDefaultInPlace for *mut LinearBitOctree {
    fn init_default_in_place(self) {
        unsafe {
            addr_of_mut!((*self).level_3).init_default_in_place();
        }
    }
}

// All of the unsafe gets should be safe, because LocalNodeIndex should never
// have the top 8 bits set, and our arrays are exactly 2^24 bytes long.
impl LinearBitOctree {
    /// Returns true if all of the bits in the node are true
    pub fn get_and_clear<const LEVEL: u8>(&mut self, index: LocalNodeIndex<LEVEL>) -> bool {
        let array_index = index.as_array_index_unscaled();

        let result;
        match LEVEL {
            0 => {
                let level_1_index = array_index >> 3;
                let bit_index = array_index & 0b111;

                let level_1_node = unsafe { unwrap_debug!(self.level_1.get_mut(level_1_index)) };

                let bit = 0b1 << bit_index;
                result = (*level_1_node & bit) != 0;
                *level_1_node &= !bit;
            }
            1 => {
                let level_1_node = unsafe { unwrap_debug!(self.level_1.get_mut(array_index)) };

                result = *level_1_node == u8::MAX;
                *level_1_node = 0_u8;
            }
            2 => {
                let level_2_node = unsafe { unwrap_debug!(self.level_2.get_mut(array_index)) };

                result = *level_2_node == u64::MAX;
                *level_2_node = 0_u64;
            }
            3 => {
                let level_3_node = unsafe { unwrap_debug!(self.level_3.get_mut(array_index)) };

                result = *level_3_node == u8x64::splat(u8::MAX);
                *level_3_node = u8x64::splat(0_u8);
            }
            _ => unreachable!(),
        }

        result
    }

    /// Sets all of the bits in the node to the given value
    pub fn set<const LEVEL: u8>(&mut self, section: LocalNodeIndex<LEVEL>, value: bool) {
        let array_index = section.as_array_index_unscaled();

        match LEVEL {
            0 => {
                let level_1_index = array_index >> 3;
                let bit_index = array_index & 0b111;

                let level_1_node = unsafe { unwrap_debug!(self.level_1.get_mut(level_1_index)) };

                let bit = 0b1 << bit_index;

                if value {
                    *level_1_node |= bit;
                } else {
                    *level_1_node &= !bit;
                }
            }
            1 => {
                let level_1_node = unsafe { unwrap_debug!(self.level_1.get_mut(array_index)) };

                *level_1_node = if value { u8::MAX } else { 0_u8 };
            }
            2 => {
                let level_2_node = unsafe { unwrap_debug!(self.level_2.get_mut(array_index)) };

                *level_2_node = if value { u64::MAX } else { 0_u64 };
            }
            3 => {
                let level_3_node = unsafe { unwrap_debug!(self.level_3.get_mut(array_index)) };

                *level_3_node = u8x64::splat(if value { u8::MAX } else { 0_u8 });
            }
            _ => unreachable!(),
        }
    }

    pub fn clear(&mut self) {
        unsafe { &mut self.level_3 }.fill(Level3Node::splat(0));
    }

    // inside of individual level 3 nodes, the cache locality is *extremely* good.
    // const INTRINSIC_LOCALITY_LEVEL: i32 = 3;
    //
    // pub fn prefetch_top_node_read(&self, index: LocalNodeIndex<3>) {
    //     unsafe {
    //         let pointer = unsafe {
    //             self.level_3
    //                 .get(index.as_array_offset().unwrap_debug() >>
    // LEVEL_3_INDEX_SHIFT)         };
    //
    //         prefetch_read_data(pointer, Self::INTRINSIC_LOCALITY_LEVEL);
    //     }
    // }
    //
    // pub fn prefetch_top_node_write(&self, index: LocalNodeIndex<3>) {
    //     unsafe {
    //         let pointer = unsafe {
    //             self.level_3
    //                 .get(index.as_array_offset().unwrap_debug() >>
    // LEVEL_3_INDEX_SHIFT)         };
    //
    //         prefetch_write_data(pointer, Self::INTRINSIC_LOCALITY_LEVEL);
    //     }
    // }
}
