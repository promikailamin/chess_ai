package com.chessai.engine;

import java.util.Arrays;

/** A small reusable array-backed move list. */
public final class MoveList {
    private int[] moves;
    private int size;

    public MoveList() {
        this(256);
    }

    public MoveList(int capacity) {
        moves = new int[Math.max(16, capacity)];
    }

    public void clear() {
        size = 0;
    }

    public void add(int move) {
        if (size == moves.length) {
            moves = Arrays.copyOf(moves, moves.length * 2);
        }
        moves[size++] = move;
    }

    public int size() {
        return size;
    }

    public int get(int index) {
        return moves[index];
    }

    public void set(int index, int move) {
        moves[index] = move;
    }

    public void swap(int a, int b) {
        int tmp = moves[a];
        moves[a] = moves[b];
        moves[b] = tmp;
    }

    public boolean contains(int move) {
        for (int i = 0; i < size; i++) {
            if (moves[i] == move) {
                return true;
            }
        }
        return false;
    }

    public int[] toArray() {
        return Arrays.copyOf(moves, size);
    }
}
