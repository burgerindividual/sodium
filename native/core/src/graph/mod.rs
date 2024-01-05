use std::mem::swap;

use core_simd::simd::Which::*;
use core_simd::simd::*;
use local::LocalCoordContext;

use crate::collections::ArrayDeque;
use crate::graph::local::index::LocalNodeIndex;
use crate::graph::local::*;
use crate::graph::octree::LinearBitOctree;
use crate::graph::visibility::*;
use crate::math::*;
use crate::region::*;

pub mod local;
mod octree;
pub mod visibility;

pub const SECTIONS_IN_GRAPH: usize = 256 * 256 * 256;

pub const MAX_VIEW_DISTANCE: u8 = 127;
pub const MAX_WORLD_HEIGHT: u8 = 254;
pub const BFS_QUEUE_SIZE: usize =
    get_bfs_queue_max_size(MAX_VIEW_DISTANCE, MAX_WORLD_HEIGHT) as usize;
pub type BfsQueue = ArrayDeque<LocalNodeIndex<1>, BFS_QUEUE_SIZE>;

pub const fn get_bfs_queue_max_size(section_render_distance: u8, world_height: u8) -> u32 {
    // for the worst case, we will assume the player is in the center of the render
    // distance and world height.
    // for traversal lengths, we don't include the chunk the player is in.

    let max_height_traversal = (world_height.div_ceil(2) - 1) as u32;
    let max_width_traversal = section_render_distance as u32;

    // the 2 accounts for the chunks directly above and below the player
    let mut count = 2;
    let mut layer_index = 1_u32;

    // check if the traversal up and down is restricted by the world height. if so,
    // remove the out-of-bounds layers from the iteration
    if max_height_traversal < max_width_traversal {
        count = 0;
        layer_index = max_width_traversal - max_height_traversal;
    }

    // add rings that are on both the top and bottom.
    // simplification of:
    // while layer_index < max_width_traversal {
    //     count += (layer_index * 8);
    //     layer_index += 1;
    // }
    count += 4 * (max_width_traversal - layer_index) * (max_width_traversal + layer_index - 1);

    // add final, outer-most ring.
    count += max_width_traversal * 4;

    // TODO: i'm pretty sure this only holds true when we do checks on the nodes
    // before enqueueing  them. however, this would result in a lot of excess
    // checks when multiple nodes try to queue  the same section.
    // if frustum {
    //     // divide by 2 because the player should never be able to see more than
    // half of the world     // at once with frustum culling. This assumes an
    // FOV maximum of 180 degrees.     count = count.div_ceil(2);
    // }

    count
}

pub struct BfsCachedState {
    incoming_directions: [GraphDirectionSet; SECTIONS_IN_GRAPH],
    staging_draw_batches: StagingRegionDrawBatches,
}

impl BfsCachedState {
    pub fn reset(&mut self) {
        self.incoming_directions.fill(GraphDirectionSet::NONE);
    }
}

impl Default for BfsCachedState {
    fn default() -> Self {
        BfsCachedState {
            incoming_directions: [GraphDirectionSet::default(); SECTIONS_IN_GRAPH],
            staging_draw_batches: Default::default(),
        }
    }
}

#[derive(Default)]
pub struct FrustumFogCachedState {
    section_is_visible_bits: LinearBitOctree,
}

impl FrustumFogCachedState {
    pub fn reset(&mut self) {
        self.section_is_visible_bits.clear();
    }
}

pub struct Graph {
    section_has_geometry_bits: LinearBitOctree,
    section_visibility_direction_sets: [VisibilityData; SECTIONS_IN_GRAPH],

    frustum_fog_cached_state: FrustumFogCachedState,
    bfs_cached_state: BfsCachedState,
}

impl Graph {
    pub fn new() -> Self {
        Graph {
            section_has_geometry_bits: Default::default(),
            section_visibility_direction_sets: [Default::default(); SECTIONS_IN_GRAPH],
            frustum_fog_cached_state: Default::default(),
            bfs_cached_state: Default::default(),
        }
    }

    pub fn cull(
        &mut self,
        coord_context: &LocalCoordContext,
        disable_occlusion_culling: bool,
    ) -> &StagingRegionDrawBatches {
        self.bfs_cached_state.staging_draw_batches.reset();

        self.frustum_and_fog_cull(coord_context);
        self.bfs_and_occlusion_cull(coord_context, disable_occlusion_culling);

        // this will make sure nothing tries to use it after culling, and it should be
        // clean for the next invocation of this method
        self.frustum_fog_cached_state.reset();

        &self.bfs_cached_state.staging_draw_batches
    }

    fn frustum_and_fog_cull(&mut self, coord_context: &LocalCoordContext) {
        let mut level_3_index = coord_context.iter_node_origin_index;

        // this could go more linearly in memory, but we probably have good enough
        // locality inside the level 3 nodes
        for _x in 0..coord_context.level_3_node_iters.x() {
            for _y in 0..coord_context.level_3_node_iters.y() {
                for _z in 0..coord_context.level_3_node_iters.z() {
                    self.check_node(level_3_index, coord_context);

                    level_3_index = level_3_index.inc_z();
                }
                level_3_index = level_3_index.inc_y();
            }
            level_3_index = level_3_index.inc_x();
        }
    }

    fn check_node<const LEVEL: u8>(
        &mut self,
        index: LocalNodeIndex<LEVEL>,
        coord_context: &LocalCoordContext,
    ) {
        match coord_context.test_node(index) {
            BoundsCheckResult::Outside => {}
            BoundsCheckResult::Inside => {
                self.frustum_fog_cached_state
                    .section_is_visible_bits
                    .set(index, true);
            }
            BoundsCheckResult::Partial => match LEVEL {
                3 => {
                    for lower_node_index in index.iter_lower_nodes::<2>() {
                        self.check_node(lower_node_index, coord_context);
                    }
                }
                2 => {
                    for lower_node_index in index.iter_lower_nodes::<1>() {
                        self.check_node(lower_node_index, coord_context);
                    }
                }
                1 => {
                    for lower_node_index in index.iter_lower_nodes::<0>() {
                        self.check_node(lower_node_index, coord_context);
                    }
                }
                0 => {
                    // TODO: perhaps this should just be set to true always? i think the
                    // geometry status should only be applied to the final bitmask and returned
                    // sections, without impeding the bfs flow.
                    self.frustum_fog_cached_state
                        .section_is_visible_bits
                        .set(index, true);
                }
                _ => unreachable!("Invalid node level: {}", LEVEL),
            },
        }
    }

    fn bfs_and_occlusion_cull(
        &mut self,
        coord_context: &LocalCoordContext,
        disable_occlusion_culling: bool,
    ) {
        let directions_modifier = if disable_occlusion_culling {
            GraphDirectionSet::ALL
        } else {
            GraphDirectionSet::NONE
        };

        // Initially the read queue
        let mut queue_1 = BfsQueue::default();
        // Initially the write queue
        let mut queue_2 = BfsQueue::default();

        // Manually add the first section to search from
        let initial_node_index = coord_context.camera_section_index;
        queue_1.push(initial_node_index);
        initial_node_index
            .index_array_unchecked_mut(&mut self.bfs_cached_state.incoming_directions)
            .add_all(GraphDirectionSet::ALL);

        let mut read_queue_ref = &mut queue_1;
        let mut write_queue_ref = &mut queue_2;

        let mut finished = false;
        // this finishes when the read queue is completely empty.
        while !finished {
            finished = true;

            while let Some(&node_index) = read_queue_ref.pop() {
                finished = false;

                if !self
                    .frustum_fog_cached_state
                    .section_is_visible_bits
                    .get(node_index)
                {
                    // skip node
                    continue;
                }

                let node_pos = node_index.unpack();
                let valid_directions = coord_context.get_valid_directions(node_pos);

                let mut node_outgoing_directions;
                if self.section_has_geometry_bits.get(node_index) {
                    self.bfs_cached_state
                        .staging_draw_batches
                        .add_section(coord_context, node_pos);

                    // use incoming directions to determine outgoing directions, given the
                    // visibility bits set
                    let node_incoming_directions = *node_index
                        .index_array_unchecked(&self.bfs_cached_state.incoming_directions);

                    node_outgoing_directions = node_index
                        .index_array_unchecked(&self.section_visibility_direction_sets)
                        .get_outgoing_directions(node_incoming_directions);
                    node_outgoing_directions.add_all(directions_modifier);
                    node_outgoing_directions &= valid_directions;
                } else {
                    node_outgoing_directions = valid_directions;
                }

                // use the outgoing directions to get the neighbors that could possibly be
                // enqueued
                let node_neighbor_indices = node_index.get_all_neighbors();

                for direction in node_outgoing_directions {
                    let neighbor_index = node_neighbor_indices.get(direction);

                    // the outgoing direction for the current node is the incoming direction for the
                    // neighbor
                    let current_incoming_direction = direction.opposite();

                    let neighbor_incoming_directions = neighbor_index
                        .index_array_unchecked_mut(&mut self.bfs_cached_state.incoming_directions);

                    // enqueue only if the node has not yet been enqueued, avoiding duplicates
                    let should_enqueue = neighbor_incoming_directions.is_empty();

                    neighbor_incoming_directions.add(current_incoming_direction);

                    unsafe {
                        write_queue_ref
                            .push_conditionally_unchecked(neighbor_index, should_enqueue);
                    }
                }
            }

            read_queue_ref.reset();
            swap(&mut read_queue_ref, &mut write_queue_ref);

            self.bfs_cached_state.reset();
        }
    }

    pub fn set_section(
        &mut self,
        section_coord: i32x3,
        has_geometry: bool,
        visibility_data: VisibilityData,
    ) {
        let local_coord = section_coord.cast::<u8>();
        let index = LocalNodeIndex::<0>::pack(local_coord);

        self.section_has_geometry_bits.set(index, has_geometry);
        *index.index_array_unchecked_mut(&mut self.section_visibility_direction_sets) =
            visibility_data;
    }

    pub fn remove_section(&mut self, section_coord: i32x3) {
        self.set_section(section_coord, false, Default::default());
    }
}
