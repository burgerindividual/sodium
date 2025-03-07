package net.caffeinemc.mods.sodium.ffi;

import org.joml.Vector4f;

public interface NativeFrustum {
    /**
     * @return An array of 6 planes representing the frustum
     */
    Vector4f[] getPlanes();
}
