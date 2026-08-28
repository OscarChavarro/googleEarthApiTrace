package pyramidalimageexporter.io;

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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import pyramidalimageexporter.diagnostics.PerformanceReport;
import pyramidalimageexporter.processing.content.ContentHashCatalog;

final class ReferencePyramidCache {
    private static final String MAGIC = "PIECACHE1";
    private static final int VERSION = 2;
    private static final String CACHE_FILE = "cache.bin";

    record Snapshot(
        Map<String, String> quadPathByImagePath,
        Map<String, String> sha256ByImagePath,
        Map<String, Long> directoryModifiedNanosByPath
    ) {
        Snapshot(Map<String, String> quadPathByImagePath, Map<String, String> sha256ByImagePath) {
            this(quadPathByImagePath, sha256ByImagePath, Map.of());
        }
    }

    Snapshot loadOrUpdate(Path rootFolder, Function<Path, String> quadPathResolver) {
        Path root = rootFolder.toAbsolutePath().normalize();
        Path cachePath = root.resolve(CACHE_FILE);
        Snapshot cached = PerformanceReport.time("referenceCache.load", () -> read(cachePath, root));
        LinkedHashMap<String, String> quadPaths = new LinkedHashMap<>(cached.quadPathByImagePath());
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>(cached.sha256ByImagePath());
        LinkedHashMap<String, Long> directoryModifiedNanos = new LinkedHashMap<>();
        Map<String, List<Path>> cachedChildDirectories = cachedChildDirectories(cached.directoryModifiedNanosByPath());
        int loadedEntries = quadPaths.size();
        ScanResult scan = PerformanceReport.time(
            "referenceCache.scan",
            () -> scanChangedDirectories(
                root,
                cached.directoryModifiedNanosByPath(),
                cachedChildDirectories,
                directoryModifiedNanos,
                quadPaths,
                hashes,
                quadPathResolver
            )
        );

        PerformanceReport.incrementBy("referenceCache.entries.loaded", loadedEntries);
        PerformanceReport.incrementBy("referenceCache.entries.new", scan.newEntries());
        PerformanceReport.incrementBy("referenceCache.directories.visited", scan.visitedDirectories());
        PerformanceReport.incrementBy("referenceCache.directories.pruned", scan.prunedDirectories());
        PerformanceReport.incrementBy("referenceCache.files.visited", scan.visitedFiles());
        PerformanceReport.incrementBy("referenceCatalog.walk.pngFiles", quadPaths.size());
        if (scan.newEntries() > 0 || loadedEntries == 0 && !quadPaths.isEmpty() || !directoryModifiedNanos.equals(cached.directoryModifiedNanosByPath())) {
            try {
                PerformanceReport.time("referenceCache.write", () -> write(cachePath, root, new Snapshot(quadPaths, hashes, directoryModifiedNanos)));
            }
            catch (ReferenceCacheException ex) {
                System.out.println("ReferencePyramidCache: could not write " + cachePath + ": " + ex.getMessage());
            }
        }
        return new Snapshot(Map.copyOf(quadPaths), Map.copyOf(hashes), Map.copyOf(directoryModifiedNanos));
    }

    private ScanResult scanChangedDirectories(
        Path directory,
        Map<String, Long> cachedDirectoryModifiedNanos,
        Map<String, List<Path>> cachedChildDirectories,
        Map<String, Long> updatedDirectoryModifiedNanos,
        Map<String, String> quadPaths,
        Map<String, String> hashes,
        Function<Path, String> quadPathResolver
    ) {
        String directoryKey = directory.toAbsolutePath().normalize().toString();
        long modifiedNanos = modifiedNanos(directory);
        updatedDirectoryModifiedNanos.put(directoryKey, modifiedNanos);
        Long cachedModifiedNanos = cachedDirectoryModifiedNanos.get(directoryKey);
        if (cachedModifiedNanos != null && cachedModifiedNanos == modifiedNanos) {
            int visitedDirectories = 1;
            int prunedDirectories = 1;
            int newEntries = 0;
            int visitedFiles = 0;
            for (Path childDirectory : cachedChildDirectories.getOrDefault(directoryKey, List.of())) {
                if (!Files.isDirectory(childDirectory)) {
                    continue;
                }
                ScanResult childResult = scanChangedDirectories(
                    childDirectory,
                    cachedDirectoryModifiedNanos,
                    cachedChildDirectories,
                    updatedDirectoryModifiedNanos,
                    quadPaths,
                    hashes,
                    quadPathResolver
                );
                newEntries += childResult.newEntries();
                visitedDirectories += childResult.visitedDirectories();
                prunedDirectories += childResult.prunedDirectories();
                visitedFiles += childResult.visitedFiles();
            }
            return new ScanResult(newEntries, visitedDirectories, prunedDirectories, visitedFiles);
        }

        List<Path> children = list(directory);
        int newEntries = 0;
        int visitedDirectories = 1;
        int prunedDirectories = 0;
        int visitedFiles = 0;
        for (Path child : children) {
            if (Files.isDirectory(child)) {
                ScanResult childResult = scanChangedDirectories(
                    child,
                    cachedDirectoryModifiedNanos,
                    cachedChildDirectories,
                    updatedDirectoryModifiedNanos,
                    quadPaths,
                    hashes,
                    quadPathResolver
                );
                newEntries += childResult.newEntries();
                visitedDirectories += childResult.visitedDirectories();
                prunedDirectories += childResult.prunedDirectories();
                visitedFiles += childResult.visitedFiles();
                continue;
            }
            visitedFiles++;
            if (!PerformanceReport.time("referenceCatalog.walk.isRegularFile", () -> Files.isRegularFile(child))
                || !isPng(child)) {
                continue;
            }
            String imagePath = child.toAbsolutePath().normalize().toString();
            if (quadPaths.containsKey(imagePath)) {
                continue;
            }
            String quadPath = quadPathResolver.apply(child);
            if (quadPath == null) {
                continue;
            }
            String hash = ContentHashCatalog.sha256(child, "referenceCache.hash");
            if (hash == null) {
                continue;
            }
            quadPaths.put(imagePath, quadPath);
            hashes.put(imagePath, hash);
            newEntries++;
        }
        return new ScanResult(newEntries, visitedDirectories, prunedDirectories, visitedFiles);
    }

    private Map<String, List<Path>> cachedChildDirectories(Map<String, Long> cachedDirectoryModifiedNanos) {
        Map<String, List<Path>> children = new LinkedHashMap<>();
        for (String directory : cachedDirectoryModifiedNanos.keySet()) {
            Path path = Path.of(directory);
            Path parent = path.getParent();
            if (parent == null) {
                continue;
            }
            children.computeIfAbsent(parent.toAbsolutePath().normalize().toString(), ignored -> new ArrayList<>())
                .add(path);
        }
        return children;
    }

    private long modifiedNanos(Path directory) {
        try {
            FileTime time = Files.getLastModifiedTime(directory);
            return time.to(TimeUnit.NANOSECONDS);
        }
        catch (IOException ex) {
            throw new ReferenceCacheException(ex);
        }
    }

    private List<Path> list(Path directory) {
        try (var stream = Files.list(directory)) {
            return PerformanceReport.time("referenceCatalog.walk.filterRegularPng", () -> stream.toList());
        }
        catch (IOException ex) {
            throw new ReferenceCacheException(ex);
        }
    }

    private Snapshot read(Path cachePath, Path root) {
        if (!Files.isRegularFile(cachePath)) {
            return new Snapshot(Map.of(), Map.of(), Map.of());
        }
        LinkedHashMap<String, String> quadPaths = new LinkedHashMap<>();
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        LinkedHashMap<String, Long> directoryModifiedNanos = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(cachePath)))) {
            if (!MAGIC.equals(in.readUTF())) {
                return new Snapshot(Map.of(), Map.of(), Map.of());
            }
            int version = in.readInt();
            if ((version != 1 && version != VERSION) || !root.toString().equals(in.readUTF())) {
                return new Snapshot(Map.of(), Map.of(), Map.of());
            }
            int entries = in.readInt();
            for (int i = 0; i < entries; i++) {
                String imagePath = in.readUTF();
                String quadPath = in.readUTF();
                String hash = in.readUTF();
                quadPaths.put(imagePath, quadPath);
                hashes.put(imagePath, hash);
            }
            if (version >= 2) {
                int directories = in.readInt();
                for (int i = 0; i < directories; i++) {
                    directoryModifiedNanos.put(in.readUTF(), in.readLong());
                }
            }
            return new Snapshot(Map.copyOf(quadPaths), Map.copyOf(hashes), Map.copyOf(directoryModifiedNanos));
        }
        catch (EOFException ex) {
            quarantine(cachePath);
            return new Snapshot(Map.of(), Map.of(), Map.of());
        }
        catch (IOException | RuntimeException ex) {
            quarantine(cachePath);
            return new Snapshot(Map.of(), Map.of(), Map.of());
        }
    }

    private void write(Path cachePath, Path root, Snapshot snapshot) {
        Path tmp = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeUTF(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(root.toString());
            out.writeInt(snapshot.quadPathByImagePath().size());
            for (Map.Entry<String, String> entry : snapshot.quadPathByImagePath().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
                out.writeUTF(snapshot.sha256ByImagePath().getOrDefault(entry.getKey(), ""));
            }
            out.writeInt(snapshot.directoryModifiedNanosByPath().size());
            for (Map.Entry<String, Long> entry : snapshot.directoryModifiedNanosByPath().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeLong(entry.getValue());
            }
        }
        catch (IOException ex) {
            throw new ReferenceCacheException(ex);
        }
        try {
            Files.move(tmp, cachePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException ex) {
            try {
                Files.move(tmp, cachePath, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException ioEx) {
                throw new ReferenceCacheException(ioEx);
            }
        }
        catch (IOException ex) {
            throw new ReferenceCacheException(ex);
        }
    }

    private void quarantine(Path cachePath) {
        try {
            if (Files.isRegularFile(cachePath)) {
                String suffix = ".bad." + Instant.now().toEpochMilli();
                Files.move(cachePath, cachePath.resolveSibling(cachePath.getFileName() + suffix));
            }
        }
        catch (IOException ignored) {
        }
    }

    private boolean isPng(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private static final class ReferenceCacheException extends RuntimeException {
        private ReferenceCacheException(Throwable cause) {
            super(cause);
        }
    }

    private record ScanResult(int newEntries, int visitedDirectories, int prunedDirectories, int visitedFiles) {}
}
