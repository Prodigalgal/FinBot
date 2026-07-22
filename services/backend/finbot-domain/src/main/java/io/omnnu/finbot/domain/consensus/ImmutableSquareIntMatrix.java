package io.omnnu.finbot.domain.consensus;

import java.util.Arrays;
import java.util.Objects;

/**
 * Deeply immutable square integer matrix used for Schulze audit artifacts.
 *
 * <p>All construction paths deep-copy input storage. {@link #toArray()} always returns a fresh
 * defensive copy so callers cannot mutate the matrix through shared references.
 */
public final class ImmutableSquareIntMatrix {

    private final int[][] values;

    private ImmutableSquareIntMatrix(int[][] values) {
        this.values = values;
    }

    public static ImmutableSquareIntMatrix zeros(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        return new ImmutableSquareIntMatrix(new int[size][size]);
    }

    /**
     * Deep-copies {@code source} after verifying it is a non-null square matrix.
     */
    public static ImmutableSquareIntMatrix copyOf(int[][] source) {
        Objects.requireNonNull(source, "source");
        var size = source.length;
        var copy = new int[size][size];
        for (var row = 0; row < size; row++) {
            Objects.requireNonNull(source[row], "source row " + row);
            if (source[row].length != size) {
                throw new IllegalArgumentException(
                        "matrix must be square; expected row length "
                                + size
                                + " but row "
                                + row
                                + " has length "
                                + source[row].length);
            }
            for (var column = 0; column < size; column++) {
                if (source[row][column] < 0) {
                    throw new IllegalArgumentException("matrix values must not be negative");
                }
            }
            System.arraycopy(source[row], 0, copy[row], 0, size);
        }
        return new ImmutableSquareIntMatrix(copy);
    }

    public int size() {
        return values.length;
    }

    public int get(int row, int column) {
        requireIndex(row, "row");
        requireIndex(column, "column");
        return values[row][column];
    }

    /** Returns a defensive deep copy of the matrix storage. */
    public int[][] toArray() {
        var size = values.length;
        var copy = new int[size][size];
        for (var row = 0; row < size; row++) {
            System.arraycopy(values[row], 0, copy[row], 0, size);
        }
        return copy;
    }

    private void requireIndex(int index, String name) {
        if (index < 0 || index >= values.length) {
            throw new IndexOutOfBoundsException(
                    name + " " + index + " out of bounds for size " + values.length);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ImmutableSquareIntMatrix that)) {
            return false;
        }
        return Arrays.deepEquals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(values);
    }

    @Override
    public String toString() {
        return "ImmutableSquareIntMatrix" + Arrays.deepToString(values);
    }
}
