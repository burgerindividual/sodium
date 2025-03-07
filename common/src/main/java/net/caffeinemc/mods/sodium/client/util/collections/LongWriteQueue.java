package net.caffeinemc.mods.sodium.client.util.collections;

public interface LongWriteQueue {
    void ensureCapacity(int numElements);

    void enqueue(long element);
}
