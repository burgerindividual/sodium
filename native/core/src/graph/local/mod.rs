pub mod coord;

use core::mem::transmute;

use core_simd::simd::*;
use std_float::StdFloat;

use crate::graph::local::coord::LocalNodeIndex;
use crate::graph::*;
use crate::region::REGION_COORD_SHIFT;

pub struct LocalCoordContext {
    frustum: LocalFrustum,

    // the camera coords relative to the local origin, which is the (0, 0, 0) point of the
    // 256x256x256 section (4096x4096x4096 block) cube we hold the section data in.
    pub camera_coords: f32x3,
    pub camera_section_coords: LocalNodeCoords<0>,
    pub camera_section_index: LocalNodeIndex<0>,

    pub origin_global_region_offset: i32x3,

    fog_distance_squared: f32,

    world_bottom_section_y: u8,
    world_top_section_y: u8,

    // this is the index that encompasses the corner of the view distance bounding box where the
    // coordinate for each axis is closest to negative infinity, and truncated to the origin of the
    // level 3 node it's contained in.
    pub iter_start_index: LocalNodeIndex<3>,
    pub level_3_node_iter_counts: LocalNodeCoords<3>,
    pub iter_start_section_coords: LocalNodeCoords<0>,
    pub iter_overflow_offset: f32x3,
    pub iter_underflow_offset: f32x3,
}

impl LocalCoordContext {
    const Y_ADD_SECTIONS: u8 = 128;
    const Y_ADD_BLOCKS: f64 = 2048.0;

    pub fn new(
        frustum_planes: [f32x6; 4],
        camera_global_coords: f64x3,
        search_distance: f32,
        world_bottom_section_y: i8,
        world_top_section_y: i8,
    ) -> Self {
        // this should never be negative, and we want to truncate (supposedly)
        let section_view_distance = (search_distance / 16.0) as u8;

        debug_assert!(
            section_view_distance <= MAX_VIEW_DISTANCE,
            "View distances above 127 are not supported"
        );

        // convert Ys from -2048..2047 to 0..4095
        let world_bottom_section_y =
            (world_bottom_section_y as u8).wrapping_add(Self::Y_ADD_SECTIONS);
        let world_top_section_y = (world_top_section_y as u8).wrapping_add(Self::Y_ADD_SECTIONS);

        let frustum = LocalFrustum::new(frustum_planes);
        // TODO: this seems to be wrong, but then why is y rendering still relative to camera pos?
        // frustum.plane_ys += Simd::splat(Self::Y_ADD_BLOCKS as f32);

        let camera_coords = (camera_global_coords + f64x3::from_xyz(0.0, Self::Y_ADD_BLOCKS, 0.0))
            .rem_euclid(f64x3::splat(4096.0))
            .cast::<f32>();

        // shift right by 4 (divide by 16) to go from block to section coords
        let camera_global_section_coords =
            camera_global_coords.floor().cast::<i32>() >> i32x3::splat(4);

        // the cast to u8 puts it in the local coordinate space by effectively doing a
        // mod 256
        let camera_section_coords = LocalNodeCoords::<0>::from_raw(
            camera_global_section_coords.cast::<u8>() + u8x3::from_xyz(0, Self::Y_ADD_SECTIONS, 0),
        );
        let camera_section_index = LocalNodeIndex::pack(camera_section_coords);

        // this includes the height shift back down by 32 regions
        let origin_global_region_offset = (camera_global_section_coords
            - camera_section_coords.into_raw().cast::<i32>())
            >> REGION_COORD_SHIFT.cast::<i32>();

        let iter_start_section_coords_tmp = camera_section_coords.into_raw().cast::<i32>()
            - i32x3::splat(section_view_distance as i32);

        let iter_underflow_offset = iter_start_section_coords_tmp
            .simd_lt(i32x3::splat(0))
            .select(f32x3::splat(-4096.0), f32x3::splat(0.0));

        let mut iter_start_section_coords_tmp = iter_start_section_coords_tmp.cast::<u8>();
        iter_start_section_coords_tmp[Y] = world_bottom_section_y;

        let iter_start_node_coords =
            LocalNodeCoords::<0>::from_raw(iter_start_section_coords_tmp).into_level::<3>();
        let iter_start_index = LocalNodeIndex::pack(iter_start_node_coords);
        let iter_start_section_coords = iter_start_node_coords.into_level::<0>();

        let view_cube_length = (section_view_distance * 2) + 1;

        let world_height = world_top_section_y - world_bottom_section_y;

        debug_assert!(
            world_height <= MAX_WORLD_HEIGHT,
            "World heights larger than {} sections are not supported",
            MAX_WORLD_HEIGHT
        );

        // the add is done to make sure we round up during truncation
        let level_3_node_iter_counts =
            (LocalNodeCoords::<0>::from_xyz(view_cube_length, world_height, view_cube_length)
                + LocalNodeCoords::<0>::from_raw(Simd::splat(LocalNodeCoords::<3>::length() - 1)))
            .into_level::<3>();

        let iter_end_section_coords_tmp = iter_start_section_coords.into_raw().cast::<i32>()
            + level_3_node_iter_counts
                .into_level::<0>()
                .into_raw()
                .cast::<i32>();

        let iter_overflow_offset = iter_end_section_coords_tmp
            .simd_gt(Simd::splat(255))
            .select(f32x3::splat(4096.0), f32x3::splat(0.0));

        let fog_distance_squared = search_distance * search_distance;

        Self {
            frustum,
            camera_coords,
            camera_section_index,
            camera_section_coords,
            origin_global_region_offset,
            fog_distance_squared,
            world_bottom_section_y,
            world_top_section_y,
            iter_start_index,
            level_3_node_iter_counts,
            iter_start_section_coords,
            iter_overflow_offset,
            iter_underflow_offset,
        }
    }

    pub fn test_node<const LEVEL: u8>(
        &self,
        local_node_index: LocalNodeIndex<LEVEL>,
    ) -> BoundsCheckResult {
        let local_node_coords = local_node_index.unpack();

        let bounds = self.node_get_local_bounds(local_node_coords);

        let mut result = self.bounds_inside_fog::<LEVEL>(&bounds);
        // should continue doing tests if we're already known to be outside? or is that
        // more of a detriment?
        result = result.combine(self.frustum.test_local_bounding_box(&bounds));
        result = result.combine(self.bounds_inside_world_height(local_node_coords));

        result
    }

    fn bounds_inside_world_height<const LEVEL: u8>(
        &self,
        local_node_coords: LocalNodeCoords<LEVEL>,
    ) -> BoundsCheckResult {
        let node_min_y = (local_node_coords.y() as u32) << LEVEL;
        let node_max_y = node_min_y + (1 << LEVEL) - 1;
        let world_min_y = self.world_bottom_section_y as u32;
        let world_max_y = self.world_top_section_y as u32;

        let min_in_bounds = (node_min_y >= world_min_y) & (node_min_y <= world_max_y);
        let max_in_bounds = (node_max_y >= world_min_y) & (node_max_y <= world_max_y);

        // in normal circumstances, this really shouldn't ever return OUTSIDE
        unsafe { BoundsCheckResult::from_int_unchecked(min_in_bounds as u8 + max_in_bounds as u8) }
    }

    // this only cares about the x and z axis
    fn bounds_inside_fog<const LEVEL: u8>(
        &self,
        local_bounds: &FrustumBoundingBox,
    ) -> BoundsCheckResult {
        // find closest to (0,0) because the bounding box coordinates are relative to
        // the camera
        let closest_in_chunk = local_bounds
            .min
            .abs()
            .simd_lt(local_bounds.max.abs())
            .select(local_bounds.min, local_bounds.max);

        let furthest_in_chunk = {
            let adjusted_int = unsafe {
                // minus 1 if the input is negative
                // SAFETY: values will never be out of range
                closest_in_chunk.to_int_unchecked::<i32>()
                    + (closest_in_chunk.to_bits().cast::<i32>() >> Simd::splat(31))
            };

            let add_bit = Simd::splat(0b1000 << LEVEL);
            // additive is nonzero if the bit is *not* set
            let additive = ((adjusted_int & add_bit) ^ add_bit) << Simd::splat(1);

            // set the bottom (4 + LEVEL) bits to 0
            let bitmask = Simd::splat(-1 << (4 + LEVEL));

            ((adjusted_int + additive) & bitmask).cast::<f32>()
        };

        // combine operations and single out the XZ lanes on both extrema from here.
        // also, we don't have to subtract from the camera pos because the bounds are
        // already relative to it
        let differences = simd_swizzle!(
            closest_in_chunk,
            furthest_in_chunk,
            [First(X), First(Z), Second(X), Second(Z)]
        );
        let differences_squared = differences * differences;

        // add Xs and Zs
        let distances_squared =
            simd_swizzle!(differences_squared, [0, 2]) + simd_swizzle!(differences_squared, [1, 3]);

        // janky way of calculating the result from the two points
        unsafe {
            BoundsCheckResult::from_int_unchecked(
                distances_squared
                    .simd_lt(f32x2::splat(self.fog_distance_squared))
                    .select(u32x2::splat(1), u32x2::splat(0))
                    .reduce_sum() as u8,
            )
        }
    }

    fn node_get_local_bounds<const LEVEL: u8>(
        &self,
        local_node_coords: LocalNodeCoords<LEVEL>,
    ) -> FrustumBoundingBox {
        let raw_section_pos = local_node_coords.into_level::<0>().into_raw();
        let converted_pos = raw_section_pos.cast::<f32>() * Simd::splat(16.0);

        let min_pos = converted_pos
            + raw_section_pos
                .simd_lt(self.iter_start_section_coords.into_raw())
                .cast()
                // TODO: psure this just aint right lol
                .select(self.iter_overflow_offset, self.iter_underflow_offset)
            // TODO: should the y coordinate of the camera be not considered here?
            - self.camera_coords;

        let max_pos = min_pos + Simd::splat((LocalNodeCoords::<LEVEL>::length() * 16) as f32);

        FrustumBoundingBox {
            min: min_pos,
            max: max_pos,
        }
    }

    pub fn get_valid_directions(
        &self,
        local_section_coords: LocalNodeCoords<0>,
    ) -> GraphDirectionSet {
        let negative = local_section_coords
            .into_raw()
            .simd_le(self.camera_section_coords.into_raw());
        let positive = local_section_coords
            .into_raw()
            .simd_ge(self.camera_section_coords.into_raw());

        GraphDirectionSet::from(negative.to_bitmask() | (positive.to_bitmask() << 3))
    }
}

/// When using this, it is expected that coordinates are relative to the camera
/// rather than the world origin.
pub struct LocalFrustum {
    plane_xs: f32x6,
    plane_ys: f32x6,
    plane_zs: f32x6,
    plane_ws: f32x6,
}

impl LocalFrustum {
    pub fn new(planes: [f32x6; 4]) -> Self {
        LocalFrustum {
            plane_xs: planes[0],
            plane_ys: planes[1],
            plane_zs: planes[2],
            plane_ws: planes[3],
        }
    }

    pub fn test_local_bounding_box(&self, bb: &FrustumBoundingBox) -> BoundsCheckResult {
        unsafe {
            // These unsafe mask shenanigans just check if the sign bit is set for each
            // lane. This is faster than doing a manual comparison with
            // something like simd_gt.
            let is_neg_x =
                Mask::from_int_unchecked(self.plane_xs.to_bits().cast::<i32>() >> Simd::splat(31));
            let is_neg_y =
                Mask::from_int_unchecked(self.plane_ys.to_bits().cast::<i32>() >> Simd::splat(31));
            let is_neg_z =
                Mask::from_int_unchecked(self.plane_zs.to_bits().cast::<i32>() >> Simd::splat(31));

            let bb_min_x = Simd::splat(bb.min.x());
            let bb_max_x = Simd::splat(bb.max.x());
            let outside_bounds_x = is_neg_x.select(bb_min_x, bb_max_x);
            let inside_bounds_x = is_neg_x.select(bb_max_x, bb_min_x);

            let bb_min_y = Simd::splat(bb.min.y());
            let bb_max_y = Simd::splat(bb.max.y());
            let outside_bounds_y = is_neg_y.select(bb_min_y, bb_max_y);
            let inside_bounds_y = is_neg_y.select(bb_max_y, bb_min_y);

            let bb_min_z = Simd::splat(bb.min.z());
            let bb_max_z = Simd::splat(bb.max.z());
            let outside_bounds_z = is_neg_z.select(bb_min_z, bb_max_z);
            let inside_bounds_z = is_neg_z.select(bb_max_z, bb_min_z);

            let outside_length_sq = self.plane_xs.fast_fma(
                outside_bounds_x,
                self.plane_ys
                    .fast_fma(outside_bounds_y, self.plane_zs * outside_bounds_z),
            );

            let inside_length_sq = self.plane_xs.fast_fma(
                inside_bounds_x,
                self.plane_ys
                    .fast_fma(inside_bounds_y, self.plane_zs * inside_bounds_z),
            );

            // if any outside lengths are greater than -w, return OUTSIDE
            // if all inside lengths are greater than -w, return INSIDE
            // otherwise, return PARTIAL
            // NOTE: it is impossible for a lane to be both inside and outside at the same
            // time
            let none_outside = outside_length_sq.simd_ge(-self.plane_ws).to_bitmask() == 0b111111;
            let all_inside = inside_length_sq.simd_ge(-self.plane_ws).to_bitmask() == 0b111111;

            BoundsCheckResult::from_int_unchecked(none_outside as u8 + all_inside as u8)
        }
    }
}

#[repr(u8)]
pub enum BoundsCheckResult {
    Outside = 0,
    Partial = 1,
    Inside = 2,
}

impl BoundsCheckResult {
    /// SAFETY: if out of bounds, this will fail to assert in debug mode
    pub unsafe fn from_int_unchecked(val: u8) -> Self {
        debug_assert!(val <= 2);
        transmute(val)
    }

    pub fn combine(self, rhs: Self) -> Self {
        // SAFETY: given 2 valid inputs, the result will always be valid
        unsafe { Self::from_int_unchecked((self as u8).min(rhs as u8)) }
    }
}

/// Relative to the camera position
pub struct FrustumBoundingBox {
    pub min: f32x3,
    pub max: f32x3,
}
