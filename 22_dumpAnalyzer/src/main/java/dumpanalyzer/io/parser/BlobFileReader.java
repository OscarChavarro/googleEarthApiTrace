package dumpanalyzer.io.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

final class BlobFileReader {
    private BlobFileReader() {
    }

    static boolean exists(Path path) {
        Path resolved = resolveReadablePath(path);
        return resolved != null && Files.exists(resolved);
    }

    static long logicalSize(Path path) {
        byte[] data = readAllBytes(path);
        return data == null ? -1L : data.length;
    }

    static byte[] readAllBytes(Path path) {
        Path resolved = resolveReadablePath(path);
        if (resolved == null) {
            return null;
        }
        try {
            if (isBzip2Path(resolved)) {
                return readBzip2AllBytes(resolved);
            }
            return Files.readAllBytes(resolved);
        }
        catch (IOException e) {
            return null;
        }
    }

    static Path resolveReadablePath(Path path) {
        if (path == null) {
            return null;
        }
        if (Files.exists(path)) {
            return path;
        }
        if (isBzip2Path(path)) {
            Path raw = rawPathForBzip2(path);
            if (raw != null && Files.exists(raw)) {
                return raw;
            }
            return path;
        }
        Path compressed = Path.of(path.toString() + ".bz2");
        if (Files.exists(compressed)) {
            return compressed;
        }
        return path;
    }

    private static byte[] readBzip2AllBytes(Path path) throws IOException {
        try (
            InputStream fileIn = Files.newInputStream(path);
            BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(fileIn);
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            bzIn.transferTo(out);
            return out.toByteArray();
        }
    }

    private static boolean isBzip2Path(Path path) {
        return path != null && path.toString().endsWith(".bz2");
    }

    private static Path rawPathForBzip2(Path path) {
        String text = path.toString();
        if (!text.endsWith(".bz2")) {
            return null;
        }
        return Path.of(text.substring(0, text.length() - 4));
    }
}
