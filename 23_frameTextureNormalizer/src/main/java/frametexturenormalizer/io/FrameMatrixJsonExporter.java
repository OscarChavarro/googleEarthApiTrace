package frametexturenormalizer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.TileMatrix;

public final class FrameMatrixJsonExporter {
    public void export(List<TileMatrix> matrices) {
        List<TileMatrix> oneMatrixPerFrame = oneMatrixPerFrame(matrices);
        cleanupObsoleteMatrices(oneMatrixPerFrame);
        if (oneMatrixPerFrame.isEmpty()) {
            return;
        }
        for (TileMatrix matrix : oneMatrixPerFrame) {
            MatrixJsonWriter.writeMatrixJson(matrix);
        }
    }

    private static List<TileMatrix> oneMatrixPerFrame(List<TileMatrix> matrices) {
        if (matrices == null || matrices.isEmpty()) {
            return List.of();
        }
        Set<Integer> emittedFrames = new LinkedHashSet<>();
        List<TileMatrix> out = new java.util.ArrayList<>();
        for (TileMatrix matrix : matrices) {
            if (matrix == null || !emittedFrames.add(matrix.getFrameId())) {
                continue;
            }
            out.add(matrix);
        }
        return out;
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
