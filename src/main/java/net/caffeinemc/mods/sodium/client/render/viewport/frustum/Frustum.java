package net.caffeinemc.mods.sodium.client.render.viewport.frustum;

public interface Frustum {
    boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);

    // /**
    //  * @return a 4x6 matrix of floats, where each array of 4 floats represents a plane of the frustum.
    //  */
    // float[][] getPlanes();
}
