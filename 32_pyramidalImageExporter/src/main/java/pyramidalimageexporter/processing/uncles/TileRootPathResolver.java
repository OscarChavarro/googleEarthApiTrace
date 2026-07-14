package pyramidalimageexporter.processing.uncles;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;

/**
 * Resolves, for every tile across every matrix layer, its full quadtree path
 * from the pyramid root (a string of quadrant digits, e.g. "0021", the same
 * format the interactive viewer and {@code SessionPyramidalImageExportService} already
 * use to place a tile in the output directory tree).
 *
 * A tile is a "seed" when its own id already is a full root path. The root is
 * exactly "0" and every descendant begins with that root marker followed by
 * one quadrant digit per level. No relative quad labels are accepted here.
 *
 * A non-seed tile can still be anchored if one of its {@code uncles}
 * relationships points (by {@code uncleContentId}) to a tile whose path is
 * already known. An uncle is the immediately coarser tile adjacent to the
 * fine tile, not the parent that contains it: {@code direction} identifies
 * the half of the uncle border touched by the fine tile. Resolution crosses
 * that border to the adjacent parent cell and selects the corresponding
 * child quadrant. Resolution proceeds as a fixpoint so that a chain of several
 * uncle hops can be walked one hop per pass. If a tile has several uncle
 * relationships that resolve to different candidate paths, the tile is
 * ambiguous and is permanently discarded (never exported).
 */
public final class TileRootPathResolver {
    private static final Pattern QUADKEY_PATTERN = Pattern.compile("[0-3]+");

    public enum PathSource {
        DIRECT,
        UNCLE,
        GRID
    }

    public record Resolution(Map<String, String> pathById, Set<String> discardedIds, Map<String, PathSource> sourceById) {}

    public Resolution resolve(List<MatrixLayer> layers) {
        return resolve(layers, Map.of(), Map.of());
    }

    /**
     * {@code externalFullPaths} maps ids whose absolute root path is already
     * known from outside the loaded layers — a loaded tile whose texture is
     * a catalogued top-level image, or an uncle id like "00012_61" naming a
     * catalogued image directly. {@code uncleAliases} maps a dangling uncle
     * id to the id of a loaded tile that shares its texture (see
     * {@link ExternalUncleBridgeBuilder}). Both exist because the planet's cells do
     * not move: a known image names the same cell forever, so a fresh
     * capture session, whose ids never overlap previous sessions', can still
     * reach the absolute quadtree.
     */
    public Resolution resolve(
        List<MatrixLayer> layers,
        Map<String, String> externalFullPaths,
        Map<String, String> uncleAliases
    ) {
        Map<String, MatrixLayerTile> tilesById = collectTilesById(layers);

        Map<String, String> resolvedPath = new HashMap<>();
        Map<String, PathSource> sourceById = new HashMap<>();
        for (Map.Entry<String, MatrixLayerTile> entry : tilesById.entrySet()) {
            String id = entry.getKey();
            if (isSeed(id)) {
                resolvedPath.put(id, id);
                sourceById.put(id, PathSource.DIRECT);
            }
            else {
                String externalPath = externalFullPaths.get(id);
                if (externalPath != null) {
                    resolvedPath.put(id, externalPath);
                    sourceById.put(id, PathSource.DIRECT);
                }
            }
        }

        Set<String> discarded = new HashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Map.Entry<String, MatrixLayerTile> entry : tilesById.entrySet()) {
                String id = entry.getKey();
                if (resolvedPath.containsKey(id) || discarded.contains(id)) {
                    continue;
                }
                Set<String> candidates =
                    candidatePathsFor(entry.getValue(), resolvedPath, externalFullPaths, uncleAliases);
                if (candidates.isEmpty()) {
                    continue;
                }
                if (candidates.size() == 1) {
                    resolvedPath.put(id, candidates.iterator().next());
                    sourceById.put(id, PathSource.UNCLE);
                }
                else {
                    discarded.add(id);
                }
                progress = true;
            }
            if (!progress) {
                progress = propagateByGridPosition(layers, resolvedPath, sourceById, discarded);
            }
        }

        return new Resolution(Map.copyOf(resolvedPath), Set.copyOf(discarded), Map.copyOf(sourceById));
    }

    /**
     * A merged matrix layer is a rigid grid: all its tiles share one level,
     * and their (i, j) cells map to quadtree row/col through one common
     * offset. So a single anchored tile positions the whole layer: every
     * other tile's full path follows from its (i, j) alone. Anchored tiles
     * must all agree on (level, rowOffset, colOffset); a disagreeing layer
     * is left untouched rather than half-guessed.
     */
    private static boolean propagateByGridPosition(
        List<MatrixLayer> layers,
        Map<String, String> resolvedPath,
        Map<String, PathSource> sourceById,
        Set<String> discarded
    ) {
        boolean progress = false;
        if (layers == null) {
            return false;
        }
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            int[] anchor = consistentGridAnchor(layer, resolvedPath);
            if (anchor == null || anchor[0] <= 0) {
                continue;
            }
            int level = anchor[0];
            int side = 1 << level;
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String id = tile.getId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                int row = tile.getI() + anchor[1];
                int col = tile.getJ() + anchor[2];
                if (row < 0 || col < 0 || row >= side || col >= side) {
                    continue;
                }
                String canonicalPath = "0" + encodeQuadtreeLabel(level, row, col);
                String previousPath = resolvedPath.put(id, canonicalPath);
                if (!canonicalPath.equals(previousPath)) {
                    sourceById.put(id, PathSource.GRID);
                    progress = true;
                }
                else if (!sourceById.containsKey(id)) {
                    sourceById.put(id, PathSource.GRID);
                }
                discarded.remove(id);
            }
        }
        return progress;
    }

    /**
     * Returns {level, rowOffset, colOffset} supported by the anchored tiles
     * of the layer (offsets being quadtree row/col minus matrix i/j), or
     * null when the layer has no usable anchor. Anchored tiles vote: a lone
     * bad anchor (typically produced by texture deduplication equating two
     * identical-looking cells, which shifts a chain by one cell) is outvoted
     * as long as a strict majority agrees; a tie is unresolvable and skips
     * the layer.
     */
    private static int[] consistentGridAnchor(MatrixLayer layer, Map<String, String> resolvedPath) {
        Map<List<Integer>, Integer> votes = new LinkedHashMap<>();
        for (MatrixLayerTile tile : layer.getTiles()) {
            if (tile == null) {
                continue;
            }
            String fullPath = resolvedPath.get(tile.getId());
            if (fullPath == null) {
                continue;
            }
            int[] cell = decodeFullPath(fullPath);
            if (cell == null) {
                continue;
            }
            int side = 1 << cell[0];
            int rowOffset = layer.getRows() == side ? 0 : cell[1] - tile.getI();
            int colOffset = layer.getCols() == side ? 0 : cell[2] - tile.getJ();
            votes.merge(List.of(cell[0], rowOffset, colOffset), 1, Integer::sum);
        }
        if (votes.isEmpty()) {
            return null;
        }
        List<Integer> best = null;
        int bestCount = 0;
        int totalCount = 0;
        for (Map.Entry<List<Integer>, Integer> vote : votes.entrySet()) {
            totalCount += vote.getValue();
            if (vote.getValue() > bestCount) {
                bestCount = vote.getValue();
                best = vote.getKey();
            }
        }
        if (votes.size() > 1) {
            if (bestCount * 2 <= totalCount) {
                System.out.println(
                    "TileRootPathResolver: layer " + layer.getSourceFolderName()
                        + " has no majority among its inconsistent anchors ("
                        + votes.size() + " candidates " + votes
                        + "); skipping grid propagation for it."
                );
                return null;
            }
            System.out.println(
                "TileRootPathResolver: layer " + layer.getSourceFolderName()
                    + " has inconsistent anchors " + votes + "; keeping majority ("
                    + bestCount + "/" + totalCount + ")."
            );
        }
        return new int[]{best.get(0), best.get(1), best.get(2)};
    }

    /** Full path "0" + quadrant digits back to {level, row, col}; null if malformed. */
    private static int[] decodeFullPath(String fullPath) {
        if (fullPath == null || fullPath.isEmpty() || fullPath.charAt(0) != '0') {
            return null;
        }
        int level = fullPath.length() - 1;
        int row = 0;
        int col = 0;
        for (int k = 1; k < fullPath.length(); k++) {
            char c = fullPath.charAt(k);
            if (c < '0' || c > '3') {
                return null;
            }
            int quadrant = c - '0';
            boolean south = quadrant == 0 || quadrant == 1;
            boolean east = quadrant == 1 || quadrant == 2;
            row = (row << 1) | (south ? 1 : 0);
            col = (col << 1) | (east ? 1 : 0);
        }
        return new int[]{level, row, col};
    }

    /**
     * Inverse of {@link #decodeFullPath}, same quadrant convention as
     * {@code TopLevelMatrixRebuilder.quadtreePathLabel}: 0 = south-west,
     * 1 = south-east, 2 = north-east, 3 = north-west, south = growing row.
     */
    private static String encodeQuadtreeLabel(int level, int row, int col) {
        StringBuilder sb = new StringBuilder(level);
        for (int depth = level - 1; depth >= 0; depth--) {
            boolean south = ((row >> depth) & 1) == 1;
            boolean east = ((col >> depth) & 1) == 1;
            int quadrant;
            if (south && !east) {
                quadrant = 0;
            }
            else if (south) {
                quadrant = 1;
            }
            else if (east) {
                quadrant = 2;
            }
            else {
                quadrant = 3;
            }
            sb.append(quadrant);
        }
        return sb.toString();
    }

    private static Map<String, MatrixLayerTile> collectTilesById(List<MatrixLayer> layers) {
        Map<String, MatrixLayerTile> tilesById = new LinkedHashMap<>();
        if (layers == null) {
            return tilesById;
        }
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String id = tile.getId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                tilesById.putIfAbsent(id, tile);
            }
        }
        return tilesById;
    }

    private static Set<String> candidatePathsFor(
        MatrixLayerTile tile,
        Map<String, String> resolvedPath,
        Map<String, String> externalFullPaths,
        Map<String, String> uncleAliases
    ) {
        Set<String> candidates = new LinkedHashSet<>();
        List<ToUncleRelationship> uncles = tile.getUncles();
        if (uncles == null) {
            return candidates;
        }
        for (ToUncleRelationship relation : uncles) {
            if (relation == null || relation.direction() == null || relation.uncleContentId() == null) {
                continue;
            }
            String uncleId = uncleAliases.getOrDefault(relation.uncleContentId(), relation.uncleContentId());
            String unclePath = resolvedPath.get(uncleId);
            if (unclePath == null) {
                unclePath = externalFullPaths.get(uncleId);
            }
            if (unclePath == null) {
                continue;
            }
            String candidate = childPathAcrossUncleBorder(unclePath, relation.direction());
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private static boolean isSeed(String id) {
        return QUADKEY_PATTERN.matcher(id).matches() && id.charAt(0) == '0';
    }

    /**
     * The direction names the uncle border and its half. For example,
     * WEST_NORTH means that the fine tile touches the north half of the
     * uncle's west border: its parent is west of the uncle and the tile is
     * the north-east child of that parent.
     */
    private static String childPathAcrossUncleBorder(String unclePath, UncleDirections direction) {
        int[] uncle = decodeFullPath(unclePath);
        if (uncle == null || direction == null) {
            return null;
        }
        int level = uncle[0];
        int side = 1 << level;
        int parentRow = uncle[1];
        int parentCol = uncle[2];
        boolean childSouth;
        boolean childEast;
        switch (direction) {
            case WEST_NORTH -> {
                parentCol--;
                childSouth = false;
                childEast = true;
            }
            case WEST_SOUTH -> {
                parentCol--;
                childSouth = true;
                childEast = true;
            }
            case EAST_NORTH -> {
                parentCol++;
                childSouth = false;
                childEast = false;
            }
            case EAST_SOUTH -> {
                parentCol++;
                childSouth = true;
                childEast = false;
            }
            case NORTH_WEST -> {
                parentRow--;
                childSouth = true;
                childEast = false;
            }
            case NORTH_EAST -> {
                parentRow--;
                childSouth = true;
                childEast = true;
            }
            case SOUTH_WEST -> {
                parentRow++;
                childSouth = false;
                childEast = false;
            }
            case SOUTH_EAST -> {
                parentRow++;
                childSouth = false;
                childEast = true;
            }
            default -> throw new IllegalStateException("Unexpected uncle direction: " + direction);
        }
        if (parentRow < 0 || parentRow >= side) {
            return null;
        }
        parentCol = Math.floorMod(parentCol, side);
        int childRow = 2 * parentRow + (childSouth ? 1 : 0);
        int childCol = 2 * parentCol + (childEast ? 1 : 0);
        return "0" + encodeQuadtreeLabel(level + 1, childRow, childCol);
    }
}
