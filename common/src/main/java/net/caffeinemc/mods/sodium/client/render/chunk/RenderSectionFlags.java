package net.caffeinemc.mods.sodium.client.render.chunk;

public class RenderSectionFlags {
    public static final byte HAS_BLOCK_GEOMETRY   = 0;
    public static final byte HAS_BLOCK_ENTITIES   = 1;
    public static final byte HAS_ANIMATED_SPRITES = 2;

    // Special Values
    public static final byte EMPTY = 0;
    public static final byte UNINITIALIZED = 0b1000000;
    public static final byte UNBUILT       = 0b0100000;

    public static boolean isBuilt(byte flags) {
        // this assures that the flags are neither set to UNBUILT nor UNINITIALIZED
        return flags < UNBUILT;
    }
}
