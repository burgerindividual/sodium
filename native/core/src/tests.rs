#![cfg(test)]

use std::collections::HashSet;

use core_simd::simd::num::SimdUint;
use core_simd::simd::Simd;

use crate::graph::local::coord::{LocalNodeCoords, LocalNodeIndex};
use crate::graph::octree::LinearBitOctree;
use crate::graph::visibility::GraphDirection;
use crate::math::Coords3;
use crate::mem;
use crate::region::LocalRegionIndex;

#[test]
fn pack_unpack_local_node_index() {
    for x in 0..=255 {
        for y in 0..=255 {
            for z in 0..=255 {
                let local_coord = LocalNodeCoords::from_xyz(x, y, z);

                let index = LocalNodeIndex::<0>::pack(local_coord);
                let unpacked = index.unpack();

                assert_eq!(unpacked, local_coord);
            }
        }
    }
}

#[test]
fn inc_dec_local_node_index() {
    let mut local_index = LocalNodeIndex::<3>::pack(LocalNodeCoords::from_xyz(0, 0, 0));

    local_index = local_index.inc_x();

    assert_eq!(
        LocalNodeCoords::<0>::from_xyz(8, 0, 0),
        local_index.unpack_section()
    );

    local_index = local_index.dec_x();

    assert_eq!(
        LocalNodeCoords::<0>::from_xyz(0, 0, 0),
        local_index.unpack_section()
    );
}

// #[test]
// fn add_local_node_index() {
//     for x in 0..=255_u8 {
//         for y in 0..=255_u8 {
//             for z in 0..=255_u8 {
//                 let mut index = LocalNodeIndex::<0>::pack(LocalNodeCoords::from_xyz(x, y, z));
//                 index = index.add::<LOCAL_NODE_INDEX_X_MASK, 1>();
//                 index = index.add::<LOCAL_NODE_INDEX_Y_MASK, 3>();
//                 index = index.add::<LOCAL_NODE_INDEX_Z_MASK, 0>();
//                 let unpacked = index.unpack();

//                 let expected_coords = LocalNodeCoords::from_xyz(
//                     x.wrapping_add(2),
//                     y.wrapping_add(8),
//                     z.wrapping_add(1),
//                 );

//                 assert_eq!(unpacked, expected_coords);
//             }
//         }
//     }
// }

#[test]
fn iterate_lower_local_node_index() {
    let local_index_3 = LocalNodeIndex::<3>::pack(LocalNodeCoords::from_xyz(0, 0, 0));
    let mut set = HashSet::new();

    for lower_index_2 in local_index_3.iter_lower_nodes::<2>() {
        let lower_coords_2 = lower_index_2.unpack();
        println!("2: {:?}", lower_coords_2);
        for lower_index_1 in lower_index_2.iter_lower_nodes::<1>() {
            let lower_coords_1 = lower_index_1.unpack();
            println!("1: {:?}", lower_coords_1);
            for lower_index_0 in lower_index_1.iter_lower_nodes::<0>() {
                let lower_coords_0 = lower_index_0.unpack();
                if !set.insert(lower_index_0.0) {
                    panic!("Already exists in set {:?}", lower_coords_0);
                }
                println!("0: {:?}", lower_coords_0)
            }
        }
    }

    println!("Unique level 0 nodes: {}", set.len());
}

#[test]
fn local_region_index() {
    let idx = LocalRegionIndex::from_local_section(LocalNodeCoords::<0>::from_xyz(22, 4, 6));
    println!("{:?}", idx);
}

#[test]
fn local_section_get_neighbors() {
    let idx = LocalNodeIndex::pack(LocalNodeCoords::<1>::from_xyz(22, 4, 6));

    let neighbors = idx.get_all_neighbors();

    assert_eq!(neighbors.get(GraphDirection::NegX), idx.dec_x());
    assert_eq!(neighbors.get(GraphDirection::NegY), idx.dec_y());
    assert_eq!(neighbors.get(GraphDirection::NegZ), idx.dec_z());
    assert_eq!(neighbors.get(GraphDirection::PosX), idx.inc_x());
    assert_eq!(neighbors.get(GraphDirection::PosY), idx.inc_y());
    assert_eq!(neighbors.get(GraphDirection::PosZ), idx.inc_z());
}

#[test]
fn bit_octree_get_set() {
    let mut bit_octree = mem::default_boxed::<LinearBitOctree>();

    let node_index =
        LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(255, 255, 255).into_level::<3>());

    bit_octree.set(node_index, true);

    assert!(bit_octree.get_and_clear(node_index));

    assert!(!bit_octree.get_and_clear(node_index));

    let node_index =
        LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(255, 255, 255).into_level::<2>());

    bit_octree.set(node_index, true);

    assert!(bit_octree.get_and_clear(node_index));

    assert!(!bit_octree.get_and_clear(node_index));

    let node_index =
        LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(255, 255, 255).into_level::<1>());

    bit_octree.set(node_index, true);

    assert!(bit_octree.get_and_clear(node_index));

    assert!(!bit_octree.get_and_clear(node_index));

    let node_index =
        LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(255, 255, 255).into_level::<0>());

    bit_octree.set(node_index, true);

    assert!(bit_octree.get_and_clear(node_index));

    assert!(!bit_octree.get_and_clear(node_index));
}

#[test]
fn morton_cube() {
    for x in 0..4 {
        for y in 0..4 {
            for z in 0..4 {
                let index =
                    LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z)).as_array_index();
                print!("{: >3}", index);
            }
            println!();
        }
        println!();
    }
}

#[test]
fn morton_cube_shift_visual() {
    const AXIS_LENGTH: u8 = 4;

    println!("------------------------------");
    println!("SHIFT NEGATIVE X:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z))
                    .as_array_index() as isize;
                if x == 0 {
                    print!("  X");
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x - 1, y, z))
                        .as_array_index() as isize;
                    print!("{: >3}", initial_idx - new_idx);
                }
            }
            println!();
        }
        println!();
    }

    println!("------------------------------");
    println!("SHIFT POSITIVE X:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z))
                    .as_array_index() as isize;
                if x == (AXIS_LENGTH - 1) {
                    print!("  X");
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x + 1, y, z))
                        .as_array_index() as isize;
                    print!("{: >3}", -(initial_idx - new_idx));
                }
            }
            println!();
        }
        println!();
    }

    println!("------------------------------");
    println!("SHIFT NEGATIVE Y:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z))
                    .as_array_index() as isize;
                if y == 0 {
                    print!("  X");
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y - 1, z))
                        .as_array_index() as isize;
                    print!("{: >3}", initial_idx - new_idx);
                }
            }
            println!();
        }
        println!();
    }

    println!("------------------------------");
    println!("SHIFT POSITIVE Y:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z))
                    .as_array_index() as isize;
                if y == (AXIS_LENGTH - 1) {
                    print!("  X");
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y + 1, z))
                        .as_array_index() as isize;
                    print!("{: >3}", -(initial_idx - new_idx));
                }
            }
            println!();
        }
        println!();
    }

    println!("------------------------------");
    println!("SHIFT NEGATIVE Z:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z))
                    .as_array_index() as isize;
                if z == 0 {
                    print!("  X");
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z - 1))
                        .as_array_index() as isize;
                    print!("{: >3}", initial_idx - new_idx);
                }
            }
            println!();
        }
        println!();
    }

    println!("------------------------------");
    println!("SHIFT POSITIVE Z:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z))
                    .as_array_index() as isize;
                if z == (AXIS_LENGTH - 1) {
                    print!("  X");
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z + 1))
                        .as_array_index() as isize;
                    print!("{: >3}", -(initial_idx - new_idx));
                }
            }
            println!();
        }
        println!();
    }
}

#[test]
fn morton_cube_shift() {
    const AXIS_LENGTH: u8 = 4;
    const FILLER: String = String::new();
    let mut array = [FILLER; 64];

    println!("------------------------------");
    println!("SHIFT NEGATIVE Z:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx =
                    LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z)).as_array_index();
                if z == 0 {
                    array[initial_idx].push('*');
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z - 1))
                        .as_array_index();
                    array[initial_idx].push_str(&new_idx.to_string());
                }
            }
        }
    }

    for str in &mut array {
        print!("{str}, ");
        str.clear();
    }
    println!();

    println!("------------------------------");
    println!("SHIFT POSITIVE Z:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx =
                    LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z)).as_array_index();
                if z == (AXIS_LENGTH - 1) {
                    array[initial_idx].push('*');
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z + 1))
                        .as_array_index();
                    array[initial_idx].push_str(&new_idx.to_string());
                }
            }
        }
    }

    for str in &array {
        print!("{str}, ");
    }
    println!();
}

#[test]
fn morton_cube_overflow() {
    const AXIS_LENGTH: u8 = 4;
    const FILLER: String = String::new();
    let mut array = [FILLER; 64];
    let mut bit_mask = 0_u64;

    println!("------------------------------");
    println!("OVERFLOW NEGATIVE Z:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx =
                    LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z)).as_array_index();
                if z != 0 {
                    array[initial_idx].push('*');
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z + 3))
                        .as_array_index();
                    bit_mask |= 1 << initial_idx;
                    array[initial_idx].push_str(&new_idx.to_string());
                }
            }
        }
    }

    for str in &mut array {
        print!("{str}, ");
        str.clear();
    }
    println!();
    println!("Bitmask: {:#018x}", bit_mask);
    bit_mask = 0;

    println!("------------------------------");
    println!("OVERFLOW POSITIVE Z:");
    for x in 0..AXIS_LENGTH {
        for y in 0..AXIS_LENGTH {
            for z in 0..AXIS_LENGTH {
                let initial_idx =
                    LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z)).as_array_index();
                if z != (AXIS_LENGTH - 1) {
                    array[initial_idx].push('*');
                } else {
                    let new_idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z - 3))
                        .as_array_index();
                    bit_mask |= 1 << initial_idx;
                    array[initial_idx].push_str(&new_idx.to_string());
                }
            }
        }
    }

    for str in &array {
        print!("{str}, ");
    }
    println!();
    println!("Bitmask: {:#018x}", bit_mask);
}

#[test]
fn morton_cube_bfs_scalar() {
    let mut bit_array = 0_u64;

    bit_array |=
        1 << LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(1, 2, 2)).as_array_index();

    for _ in 0..3 {
        println!("-------------------------");

        // negative X shift
        let bit_array_shift_nx =
            ((bit_array & 0x0f0f0f0f00000000) >> 28) | ((bit_array & 0xf0f0f0f0f0f0f0f0) >> 4);
        // negative Y shift
        let bit_array_shift_ny =
            ((bit_array & 0x3333000033330000) >> 14) | ((bit_array & 0xcccccccccccccccc) >> 2);
        // negative Z shift
        let bit_array_shift_nz =
            ((bit_array & 0x5500550055005500) >> 7) | ((bit_array & 0xaaaaaaaaaaaaaaaa) >> 1);
        // positive X shift
        let bit_array_shift_px =
            ((bit_array & 0x0f0f0f0f0f0f0f0f) << 4) | ((bit_array & 0x00000000f0f0f0f0) << 28);
        // positive Y shift
        let bit_array_shift_py =
            ((bit_array & 0x3333333333333333) << 2) | ((bit_array & 0x0000cccc0000cccc) << 14);
        // positive Z shift
        let bit_array_shift_pz =
            ((bit_array & 0x5555555555555555) << 1) | ((bit_array & 0x00aa00aa00aa00aa) << 7);

        // negative Z overflow
        let bit_array_overflow_nz = (bit_array & 0x0055005500550055) << 9;
        // positive Z overflow
        let bit_array_overflow_pz = (bit_array & 0xaa00aa00aa00aa00) >> 9;

        bit_array |= bit_array_shift_ny;
        bit_array |= bit_array_shift_py;
        bit_array |= bit_array_shift_nx;
        bit_array |= bit_array_shift_px;
        bit_array |= bit_array_shift_nz;
        bit_array |= bit_array_shift_pz;

        for x in 0..4 {
            for y in 0..4 {
                for z in 0..4 {
                    let index = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z))
                        .as_array_index();
                    print!("{: >3}", (bit_array_overflow_pz >> index) & 0b1);
                }
                println!();
            }
            println!();
        }
    }
}

#[test]
fn morton_cube_bfs_vector() {
    let mut bit_array = 0_u64;

    bit_array |=
        1 << LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(1, 2, 2)).as_array_index();

    bit_array |=
        1 << LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(0, 1, 2)).as_array_index();

    for x in 0..4 {
        for y in 0..4 {
            for z in 0..4 {
                let index =
                    LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z)).as_array_index();
                print!("{: >3}", (bit_array >> index) & 0b1);
            }
            println!();
        }
        println!();
    }

    for _ in 0..3 {
        println!("-------------------------");

        let bit_array_vec = Simd::<u64, 12>::splat(bit_array);
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
            ]))
            >> Simd::from_array([
                28, 4, // -X
                14, 2, // -Y
                7, 1, // -Z
                0, 0, // +X
                0, 0, // +Y
                0, 0, // +Z
            ]))
            << Simd::from_array([
                0, 0, // -X
                0, 0, // -Y
                0, 0, // -Z
                4, 28, // +X
                2, 14, // +Y
                1, 7, // +Z
            ]);

        bit_array |= shifted_bit_arrays.reduce_or();

        for x in 0..4 {
            for y in 0..4 {
                for z in 0..4 {
                    let index = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(x, y, z))
                        .as_array_index();
                    print!("{: >3}", (bit_array >> index) & 0b1);
                }
                println!();
            }
            println!();
        }
    }
}
