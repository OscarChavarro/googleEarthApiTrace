package pyramidalimageexporter.processing.geography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;
import pyramidalimageexporter.processing.uncles.ToUncleRelationship;

public final class FrameCameraGeoAnchorResolver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MIN_EXACT_VOTES_FOR_LOW_LEVEL_ANCHOR = 3;
    private static final int LOW_LEVEL_CAMERA_LIMIT = 11;

    public record Anchors(Map<String, String> fullPathByTileId, Set<String> anchoredLayerNames) {}

    public Anchors resolve(
        List<MatrixLayer> layers,
        Path frameRoot,
        Map<String, String> referencePathsByImage,
        TileRootPathResolver.Resolution preliminaryResolution
    ) {
        int deepestReferenceLevel = deepestLevel(referencePathsByImage);
        int deepestHierarchyLevel = deepestHierarchyLevel(layers);
        if (deepestReferenceLevel < 0 || deepestHierarchyLevel < 0 || !Files.isDirectory(frameRoot)) {
            return new Anchors(Map.of(), Set.of());
        }

        Map<Integer, CameraAnchor> byFrameId = new HashMap<>();
        Map<String, String> paths = new LinkedHashMap<>();
        Set<String> anchoredLayers = new LinkedHashSet<>();
        for (MatrixLayer layer : layers) {
            if (!isCameraAnchorRoot(layer)) {
                continue;
            }
            int absoluteLevel = deepestReferenceLevel
                - (deepestHierarchyLevel - layer.getHierarchyLevel());
            if (absoluteLevel < 0 || absoluteLevel >= 30) {
                continue;
            }
            Integer preliminaryLevel = firstResolvedLevel(layer, preliminaryResolution);
            if (preliminaryLevel != null && preliminaryLevel != absoluteLevel) {
                continue;
            }
            GridOffset offset = cameraGridOffset(layer, absoluteLevel, frameRoot, byFrameId);
            if (offset == null
                || contradictsStructuralPlacement(layer, absoluteLevel, offset, preliminaryResolution)) {
                continue;
            }
            anchoredLayers.add(layer.getSourceFolderName());
        }
        anchoredLayers = descendantLayerNames(layers, anchoredLayers);
        Set<String> contradictedLayers = new LinkedHashSet<>();
        for (MatrixLayer layer : layers) {
            if (!isImportedLayer(layer) || !anchoredLayers.contains(layer.getSourceFolderName())
                || layer.getHierarchyLevel() == null) {
                continue;
            }
            int absoluteLevel = deepestReferenceLevel
                - (deepestHierarchyLevel - layer.getHierarchyLevel());
            GridOffset offset = cameraGridOffset(layer, absoluteLevel, frameRoot, byFrameId);
            if (offset == null) {
                continue;
            }
            if (contradictsStructuralPlacement(layer, absoluteLevel, offset, preliminaryResolution)) {
                contradictedLayers.add(layer.getSourceFolderName());
                continue;
            }
            int side = 1 << absoluteLevel;
            for (MatrixLayerTile tile : layer.getTiles()) {
                int row = offset.row() + tile.getI();
                int col = Math.floorMod(offset.col() + tile.getJ(), side);
                if (row >= 0 && row < side) {
                    paths.put(tile.getId(), quadPath(absoluteLevel, row, col));
                }
            }
        }
        anchoredLayers.removeAll(contradictedLayers);
        if (!paths.isEmpty()) {
            System.out.println(
                "FrameCameraGeoAnchorResolver: resolved " + paths.size()
                    + " geographic tile anchor(s) in " + anchoredLayers.size() + " layer(s)."
            );
        }
        return new Anchors(Map.copyOf(paths), Set.copyOf(anchoredLayers));
    }

    /**
     * Reports whether a camera-derived grid offset contradicts the placement
     * the structural resolution already reached for the same layer.
     *
     * <p>Structural paths come from images matched against the catalogued
     * reference pyramid, so they name real planet cells; a camera offset is
     * trigonometry over the frame's view vector and can be defeated by a
     * frame whose view centre is not the tile it is attributed to. When the
     * two disagree for most of the tiles that structure did resolve, the
     * offset is wrong for the whole layer — it is a single rigid translation,
     * so one bad vote cluster displaces every tile — and anchoring on it
     * would silently overwrite correct absolute paths with shifted ones.</p>
     */
    static boolean contradictsStructuralPlacement(
        MatrixLayer layer,
        int absoluteLevel,
        GridOffset offset,
        TileRootPathResolver.Resolution structure
    ) {
        if (structure == null || absoluteLevel < 0 || absoluteLevel >= 30) {
            return false;
        }
        int side = 1 << absoluteLevel;
        int agreements = 0;
        int disagreements = 0;
        for (MatrixLayerTile tile : layer.getTiles()) {
            String structuralPath = structure.pathFor(layer, tile);
            if (structuralPath == null) {
                continue;
            }
            int row = offset.row() + tile.getI();
            if (row < 0 || row >= side) {
                continue;
            }
            int col = Math.floorMod(offset.col() + tile.getJ(), side);
            if (structuralPath.equals(quadPath(absoluteLevel, row, col))) {
                agreements++;
            }
            else {
                disagreements++;
            }
        }
        if (disagreements > agreements) {
            System.out.println(
                "FrameCameraGeoAnchorResolver: rejected camera anchor for layer "
                    + layer.getSourceFolderName() + " at [" + absoluteLevel + ", "
                    + offset.row() + ", " + offset.col() + "]; it contradicts the structural"
                    + " placement of " + disagreements + " tile(s) and matches only "
                    + agreements + "."
            );
            return true;
        }
        return false;
    }

    private static GridOffset cameraGridOffset(
        MatrixLayer layer,
        int absoluteLevel,
        Path frameRoot,
        Map<Integer, CameraAnchor> byFrameId
    ) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, MatrixLayerTile> tilesById = new LinkedHashMap<>();
        Map<Integer, MatrixLayerTile> fallbackTileByFrameId = new HashMap<>();
        double centerI = layer.getRows() > 0 ? (layer.getRows() - 1) / 2.0 : 0.0;
        double centerJ = layer.getCols() > 0 ? (layer.getCols() - 1) / 2.0 : 0.0;
        for (MatrixLayerTile tile : layer.getTiles()) {
            if (tile != null && tile.getId() != null) {
                ids.add(tile.getId());
                tilesById.put(tile.getId(), tile);
                Integer frameId = frameIdOf(tile.getId());
                if (frameId != null) {
                    MatrixLayerTile previous = fallbackTileByFrameId.get(frameId);
                    if (isCloserTo(centerI, centerJ, tile, previous)) {
                        fallbackTileByFrameId.put(frameId, tile);
                    }
                }
            }
        }
        Map<GridOffset, Integer> offsetVotes = new LinkedHashMap<>();
        Map<GridOffset, Integer> exactOffsetVotes = new LinkedHashMap<>();
        for (String id : ids) {
            Integer frameId = frameIdOf(id);
            if (frameId == null) {
                continue;
            }
            CameraAnchor anchor = byFrameId.computeIfAbsent(
                frameId,
                ignored -> readAnchor(frameRoot, frameId)
            );
            if (anchor != null) {
                String anchorPath = quadPath(anchor.longitude(), anchor.latitude(), absoluteLevel);
                int[] cell = decode(anchorPath);
                MatrixLayerTile anchorTile = tilesById.get(anchor.tileId());
                boolean exactTile = anchorTile != null;
                if (anchorTile == null) {
                    anchorTile = fallbackTileByFrameId.get(frameId);
                }
                if (cell != null && anchorTile != null) {
                    GridOffset vote = new GridOffset(cell[0] - anchorTile.getI(), cell[1] - anchorTile.getJ());
                    offsetVotes.merge(vote, 1, Integer::sum);
                    if (exactTile) {
                        exactOffsetVotes.merge(vote, 1, Integer::sum);
                    }
                }
            }
        }
        GridOffset offset = bestOffset(offsetVotes);
        if (offset != null && !hasSufficientExactCameraEvidence(
            absoluteLevel,
            clusterSupport(offset, exactOffsetVotes)
        )) {
            offset = null;
        }
        if (offset == null) {
            CameraAnchor representativeAnchor = byFrameId.computeIfAbsent(
                layer.getFrameId(),
                ignored -> readAnchor(frameRoot, layer.getFrameId())
            );
            MatrixLayerTile representativeTile = fallbackTileByFrameId.get(layer.getFrameId());
            if (absoluteLevel >= LOW_LEVEL_CAMERA_LIMIT
                && representativeAnchor != null && representativeTile != null) {
                String anchorPath = quadPath(representativeAnchor.longitude(), representativeAnchor.latitude(), absoluteLevel);
                int[] cell = decode(anchorPath);
                if (cell != null) {
                    offset = new GridOffset(
                        cell[0] - representativeTile.getI(),
                        cell[1] - representativeTile.getJ()
                    );
                    System.out.println(
                        "FrameCameraGeoAnchorResolver: layer " + layer.getSourceFolderName()
                            + " fell back to representative frame " + layer.getFrameId()
                            + " for geographic anchoring at [" + absoluteLevel + ", "
                            + offset.row() + ", " + offset.col() + "]."
                    );
                }
            }
        }
        if (offset != null) {
            int exactVotes = clusterSupport(offset, exactOffsetVotes);
            int clusterVotes = clusterSupport(offset, offsetVotes);
            int totalVotes = offsetVotes.values().stream().mapToInt(Integer::intValue).sum();
            System.out.println(
                "FrameCameraGeoAnchorResolver: layer " + layer.getSourceFolderName()
                    + " anchored at [" + absoluteLevel + ", " + offset.row() + ", "
                    + offset.col() + "] from " + clusterVotes + "/" + totalVotes
                    + " clustered camera vote(s), including " + exactVotes + " exact."
            );
        }
        return offset;
    }

    private static boolean isCloserTo(
        double centerI,
        double centerJ,
        MatrixLayerTile candidate,
        MatrixLayerTile current
    ) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        return squaredDistance(centerI, centerJ, candidate)
            < squaredDistance(centerI, centerJ, current);
    }

    private static double squaredDistance(double centerI, double centerJ, MatrixLayerTile tile) {
        double di = tile.getI() - centerI;
        double dj = tile.getJ() - centerJ;
        return di * di + dj * dj;
    }

    private static Set<String> descendantLayerNames(
        List<MatrixLayer> layers,
        Set<String> rootLayerNames
    ) {
        Set<String> descendants = new LinkedHashSet<>(rootLayerNames);
        Set<String> componentTileIds = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            componentTileIds.clear();
            for (MatrixLayer layer : layers) {
                if (layer != null && descendants.contains(layer.getSourceFolderName())) {
                    for (MatrixLayerTile tile : layer.getTiles()) {
                        if (tile != null && tile.getId() != null) {
                            componentTileIds.add(tile.getId());
                        }
                    }
                }
            }
            for (MatrixLayer layer : layers) {
                if (!isImportedLayer(layer) || descendants.contains(layer.getSourceFolderName())) {
                    continue;
                }
                if (hasParentIn(layer, descendants) || referencesAny(layer, componentTileIds)) {
                    descendants.add(layer.getSourceFolderName());
                    changed = true;
                }
            }
        }
        return descendants;
    }

    private static boolean hasParentIn(MatrixLayer layer, Set<String> layerNames) {
        Integer parentIndex = layer.getParentMatrixIndex();
        return parentIndex != null && layerNames.contains("matrix_" + parentIndex);
    }

    private static boolean referencesAny(MatrixLayer layer, Set<String> tileIds) {
        for (List<ToUncleRelationship> relationships
            : layer.getHierarchyRelationshipsByTileId().values()) {
            for (ToUncleRelationship relationship : relationships) {
                if (relationship != null && tileIds.contains(relationship.referenceContentId())) {
                    return true;
                }
            }
        }
        for (List<String> uncleIds : layer.getHierarchyUnclesByTileId().values()) {
            for (String uncleId : uncleIds) {
                if (tileIds.contains(uncleId)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String quadPath(double longitude, double latitude, int level) {
        int side = 1 << level;
        int col = Math.min(side - 1, Math.max(0, (int)Math.floor((longitude + 180.0) / 360.0 * side)));
        double boundedLatitude = Math.max(-180.0, Math.min(180.0, latitude));
        int southRow = Math.min(
            side - 1,
            Math.max(0, (int)Math.floor((boundedLatitude + 180.0) / 360.0 * side))
        );
        int row = side - 1 - southRow;
        return quadPath(level, row, col);
    }

    static String quadPathForCell(int level, int row, int col) {
        return quadPath(level, row, col);
    }

    private static String quadPath(int level, int row, int col) {
        StringBuilder path = new StringBuilder(level + 1).append('0');
        for (int bit = level - 1; bit >= 0; bit--) {
            boolean south = ((row >> bit) & 1) != 0;
            boolean east = ((col >> bit) & 1) != 0;
            path.append(south ? (east ? '1' : '0') : (east ? '2' : '3'));
        }
        return path.toString();
    }

    private static int[] decode(String path) {
        if (path == null || !path.matches("0[0-3]*")) {
            return null;
        }
        int row = 0;
        int col = 0;
        for (int index = 1; index < path.length(); index++) {
            int quadrant = path.charAt(index) - '0';
            row = (row << 1) | ((quadrant == 0 || quadrant == 1) ? 1 : 0);
            col = (col << 1) | ((quadrant == 1 || quadrant == 2) ? 1 : 0);
        }
        return new int[]{row, col};
    }

    private static GridOffset bestOffset(Map<GridOffset, Integer> votes) {
        GridOffset best = null;
        int bestSupport = 0;
        int total = 0;
        for (Integer count : votes.values()) {
            total += count;
        }
        for (GridOffset candidate : votes.keySet()) {
            int support = clusterSupport(candidate, votes);
            if (support > bestSupport
                || (support == bestSupport && best != null
                    && votes.get(candidate) > votes.get(best))) {
                best = candidate;
                bestSupport = support;
            }
        }
        return bestSupport >= 2 && bestSupport * 2 > total ? best : null;
    }

    private static int clusterSupport(GridOffset candidate, Map<GridOffset, Integer> votes) {
        int support = 0;
        for (Map.Entry<GridOffset, Integer> entry : votes.entrySet()) {
            GridOffset other = entry.getKey();
            if (Math.abs(candidate.row() - other.row()) <= 4
                && Math.abs(candidate.col() - other.col()) <= 4) {
                support += entry.getValue();
            }
        }
        return support;
    }

    static boolean hasSufficientExactCameraEvidence(int absoluteLevel, int exactVotes) {
        return absoluteLevel >= LOW_LEVEL_CAMERA_LIMIT
            || exactVotes >= MIN_EXACT_VOTES_FOR_LOW_LEVEL_ANCHOR;
    }

    private static Integer firstResolvedLevel(
        MatrixLayer layer,
        TileRootPathResolver.Resolution resolution
    ) {
        if (resolution == null) {
            return null;
        }
        for (MatrixLayerTile tile : layer.getTiles()) {
            String path = resolution.pathFor(layer, tile);
            if (path != null && path.matches("0[0-3]*")) {
                return path.length() - 1;
            }
        }
        return null;
    }

    private static int deepestLevel(Map<String, String> pathsByImage) {
        int deepest = -1;
        if (pathsByImage != null) {
            for (String path : pathsByImage.values()) {
                if (path != null && path.matches("0[0-3]*")) {
                    deepest = Math.max(deepest, path.length() - 1);
                }
            }
        }
        return deepest;
    }

    private static int deepestHierarchyLevel(List<MatrixLayer> layers) {
        int deepest = -1;
        if (layers != null) {
            for (MatrixLayer layer : layers) {
                if (isImportedLayer(layer) && layer.getHierarchyLevel() != null) {
                    deepest = Math.max(deepest, layer.getHierarchyLevel());
                }
            }
        }
        return deepest;
    }

    private static boolean isImportedLayer(MatrixLayer layer) {
        return layer != null && layer.getSourceFolderName() != null
            && layer.getSourceFolderName().matches("matrix_\\d+");
    }

    private static boolean isCameraAnchorRoot(MatrixLayer layer) {
        return isImportedLayer(layer)
            && layer.getHierarchyLevel() != null
            && layer.getParentMatrixIndex() == null;
    }

    private static Integer frameIdOf(String id) {
        if (id == null || !id.matches("\\d{5}_\\d+")) {
            return null;
        }
        try {
            return Integer.parseInt(id.substring(0, id.indexOf('_')));
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static CameraAnchor readAnchor(Path frameRoot, int frameId) {
        Path frameJson = frameRoot.resolve(String.format("%05d", frameId)).resolve("frame.json");
        if (!Files.isRegularFile(frameJson)) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(frameJson.toFile());
            JsonNode camera = root.path("camera");
            JsonNode googleCamera = camera.path("googleCamera");
            double frontX = googleCamera.path("frontX").asDouble(Double.NaN);
            double frontY = googleCamera.path("frontY").asDouble(Double.NaN);
            double frontZ = googleCamera.path("frontZ").asDouble(Double.NaN);
            String centerTileId = centerTileId(root.path("tiles"), camera.path("projectionMatrix"));
            if (!Double.isFinite(frontX) || !Double.isFinite(frontY) || !Double.isFinite(frontZ)) {
                return null;
            }
            double longitude = Math.toDegrees(Math.atan2(-frontX, -frontZ));
            double latitude = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, frontY))));
            return new CameraAnchor(centerTileId, longitude, latitude);
        }
        catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String centerTileId(JsonNode tiles, JsonNode projectionNode) {
        double[] projection = array16(projectionNode);
        if (projection == null || !tiles.isArray()) {
            return null;
        }
        String bestId = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (JsonNode tile : tiles) {
            double[] modelView = array16(tile.get("modelViewMatrix"));
            JsonNode vertices = tile.path("triangleStrip").path("vertices");
            String canonicalId = canonicalId(tile.path("textureFile").asText(null));
            if (modelView == null || !vertices.isArray() || vertices.isEmpty() || canonicalId == null) {
                continue;
            }
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            int count = 0;
            for (JsonNode vertex : vertices) {
                x += vertex.path("x").asDouble();
                y += vertex.path("y").asDouble();
                z += vertex.path("z").asDouble();
                count++;
            }
            double[] eye = multiply(modelView, x / count, y / count, z / count, 1.0);
            double[] clip = multiply(projection, eye[0], eye[1], eye[2], eye[3]);
            if (Math.abs(clip[3]) < 1.0e-12) {
                continue;
            }
            double nx = clip[0] / clip[3];
            double ny = clip[1] / clip[3];
            double distance = nx * nx + ny * ny;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestId = canonicalId;
            }
        }
        return bestId;
    }

    private static double[] multiply(double[] matrix, double x, double y, double z, double w) {
        return new double[]{
            matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12] * w,
            matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13] * w,
            matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14] * w,
            matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15] * w
        };
    }

    private static double[] array16(JsonNode node) {
        if (node == null || !node.isArray() || node.size() != 16) {
            return null;
        }
        double[] out = new double[16];
        for (int i = 0; i < 16; i++) {
            out[i] = node.get(i).asDouble();
        }
        return out;
    }

    private static String canonicalId(String textureFile) {
        if (textureFile == null) {
            return null;
        }
        try {
            Path path = Path.of(textureFile);
            String frame = path.getParent().getFileName().toString();
            String name = path.getFileName().toString();
            if (!frame.matches("\\d+") || !name.matches("256x256_\\d+\\.png")) {
                return null;
            }
            String tile = name.substring("256x256_".length(), name.length() - ".png".length());
            return String.format("%05d_%s", Integer.parseInt(frame), tile);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private record CameraAnchor(String tileId, double longitude, double latitude) {}
    record GridOffset(int row, int col) {}
}
