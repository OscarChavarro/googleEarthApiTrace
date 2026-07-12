package matrixmerger.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import matrixmerger.model.MatrixMergerModel;

public final class ResultsExporter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MatrixMergerModel model;
    private final Path sourceOutputDirectory;

    public ResultsExporter(MatrixMergerModel model, Path sourceOutputDirectory) {
        this.model = model;
        this.sourceOutputDirectory = sourceOutputDirectory;
    }

    public void export(String path) {
        Path outputDirectory = prepareOutputDirectory(path);
        if (model == null) {
            fatal("Can not export results: model is not available.");
            return;
        }
        if (sourceOutputDirectory == null || !Files.isDirectory(sourceOutputDirectory) || !Files.isReadable(sourceOutputDirectory)) {
            fatal("Can not export results: source output.directory is not accessible: " + sourceOutputDirectory);
        }
        List<FrameMatrices> frames = model.getFrameMatrices();
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            FrameMatrices frame = frames.get(frameIndex);
            Path frameDirectory = outputDirectory.resolve("matrix_" + frameIndex);
            createDirectory(frameDirectory, "frame export directory");
            FrameMatrices exportedFrame = copyFrameAssets(frame, frameDirectory);
            writeFrameJson(exportedFrame, frameDirectory.resolve("matrixLayer.json"));
            System.out.println("Export folder ready: " + frameDirectory);
        }
        System.out.println(
            "Exported matrix-layer JSON files and image tiles to absolute folder: "
                + outputDirectory.toAbsolutePath().normalize()
        );
    }

    private Path prepareOutputDirectory(String path) {
        if (path == null || path.isBlank()) {
            fatal("Can not export results: destination directory path is empty.");
            return null;
        }
        Path directory = Path.of(path).toAbsolutePath().normalize();
        if (Files.exists(directory) && !Files.isDirectory(directory)) {
            fatal("Can not export results: destination path exists but is not a directory: " + directory);
        }
        if (!Files.exists(directory)) {
            createDirectory(directory, "destination directory");
        }
        if (!Files.isDirectory(directory) || !Files.isReadable(directory) || !Files.isWritable(directory) || !Files.isExecutable(directory)) {
            fatal("Can not export results: destination directory is not accessible: " + directory);
        }
        return directory;
    }

    private void createDirectory(Path directory, String label) {
        try {
            Files.createDirectories(directory);
        }
        catch (IOException ex) {
            fatal("Can not create " + label + ": " + directory + " (" + ex.getMessage() + ")");
        }
    }

    private FrameMatrices copyFrameAssets(FrameMatrices frame, Path frameDirectory) {
        FrameMatrices exportedFrame = new FrameMatrices();
        if (frame == null) {
            exportedFrame.setMatrices(List.of());
            return exportedFrame;
        }
        exportedFrame.setFrameId(frame.getFrameId());
        List<TileMatrix> exportedMatrices = new ArrayList<>();
        List<TileMatrix> matrices = frame.getMatrices();
        if (matrices != null) {
            for (TileMatrix matrix : matrices) {
                exportedMatrices.add(copyMatrixAssets(frame.getFrameId(), matrix, frameDirectory));
            }
        }
        exportedFrame.setMatrices(exportedMatrices);
        return exportedFrame;
    }

    private TileMatrix copyMatrixAssets(int frameId, TileMatrix matrix, Path frameDirectory) {
        TileMatrix exportedMatrix = new TileMatrix();
        if (matrix == null) {
            exportedMatrix.setFrameId(frameId);
            return exportedMatrix;
        }
        exportedMatrix.setFrameId(matrix.getFrameId());
        exportedMatrix.setRows(matrix.getRows());
        exportedMatrix.setCols(matrix.getCols());
        List<TileMatrix.TileCoord> exportedTiles = new ArrayList<>();
        List<TileMatrix.TileCoord> tiles = matrix.getTiles();
        if (tiles != null) {
            for (TileMatrix.TileCoord tile : tiles) {
                exportedTiles.add(copyTileAsset(frameId, tile, frameDirectory));
            }
        }
        exportedMatrix.setTiles(exportedTiles);
        return exportedMatrix;
    }

    private TileMatrix.TileCoord copyTileAsset(int frameId, TileMatrix.TileCoord tile, Path frameDirectory) {
        TileMatrix.TileCoord exportedTile = new TileMatrix.TileCoord();
        if (tile == null) {
            return exportedTile;
        }
        exportedTile.setId(tile.getId());
        exportedTile.setI(tile.getI());
        exportedTile.setJ(tile.getJ());
        exportedTile.setUncles(tile.getUncles());
        Path sourceFile = resolveSourceTexture(frameId, tile);
        String targetFileName = buildTargetTextureFileName(tile, sourceFile);
        Path targetFile = frameDirectory.resolve(targetFileName).toAbsolutePath().normalize();
        copyFile(sourceFile, targetFile);
        exportedTile.setTextureFile(targetFile.toString());
        return exportedTile;
    }

    private Path resolveSourceTexture(int frameId, TileMatrix.TileCoord tile) {
        String textureFile = tile.getTextureFile();
        Path frameSourceDirectory = resolveFrameSourceDirectory(frameId);
        if (textureFile != null && !textureFile.isBlank()) {
            Path candidate = Path.of(textureFile);
            if (candidate.isAbsolute() && Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                return candidate;
            }
            if (frameSourceDirectory != null) {
                Path resolved = frameSourceDirectory.resolve(textureFile).normalize();
                if (Files.isRegularFile(resolved) && Files.isReadable(resolved)) {
                    return resolved;
                }
            }
        }
        Integer numericTileId = tile.getNumericTileId();
        if (frameSourceDirectory != null && numericTileId != null) {
            Path fallback = frameSourceDirectory.resolve("256x256_" + numericTileId + ".png");
            if (Files.isRegularFile(fallback) && Files.isReadable(fallback)) {
                return fallback;
            }
        }
        fatal("Can not export results: source texture file is not accessible for tile " + tile.getId() + ".");
        return null;
    }

    private Path resolveFrameSourceDirectory(int frameId) {
        if (sourceOutputDirectory == null) {
            return null;
        }
        return sourceOutputDirectory.resolve(String.format("%05d", frameId));
    }

    private String buildTargetTextureFileName(TileMatrix.TileCoord tile, Path sourceFile) {
        String tileId = tile.getId();
        if (tileId == null || tileId.isBlank()) {
            Integer numericTileId = tile.getNumericTileId();
            if (numericTileId == null) {
                fatal("Can not export results: tile without valid identifier.");
            }
            tileId = Integer.toString(numericTileId);
        }
        String extension = extensionOf(sourceFile == null ? null : sourceFile.getFileName().toString());
        return tileId + extension;
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return ".png";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : ".png";
    }

    private void copyFile(Path sourceFile, Path targetFile) {
        try {
            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException ex) {
            fatal("Can not export results: failed to copy " + sourceFile + " to " + targetFile + " (" + ex.getMessage() + ")");
        }
    }

    private void writeFrameJson(FrameMatrices frame, Path targetFile) {
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(targetFile.toFile(), frame);
        }
        catch (IOException ex) {
            fatal("Can not export results: failed to write " + targetFile + " (" + ex.getMessage() + ")");
        }
    }

    private static void fatal(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
