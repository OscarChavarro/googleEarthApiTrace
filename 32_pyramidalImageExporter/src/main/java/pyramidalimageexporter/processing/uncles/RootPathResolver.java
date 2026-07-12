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
import pyramidalimageexporter.model.TileCoord;

/**
 * Resolves, for every tile across every matrix layer, its full quadtree path
 * from the pyramid root (a string of quadrant digits, e.g. "0021", the same
 * format the interactive viewer and {@code PyramidalImageExporter} already
 * use to place a tile in the output directory tree).
 *
 * A tile is a "seed": its own id already IS a full root path, i.e. it is
 * exactly "0" (the root) or a string made only of quadrant digits 0-3 (the
 * convention used by {@code TopLevelsMatricesImporter.quadtreePathLabel}: a
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
public final class RootPathResolver {
    private static final Pattern QUADKEY_PATTERN = Pattern.compile("[0-3]+");

    public record Resolution(Map<String, String> pathById, Set<String> discardedIds) {}

    public Resolution resolve(List<MatrixLayer> layers) {
        Map<String, TileCoord> tilesById = collectTilesById(layers);

        Map<String, String> resolvedPath = new HashMap<>();
        for (Map.Entry<String, TileCoord> entry : tilesById.entrySet()) {
            String id = entry.getKey();
            if (isSeed(id)) {
                resolvedPath.put(id, seedPath(id));
            }
        }

        Set<String> discarded = new HashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Map.Entry<String, TileCoord> entry : tilesById.entrySet()) {
                String id = entry.getKey();
                if (resolvedPath.containsKey(id) || discarded.contains(id)) {
                    continue;
                }
                Set<String> candidates = candidatePathsFor(entry.getValue(), resolvedPath);
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
        }

        return new Resolution(Map.copyOf(resolvedPath), Set.copyOf(discarded));
    }

    private static Map<String, TileCoord> collectTilesById(List<MatrixLayer> layers) {
        Map<String, TileCoord> tilesById = new LinkedHashMap<>();
        if (layers == null) {
            return tilesById;
        }
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (TileCoord tile : layer.getTiles()) {
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

    private static Set<String> candidatePathsFor(TileCoord tile, Map<String, String> resolvedPath) {
        Set<String> candidates = new LinkedHashSet<>();
        List<ToUncleRelationship> uncles = tile.getUncles();
        if (uncles == null) {
            return candidates;
        }
        for (ToUncleRelationship relation : uncles) {
            if (relation == null || relation.direction() == null || relation.uncleContentId() == null) {
                continue;
            }
            String unclePath = resolvedPath.get(relation.uncleContentId());
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
     * {@code TopLevelsMatricesImporter.quadtreePathLabel}: 0 = south-west,
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
