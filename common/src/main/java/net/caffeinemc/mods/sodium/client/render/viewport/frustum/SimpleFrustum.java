package net.caffeinemc.mods.sodium.client.render.viewport.frustum;

import net.caffeinemc.mods.sodium.ffi.NativeFrustum;
import net.caffeinemc.mods.sodium.mixin.core.render.frustum.FrustumIntersectionAccessor;
import org.joml.FrustumIntersection;
import org.joml.Vector4f;

public final class SimpleFrustum implements Frustum, NativeFrustum {
    private final FrustumIntersection frustum;

    public SimpleFrustum(FrustumIntersection frustumIntersection) {
        this.frustum = frustumIntersection;
    }

    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return this.frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public Vector4f[] getPlanes() {
        return ((FrustumIntersectionAccessor) this.frustum).getPlanes();
    }
}
