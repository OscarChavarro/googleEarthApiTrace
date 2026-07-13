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
 * A tile is a "seed": its own id already IS a full root path, i.e. it is
 * exactly "0" (the root) or a string made only of quadrant digits 0-3 (the
 * convention used by {@code TopLevelMatrixRebuilder.quadtreePathLabel}: a
 * level-N tile is labeled with N quadrant digits, with an implicit "0" root
 * marker prepended to form the actual full path).
 *
 * A non-seed tile can still be anchored if one of its {@code uncles}
 * relationships points (by {@code uncleContentId}) to a tile whose path is
 * already known: the uncle is the immediately coarser tile containing this
 * tile in one of its 4 quadrants, and {@code direction} names that quadrant,
 * so the tile's path is simply the uncle's path with one quadrant digit
 * appended. Resolution proceeds as a fixpoint so that a chain of several
 * uncle hops can be walked one hop per pass. If a tile has several uncle
 * relationships that resolve to different candidate paths, the tile is
 * ambiguous and is permanently discarded (never exported).
 */
public final class TileRootPathResolver {
    private static final Pattern QUADKEY_PATTERN = Pattern.compile("[0-3]+");

    public record Resolution(Map<String, String> pathById, Set<String> discardedIds) {}

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
        for (Map.Entry<String, MatrixLayerTile> entry : tilesById.entrySet()) {
            String id = entry.getKey();
            if (isSeed(id)) {
                resolvedPath.put(id, seedPath(id));
            }
            else {
                String externalPath = externalFullPaths.get(id);
                if (externalPath != null) {
                    resolvedPath.put(id, externalPath);
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
                }
                else {
                    discarded.add(id);
                }
                progress = true;
            }
            if (!progress) {
                progress = propagateByGridPosition(layers, resolvedPath, discarded);
            }
        }

        return new Resolution(Map.copyOf(resolvedPath), Set.copyOf(discarded));
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
                if (id == null || id.isBlank() || resolvedPath.containsKey(id) || discarded.contains(id)) {
                    continue;
                }
                int row = tile.getI() + anchor[1];
                int col = tile.getJ() + anchor[2];
                if (row < 0 || col < 0 || row >= side || col >= side) {
                    continue;
                }
                resolvedPath.put(id, "0" + encodeQuadtreeLabel(level, row, col));
                progress = true;
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
            votes.merge(List.of(cell[0], cell[1] - tile.getI(), cell[2] - tile.getJ()), 1, Integer::sum);
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
                        + votes.size() + " candidates); skipping grid propagation for it."
                );
                return null;
            }
            System.out.println(
                "TileRootPathResolver: layer " + layer.getSourceFolderName()
                    + " has inconsistent anchors; keeping majority (" + bestCount + "/" + totalCount + ")."
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
            candidates.add(unclePath + quadrantDigit(relation.direction()));
        }
        return candidates;
    }

    private static boolean isSeed(String id) {
        return QUADKEY_PATTERN.matcher(id).matches();
    }

    private static String seedPath(String id) {
        return "0".equals(id) ? "0" : "0" + id;
    }

    /**
     * Quadrant digit convention shared with
     * {@code TopLevelMatrixRebuilder.quadtreePathLabel}: 0 = south-west,
     * 1 = south-east, 2 = north-east, 3 = north-west. Each
     * {@link UncleDirections} value names the quadrant a tile occupies
     * inside its uncle; the two spellings of each quadrant (e.g.
     * WEST_SOUTH/SOUTH_WEST) record which detection axis produced the
     * evidence and are equivalent.
     */
    private static int quadrantDigit(UncleDirections direction) {
        return switch (direction) {
            case WEST_SOUTH, SOUTH_WEST -> 0;
            case EAST_SOUTH, SOUTH_EAST -> 1;
            case EAST_NORTH, NORTH_EAST -> 2;
            case WEST_NORTH, NORTH_WEST -> 3;
        };
    }
}
