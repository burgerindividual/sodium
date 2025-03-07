package net.caffeinemc.mods.sodium.client.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

public class SectionPosUtil {
    public static int originCoord(int sectionCoord) {
        return sectionCoord << 4;
    }

    public static int centerCoord(int sectionCoord) {
        return originCoord(sectionCoord) + 8;
    }

    /**
     * @return The squared distance from the center of a section to the center of the block position given by
     * {@param blockPos}
     */
    public static float getSquaredDistance(int sectionX, int sectionY, int sectionZ, BlockPos blockPos) {
        return getSquaredDistance(
                sectionX,
                sectionY,
                sectionZ,
                blockPos.getX() + 0.5f,
                blockPos.getY() + 0.5f,
                blockPos.getZ() + 0.5f
        );
    }

    /**
     * @return The squared distance from the center of a section to a given block position
     */
    public static float getSquaredDistance(int sectionX, int sectionY, int sectionZ, float blockX, float blockY, float blockZ) {
        float xDist = blockX - centerCoord(sectionX);
        float yDist = blockY - centerCoord(sectionY);
        float zDist = blockZ - centerCoord(sectionZ);

        return (xDist * xDist) + (yDist * yDist) + (zDist * zDist);
    }

    public static String packedToString(long sectionPos) {
        return String.format(
                "X: %d, Y: %d, Z: %d",
                SectionPos.x(sectionPos),
                SectionPos.y(sectionPos),
                SectionPos.z(sectionPos)
        );
    }
}
