package net.caffeinemc.mods.sodium.client.render.chunk;

public class SectionIteration {
    /**
     * start is inclusive, end is exclusive
     */
    public static void iterateSplitsOnAxis(int start, int end, int splitSize, AxisSplitProcessor processor) {
        int splitShift = Integer.numberOfTrailingZeros(splitSize);
        int splitMask = splitSize - 1;
        int current = start;
        int processed = 0;

        while (current < end) {
            int next = Math.min((current + splitSize) & ~splitMask, end);

            int split = current >> splitShift;
            int splitStart = current & splitMask;
            int splitLength = next - current;
            int splitEnd = splitStart + splitLength;
            processor.processSplit(split, splitStart, splitEnd, processed);

            processed += splitLength;
            current = next;
        }
    }

    public interface AxisSplitProcessor {
        /**
         * @param split The split coordinate in the world
         * @param splitStart The start section in the split (inclusive)
         * @param splitEnd The end section in the split (exclusive)
         * @param processed The amount of sections that have been processed since the start of the iteration
         */
        void processSplit(int split, int splitStart, int splitEnd, int processed);
    }
}
