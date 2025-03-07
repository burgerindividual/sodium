package net.caffeinemc.mods.sodium.client.util.collections;

public interface LongReadQueue {
    long dequeue();

    boolean isEmpty();
}
