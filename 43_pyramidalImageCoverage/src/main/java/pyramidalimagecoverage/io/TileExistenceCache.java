package pyramidalimagecoverage.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.TileAddress;
import pyramidalimagecoverage.model.TileRecord;

/**
 * Append-only knowledge about tiles which have existed in the immutable top-level pyramid.
 *
 * <p>The flags are sparse rather than a dense {@code 2^level x 2^level} bitmap: a dense
 * level-30 bitmap would itself be impractically large. Each stored 64-bit position is an
 * affirmative existence flag. Missing positions are deliberately not persisted because a tile
 * which is absent now may be produced by a later capture.</p>
 */
final class TileExistenceCache {
    static final Path PRODUCTION_PYRAMID = Path.of("/samples/datasets/googleEarth/toplevel");
    static final Path PRODUCTION_CACHE_FOLDER = Path.of("/media/ramdisk");
    static final String FILE_PREFIX = "43_pyramidalImageCoverage-toplevel-level-";

    private static final int MAGIC = 0x50494346; // PICF
    private static final int VERSION = 1;
    private static final Pattern CACHE_FILE_PATTERN = Pattern.compile(
        Pattern.quote(FILE_PREFIX) + "(\\d+)\\.flags"
    );

    private final long[][] flagsByLevel = new long[PyramidCatalog.MAX_ADDRESSABLE_DEPTH + 1][];
    private final LongAccumulator[] additionsByLevel =
        new LongAccumulator[PyramidCatalog.MAX_ADDRESSABLE_DEPTH + 1];
    private final BitSet dirtyLevels = new BitSet(PyramidCatalog.MAX_ADDRESSABLE_DEPTH + 1);
    private final Path pyramid;
    private final Path cacheFolder;
    private final boolean enabled;

    TileExistenceCache(Path rootFolder, Path cacheEligiblePyramid, Path cacheFolder) {
        this.pyramid = rootFolder.toAbsolutePath().normalize();
        this.cacheFolder = cacheFolder.toAbsolutePath().normalize();
        this.enabled = pyramid.equals(cacheEligiblePyramid.toAbsolutePath().normalize());
        for (int level = 0; level < flagsByLevel.length; level++) {
            flagsByLevel[level] = new long[0];
            additionsByLevel[level] = new LongAccumulator();
        }
    }

    boolean enabled() {
        return enabled;
    }

    int loadInto(PyramidCatalog catalog) throws IOException {
        if (!enabled || !Files.isDirectory(cacheFolder)) {
            return 0;
        }
        int loaded = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(
            cacheFolder, FILE_PREFIX + "*.flags"
        )) {
            for (Path file : files) {
                Matcher matcher = CACHE_FILE_PATTERN.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                int level;
                try {
                    level = Integer.parseInt(matcher.group(1));
                }
                catch (NumberFormatException ex) {
                    continue;
                }
                if (level < 0 || level > PyramidCatalog.MAX_ADDRESSABLE_DEPTH) {
                    continue;
                }
                try {
                    loaded += loadLevel(file, level, catalog);
                }
                catch (IOException ex) {
                    System.err.println(
                        "WARNING: Ignoring invalid tile existence cache " + file + ": "
                            + ex.getMessage()
                    );
                }
            }
        }
        return loaded;
    }

    boolean contains(TileAddress address) {
        return enabled
            && Arrays.binarySearch(flagsByLevel[address.depth()], position(address)) >= 0;
    }

    void remember(TileAddress address) {
        if (enabled && !contains(address)) {
            additionsByLevel[address.depth()].add(position(address));
            synchronized (dirtyLevels) {
                dirtyLevels.set(address.depth());
            }
        }
    }

    void flush() throws IOException {
        synchronized (dirtyLevels) {
            if (!enabled || dirtyLevels.isEmpty()) {
                return;
            }
        }
        Files.createDirectories(cacheFolder);
        for (int level = 0; level < flagsByLevel.length; level++) {
            boolean dirty;
            synchronized (dirtyLevels) {
                dirty = dirtyLevels.get(level);
                dirtyLevels.clear(level);
            }
            if (dirty) {
                mergeAdditions(level);
                writeLevel(level);
            }
        }
    }

    private void mergeAdditions(int level) {
        long[] additions = additionsByLevel[level].take();
        if (additions.length == 0) {
            return;
        }
        long[] previous = flagsByLevel[level];
        long[] merged = Arrays.copyOf(previous, previous.length + additions.length);
        System.arraycopy(additions, 0, merged, previous.length, additions.length);
        Arrays.sort(merged);
        int unique = 0;
        for (long flag : merged) {
            if (unique == 0 || merged[unique - 1] != flag) {
                merged[unique++] = flag;
            }
        }
        flagsByLevel[level] = Arrays.copyOf(merged, unique);
    }

    private int loadLevel(Path file, int expectedLevel, PyramidCatalog catalog) throws IOException {
        long[] flags;
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(file))
        )) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("unsupported cache header in " + file);
            }
            int level = input.readInt();
            int count = input.readInt();
            long expectedBytes = 16L + (long) count * Long.BYTES;
            if (level != expectedLevel || count < 0 || Files.size(file) != expectedBytes) {
                throw new IOException("invalid level or flag count in " + file);
            }
            long maximumPosition = maximumPosition(level);
            flags = new long[count];
            for (int index = 0; index < count; index++) {
                long position = input.readLong();
                if (position < 0 || position > maximumPosition) {
                    throw new IOException("invalid tile flag in " + file);
                }
                flags[index] = position;
            }
            if (input.read() != -1) {
                throw new IOException("trailing data in " + file);
            }
        }
        catch (EOFException ex) {
            throw new IOException("truncated cache file " + file, ex);
        }
        Arrays.sort(flags);
        flagsByLevel[expectedLevel] = deduplicate(flags, flags.length);
        int loaded = 0;
        for (long position : flagsByLevel[expectedLevel]) {
            TileAddress address = address(expectedLevel, position);
            Path image = pyramid.resolve(relativePathFor(address));
            if (catalog.add(new TileRecord(address, image))) {
                loaded++;
            }
        }
        return loaded;
    }

    private void writeLevel(int level) throws IOException {
        Path target = cacheFile(level);
        Path temporary = Files.createTempFile(cacheFolder, FILE_PREFIX + level + "-", ".tmp");
        boolean moved = false;
        try {
            long[] flags = flagsByLevel[level];
            try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temporary))
            )) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(level);
                output.writeInt(flags.length);
                for (long position : flags) {
                    output.writeLong(position);
                }
            }
            try {
                Files.move(
                    temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                );
            }
            catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        }
        finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private Path cacheFile(int level) {
        return cacheFolder.resolve(FILE_PREFIX + level + ".flags");
    }

    static Path relativePathFor(TileAddress address) {
        String quadKey = address.quadKey();
        Path path = Path.of(quadKey + ".png");
        for (int index = quadKey.length() - 1; index >= 1; index--) {
            path = Path.of(String.valueOf(quadKey.charAt(index)), path.toString());
        }
        return path;
    }

    private static long position(TileAddress address) {
        return ((long) address.southRow() << address.depth()) | address.column();
    }

    private static TileAddress address(int level, long position) {
        long coordinateMask = (1L << level) - 1L;
        int column = (int) (position & coordinateMask);
        int southRow = (int) (position >>> level);
        return TileAddress.fromCoordinates(level, column, southRow);
    }

    private static long maximumPosition(int level) {
        return level == 0 ? 0 : (1L << (level * 2)) - 1L;
    }

    private static long[] deduplicate(long[] sorted, int length) {
        int unique = 0;
        for (int index = 0; index < length; index++) {
            if (unique == 0 || sorted[unique - 1] != sorted[index]) {
                sorted[unique++] = sorted[index];
            }
        }
        return Arrays.copyOf(sorted, unique);
    }

    /** Primitive concurrent buffer, avoiding one boxed Long allocation per tile. */
    private static final class LongAccumulator {
        private long[] values = new long[16];
        private int size;

        synchronized void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        synchronized long[] take() {
            long[] result = Arrays.copyOf(values, size);
            size = 0;
            return result;
        }
    }
}
