package frametexturenormalizer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.TileMatrix;

public final class TileMatrixExporter {
    public void export(List<TileMatrix> matrices) {
        cleanupObsoleteMatrices(matrices);
        if (matrices == null || matrices.isEmpty()) {
            return;
        }
        for (TileMatrix matrix : matrices) {
            MatrixWriter.writeMatrixJson(matrix);
        }
    }

    private static void cleanupObsoleteMatrices(List<TileMatrix> matrices) {
        Path outputRoot = Path.of(Configuration.INPUT_PATH);
        if (!Files.isDirectory(outputRoot)) {
            return;
        }
        Set<String> keptFrameDirs = new LinkedHashSet<>();
        if (matrices != null) {
            for (TileMatrix matrix : matrices) {
                if (matrix != null) {
                    keptFrameDirs.add(String.format("%05d", matrix.getFrameId()));
                }
            }
        }
        try (var dirs = Files.list(outputRoot)) {
            dirs.filter(Files::isDirectory)
                .forEach(dir -> cleanupFrameDir(dir, keptFrameDirs));
        }
        catch (IOException ignored) {
        }
    }

    private static void cleanupFrameDir(Path frameDir, Set<String> keptFrameDirs) {
        if (frameDir == null || frameDir.getFileName() == null) {
            return;
        }
        String dirName = frameDir.getFileName().toString();
        if (!dirName.matches("\\d+") || keptFrameDirs.contains(dirName)) {
            return;
        }
        try {
            Files.deleteIfExists(frameDir.resolve("matrix.json"));
            Files.deleteIfExists(frameDir.resolve("matrix.txt"));
        }
        catch (IOException ignored) {
        }
    }
}
