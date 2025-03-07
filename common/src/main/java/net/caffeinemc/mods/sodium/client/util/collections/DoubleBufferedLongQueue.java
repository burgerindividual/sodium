package net.caffeinemc.mods.sodium.client.util.collections;

public final class DoubleBufferedLongQueue {
    private LongQueueImpl read, write;

    public DoubleBufferedLongQueue() {
        this.read = new LongQueueImpl();
        this.write = new LongQueueImpl();
    }

    public boolean flip() {
        if (this.write.isEmpty()) {
            return false;
        }

        var tmp = this.read;
        this.read = this.write;
        this.write = tmp;

        this.write.clear();

        return true;
    }

    public void reset() {
        this.read.clear();
        this.write.clear();
    }

    public LongReadQueue read() {
        return this.read;
    }

    public LongWriteQueue write() {
        return this.write;
    }

    private static final class LongQueueImpl implements LongReadQueue, LongWriteQueue {
        private long[] elements;
        private int readIndex, writeIndex;

        LongQueueImpl() {
            this(256);
        }

        LongQueueImpl(int capacity) {
            this.elements = new long[capacity];
        }

        @Override
        public void ensureCapacity(int numElements) {
            int len = this.writeIndex + numElements;

            if (len > this.elements.length) {
                this.grow(len);
            }
        }

        @Override
        public long dequeue() {
            return this.elements[this.readIndex++];
        }

        @Override
        public boolean isEmpty() {
            return this.readIndex == this.writeIndex;
        }

        @Override
        public void enqueue(long element) {
            if (this.writeIndex >= this.elements.length) {
                this.resize(this.writeIndex + 1);
            }

            this.elements[this.writeIndex++] = element;
        }


        public void clear() {
            this.readIndex = 0;
            this.writeIndex = 0;
        }

        private void grow(int minimumSize) {
            this.resize(getNextSize(minimumSize, this.elements.length));
        }

        private void resize(int length) {
            long[] elements = new long[length];
            System.arraycopy(this.elements, 0, elements, 0, this.writeIndex);

            this.elements = elements;
        }

        private static int getNextSize(int minimumSize, int currentSize) {
            return Math.max(minimumSize, currentSize << 1);
        }
    }
}
