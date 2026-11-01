package pyramidalimageexporter.processing.toplevels;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import pyramidalimageexporter.diagnostics.PerformanceReport;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;

/**
 * Anchors an unresolved imported layer by comparing its native tiles with
 * quadrants of an already canonicalized imported layer from the same session.
 */
public final class ImportedLayerVisualAnchorResolver {
    private static final int MAX_PROBES = 16;
    private static final int MAX_ANCESTOR_LEVEL_GAP = 3;
    private static final int MIN_ANCHOR_VOTES = 3;
    private static final double MAX_RMSE = 35.0;
    private static final double MAX_BEST_TO_SECOND_RATIO = 0.75;
    private static final double MAX_ANCESTOR_BEST_TO_SECOND_RATIO = 0.90;

    private final Map<String, SampledImage> imageCache = new HashMap<>();
    private final Map<FlatMatchKey, MatchPair> flatMatchCache = new HashMap<>();

    Map<String, String> resolve(
        List<MatrixLayer> layers,
        TileRootPathResolver.Resolution resolution
    ) {
        return resolve(layers, resolution, Map.of());
    }

    /**
     * Validates already placed layers at the deepest reference level against
     * the quadrants of the reference level immediately above them. This is
     * deliberately independent of the layer's structural parent: it catches
     * a rigid but geographically shifted descendant chain when the new area
     * has no byte-identical destination tiles yet.
     */
    public Map<String, String> resolveDeepReferenceLayers(
        List<MatrixLayer> layers,
        TileRootPathResolver.Resolution resolution,
        Map<String, String> referenceQuadPathsByImagePath
    ) {
        Map<String, String> anchors = new LinkedHashMap<>();
        if (layers == null || resolution == null
            || referenceQuadPathsByImagePath == null || referenceQuadPathsByImagePath.isEmpty()) {
            return anchors;
        }
        int deepestLevel = deepestReferenceLevel(referenceQuadPathsByImagePath);
        List<ParentTile> parents = referenceParentTiles(referenceQuadPathsByImagePath);
        if (deepestLevel == Integer.MAX_VALUE || parents.isEmpty()) {
            return anchors;
        }
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null || layer.getTiles().isEmpty()
                || !isPlacedAtLevel(layer, resolution, deepestLevel)) {
                continue;
            }
            // Supplying only this layer prevents its structural parent from
            // hiding the independent reference-parent candidates.
            AnchorChoice choice = chooseAnchor(layer, List.of(layer), parents, deepestLevel);
            if (choice == null || choice.anchor().level() != deepestLevel) {
                continue;
            }
            int side = 1 << deepestLevel;
            int assigned = 0;
            for (MatrixLayerTile tile : layer.getTiles()) {
                int row = tile.getI() + choice.anchor().rowOffset();
                int col = Math.floorMod(tile.getJ() + choice.anchor().colOffset(), side);
                if (row < 0 || row >= side || tile.getId() == null || tile.getId().isBlank()) {
                    continue;
                }
                anchors.put(tile.getId(), encodeFullPath(deepestLevel, row, col));
                assigned++;
            }
            System.out.println(
                "ImportedLayerVisualAnchorResolver: independently reference-validated layer "
                    + layer.getSourceFolderName() + " at [" + deepestLevel + ", "
                    + choice.anchor().rowOffset() + ", " + choice.anchor().colOffset() + "]"
                    + " from " + choice.votes() + "/" + choice.acceptedProbes()
                    + " confident probes; assigned " + assigned + " tile(s)."
            );
        }
        imageCache.clear();
        flatMatchCache.clear();
        return anchors;
    }

    private static boolean isPlacedAtLevel(
        MatrixLayer layer,
        TileRootPathResolver.Resolution resolution,
        int expectedLevel
    ) {
        for (MatrixLayerTile tile : layer.getTiles()) {
            String path = resolution.pathFor(layer, tile);
            if (path != null && path.length() - 1 == expectedLevel) {
                return true;
            }
        }
        return false;
    }

    Map<String, String> resolve(
        List<MatrixLayer> layers,
        TileRootPathResolver.Resolution resolution,
        Map<String, String> referenceQuadPathsByImagePath
    ) {
        Map<String, String> anchors = new LinkedHashMap<>();
        if (layers == null || resolution == null) {
            return anchors;
        }
        List<MatrixLayer> unresolvedLayers = unresolvedLayers(layers, resolution);
        if (unresolvedLayers.isEmpty()) {
            PerformanceReport.increment("importedVisualAnchor.layers.noneUnresolved");
            return anchors;
        }
        List<ParentTile> parents = PerformanceReport.time(
            "importedVisualAnchor.stronglyAnchoredParents",
            () -> stronglyAnchoredParents(layers, resolution)
        );
        parents.addAll(PerformanceReport.time(
            "importedVisualAnchor.referenceParentTiles",
            () -> referenceParentTiles(referenceQuadPathsByImagePath)
        ));
        int maximumTargetLevel = PerformanceReport.time(
            "importedVisualAnchor.deepestReferenceLevel",
            () -> deepestReferenceLevel(referenceQuadPathsByImagePath)
        );
        PerformanceReport.incrementBy("importedVisualAnchor.layers.unresolved", unresolvedLayers.size());
        PerformanceReport.incrementBy("importedVisualAnchor.parents", parents.size());
        for (MatrixLayer childLayer : unresolvedLayers) {
            AnchorChoice choice = chooseAnchor(childLayer, layers, parents, maximumTargetLevel);
            if (choice == null) {
                continue;
            }
            int side = 1 << choice.anchor().level();
            int assigned = 0;
            for (MatrixLayerTile tile : childLayer.getTiles()) {
                int row = tile.getI() + choice.anchor().rowOffset();
                int col = Math.floorMod(tile.getJ() + choice.anchor().colOffset(), side);
                if (row < 0 || row >= side || tile.getId() == null || tile.getId().isBlank()) {
                    continue;
                }
                anchors.put(tile.getId(), encodeFullPath(choice.anchor().level(), row, col));
                assigned++;
            }
            System.out.println(
                "ImportedLayerVisualAnchorResolver: layer " + childLayer.getSourceFolderName()
                    + " anchored at [" + choice.anchor().level() + ", "
                    + choice.anchor().rowOffset() + ", " + choice.anchor().colOffset() + "]"
                    + " from " + choice.votes() + "/" + choice.acceptedProbes()
                    + " confident parent-quadrant probes; assigned " + assigned + " tile(s)."
            );
        }
        imageCache.clear();
        flatMatchCache.clear();
        return anchors;
    }

    private List<MatrixLayer> unresolvedLayers(
        List<MatrixLayer> layers,
        TileRootPathResolver.Resolution resolution
    ) {
        List<MatrixLayer> unresolved = new ArrayList<>();
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null || layer.getTiles().isEmpty()) {
                continue;
            }
            if (!isStronglyResolved(layer, resolution)) {
                unresolved.add(layer);
            }
        }
        return unresolved;
    }

    Map<String, String> resolveExternalTextures(
        Map<String, String> texturePathByExternalId,
        List<MatrixLayer> layers,
        TileRootPathResolver.Resolution resolution,
        int maximumTargetLevel
    ) {
        Map<String, String> anchors = new LinkedHashMap<>();
        if (texturePathByExternalId == null || texturePathByExternalId.isEmpty()
            || layers == null || resolution == null) {
            return anchors;
        }
        List<ParentTile> parents = stronglyAnchoredParents(layers, resolution);
        Map<String, String> plausibleAnchors = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : texturePathByExternalId.entrySet()) {
            MatrixLayerTile external = new MatrixLayerTile();
            external.setId(entry.getKey());
            external.setTextureFile(entry.getValue());
            MatchPair pair = bestTwoMatches(
                external,
                null,
                null,
                parents,
                maximumTargetLevel,
                maximumTargetLevel,
                1
            );
            if (pair != null && pair.best().rmse() < pair.second().rmse()
                && pair.best().rmse() <= MAX_RMSE) {
                Anchor candidate = anchorFor(external, pair.best());
                plausibleAnchors.put(
                    entry.getKey(),
                    encodeFullPath(candidate.level(), candidate.rowOffset(), candidate.colOffset())
                );
            }
            if (!confident(pair)) {
                continue;
            }
            Anchor anchor = anchorFor(external, pair.best());
            anchors.put(entry.getKey(), encodeFullPath(anchor.level(), anchor.rowOffset(), anchor.colOffset()));
        }
        if (plausibleAnchors.size() >= 2) {
            plausibleAnchors.forEach(anchors::putIfAbsent);
            System.out.println(
                "ImportedLayerVisualAnchorResolver: forwarding " + plausibleAnchors.size()
                    + " jointly plausible external texture anchor(s) for structural consistency checks."
            );
        }
        if (!anchors.isEmpty()) {
            System.out.println(
                "ImportedLayerVisualAnchorResolver: visually anchored " + anchors.size()
                    + " external texture(s) against resolved immediate-parent tiles."
            );
        }
        imageCache.clear();
        flatMatchCache.clear();
        return anchors;
    }

    private AnchorChoice chooseAnchor(
        MatrixLayer childLayer,
        List<MatrixLayer> layers,
        List<ParentTile> parents,
        int maximumTargetLevel
    ) {
        Map<Anchor, Integer> votes = new LinkedHashMap<>();
        Map<Anchor, Integer> plausibleVotes = new LinkedHashMap<>();
        int accepted = 0;
        int readable = 0;
        double lowestRmse = Double.POSITIVE_INFINITY;
        double lowestRatio = Double.POSITIVE_INFINITY;
        MatrixLayer designatedParent = designatedParentOf(childLayer, layers);
        for (MatrixLayerTile child : evenlySpaced(childLayer.getTiles(), MAX_PROBES)) {
            MatchPair pair = bestTwoMatches(
                child,
                childLayer,
                designatedParent,
                parents,
                0,
                maximumTargetLevel,
                MAX_ANCESTOR_LEVEL_GAP
            );
            if (pair != null) {
                readable++;
                double ratio = pair.best().rmse() / Math.max(1.0e-9, pair.second().rmse());
                lowestRmse = Math.min(lowestRmse, pair.best().rmse());
                lowestRatio = Math.min(lowestRatio, ratio);
                if (pair.best().rmse() <= MAX_RMSE) {
                    plausibleVotes.merge(anchorFor(child, pair.best()), 1, Integer::sum);
                }
            }
            if (!confident(pair)) {
                continue;
            }
            votes.merge(anchorFor(child, pair.best()), 1, Integer::sum);
            accepted++;
        }
        Anchor best = null;
        int bestVotes = 0;
        for (Map.Entry<Anchor, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestVotes) {
                best = vote.getKey();
                bestVotes = vote.getValue();
            }
        }
        if (best != null && bestVotes >= MIN_ANCHOR_VOTES && bestVotes * 2 > accepted) {
            return new AnchorChoice(best, bestVotes, accepted);
        }
        System.out.println(
            "ImportedLayerVisualAnchorResolver: layer " + childLayer.getSourceFolderName()
                + " not anchored; confident probes=" + accepted
                + ", distinct confident anchors=" + votes.size()
                + ", best confident vote=" + bestVotes
                + ", plausible probes=" + plausibleVotes.values().stream().mapToInt(Integer::intValue).sum()
                + ", distinct plausible anchors=" + plausibleVotes.size()
                + ", best plausible vote="
                + plausibleVotes.values().stream().mapToInt(Integer::intValue).max().orElse(0)
                + ", readable probes=" + readable
                + ", lowest RMSE=" + String.format(java.util.Locale.ROOT, "%.2f", lowestRmse)
                + ", lowest best/second ratio="
                + String.format(java.util.Locale.ROOT, "%.3f", lowestRatio) + "."
        );
        return null;
    }

    private static Anchor anchorFor(MatrixLayerTile child, Match match) {
        ParentTile parent = match.parent();
        int level = parent.level() + match.levelGap();
        int side = 1 << level;
        int scale = 1 << match.levelGap();
        int childRow = scale * parent.row() + match.subRow();
        int childCol = scale * parent.col() + match.subCol();
        return new Anchor(
            level,
            childRow - child.getI(),
            Math.floorMod(childCol - child.getJ(), side)
        );
    }

    private MatchPair bestTwoMatches(
        MatrixLayerTile child,
        MatrixLayer childLayer,
        MatrixLayer designatedParent,
        List<ParentTile> parents,
        int minimumTargetLevel,
        int maximumTargetLevel,
        int maximumLevelGap
    ) {
        SampledImage childImage = imageOf(child, 16);
        if (childImage == null) {
            return null;
        }
        FlatMatchKey flatKey = flatMatchKey(childImage, childLayer, designatedParent,
            parents, minimumTargetLevel, maximumTargetLevel, maximumLevelGap);
        if (flatKey != null && flatMatchCache.containsKey(flatKey)) {
            PerformanceReport.increment("importedVisualAnchor.flatMatchCache.hit");
            return flatMatchCache.get(flatKey);
        }
        if (flatKey != null) {
            PerformanceReport.increment("importedVisualAnchor.flatMatchCache.miss");
        }
        Map<CandidateCell, Match> bestMatchByCell = new HashMap<>();
        for (ParentTile parent : parents) {
            if (parent.layer() == childLayer
                || (designatedParent != null && parent.layer() != designatedParent)) {
                continue;
            }
            int minimumApplicableGap = Math.max(1, minimumTargetLevel - parent.level());
            int maximumApplicableGap = Math.min(
                maximumLevelGap,
                maximumTargetLevel - parent.level()
            );
            if (maximumApplicableGap < minimumApplicableGap) {
                continue;
            }
            int parentSampleStep = Math.max(1, 16 >> maximumApplicableGap);
            SampledImage parentImage = imageOf(parent.tile(), parentSampleStep);
            if (parentImage == null) {
                continue;
            }
            for (int levelGap = 1; levelGap <= maximumLevelGap; levelGap++) {
                int targetLevel = parent.level() + levelGap;
                if (targetLevel < minimumTargetLevel || targetLevel > maximumTargetLevel) {
                    continue;
                }
                int scale = 1 << levelGap;
                for (int subRow = 0; subRow < scale; subRow++) {
                    for (int subCol = 0; subCol < scale; subCol++) {
                        Match candidate = new Match(
                            parent,
                            levelGap,
                            subRow,
                            subCol,
                            rmse(parentImage, childImage, levelGap, subRow, subCol)
                        );
                        CandidateCell cell = new CandidateCell(
                            parent.level() + levelGap,
                            scale * parent.row() + subRow,
                            scale * parent.col() + subCol
                        );
                        Match previous = bestMatchByCell.get(cell);
                        if (previous == null || candidate.rmse() < previous.rmse()) {
                            bestMatchByCell.put(cell, candidate);
                        }
                    }
                }
            }
        }
        Match best = null;
        Match second = null;
        for (Match candidate : bestMatchByCell.values()) {
            if (best == null
                || candidate.rmse() < best.rmse()
                || (candidate.rmse() == best.rmse() && candidate.levelGap() < best.levelGap())) {
                best = candidate;
            }
        }
        if (best == null) {
            if (flatKey != null) {
                flatMatchCache.put(flatKey, null);
            }
            return null;
        }
        int bestTargetLevel = best.parent().level() + best.levelGap();
        for (Match candidate : bestMatchByCell.values()) {
            int candidateTargetLevel = candidate.parent().level() + candidate.levelGap();
            if (candidate == best || candidateTargetLevel != bestTargetLevel) {
                continue;
            }
            if (second == null || candidate.rmse() < second.rmse()) {
                second = candidate;
            }
        }
        MatchPair result = second == null ? null : new MatchPair(best, second);
        if (flatKey != null) {
            flatMatchCache.put(flatKey, result);
        }
        return result;
    }

    private static FlatMatchKey flatMatchKey(
        SampledImage image, MatrixLayer childLayer, MatrixLayer designatedParent,
        List<ParentTile> parents, int minimumLevel, int maximumLevel, int maximumGap
    ) {
        int color = image.pixels()[0];
        for (int pixel : image.pixels()) {
            if (pixel != color) {
                return null;
            }
        }
        MatrixLayer excluded = parents.stream().anyMatch(parent -> parent.layer() == childLayer)
            ? childLayer : null;
        // Parent lists are fixed during each resolve call. Include all candidate
        // restrictions so sharing a flat color never shares a geographic answer
        // across different reference sets or structural parents.
        return new FlatMatchKey(parents, excluded, childLayer == null, designatedParent,
            minimumLevel, maximumLevel, maximumGap, image.width(), image.height(), color);
    }

    private record FlatMatchKey(
        List<ParentTile> parents, MatrixLayer excludedLayer, boolean excludeNullParents,
        MatrixLayer designatedParent, int minimumLevel, int maximumLevel,
        int maximumGap, int width, int height, int color
    ) {}

    private static MatrixLayer designatedParentOf(MatrixLayer child, List<MatrixLayer> layers) {
        Integer parentIndex = child.getParentMatrixIndex();
        if (parentIndex == null || parentIndex < 0) {
            return null;
        }
        String expectedFolder = "matrix_" + parentIndex;
        for (MatrixLayer layer : layers) {
            if (layer != null && expectedFolder.equals(layer.getSourceFolderName())) {
                return layer;
            }
        }
        return null;
    }

    private List<ParentTile> stronglyAnchoredParents(
        List<MatrixLayer> layers,
        TileRootPathResolver.Resolution resolution
    ) {
        List<ParentTile> parents = new ArrayList<>();
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                TileRootPathResolver.PathSource source = resolution.sourceFor(layer, tile);
                if (source != TileRootPathResolver.PathSource.DIRECT
                    && source != TileRootPathResolver.PathSource.GRID) {
                    continue;
                }
                int[] cell = decodeFullPath(resolution.pathFor(layer, tile));
                if (cell != null) {
                    parents.add(new ParentTile(layer, tile, cell[0], cell[1], cell[2]));
                }
            }
        }
        return parents;
    }

    private static List<ParentTile> referenceParentTiles(Map<String, String> pathsByImage) {
        if (pathsByImage == null || pathsByImage.isEmpty()) {
            return List.of();
        }
        int deepestLevel = pathsByImage.values().stream()
            .filter(path -> path != null && path.matches("0[0-3]*"))
            .mapToInt(path -> path.length() - 1)
            .max()
            .orElse(-1);
        int parentLevel = deepestLevel - 1;
        if (parentLevel < 0) {
            return List.of();
        }
        List<ParentTile> parents = new ArrayList<>();
        for (Map.Entry<String, String> entry : pathsByImage.entrySet()) {
            int[] cell = decodeFullPath(entry.getValue());
            if (cell == null || cell[0] != parentLevel) {
                continue;
            }
            MatrixLayerTile tile = new MatrixLayerTile();
            tile.setId(entry.getValue());
            tile.setTextureFile(entry.getKey());
            parents.add(new ParentTile(null, tile, cell[0], cell[1], cell[2]));
        }
        return parents;
    }

    private static int deepestReferenceLevel(Map<String, String> pathsByImage) {
        if (pathsByImage == null || pathsByImage.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return pathsByImage.values().stream()
            .filter(path -> path != null && path.matches("0[0-3]*"))
            .mapToInt(path -> path.length() - 1)
            .max()
            .orElse(Integer.MAX_VALUE);
    }

    private boolean isStronglyResolved(MatrixLayer layer, TileRootPathResolver.Resolution resolution) {
        for (MatrixLayerTile tile : layer.getTiles()) {
            TileRootPathResolver.PathSource source = resolution.sourceFor(layer, tile);
            if (source != TileRootPathResolver.PathSource.DIRECT
                && source != TileRootPathResolver.PathSource.GRID) {
                return false;
            }
        }
        return true;
    }

    private SampledImage imageOf(MatrixLayerTile tile, int sampleStep) {
        String file = tile == null ? null : tile.getTextureFile();
        if (file == null || file.isBlank()) {
            return null;
        }
        SampledImage cached = imageCache.get(file);
        if (cached != null && cached.sampleStep() <= sampleStep) {
            return cached;
        }
        if (cached == null && imageCache.containsKey(file)) {
            return null;
        }
        BufferedImage image = null;
        try {
            image = ImageIO.read(Path.of(file).toFile());
        }
        catch (IOException | RuntimeException ignored) {
            // An unreadable texture provides no visual anchor evidence.
        }
        if (image == null) {
            imageCache.put(file, null);
            return null;
        }
        SampledImage sampled = SampledImage.from(image, sampleStep);
        image.flush();
        imageCache.put(file, sampled);
        return sampled;
    }

    private static double rmse(
        SampledImage parent,
        SampledImage child,
        int levelGap,
        int subRow,
        int subCol
    ) {
        if (parent.width() < 256 || parent.height() < 256
            || child.width() < 256 || child.height() < 256) {
            return Double.POSITIVE_INFINITY;
        }
        int scale = 1 << levelGap;
        int regionSize = 256 / scale;
        int x0 = subCol * regionSize;
        int y0 = subRow * regionSize;
        int step = Math.max(1, regionSize / 16);
        double sum = 0.0;
        long count = 0L;
        for (int y = 0; y < regionSize; y += step) {
            for (int x = 0; x < regionSize; x += step) {
                int parentRgb = parent.rgbAt(x0 + x, y0 + y);
                int childRgb = child.rgbAt(x * scale, y * scale);
                for (int shift : new int[]{16, 8, 0}) {
                    int delta = ((parentRgb >> shift) & 0xff) - ((childRgb >> shift) & 0xff);
                    sum += delta * delta;
                    count++;
                }
            }
        }
        return Math.sqrt(sum / count);
    }

    private record SampledImage(int width, int height, int sampleStep, int columns, int[] pixels) {
        private static SampledImage from(BufferedImage image, int sampleStep) {
            int step = Math.max(1, sampleStep);
            int columns = (image.getWidth() + step - 1) / step;
            int rows = (image.getHeight() + step - 1) / step;
            int[] pixels = new int[columns * rows];
            for (int row = 0; row < rows; row++) {
                int y = row * step;
                for (int column = 0; column < columns; column++) {
                    pixels[row * columns + column] = image.getRGB(column * step, y);
                }
            }
            return new SampledImage(image.getWidth(), image.getHeight(), step, columns, pixels);
        }

        private int rgbAt(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height
                || x % sampleStep != 0 || y % sampleStep != 0) {
                throw new IllegalArgumentException(
                    "Pixel (" + x + ", " + y + ") is outside sampled grid step " + sampleStep
                );
            }
            return pixels[(y / sampleStep) * columns + x / sampleStep];
        }
    }

    private static boolean confident(MatchPair pair) {
        double maxRatio = pair != null && pair.best().levelGap() > 1
            ? MAX_ANCESTOR_BEST_TO_SECOND_RATIO
            : MAX_BEST_TO_SECOND_RATIO;
        return pair != null
            && pair.best().rmse() < pair.second().rmse()
            && pair.best().rmse() <= MAX_RMSE
            && pair.best().rmse() / Math.max(1.0e-9, pair.second().rmse()) <= maxRatio;
    }

    private static <T> List<T> evenlySpaced(List<T> values, int limit) {
        if (values.size() <= limit) {
            return values;
        }
        List<T> selected = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            selected.add(values.get(index * (values.size() - 1) / (limit - 1)));
        }
        return selected;
    }

    private static int[] decodeFullPath(String path) {
        if (path == null || !path.matches("0[0-3]*")) {
            return null;
        }
        int row = 0;
        int col = 0;
        for (int index = 1; index < path.length(); index++) {
            int quadrant = path.charAt(index) - '0';
            row = 2 * row + (quadrant <= 1 ? 1 : 0);
            col = 2 * col + (quadrant == 1 || quadrant == 2 ? 1 : 0);
        }
        return new int[]{path.length() - 1, row, col};
    }

    private static String encodeFullPath(int level, int row, int col) {
        StringBuilder path = new StringBuilder("0");
        for (int depth = level - 1; depth >= 0; depth--) {
            boolean south = ((row >> depth) & 1) == 1;
            boolean east = ((col >> depth) & 1) == 1;
            path.append(south ? (east ? '1' : '0') : (east ? '2' : '3'));
        }
        return path.toString();
    }

    private record Anchor(int level, int rowOffset, int colOffset) {}
    private record AnchorChoice(Anchor anchor, int votes, int acceptedProbes) {}
    private record ParentTile(MatrixLayer layer, MatrixLayerTile tile, int level, int row, int col) {}
    private record CandidateCell(int level, int row, int col) {}
    private record Match(ParentTile parent, int levelGap, int subRow, int subCol, double rmse) {}
    private record MatchPair(Match best, Match second) {}
}
