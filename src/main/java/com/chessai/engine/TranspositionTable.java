package com.chessai.engine;

/** A fixed-size, direct-mapped transposition table. */
public final class TranspositionTable {
    public static final int EXACT = 0;
    public static final int LOWER = 1;
    public static final int UPPER = 2;

    public static final class Entry {
        public long key;
        public int depth;
        public int score;
        public int flag;
        public int move;
        public boolean valid;
    }

    private final Entry[] entries;
    private final int mask;

    public TranspositionTable(int megabytes) {
        int bytes = Math.max(1, megabytes) * 1024 * 1024;
        int count = Integer.highestOneBit(Math.max(1024, bytes / 40));
        entries = new Entry[count];
        for (int i = 0; i < count; i++) {
            entries[i] = new Entry();
        }
        mask = count - 1;
    }

    public Entry probe(long key) {
        Entry entry = entries[(int) (key & mask)];
        if (entry.valid && entry.key == key) {
            return entry;
        }
        return null;
    }

    public void store(long key, int depth, int score, int flag, int move) {
        Entry entry = entries[(int) (key & mask)];
        if (!entry.valid || entry.key != key || depth >= entry.depth) {
            entry.valid = true;
            entry.key = key;
            entry.depth = depth;
            entry.score = score;
            entry.flag = flag;
            entry.move = move;
        }
    }

    public void clear() {
        for (Entry entry : entries) {
            entry.valid = false;
        }
    }
}
