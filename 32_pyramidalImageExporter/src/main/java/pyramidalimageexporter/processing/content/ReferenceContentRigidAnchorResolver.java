package pyramidalimageexporter.processing.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;

/** Validates a resolved layer against byte-identical tiles in the reference pyramid. */
public final class ReferenceContentRigidAnchorResolver {
    private static final int MINIMUM_MATCHES = 3;
    private static final int SEARCH_RADIUS_MULTIPLIER = 2;
    private static final int MAXIMUM_SEARCH_RADIUS = 128;

    public record Anchors(Map<String, String> fullPathByTileId, Set<String> anchoredLayerNames) {}

    public Anchors resolve(
        List<MatrixLayer> layers,
        Path referenceRoot,
        TileRootPathResolver.Resolution preliminaryResolution
    ) {
        if (layers == null || referenceRoot == null || preliminaryResolution == null
            || !Files.isDirectory(referenceRoot)) {
            return new Anchors(Map.of(), Set.of());
        }

        Map<String, String> paths = new LinkedHashMap<>();
        Set<String> anchoredLayers = new LinkedHashSet<>();
        for (MatrixLayer layer : layers) {
            LayerPlacement placement = placementOf(layer, preliminaryResolution);
            if (placement == null || layer.getTiles().size() < MINIMUM_MATCHES) {
                continue;
            }
            GridOffset currentOffset = placement.offset();
            int exactCurrentMatches = exactMatchesAtOffset(layer, referenceRoot, placement.level(), currentOffset);
            GridOffset acceptedOffset = exactCurrentMatches >= MINIMUM_MATCHES
                ? currentOffset
                : searchOffset(layer, referenceRoot, placement);
            if (acceptedOffset == null) {
                continue;
            }
            anchoredLayers.add(layer.getSourceFolderName());
            for (MatrixLayerTile tile : layer.getTiles()) {
                int row = acceptedOffset.row() + tile.getI();
                int col = Math.floorMod(acceptedOffset.col() + tile.getJ(), placement.side());
                if (row >= 0 && row < placement.side()) {
                    paths.put(tile.getId(), encode(placement.level(), row, col));
                }
            }
            if (!acceptedOffset.equals(currentOffset)) {
                System.out.println(
                    "ReferenceContentRigidAnchorResolver: corrected layer " + layer.getSourceFolderName()
                        + " at level " + placement.level() + " from offset [" + currentOffset.row()
                        + ", " + currentOffset.col() + "] to [" + acceptedOffset.row() + ", "
                        + acceptedOffset.col() + "]."
                );
            }
        }
        if (!paths.isEmpty()) {
            System.out.println(
                "ReferenceContentRigidAnchorResolver: validated " + paths.size()
                    + " tile path(s) in " + anchoredLayers.size() + " layer(s)."
            );
        }
        return new Anchors(Map.copyOf(paths), Set.copyOf(anchoredLayers));
    }

    private static LayerPlacement placementOf(
        MatrixLayer layer,
        TileRootPathResolver.Resolution resolution
    ) {
        if (layer == null || layer.getTiles() == null || layer.getTiles().isEmpty()
            || layer.getSourceFolderName() == null) {
            return null;
        }
        Integer level = null;
        GridOffset offset = null;
        for (MatrixLayerTile tile : layer.getTiles()) {
            int[] cell = decode(resolution.pathFor(layer, tile));
            if (cell == null) {
                continue;
            }
            int tileLevel = cell[0];
            int side = 1 << tileLevel;
            GridOffset tileOffset = new GridOffset(
                cell[1] - tile.getI(),
                Math.floorMod(cell[2] - tile.getJ(), side)
            );
            if (level == null) {
                level = tileLevel;
                offset = tileOffset;
            }
            else if (level != tileLevel || !offset.equals(tileOffset)) {
                return null;
            }
        }
        return level == null || level >= 30 ? null : new LayerPlacement(level, 1 << level, offset);
    }

    private static int exactMatchesAtOffset(
        MatrixLayer layer,
        Path referenceRoot,
        int level,
        GridOffset offset
    ) {
        int side = 1 << level;
        int matches = 0;
        for (MatrixLayerTile tile : layer.getTiles()) {
            if (tile.getTextureFile() == null) {
                continue;
            }
            int row = offset.row() + tile.getI();
            int col = Math.floorMod(offset.col() + tile.getJ(), side);
            Path reference = tilePath(referenceRoot, encode(level, row, col));
            if (sameFileContent(Path.of(tile.getTextureFile()), reference)) {
                matches++;
            }
        }
        return matches;
    }

    private static GridOffset searchOffset(MatrixLayer layer, Path referenceRoot, LayerPlacement placement) {
        int radius = Math.min(
            MAXIMUM_SEARCH_RADIUS,
            Math.max(8, Math.max(layer.getRows(), layer.getCols()) * SEARCH_RADIUS_MULTIPLIER)
        );
        int minimumRow = Integer.MAX_VALUE;
        int maximumRow = Integer.MIN_VALUE;
        int minimumCol = Integer.MAX_VALUE;
        int maximumCol = Integer.MIN_VALUE;
        for (MatrixLayerTile tile : layer.getTiles()) {
            int row = placement.offset().row() + tile.getI();
            int unwrappedCol = placement.offset().col() + tile.getJ();
            minimumRow = Math.min(minimumRow, row);
            maximumRow = Math.max(maximumRow, row);
            minimumCol = Math.min(minimumCol, unwrappedCol);
            maximumCol = Math.max(maximumCol, unwrappedCol);
        }

        Map<String, Cell> referenceCellByHash = new HashMap<>();
        Set<String> ambiguousHashes = new HashSet<>();
        int firstRow = Math.max(0, minimumRow - radius);
        int lastRow = Math.min(placement.side() - 1, maximumRow + radius);
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = minimumCol - radius; col <= maximumCol + radius; col++) {
                int wrappedCol = Math.floorMod(col, placement.side());
                Path reference = tilePath(referenceRoot, encode(placement.level(), row, wrappedCol));
                String hash = hash(reference);
                if (hash == null || ambiguousHashes.contains(hash)) {
                    continue;
                }
                Cell previous = referenceCellByHash.putIfAbsent(hash, new Cell(row, wrappedCol));
                if (previous != null && (previous.row() != row || previous.col() != wrappedCol)) {
                    referenceCellByHash.remove(hash);
                    ambiguousHashes.add(hash);
                }
            }
        }

        Map<GridOffset, Integer> votes = new LinkedHashMap<>();
        for (MatrixLayerTile tile : layer.getTiles()) {
            String hash = tile.getTextureFile() == null ? null : hash(Path.of(tile.getTextureFile()));
            Cell cell = hash == null || ambiguousHashes.contains(hash) ? null : referenceCellByHash.get(hash);
            if (cell != null) {
                GridOffset candidate = new GridOffset(
                    cell.row() - tile.getI(),
                    Math.floorMod(cell.col() - tile.getJ(), placement.side())
                );
                votes.merge(candidate, 1, Integer::sum);
            }
        }
        int total = votes.values().stream().mapToInt(Integer::intValue).sum();
        GridOffset winner = null;
        int winnerVotes = 0;
        for (Map.Entry<GridOffset, Integer> entry : votes.entrySet()) {
            if (entry.getValue() > winnerVotes) {
                winner = entry.getKey();
                winnerVotes = entry.getValue();
            }
        }
        return winnerVotes >= MINIMUM_MATCHES && winnerVotes * 2 > total ? winner : null;
    }

    private static Path tilePath(Path root, String quadPath) {
        Path path = root;
        for (int index = 1; index < quadPath.length(); index++) {
            path = path.resolve(String.valueOf(quadPath.charAt(index)));
        }
        Path perDigit = path.resolve(quadPath + ".png");
        if (Files.isRegularFile(perDigit)) {
            return perDigit;
        }
        path = root;
        for (int length = 2; length <= quadPath.length(); length++) {
            path = path.resolve(quadPath.substring(0, length));
        }
        return path.resolve(quadPath + ".png");
    }

    private static boolean sameFileContent(Path left, Path right) {
        try {
            return Files.isRegularFile(left) && Files.isRegularFile(right) && Files.mismatch(left, right) == -1L;
        }
        catch (IOException ex) {
            return false;
        }
    }

    private static String hash(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        }
        catch (IOException | NoSuchAlgorithmException ex) {
            return null;
        }
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
        return new int[]{path.length() - 1, row, col};
    }

    private static String encode(int level, int row, int col) {
        StringBuilder path = new StringBuilder(level + 1).append('0');
        for (int bit = level - 1; bit >= 0; bit--) {
            boolean south = ((row >> bit) & 1) != 0;
            boolean east = ((col >> bit) & 1) != 0;
            path.append(south ? (east ? '1' : '0') : (east ? '2' : '3'));
        }
        return path.toString();
    }

    private record GridOffset(int row, int col) {}
    private record Cell(int row, int col) {}
    private record LayerPlacement(int level, int side, GridOffset offset) {}
}
