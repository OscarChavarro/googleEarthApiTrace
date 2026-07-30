package demresampler.manifest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;

public final class LevelManifest implements AutoCloseable {
    private static final int COORDINATE_MAGIC = 0x3544434d;
    private static final int STATE_MAGIC = 0x3554534d;
    private static final int FORMAT_VERSION = 1;
    private static final long CHECKPOINT_SECONDS = 30;

    private final int level;
    private final Path coordinatesPath;
    private final Path statePath;
    private final long[] coordinates;
    private final AtomicLongArray processed;
    private final AtomicLongArray present;
    private final AtomicLongArray halo;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final ScheduledExecutorService checkpointer;

    public LevelManifest(Path directory, int level, Set<Long> expectedCoordinates)
        throws IOException {
        this.level = level;
        coordinatesPath = directory.resolve("level-" + level + "-coordinates.bin");
        statePath = directory.resolve("level-" + level + "-state.bin");
        coordinates = expectedCoordinates.stream().mapToLong(Long::longValue).sorted().toArray();
        Files.createDirectories(directory);

        if (!coordinateFileMatches()) {
            writeCoordinates();
            Files.deleteIfExists(statePath);
        }

        int wordCount = wordCount(coordinates.length);
        long[][] state = readState(wordCount);
        if (state == null) {
            state = emptyState(wordCount);
            dirty.set(true);
        }
        processed = new AtomicLongArray(state[0]);
        present = new AtomicLongArray(state[1]);
        halo = new AtomicLongArray(state[2]);

        checkpointer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                runnable, "level-" + level + "-manifest-checkpoint");
            thread.setDaemon(true);
            return thread;
        });
        checkpointer.scheduleAtFixedRate(
            this::checkpointQuietly,
            CHECKPOINT_SECONDS,
            CHECKPOINT_SECONDS,
            TimeUnit.SECONDS);
    }

    public int level() {
        return level;
    }

    public int coordinateCount() {
        return coordinates.length;
    }

    public long[] unprocessedCoordinates() {
        return selectCoordinates(processed, false);
    }

    public long[] incompleteHaloCoordinates() {
        long[] result = new long[presentCount()];
        int count = 0;
        for (int index = 0; index < coordinates.length; index++) {
            if (get(present, index) && !get(halo, index)) {
                result[count++] = coordinates[index];
            }
        }
        return count == result.length ? result : Arrays.copyOf(result, count);
    }

    public Set<Long> presentCoordinateSet() {
        Set<Long> result = new HashSet<>(Math.max(16, presentCount() * 4 / 3));
        for (int index = 0; index < coordinates.length; index++) {
            if (get(present, index)) {
                result.add(coordinates[index]);
            }
        }
        return result;
    }

    public int presentCount() {
        return bitCount(present);
    }

    public boolean isPresent(long packedCoordinates) {
        int index = Arrays.binarySearch(coordinates, packedCoordinates);
        return index >= 0 && get(present, index);
    }

    public void markCoreProcessed(long packedCoordinates, boolean hasData) {
        int index = requireIndex(packedCoordinates);
        if (hasData) {
            set(present, index);
        }
        set(processed, index);
        dirty.set(true);
    }

    public void markHaloComplete(long packedCoordinates) {
        int index = requireIndex(packedCoordinates);
        if (!get(present, index)) {
            throw new IllegalStateException(
                "Cannot complete halo for an absent tile at level " + level);
        }
        set(halo, index);
        dirty.set(true);
    }

    public synchronized void checkpoint() throws IOException {
        if (!dirty.getAndSet(false) && Files.isRegularFile(statePath)) {
            return;
        }
        Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(STATE_MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeInt(level);
                output.writeInt(coordinates.length);
                writeWords(output, processed);
                writeWords(output, present);
                writeWords(output, halo);
            }
            atomicReplace(temporary, statePath);
        } catch (IOException exception) {
            dirty.set(true);
            throw exception;
        }
    }

    private boolean coordinateFileMatches() {
        if (!Files.isRegularFile(coordinatesPath)) {
            return false;
        }
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(coordinatesPath)))) {
            if (input.readInt() != COORDINATE_MAGIC
                || input.readInt() != FORMAT_VERSION
                || input.readInt() != level
                || input.readInt() != coordinates.length) {
                return false;
            }
            for (long coordinate : coordinates) {
                if (input.readLong() != coordinate) {
                    return false;
                }
            }
            return input.read() == -1;
        } catch (IOException exception) {
            return false;
        }
    }

    private void writeCoordinates() throws IOException {
        Path temporary =
            coordinatesPath.resolveSibling(coordinatesPath.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(
            new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            output.writeInt(COORDINATE_MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(level);
            output.writeInt(coordinates.length);
            for (long coordinate : coordinates) {
                output.writeLong(coordinate);
            }
        }
        atomicReplace(temporary, coordinatesPath);
    }

    private long[][] readState(int wordCount) {
        if (!Files.isRegularFile(statePath)) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(statePath)))) {
            if (input.readInt() != STATE_MAGIC
                || input.readInt() != FORMAT_VERSION
                || input.readInt() != level
                || input.readInt() != coordinates.length) {
                return null;
            }
            long[][] result = new long[][] {
                readWords(input, wordCount),
                readWords(input, wordCount),
                readWords(input, wordCount)
            };
            return input.read() == -1 ? result : null;
        } catch (EOFException exception) {
            return null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static long[][] emptyState(int wordCount) {
        return new long[][] {
            new long[wordCount],
            new long[wordCount],
            new long[wordCount]
        };
    }

    private long[] selectCoordinates(AtomicLongArray bits, boolean selectedValue) {
        int selectedCount = selectedValue
            ? bitCount(bits)
            : coordinates.length - bitCount(bits);
        long[] result = new long[selectedCount];
        int count = 0;
        for (int index = 0; index < coordinates.length; index++) {
            if (get(bits, index) == selectedValue) {
                result[count++] = coordinates[index];
            }
        }
        return result;
    }

    private int requireIndex(long packedCoordinates) {
        int index = Arrays.binarySearch(coordinates, packedCoordinates);
        if (index < 0) {
            throw new IllegalArgumentException(
                "Coordinate is not part of level " + level + " manifest");
        }
        return index;
    }

    private static int wordCount(int bitCount) {
        return (bitCount + Long.SIZE - 1) / Long.SIZE;
    }

    private static boolean get(AtomicLongArray words, int index) {
        long mask = 1L << (index & 63);
        return (words.get(index >>> 6) & mask) != 0;
    }

    private static void set(AtomicLongArray words, int index) {
        int word = index >>> 6;
        long mask = 1L << (index & 63);
        words.getAndUpdate(word, value -> value | mask);
    }

    private int bitCount(AtomicLongArray words) {
        int result = 0;
        for (int index = 0; index < words.length(); index++) {
            result += Long.bitCount(words.get(index));
        }
        int excess = words.length() * Long.SIZE - coordinates.length;
        if (excess > 0 && words.length() > 0) {
            long validMask = -1L >>> excess;
            long finalWord = words.get(words.length() - 1);
            result -= Long.bitCount(finalWord & ~validMask);
        }
        return result;
    }

    private static void writeWords(DataOutputStream output, AtomicLongArray words)
        throws IOException {
        output.writeInt(words.length());
        for (int index = 0; index < words.length(); index++) {
            output.writeLong(words.get(index));
        }
    }

    private static long[] readWords(DataInputStream input, int expectedCount)
        throws IOException {
        int count = input.readInt();
        if (count != expectedCount) {
            throw new IOException("Invalid manifest bitset length");
        }
        long[] result = new long[count];
        for (int index = 0; index < count; index++) {
            result[index] = input.readLong();
        }
        return result;
    }

    private static void atomicReplace(Path temporary, Path destination)
        throws IOException {
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void checkpointQuietly() {
        try {
            checkpoint();
        } catch (IOException exception) {
            System.err.println(
                "WARNING: could not checkpoint level " + level + " manifest: "
                    + exception.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        checkpointer.shutdownNow();
        checkpoint();
    }
}
