#![cfg(test)]

use std::collections::HashSet;

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
fn morten_cube() {
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
fn morten_cube_shift() {
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
