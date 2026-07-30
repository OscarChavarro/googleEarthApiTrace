package demresampler.io;

import demresampler.model.TileAddress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Semaphore;

public final class RawTileIO {
    public static final int CORE_SIDE = 256;
    public static final int STORED_SIDE = CORE_SIDE + 2;
    public static final int CORE_SAMPLE_COUNT = CORE_SIDE * CORE_SIDE;
    public static final int STORED_SAMPLE_COUNT = STORED_SIDE * STORED_SIDE;
    public static final int BYTE_SIZE = STORED_SAMPLE_COUNT * Short.BYTES;
    public static final short NODATA = Short.MIN_VALUE;
    public static final int MAX_CONCURRENT_WRITERS = 4;

    private static final int BORDER_BYTE_SIZE = CORE_SIDE * Short.BYTES;
    private static final Semaphore WRITE_PERMITS =
        new Semaphore(MAX_CONCURRENT_WRITERS, true);

    private RawTileIO() {
    }

    /**
     * Reads a published 258x258 tile, including its one-sample halo.
     */
    public static short[] read(Path pyramidRoot, TileAddress address) throws IOException {
        Path path = address.path(pyramidRoot);
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != BYTE_SIZE) {
            throw invalidSize(path, BYTE_SIZE, bytes.length);
        }
        return decode(bytes, STORED_SAMPLE_COUNT);
    }

    /**
     * Reads only the central 256x256 elevation core of a 258x258 tile.
     */
    public static short[] readCore(Path pyramidRoot, TileAddress address) throws IOException {
        Path path = address.path(pyramidRoot);
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != BYTE_SIZE) {
            throw invalidSize(path, BYTE_SIZE, bytes.length);
        }

        short[] stored = decode(bytes, STORED_SAMPLE_COUNT);
        short[] result = new short[CORE_SAMPLE_COUNT];
        for (int y = 0; y < CORE_SIDE; y++) {
            System.arraycopy(
                stored,
                (y + 1) * STORED_SIDE + 1,
                result,
                y * CORE_SIDE,
                CORE_SIDE);
        }
        return result;
    }

    /**
     * Reads one edge of the central 256x256 core directly from its 258x258 tile.
     */
    public static short[] readCoreBorder(
        Path pyramidRoot,
        TileAddress address,
        Border border
    ) throws IOException {
        Path path = address.path(pyramidRoot);
        long size = Files.size(path);
        if (size != BYTE_SIZE) {
            throw invalidSize(path, BYTE_SIZE, size);
        }

        if (border == Border.NORTH || border == Border.SOUTH) {
            int row = border == Border.NORTH ? 1 : CORE_SIDE;
            ByteBuffer bytes = ByteBuffer.allocate(BORDER_BYTE_SIZE);
            readFully(path, bytes, sampleByteOffset(row, 1));
            short[] result = new short[CORE_SIDE];
            bytes.flip();
            bytes.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(result);
            return result;
        }

        int column = border == Border.WEST ? 1 : CORE_SIDE;
        int spanSamples = (CORE_SIDE - 1) * STORED_SIDE + 1;
        ByteBuffer bytes = ByteBuffer.allocate(spanSamples * Short.BYTES);
        readFully(path, bytes, sampleByteOffset(1, column));
        bytes.flip();
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        short[] result = new short[CORE_SIDE];
        for (int y = 0; y < CORE_SIDE; y++) {
            result[y] = bytes.getShort(y * STORED_SIDE * Short.BYTES);
        }
        return result;
    }

    /**
     * Writes a complete 258x258 tile whose core is populated and whose halo is
     * initially NoData. Halo publication later atomically rewrites this same file.
     */
    public static void writeCore(
        Path pyramidRoot,
        TileAddress address,
        short[] samples
    ) throws IOException {
        requireLength(samples, CORE_SAMPLE_COUNT, "core");
        short[] stored = new short[STORED_SAMPLE_COUNT];
        java.util.Arrays.fill(stored, NODATA);
        for (int y = 0; y < CORE_SIDE; y++) {
            System.arraycopy(
                samples,
                y * CORE_SIDE,
                stored,
                (y + 1) * STORED_SIDE + 1,
                CORE_SIDE);
        }
        write(pyramidRoot, address, stored);
        deleteLegacyEdges(pyramidRoot, address);
    }

    public static void write(
        Path pyramidRoot,
        TileAddress address,
        short[] samples
    ) throws IOException {
        requireLength(samples, STORED_SAMPLE_COUNT, "stored tile");
        ByteBuffer bytes = ByteBuffer.allocate(BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(samples);
        atomicWrite(address.path(pyramidRoot), bytes.array());
    }

    public static boolean isComplete(Path pyramidRoot, TileAddress address) throws IOException {
        Path path = address.path(pyramidRoot);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && attributes.size() == BYTE_SIZE;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    public static boolean isCoreComplete(Path pyramidRoot, TileAddress address) throws IOException {
        return isComplete(pyramidRoot, address);
    }

    public static void deleteLegacyEdges(Path pyramidRoot, TileAddress address)
        throws IOException {
        withWritePermit(() -> Files.deleteIfExists(legacyEdgePath(pyramidRoot, address)));
    }

    public enum Border {
        NORTH,
        SOUTH,
        WEST,
        EAST
    }

    private static long sampleByteOffset(int row, int column) {
        return ((long) row * STORED_SIDE + column) * Short.BYTES;
    }

    private static void readFully(Path path, ByteBuffer target, long position)
        throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (target.hasRemaining()) {
                int count = channel.read(target, position + target.position());
                if (count < 0) {
                    throw new IOException("Unexpected end of raw tile: " + path);
                }
            }
        }
    }

    private static Path legacyEdgePath(Path pyramidRoot, TileAddress address) {
        Path destination = address.path(pyramidRoot);
        return destination.resolveSibling(destination.getFileName() + ".edges");
    }

    private static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        withWritePermit(() -> atomicWriteWithPermit(destination, bytes));
    }

    private static void atomicWriteWithPermit(Path destination, byte[] bytes)
        throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(
            destination.getFileName() + ".tmp-" + Thread.currentThread().getId());
        Files.write(temporary, bytes);
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

    private static void withWritePermit(IoOperation operation) throws IOException {
        boolean acquired = false;
        try {
            WRITE_PERMITS.acquire();
            acquired = true;
            operation.run();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for a tile write permit", exception);
        } finally {
            if (acquired) {
                WRITE_PERMITS.release();
            }
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }

    private static short[] decode(byte[] bytes, int sampleCount) {
        short[] result = new short[sampleCount];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(result);
        return result;
    }

    private static void requireLength(short[] samples, int expected, String description) {
        if (samples.length != expected) {
            throw new IllegalArgumentException(
                "A " + description + " must contain exactly " + expected + " samples");
        }
    }

    private static IOException invalidSize(Path path, long expected, long actual) {
        return new IOException("Invalid raw tile size at " + path
            + ": expected " + expected + ", got " + actual);
    }
}
