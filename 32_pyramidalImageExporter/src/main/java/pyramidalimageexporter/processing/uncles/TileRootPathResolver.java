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
import pyramidalimageexporter.model.ParentGridTransform;

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
 * already known. The referenced texture is the immediately coarser tile that
 * contains the fine tile, and {@code direction} identifies the quadrant of
 * that texture used by the fine tile. Its path is therefore the uncle path
 * followed by that quadrant digit. Resolution proceeds as a fixpoint so that
 * a chain of several uncle hops can be walked one hop per pass. If a tile has
 * several uncle relationships that resolve to different candidate paths, the tile is
 * ambiguous and is permanently discarded (never exported).
 */
public final class TileRootPathResolver {
    private static final Pattern QUADKEY_PATTERN = Pattern.compile("[0-3]+");
    private static final int MIN_GRID_ANCHOR_VOTES = 3;
    private final UncleRmsAnalyzer rmsAnalyzer = new UncleRmsAnalyzer();

    public enum PathSource {
        DIRECT,
        UNCLE,
        GRID
    }

    public record Resolution(
        Map<String, String> pathById,
        Set<String> discardedIds,
        Map<String, PathSource> sourceById,
        Map<String, String> pathByOccurrence,
        Set<String> discardedOccurrences,
        Map<String, PathSource> sourceByOccurrence
    ) {
        public Resolution(Map<String, String> pathById, Set<String> discardedIds, Map<String, PathSource> sourceById) {
            this(pathById, discardedIds, sourceById, Map.of(), Set.of(), Map.of());
        }

        public String pathFor(MatrixLayer layer, MatrixLayerTile tile) {
            if (tile == null) {
                return null;
            }
            String occurrencePath = pathByOccurrence.get(occurrenceKey(layer, tile));
            return occurrencePath == null ? pathById.get(tile.getId()) : occurrencePath;
        }

        public PathSource sourceFor(MatrixLayer layer, MatrixLayerTile tile) {
            if (tile == null) {
                return null;
            }
            PathSource occurrenceSource = sourceByOccurrence.get(occurrenceKey(layer, tile));
            return occurrenceSource == null ? sourceById.get(tile.getId()) : occurrenceSource;
        }

        public boolean isDiscarded(MatrixLayer layer, MatrixLayerTile tile) {
            return tile != null
                && (discardedOccurrences.contains(occurrenceKey(layer, tile)) || discardedIds.contains(tile.getId()));
        }
    }

    private record TileOccurrence(MatrixLayer layer, MatrixLayerTile tile, String key, String id) {}

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
        List<TileOccurrence> occurrences = collectTileOccurrences(layers);
        Map<String, List<String>> occurrenceKeysById = occurrenceKeysById(occurrences);
        UncleRmsAnalyzer.Analysis rmsAnalysis = rmsAnalyzer.analyze(layers, uncleAliases);

        Map<String, String> resolvedPath = new HashMap<>();
        Map<String, PathSource> sourceById = new HashMap<>();
        for (TileOccurrence occurrence : occurrences) {
            if (isSeed(occurrence.id())) {
                resolvedPath.put(occurrence.key(), occurrence.id());
                sourceById.put(occurrence.key(), PathSource.DIRECT);
            }
            else {
                String externalPath = externalFullPaths.get(occurrence.id());
                if (externalPath != null) {
                    resolvedPath.put(occurrence.key(), externalPath);
                    sourceById.put(occurrence.key(), PathSource.DIRECT);
                }
            }
        }

        Set<String> discarded = new HashSet<>();
        // Canonicalize absolute seeds before walking uncle links. In particular, a
        // full-world layer has no arbitrary longitude phase: matrix column zero is
        // the antimeridian. Letting its noisy anchors reach a child first doubles
        // the longitudinal error at every subsequent quadtree level.
        // Canonicalize complete-world grids to a fixpoint before relative evidence
        // can escape into descendants. Grid propagation itself advances one layer
        // at a time so every newly completed parent gets a fresh uncle pass.
        while (propagateByGridPosition(layers, resolvedPath, sourceById, discarded, true)) {
            // Deliberately empty.
        }
        propagateByParentGridTransforms(layers, resolvedPath, sourceById, discarded);
        Set<String> seenStates = new HashSet<>();
        seenStates.add(resolutionStateSignature(resolvedPath, sourceById, discarded));
        boolean progress = true;
        while (progress) {
            progress = false;
            progress |= propagateLegacyRelationshipConsensus(
                layers,
                resolvedPath,
                sourceById,
                discarded,
                externalFullPaths,
                uncleAliases,
                occurrenceKeysById
            );
            for (TileOccurrence occurrence : occurrences) {
                if (resolvedPath.containsKey(occurrence.key()) || discarded.contains(occurrence.key())) {
                    continue;
                }
                Set<String> candidates =
                    candidatePathsFor(
                        occurrence,
                        resolvedPath,
                        externalFullPaths,
                        uncleAliases,
                        occurrenceKeysById,
                        rmsAnalysis
                    );
                if (candidates.isEmpty()) {
                    continue;
                }
                if (candidates.size() == 1) {
                    resolvedPath.put(occurrence.key(), candidates.iterator().next());
                    sourceById.put(occurrence.key(), PathSource.UNCLE);
                }
                else {
                    discarded.add(occurrence.key());
                }
                progress = true;
            }
            progress |= propagateByParentGridTransforms(layers, resolvedPath, sourceById, discarded);
            progress |= propagateByGridPosition(layers, resolvedPath, sourceById, discarded, false);
            String stateSignature = resolutionStateSignature(resolvedPath, sourceById, discarded);
            if (!seenStates.add(stateSignature)) {
                System.out.println(
                    "TileRootPathResolver: repeated resolution state detected after "
                        + seenStates.size()
                        + " unique state(s); stopping propagation to avoid an infinite cycle."
                );
                break;
            }
        }

        return new Resolution(
            Map.copyOf(collapseById(occurrences, resolvedPath)),
            Set.copyOf(collapseDiscardedById(occurrences, discarded)),
            Map.copyOf(collapseSourceById(occurrences, sourceById)),
            Map.copyOf(resolvedPath),
            Set.copyOf(discarded),
            Map.copyOf(sourceById)
        );
    }

    /**
     * Applies a contract-v3 parent transform only after the referenced parent
     * grid has an accepted absolute anchor. This preserves the distinction
     * between a rigid matrix transform and an observed per-tile coarse-texture
     * quadrant relationship.
     */
    private static boolean propagateByParentGridTransforms(
        List<MatrixLayer> layers,
        Map<String, String> resolvedPath,
        Map<String, PathSource> sourceById,
        Set<String> discarded
    ) {
        if (layers == null || layers.isEmpty()) {
            return false;
        }
        boolean progress = false;
        for (MatrixLayer child : layers) {
            ParentGridTransform transform = child == null ? null : child.getParentGridTransform();
            Integer parentIndex = child == null ? null : child.getParentMatrixIndex();
            if (transform == null || parentIndex == null || parentIndex < 0) {
                continue;
            }
            MatrixLayer parent = findImportedLayer(layers, parentIndex);
            if (parent == null) {
                continue;
            }
            int[] parentAnchor = consistentGridAnchor(parent, resolvedPath, sourceById);
            if (parentAnchor == null || parentAnchor[0] < 0) {
                continue;
            }
            int levelDelta = child.getParentLevelDelta() == null ? 1 : child.getParentLevelDelta();
            if (levelDelta <= 0 || levelDelta >= 30) {
                continue;
            }
            int childLevel = parentAnchor[0] + levelDelta;
            int side = 1 << childLevel;
            int scale = 1 << levelDelta;
            int rowOffset = scale * parentAnchor[1] + transform.rowOffset();
            int colOffset = Math.floorMod(scale * parentAnchor[2] + transform.colOffset(), side);
            for (MatrixLayerTile tile : child.getTiles()) {
                if (tile == null || tile.getId() == null || tile.getId().isBlank()) {
                    continue;
                }
                String key = occurrenceKey(child, tile);
                PathSource previousSource = sourceById.get(key);
                if (previousSource == PathSource.DIRECT) {
                    continue;
                }
                int row = tile.getI() + rowOffset;
                int col = Math.floorMod(tile.getJ() + colOffset, side);
                if (row < 0 || row >= side) {
                    continue;
                }
                String path = "0" + encodeQuadtreeLabel(childLevel, row, col);
                if (!path.equals(resolvedPath.put(key, path)) || previousSource != PathSource.GRID) {
                    progress = true;
                }
                sourceById.put(key, PathSource.GRID);
                discarded.remove(key);
            }
        }
        return progress;
    }

    private static MatrixLayer findImportedLayer(List<MatrixLayer> layers, int matrixIndex) {
        String sourceFolder = "matrix_" + matrixIndex;
        for (MatrixLayer layer : layers) {
            if (layer != null && sourceFolder.equals(layer.getSourceFolderName())) {
                return layer;
            }
        }
        return null;
    }

    /**
     * A merged matrix layer is a rigid grid: all its tiles share one level,
     * and their (i, j) cells map to quadtree row/col through one common
     * offset. So a single anchored tile positions the whole layer: every
     * other tile's full path follows from its (i, j) alone. When noisy
     * anchors disagree, placement requires either a strict majority for the
     * complete anchor or strict majorities for each independent component.
     */
    private static boolean propagateByGridPosition(
        List<MatrixLayer> layers,
        Map<String, String> resolvedPath,
        Map<String, PathSource> sourceById,
        Set<String> discarded,
        boolean fullWorldOnly
    ) {
        boolean progress = false;
        if (layers == null) {
            return false;
        }
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            int[] anchor = consistentGridAnchor(layer, resolvedPath, sourceById);
            if (anchor == null || anchor[0] <= 0) {
                continue;
            }
            int level = anchor[0];
            int side = 1 << level;
            if (fullWorldOnly && layer.getCols() != side) {
                continue;
            }
            boolean layerChanged = false;
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String id = tile.getId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                int row = tile.getI() + anchor[1];
                int col = Math.floorMod(tile.getJ() + anchor[2], side);
                if (row < 0 || row >= side) {
                    continue;
                }
                String canonicalPath = "0" + encodeQuadtreeLabel(level, row, col);
                String key = occurrenceKey(layer, tile);
                String previousPath = resolvedPath.put(key, canonicalPath);
                if (!canonicalPath.equals(previousPath)) {
                    sourceById.put(key, PathSource.GRID);
                    progress = true;
                    layerChanged = true;
                }
                else if (sourceById.get(key) != PathSource.DIRECT) {
                    if (sourceById.get(key) != PathSource.GRID) {
                        progress = true;
                        layerChanged = true;
                    }
                    sourceById.put(key, PathSource.GRID);
                }
                discarded.remove(key);
            }
            if (layerChanged) {
                return true;
            }
        }
        return progress;
    }

    /**
     * Returns {level, rowOffset, colOffset} supported by the anchored tiles
     * of the layer (offsets being quadtree row/col minus matrix i/j), or
     * null when the layer has no usable anchor. Anchored tiles vote: a lone
     * bad anchor (typically produced by texture deduplication equating two
     * identical-looking cells, which shifts a chain by one cell) is outvoted.
     * A complete tuple majority wins first. Otherwise level, row offset and
     * cyclic column offset vote independently; all three still need strict
     * majorities, or the layer is left unresolved for stronger evidence.
     */
    private static int[] consistentGridAnchor(
        MatrixLayer layer,
        Map<String, String> resolvedPath,
        Map<String, PathSource> sourceById
    ) {
        PathSource strongestSource = strongestSourceInLayer(layer, resolvedPath, sourceById);
        Map<List<Integer>, Integer> votes = new LinkedHashMap<>();
        for (MatrixLayerTile tile : layer.getTiles()) {
            if (tile == null) {
                continue;
            }
            String fullPath = resolvedPath.get(occurrenceKey(layer, tile));
            if (fullPath == null) {
                continue;
            }
            if (sourceById.get(occurrenceKey(layer, tile)) != strongestSource) {
                continue;
            }
            int[] cell = decodeFullPath(fullPath);
            if (cell == null) {
                continue;
            }
            int side = 1 << cell[0];
            int rowOffset = layer.getRows() == side ? 0 : cell[1] - tile.getI();
            int colOffset = layer.getCols() == side
                ? 0
                : Math.floorMod(cell[2] - tile.getJ(), side);
            List<Integer> anchor = List.of(cell[0], rowOffset, colOffset);
            votes.merge(anchor, 1, Integer::sum);
        }
        if (votes.isEmpty()) {
            return null;
        }
        AnchorVote winner = strictMajority(votes);
        if (winner == null) {
            ComponentVote level = strictComponentMajority(votes, 0);
            Map<List<Integer>, Integer> votesAtWinningLevel =
                level == null ? Map.of() : votesAtLevel(votes, level.value());
            ComponentVote row = strictComponentMajority(votesAtWinningLevel, 1);
            ComponentVote col = strictComponentMajority(votesAtWinningLevel, 2);
            if (level != null && row != null && col != null) {
                List<Integer> combined = List.of(level.value(), row.value(), col.value());
                winner = new AnchorVote(combined, Math.min(row.count(), col.count()), level.total(), true);
                System.out.println(
                    "TileRootPathResolver: layer " + layer.getSourceFolderName()
                        + " has no majority for one complete anchor " + votes
                        + "; combining independent strict majorities as " + combined + "."
                );
            }
        }
        if (winner == null) {
            System.out.println(
                "TileRootPathResolver: layer " + layer.getSourceFolderName()
                    + " has no majority among its inconsistent anchors ("
                    + votes.size() + " candidates " + votes
                    + "); skipping grid propagation for it."
            );
            return null;
        }
        int requiredVotes = strongestSource == PathSource.UNCLE
            ? Math.min(MIN_GRID_ANCHOR_VOTES, layer.getTiles().size())
            : 1;
        if (winner.count() < requiredVotes) {
            System.out.println(
                "TileRootPathResolver: layer " + layer.getSourceFolderName()
                    + " has only " + winner.count() + " vote(s) for its best anchor; "
                    + requiredVotes + " are required before propagating a rigid grid."
            );
            return null;
        }
        if (votes.size() > 1 && !winner.componentWise()) {
            System.out.println(
                "TileRootPathResolver: layer " + layer.getSourceFolderName()
                    + " has inconsistent anchors " + votes + "; keeping majority ("
                    + winner.count() + "/" + winner.total() + ")."
            );
        }
        List<Integer> best = winner.anchor();
        return new int[]{best.get(0), best.get(1), best.get(2)};
    }

    /** Absolute/catalogued evidence outranks a grid consensus, which outranks relative uncle links. */
    private static PathSource strongestSourceInLayer(
        MatrixLayer layer,
        Map<String, String> resolvedPath,
        Map<String, PathSource> sourceById
    ) {
        PathSource strongest = null;
        for (MatrixLayerTile tile : layer.getTiles()) {
            if (tile == null || !resolvedPath.containsKey(occurrenceKey(layer, tile))) {
                continue;
            }
            PathSource source = sourceById.get(occurrenceKey(layer, tile));
            if (source == PathSource.DIRECT) {
                return source;
            }
            if (source == PathSource.GRID) {
                strongest = source;
            }
            else if (source == PathSource.UNCLE && strongest == null) {
                strongest = source;
            }
        }
        return strongest;
    }

    private record AnchorVote(List<Integer> anchor, int count, int total, boolean componentWise) {}
    private record ComponentVote(int value, int count, int total) {}

    private static AnchorVote strictMajority(Map<List<Integer>, Integer> votes) {
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
        return best != null && bestCount * 2 > totalCount
            ? new AnchorVote(best, bestCount, totalCount, false)
            : null;
    }

    private static ComponentVote strictComponentMajority(
        Map<List<Integer>, Integer> votes,
        int componentIndex
    ) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int totalCount = 0;
        for (Map.Entry<List<Integer>, Integer> vote : votes.entrySet()) {
            if (vote.getKey().size() <= componentIndex) {
                continue;
            }
            counts.merge(vote.getKey().get(componentIndex), vote.getValue(), Integer::sum);
            totalCount += vote.getValue();
        }
        int bestValue = 0;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> count : counts.entrySet()) {
            if (count.getValue() > bestCount) {
                bestValue = count.getKey();
                bestCount = count.getValue();
            }
        }
        return bestCount * 2 > totalCount
            ? new ComponentVote(bestValue, bestCount, totalCount)
            : null;
    }

    private static Map<List<Integer>, Integer> votesAtLevel(
        Map<List<Integer>, Integer> votes,
        int level
    ) {
        Map<List<Integer>, Integer> filtered = new LinkedHashMap<>();
        for (Map.Entry<List<Integer>, Integer> vote : votes.entrySet()) {
            if (!vote.getKey().isEmpty() && vote.getKey().get(0) == level) {
                filtered.put(vote.getKey(), vote.getValue());
            }
        }
        return filtered;
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

    private static List<TileOccurrence> collectTileOccurrences(List<MatrixLayer> layers) {
        List<TileOccurrence> occurrences = new java.util.ArrayList<>();
        if (layers == null) {
            return occurrences;
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
                occurrences.add(new TileOccurrence(layer, tile, occurrenceKey(layer, tile), id));
            }
        }
        return occurrences;
    }

    private static Map<String, List<String>> occurrenceKeysById(List<TileOccurrence> occurrences) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (TileOccurrence occurrence : occurrences) {
            out.computeIfAbsent(occurrence.id(), ignored -> new java.util.ArrayList<>()).add(occurrence.key());
        }
        return out;
    }

    private static Map<String, String> collapseById(List<TileOccurrence> occurrences, Map<String, String> pathByOccurrence) {
        Map<String, String> out = new LinkedHashMap<>();
        for (TileOccurrence occurrence : occurrences) {
            String path = pathByOccurrence.get(occurrence.key());
            if (path != null) {
                out.putIfAbsent(occurrence.id(), path);
            }
        }
        return out;
    }

    private static Set<String> collapseDiscardedById(List<TileOccurrence> occurrences, Set<String> discardedOccurrences) {
        Set<String> out = new LinkedHashSet<>();
        for (TileOccurrence occurrence : occurrences) {
            if (discardedOccurrences.contains(occurrence.key())) {
                out.add(occurrence.id());
            }
        }
        return out;
    }

    private static Map<String, PathSource> collapseSourceById(List<TileOccurrence> occurrences, Map<String, PathSource> sourceByOccurrence) {
        Map<String, PathSource> out = new LinkedHashMap<>();
        for (TileOccurrence occurrence : occurrences) {
            PathSource source = sourceByOccurrence.get(occurrence.key());
            if (source != null) {
                out.putIfAbsent(occurrence.id(), source);
            }
        }
        return out;
    }

    private static String occurrenceKey(MatrixLayer layer, MatrixLayerTile tile) {
        String layerName = layer == null || layer.getSourceFolderName() == null
            ? "<unknown>"
            : layer.getSourceFolderName();
        String tileId = tile == null ? "" : tile.getId();
        return layerName + "\u0000" + tileId;
    }

    private static Set<String> candidatePathsFor(
        TileOccurrence occurrence,
        Map<String, String> resolvedPath,
        Map<String, String> externalFullPaths,
        Map<String, String> uncleAliases,
        Map<String, List<String>> occurrenceKeysById,
        UncleRmsAnalyzer.Analysis rmsAnalysis
    ) {
        Set<String> candidates = new LinkedHashSet<>();
        MatrixLayerTile tile = occurrence == null ? null : occurrence.tile();
        if (tile == null) {
            return candidates;
        }
        List<ToUncleRelationship> uncles = tile.getUncles();
        if (uncles == null) {
            return candidates;
        }
        for (ToUncleRelationship relation : uncles) {
            if (relation == null || relation.referenceContentId() == null
                || (!relation.hasGridOffset() && relation.direction() == null)) {
                continue;
            }
            UncleRelationshipKind relationshipKind = relation.relationshipKind();
            if (relationshipKind == null) {
                // Legacy records are resolved as competing containing/adjacent
                // hypotheses by the rigid-grid consensus pass.
                continue;
            }
            if (relationshipKind == UncleRelationshipKind.CONTAINING_QUADRANT
                && !relation.hasGridOffset()
                && rmsAnalysis != null
                && !rmsAnalysis.accepts(occurrence.layer(), tile, relation)) {
                continue;
            }
            String uncleId = uncleAliases.getOrDefault(relation.uncleContentId(), relation.uncleContentId());
            boolean foundLoadedUnclePath = false;
            for (String uncleOccurrenceKey : occurrenceKeysById.getOrDefault(uncleId, List.of())) {
                String unclePath = resolvedPath.get(uncleOccurrenceKey);
                if (unclePath != null) {
                    foundLoadedUnclePath = true;
                    String candidate = childPathForRelationship(
                        unclePath,
                        relation,
                        relationshipKind
                    );
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }
            }
            if (foundLoadedUnclePath) {
                continue;
            }
            String unclePath = externalFullPaths.get(uncleId);
            if (unclePath == null) {
                continue;
            }
            String candidate = childPathForRelationship(
                unclePath,
                relation,
                relationshipKind
            );
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    /**
     * Contract-v3 and older files did not distinguish a containing coarse
     * texture from an adjacent coarse tile. Evaluate both interpretations
     * once per relationship and select the unique rigid-grid anchor supported
     * by the most distinct relationships.
     */
    private static boolean propagateLegacyRelationshipConsensus(
        List<MatrixLayer> layers,
        Map<String, String> resolvedPath,
        Map<String, PathSource> sourceById,
        Set<String> discarded,
        Map<String, String> externalFullPaths,
        Map<String, String> uncleAliases,
        Map<String, List<String>> occurrenceKeysById
    ) {
        if (layers == null) {
            return false;
        }
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null || layer.getTiles().isEmpty()) {
                continue;
            }
            PathSource strongest = strongestSourceInLayer(layer, resolvedPath, sourceById);
            if (strongest == PathSource.DIRECT || strongest == PathSource.GRID) {
                continue;
            }
            Map<List<Integer>, Set<String>> supportersByAnchor = new LinkedHashMap<>();
            Set<String> usableRelationships = new LinkedHashSet<>();
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null || tile.getUncles() == null) {
                    continue;
                }
                for (ToUncleRelationship relationship : tile.getUncles()) {
                    if (relationship == null
                        || relationship.relationshipKind() != null
                        || relationship.direction() == null
                        || relationship.uncleContentId() == null) {
                        continue;
                    }
                    String relationshipKey = occurrenceKey(layer, tile)
                        + "\u0001" + relationship.direction()
                        + "\u0001" + relationship.uncleContentId();
                    Set<String> parentPaths = parentPathsFor(
                        relationship,
                        resolvedPath,
                        externalFullPaths,
                        uncleAliases,
                        occurrenceKeysById
                    );
                    Set<String> childCandidates = new LinkedHashSet<>();
                    for (String parentPath : parentPaths) {
                        childCandidates.add(parentPath + quadrantDigit(relationship.direction()));
                        String adjacent = childPathAcrossUncleBorder(parentPath, relationship.direction());
                        if (adjacent != null) {
                            childCandidates.add(adjacent);
                        }
                    }
                    if (childCandidates.isEmpty()) {
                        continue;
                    }
                    usableRelationships.add(relationshipKey);
                    for (String candidate : childCandidates) {
                        int[] cell = decodeFullPath(candidate);
                        if (cell == null) {
                            continue;
                        }
                        int side = 1 << cell[0];
                        List<Integer> anchor = List.of(
                            cell[0],
                            cell[1] - tile.getI(),
                            Math.floorMod(cell[2] - tile.getJ(), side)
                        );
                        supportersByAnchor
                            .computeIfAbsent(anchor, ignored -> new LinkedHashSet<>())
                            .add(relationshipKey);
                    }
                }
            }
            if (usableRelationships.size() < MIN_GRID_ANCHOR_VOTES || supportersByAnchor.isEmpty()) {
                continue;
            }
            List<Integer> winner = null;
            int winnerSupport = 0;
            int runnerUpSupport = 0;
            for (Map.Entry<List<Integer>, Set<String>> candidate : supportersByAnchor.entrySet()) {
                int support = candidate.getValue().size();
                if (support > winnerSupport) {
                    runnerUpSupport = winnerSupport;
                    winner = candidate.getKey();
                    winnerSupport = support;
                }
                else if (support > runnerUpSupport) {
                    runnerUpSupport = support;
                }
            }
            if (winner == null
                || winnerSupport < MIN_GRID_ANCHOR_VOTES
                || winnerSupport * 2 <= usableRelationships.size()
                || winnerSupport <= runnerUpSupport) {
                continue;
            }
            int level = winner.get(0);
            int side = 1 << level;
            boolean changed = false;
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null || tile.getId() == null || tile.getId().isBlank()) {
                    continue;
                }
                int row = tile.getI() + winner.get(1);
                int col = Math.floorMod(tile.getJ() + winner.get(2), side);
                if (row < 0 || row >= side) {
                    continue;
                }
                String key = occurrenceKey(layer, tile);
                if (sourceById.get(key) == PathSource.DIRECT) {
                    continue;
                }
                String path = "0" + encodeQuadtreeLabel(level, row, col);
                if (!path.equals(resolvedPath.put(key, path)) || sourceById.get(key) != PathSource.GRID) {
                    changed = true;
                }
                sourceById.put(key, PathSource.GRID);
                discarded.remove(key);
            }
            if (changed) {
                System.out.println(
                    "TileRootPathResolver: layer " + layer.getSourceFolderName()
                        + " resolved legacy uncle semantics at " + winner
                        + " with " + winnerSupport + "/" + usableRelationships.size()
                        + " relationship(s); runner-up support=" + runnerUpSupport + "."
                );
                return true;
            }
        }
        return false;
    }

    private static Set<String> parentPathsFor(
        ToUncleRelationship relationship,
        Map<String, String> resolvedPath,
        Map<String, String> externalFullPaths,
        Map<String, String> uncleAliases,
        Map<String, List<String>> occurrenceKeysById
    ) {
        Set<String> paths = new LinkedHashSet<>();
        String uncleId = uncleAliases.getOrDefault(
            relationship.uncleContentId(),
            relationship.uncleContentId()
        );
        for (String occurrenceKey : occurrenceKeysById.getOrDefault(uncleId, List.of())) {
            String path = resolvedPath.get(occurrenceKey);
            if (path != null) {
                paths.add(path);
            }
        }
        if (paths.isEmpty()) {
            String external = externalFullPaths.get(uncleId);
            if (external != null) {
                paths.add(external);
            }
        }
        return paths;
    }

    private static String childPathForRelationship(
        String unclePath,
        ToUncleRelationship relationship,
        UncleRelationshipKind relationshipKind
    ) {
        if (relationship != null && relationship.hasGridOffset()) {
            return pathAtRelativeGridOffset(
                unclePath,
                relationship.levelDelta(),
                relationship.rowOffset(),
                relationship.columnOffset()
            );
        }
        UncleDirections direction = relationship == null ? null : relationship.direction();
        return switch (relationshipKind) {
            case CONTAINING_QUADRANT -> unclePath + quadrantDigit(direction);
            case ADJACENT_BORDER -> childPathAcrossUncleBorder(unclePath, direction);
        };
    }

    private static String pathAtRelativeGridOffset(
        String referencePath,
        int levelDelta,
        int rowOffset,
        int columnOffset
    ) {
        int[] reference = decodeFullPath(referencePath);
        if (reference == null || levelDelta <= 0 || reference[0] + levelDelta >= 30) {
            return null;
        }
        int scale = 1 << levelDelta;
        int targetLevel = reference[0] + levelDelta;
        int side = 1 << targetLevel;
        int row = scale * reference[1] + rowOffset;
        if (row < 0 || row >= side) {
            return null;
        }
        int column = Math.floorMod(scale * reference[2] + columnOffset, side);
        return "0" + encodeQuadtreeLabel(targetLevel, row, column);
    }

    private static String childPathAcrossUncleBorder(
        String unclePath,
        UncleDirections direction
    ) {
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

    private static String resolutionStateSignature(
        Map<String, String> resolvedPath,
        Map<String, PathSource> sourceById,
        Set<String> discarded
    ) {
        StringBuilder sb = new StringBuilder();
        resolvedPath.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sb
                .append(entry.getKey())
                .append('=')
                .append(entry.getValue())
                .append('@')
                .append(sourceById.get(entry.getKey()))
                .append(';'));
        sb.append('|');
        discarded.stream()
            .sorted()
            .forEach(id -> sb.append(id).append(';'));
        return sb.toString();
    }

    private static boolean isSeed(String id) {
        return QUADKEY_PATTERN.matcher(id).matches() && id.charAt(0) == '0';
    }

    /** Direction spellings collapse to the four quadrants of the coarser texture. */
    private static int quadrantDigit(UncleDirections direction) {
        return switch (direction) {
            case WEST_SOUTH, SOUTH_WEST -> 0;
            case EAST_SOUTH, SOUTH_EAST -> 1;
            case EAST_NORTH, NORTH_EAST -> 2;
            case WEST_NORTH, NORTH_WEST -> 3;
        };
    }
}
