#![cfg(test)]

use std::collections::HashSet;

use crate::graph::local::coord::{LocalNodeCoords, LocalNodeIndex};
use crate::graph::visibility::GraphDirection;
use crate::math::Coords3;
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
        local_index.unpack().into_level::<0>()
    );

    local_index = local_index.dec_x();

    assert_eq!(
        LocalNodeCoords::<0>::from_xyz(0, 0, 0),
        local_index.unpack().into_level::<0>()
    );
}

#[test]
fn iterate_lower_local_node_index() {
    let local_index_3 = LocalNodeIndex::<3>::pack(LocalNodeCoords::from_xyz(0, 0, 0));
    let mut set = HashSet::new();

    for lower_index_2 in local_index_3.iter_lower_nodes::<2>() {
        let _lower_coords_2 = lower_index_2.unpack();
        // println!("2: {:?}", lower_coords_2);
        for lower_index_1 in lower_index_2.iter_lower_nodes::<1>() {
            let _lower_coords_1 = lower_index_1.unpack();
            // println!("1: {:?}", lower_coords_1);
            for lower_index_0 in lower_index_1.iter_lower_nodes::<0>() {
                let lower_coords_0 = lower_index_0.unpack();
                if !set.insert(lower_index_0.0) {
                    panic!("Already exists in set {:?}", lower_coords_0);
                }
                // println!("0: {:?}", lower_coords_0)
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
    let idx = LocalNodeIndex::pack(LocalNodeCoords::<0>::from_xyz(22, 4, 6));

    let neighbors = idx.get_all_neighbors();

    assert_eq!(neighbors.get(GraphDirection::NegX), idx.dec_x());
    assert_eq!(neighbors.get(GraphDirection::NegY), idx.dec_y());
    assert_eq!(neighbors.get(GraphDirection::NegZ), idx.dec_z());
    assert_eq!(neighbors.get(GraphDirection::PosX), idx.inc_x());
    assert_eq!(neighbors.get(GraphDirection::PosY), idx.inc_y());
    assert_eq!(neighbors.get(GraphDirection::PosZ), idx.inc_z());
}
