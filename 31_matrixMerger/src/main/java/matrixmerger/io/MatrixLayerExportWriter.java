package matrixmerger.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.model.state.MatrixMergerState;

public final class MatrixLayerExportWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MatrixMergerState model;
    private final Path sourceOutputDirectory;

    public MatrixLayerExportWriter(MatrixMergerState model, Path sourceOutputDirectory) {
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
        clearOutputDirectory(outputDirectory);
        List<FrameMatrixSet> frames = model.getFrameMatrices();
        List<MatrixMergerState.HierarchyOrderDiagnostic> hierarchy = model.getHierarchyOrderDiagnostics();
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            FrameMatrixSet frame = frames.get(frameIndex);
            Path frameDirectory = outputDirectory.resolve("matrix_" + frameIndex);
            createDirectory(frameDirectory, "frame export directory");
            MatrixMergerState.HierarchyOrderDiagnostic hierarchyItem =
                frameIndex < hierarchy.size() ? hierarchy.get(frameIndex) : null;
            FrameMatrixSet exportedFrame = copyFrameAssets(frame, frameDirectory, hierarchyItem);
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

    private void clearOutputDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths
                .filter(path -> !directory.equals(path))
                .sorted(Comparator.reverseOrder())
                .forEach(this::deletePath);
        }
        catch (IOException ex) {
            fatal("Can not clean destination directory: " + directory + " (" + ex.getMessage() + ")");
        }
    }

    private void createDirectory(Path directory, String label) {
        try {
            Files.createDirectories(directory);
        }
        catch (IOException ex) {
            fatal("Can not create " + label + ": " + directory + " (" + ex.getMessage() + ")");
        }
    }

    private void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ex) {
            fatal("Can not delete stale export path: " + path + " (" + ex.getMessage() + ")");
        }
    }

    private FrameMatrixSet copyFrameAssets(
        FrameMatrixSet frame,
        Path frameDirectory,
        MatrixMergerState.HierarchyOrderDiagnostic hierarchy
    ) {
        FrameMatrixSet exportedFrame = new FrameMatrixSet();
        if (frame == null) {
            exportedFrame.setMatrices(List.of());
            return exportedFrame;
        }
        exportedFrame.setContractVersion(6);
        exportedFrame.setFrameId(frame.getFrameId());
        exportedFrame.setHierarchyLevel(hierarchy == null ? null : hierarchy.level());
        exportedFrame.setParentMatrixIndex(
            hierarchy == null || hierarchy.resolvedParentIndexes().size() != 1
                ? null
                : hierarchy.resolvedParentIndexes().get(0)
        );
        exportedFrame.setParentGridTransform(frame.getParentGridTransform());
        exportedFrame.setParentLevelDelta(frame.getParentLevelDelta());
        exportedFrame.setHierarchyUnclesByTileId(frame.getHierarchyUnclesByTileId());
        exportedFrame.setHierarchyRelationshipsByTileId(frame.getHierarchyRelationshipsByTileId());
        List<FrameTileMatrix> exportedMatrices = new ArrayList<>();
        List<FrameTileMatrix> matrices = frame.getMatrices();
        if (matrices != null) {
            for (FrameTileMatrix matrix : matrices) {
                exportedMatrices.add(copyMatrixAssets(frame.getFrameId(), matrix, frameDirectory));
            }
        }
        exportedFrame.setMatrices(exportedMatrices);
        exportedFrame.setExternalUncleTextureFilesById(copyExternalUncleTextures(frame, frameDirectory));
        return exportedFrame;
    }

    private Map<String, String> copyExternalUncleTextures(FrameMatrixSet frame, Path frameDirectory) {
        Set<String> uncleIds = collectExternalUncleIds(frame);
        if (uncleIds.isEmpty()) {
            return Map.of();
        }
        Path uncleDirectory = frameDirectory.resolve("uncleTextures");
        Map<String, String> exported = new LinkedHashMap<>();
        for (String uncleId : uncleIds) {
            Path source = resolveExternalUncleTexture(uncleId);
            if (source == null) {
                continue;
            }
            createDirectory(uncleDirectory, "external uncle texture directory");
            Path target = uncleDirectory.resolve(uncleId + extensionOf(source.getFileName().toString()))
                .toAbsolutePath().normalize();
            copyFile(source, target);
            exported.put(uncleId, target.toString());
        }
        if (!uncleIds.isEmpty()) {
            System.out.println(
                "MatrixLayerExportWriter: preserved " + exported.size() + "/" + uncleIds.size()
                    + " external uncle texture(s) in " + frameDirectory.getFileName() + "."
            );
        }
        return exported;
    }

    private static Set<String> collectExternalUncleIds(FrameMatrixSet frame) {
        Set<String> ids = new LinkedHashSet<>();
        if (frame == null) {
            return ids;
        }
        if (frame.getMatrices() != null) {
            for (FrameTileMatrix matrix : frame.getMatrices()) {
                if (matrix == null || matrix.getTiles() == null) {
                    continue;
                }
                for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                    collectUncleIds(tile == null ? null : tile.getUncles(), ids);
                }
            }
        }
        if (frame.getHierarchyRelationshipsByTileId() != null) {
            for (var relationships : frame.getHierarchyRelationshipsByTileId().values()) {
                collectUncleIds(relationships, ids);
            }
        }
        return ids;
    }

    private static void collectUncleIds(
        List<matrixmerger.processing.uncles.ToUncleRelationship> relationships,
        Set<String> out
    ) {
        if (relationships == null) {
            return;
        }
        for (var relationship : relationships) {
            if (relationship != null && relationship.uncleContentId() != null
                && !relationship.uncleContentId().isBlank()) {
                out.add(relationship.uncleContentId());
            }
        }
    }

    Path resolveExternalUncleTexture(String uncleId) {
        if (uncleId == null || sourceOutputDirectory == null || !uncleId.matches("\\d{1,5}_\\d+")) {
            return null;
        }
        int separator = uncleId.lastIndexOf('_');
        if (separator <= 0 || separator >= uncleId.length() - 1) {
            return null;
        }
        int frameId;
        try {
            frameId = Integer.parseInt(uncleId.substring(0, separator));
        }
        catch (NumberFormatException ex) {
            return null;
        }
        Path frameJson = sourceOutputDirectory.resolve(String.format("%05d", frameId)).resolve("frame.json");
        if (!Files.isRegularFile(frameJson) || !Files.isReadable(frameJson)) {
            return null;
        }
        try {
            JsonNode tiles = JSON.readTree(frameJson.toFile()).get("tiles");
            if (tiles == null || !tiles.isArray()) {
                return null;
            }
            String wantedContentId = frameId + "_" + uncleId.substring(separator + 1);
            for (JsonNode tile : tiles) {
                String contentId = tile.path("contentId").asText();
                if (!wantedContentId.equals(contentId) && !uncleId.equals(contentId)) {
                    continue;
                }
                String textureFile = tile.path("textureFile").asText(null);
                if (textureFile == null || textureFile.isBlank()) {
                    return null;
                }
                Path texture = Path.of(textureFile);
                if (!texture.isAbsolute()) {
                    texture = frameJson.getParent().resolve(texture).normalize();
                }
                return Files.isRegularFile(texture) && Files.isReadable(texture) ? texture : null;
            }
        }
        catch (IOException | RuntimeException ex) {
            System.out.println(
                "MatrixLayerExportWriter: could not preserve uncle " + uncleId + " from "
                    + frameJson + " (" + ex.getMessage() + ")."
            );
        }
        return null;
    }

    private FrameTileMatrix copyMatrixAssets(int frameId, FrameTileMatrix matrix, Path frameDirectory) {
        FrameTileMatrix exportedMatrix = new FrameTileMatrix();
        if (matrix == null) {
            exportedMatrix.setFrameId(frameId);
            return exportedMatrix;
        }
        exportedMatrix.setFrameId(matrix.getFrameId());
        exportedMatrix.setRows(matrix.getRows());
        exportedMatrix.setCols(matrix.getCols());
        List<FrameTileMatrix.TileCoord> exportedTiles = new ArrayList<>();
        List<FrameTileMatrix.TileCoord> tiles = matrix.getTiles();
        if (tiles != null) {
            for (FrameTileMatrix.TileCoord tile : tiles) {
                exportedTiles.add(copyTileAsset(frameId, tile, frameDirectory));
            }
        }
        exportedMatrix.setTiles(exportedTiles);
        return exportedMatrix;
    }

    private FrameTileMatrix.TileCoord copyTileAsset(int frameId, FrameTileMatrix.TileCoord tile, Path frameDirectory) {
        FrameTileMatrix.TileCoord exportedTile = new FrameTileMatrix.TileCoord();
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

    private Path resolveSourceTexture(int frameId, FrameTileMatrix.TileCoord tile) {
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

    private String buildTargetTextureFileName(FrameTileMatrix.TileCoord tile, Path sourceFile) {
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

    private void writeFrameJson(FrameMatrixSet frame, Path targetFile) {
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
