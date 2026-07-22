package pyramidalimageexporter.processing.uncles;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;

/**
 * Compares a fine tile with the four quadrants of each referenced coarse
 * texture. There is deliberately no absolute RMS threshold: the declared
 * quadrant is a visual match when its RMS is the minimum for that image pair.
 * RMS percentiles are computed inside each parent/child layer pair so the
 * debugger can show relative outliers without assuming one global texture
 * distribution for ocean, ice, desert, forest, or any other terrain.
 */
public final class UncleRmsAnalyzer {
    public record RelationshipKey(
        String childLayer,
        String childId,
        UncleDirections direction,
        String uncleId
    ) {}

    public record Match(
        RelationshipKey key,
        String parentLayer,
        String parentId,
        double declaredRms,
        double minimumRms,
        int bestQuadrant,
        boolean declaredQuadrantIsMinimum,
        double layerPairPercentile
    ) {}

    public record TileScore(
        double layerPairPercentile,
        boolean hasVisualMatch,
        int comparedRelationships,
        double lowestDeclaredRms
    ) {}

    public static final class Analysis {
        private final Map<RelationshipKey, Match> matches;
        private final Map<String, TileScore> tileScores;

        private Analysis(Map<RelationshipKey, Match> matches, Map<String, TileScore> tileScores) {
            this.matches = Map.copyOf(matches);
            this.tileScores = Map.copyOf(tileScores);
        }

        public static Analysis empty() {
            return new Analysis(Map.of(), Map.of());
        }

        public Map<RelationshipKey, Match> matches() {
            return matches;
        }

        public Match matchFor(MatrixLayer layer, MatrixLayerTile tile, ToUncleRelationship relationship) {
            if (tile == null || relationship == null) {
                return null;
            }
            return matches.get(keyOf(layer, tile, relationship));
        }

        public TileScore scoreFor(MatrixLayer layer, MatrixLayerTile tile) {
            return tile == null ? null : tileScores.get(tileKey(layer, tile));
        }

        /** Unreadable/unavailable image evidence stays neutral for legacy datasets. */
        public boolean accepts(MatrixLayer layer, MatrixLayerTile tile, ToUncleRelationship relationship) {
            Match match = matchFor(layer, tile, relationship);
            return match == null || match.declaredQuadrantIsMinimum();
        }
    }

    private record TileOccurrence(MatrixLayer layer, MatrixLayerTile tile) {}
    private record PairKey(String childLayer, String parentLayer) {}
    private record RawMatch(
        RelationshipKey key,
        String parentLayer,
        String parentId,
        double declaredRms,
        double minimumRms,
        int bestQuadrant,
        boolean declaredQuadrantIsMinimum
    ) {}
    private record ComparisonCacheKey(String childTexture, String parentTexture, UncleDirections direction) {}
    private record Comparison(double declaredRms, double minimumRms, int bestQuadrant, boolean declaredIsMinimum) {}

    private final Map<String, BufferedImage> imageCache = new HashMap<>();
    private final Map<ComparisonCacheKey, Comparison> comparisonCache = new HashMap<>();

    public Analysis analyze(List<MatrixLayer> layers, Map<String, String> uncleAliases) {
        if (layers == null || layers.isEmpty()) {
            return Analysis.empty();
        }
        Map<String, List<TileOccurrence>> occurrencesById = collectOccurrences(layers);
        List<RawMatch> rawMatches = new ArrayList<>();
        Map<String, String> aliases = uncleAliases == null ? Map.of() : uncleAliases;

        for (MatrixLayer childLayer : layers) {
            if (childLayer == null || childLayer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile child : childLayer.getTiles()) {
                if (child == null || child.getUncles() == null) {
                    continue;
                }
                for (ToUncleRelationship relationship : child.getUncles()) {
                    if (relationship == null || relationship.direction() == null
                        || relationship.uncleContentId() == null) {
                        continue;
                    }
                    String parentId = aliases.getOrDefault(
                        relationship.uncleContentId(),
                        relationship.uncleContentId()
                    );
                    for (TileOccurrence parent : occurrencesById.getOrDefault(parentId, List.of())) {
                        Comparison comparison = compare(child, parent.tile(), relationship.direction());
                        if (comparison == null) {
                            continue;
                        }
                        rawMatches.add(new RawMatch(
                            keyOf(childLayer, child, relationship),
                            layerName(parent.layer()),
                            parent.tile().getId(),
                            comparison.declaredRms(),
                            comparison.minimumRms(),
                            comparison.bestQuadrant(),
                            comparison.declaredIsMinimum()
                        ));
                    }
                }
            }
        }
        return buildAnalysis(rawMatches);
    }

    private Analysis buildAnalysis(List<RawMatch> rawMatches) {
        Map<PairKey, List<RawMatch>> byPair = new LinkedHashMap<>();
        for (RawMatch raw : rawMatches) {
            byPair.computeIfAbsent(
                new PairKey(raw.key().childLayer(), raw.parentLayer()),
                ignored -> new ArrayList<>()
            ).add(raw);
        }

        Map<RelationshipKey, Match> matches = new LinkedHashMap<>();
        for (List<RawMatch> pairMatches : byPair.values()) {
            List<RawMatch> ranked = pairMatches.stream()
                .sorted(Comparator.comparingDouble(RawMatch::declaredRms))
                .toList();
            int count = ranked.size();
            for (int rank = 0; rank < count; rank++) {
                RawMatch raw = ranked.get(rank);
                double percentile = count <= 1 ? 0.0 : (double) rank / (count - 1);
                Match candidate = new Match(
                    raw.key(), raw.parentLayer(), raw.parentId(), raw.declaredRms(),
                    raw.minimumRms(), raw.bestQuadrant(), raw.declaredQuadrantIsMinimum(), percentile
                );
                Match current = matches.get(raw.key());
                if (current == null || candidate.declaredRms() < current.declaredRms()) {
                    matches.put(raw.key(), candidate);
                }
            }
        }

        Map<String, List<Match>> matchesByTile = new LinkedHashMap<>();
        for (Match match : matches.values()) {
            matchesByTile.computeIfAbsent(
                match.key().childLayer() + "\u0000" + match.key().childId(),
                ignored -> new ArrayList<>()
            ).add(match);
        }
        Map<String, TileScore> tileScores = new LinkedHashMap<>();
        for (Map.Entry<String, List<Match>> entry : matchesByTile.entrySet()) {
            List<Match> tileMatches = entry.getValue();
            Match bestAccepted = tileMatches.stream()
                .filter(Match::declaredQuadrantIsMinimum)
                .min(Comparator.comparingDouble(Match::declaredRms))
                .orElse(null);
            Match representative = bestAccepted != null
                ? bestAccepted
                : tileMatches.stream().min(Comparator.comparingDouble(Match::declaredRms)).orElseThrow();
            tileScores.put(entry.getKey(), new TileScore(
                representative.layerPairPercentile(),
                bestAccepted != null,
                tileMatches.size(),
                representative.declaredRms()
            ));
        }
        return new Analysis(matches, tileScores);
    }

    private Comparison compare(MatrixLayerTile child, MatrixLayerTile parent, UncleDirections direction) {
        String childTexture = child == null ? null : child.getTextureFile();
        String parentTexture = parent == null ? null : parent.getTextureFile();
        if (childTexture == null || childTexture.isBlank() || parentTexture == null || parentTexture.isBlank()) {
            return null;
        }
        ComparisonCacheKey cacheKey = new ComparisonCacheKey(childTexture, parentTexture, direction);
        if (comparisonCache.containsKey(cacheKey)) {
            return comparisonCache.get(cacheKey);
        }
        BufferedImage childImage = imageOf(childTexture);
        BufferedImage parentImage = imageOf(parentTexture);
        if (childImage == null || parentImage == null || parentImage.getWidth() < 2 || parentImage.getHeight() < 2) {
            comparisonCache.put(cacheKey, null);
            return null;
        }

        int halfWidth = parentImage.getWidth() / 2;
        int halfHeight = parentImage.getHeight() / 2;
        if (halfWidth <= 0 || halfHeight <= 0) {
            comparisonCache.put(cacheKey, null);
            return null;
        }
        BufferedImage scaledChild = resize(childImage, halfWidth, halfHeight);
        double[] rms = new double[4];
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            int x = quadrant == 1 || quadrant == 2 ? parentImage.getWidth() - halfWidth : 0;
            int y = quadrant == 0 || quadrant == 1 ? parentImage.getHeight() - halfHeight : 0;
            rms[quadrant] = rms(scaledChild, parentImage, x, y, halfWidth, halfHeight);
        }
        int bestQuadrant = 0;
        for (int quadrant = 1; quadrant < rms.length; quadrant++) {
            if (rms[quadrant] < rms[bestQuadrant]) {
                bestQuadrant = quadrant;
            }
        }
        int declaredQuadrant = quadrantDigit(direction);
        double minimum = rms[bestQuadrant];
        // Exact ties remain valid: a uniform texture contains no evidence for
        // preferring a different quadrant, so the structural relationship is kept.
        boolean declaredIsMinimum = Double.compare(rms[declaredQuadrant], minimum) == 0;
        Comparison comparison = new Comparison(rms[declaredQuadrant], minimum, bestQuadrant, declaredIsMinimum);
        comparisonCache.put(cacheKey, comparison);
        return comparison;
    }

    private BufferedImage imageOf(String textureFile) {
        if (imageCache.containsKey(textureFile)) {
            return imageCache.get(textureFile);
        }
        BufferedImage image = null;
        try {
            image = ImageIO.read(Path.of(textureFile).toFile());
            if (image != null && image.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = rgb.createGraphics();
                graphics.drawImage(image, 0, 0, null);
                graphics.dispose();
                image = rgb;
            }
        }
        catch (IOException | RuntimeException ignored) {
            // Missing visual evidence is neutral; structural resolution remains available.
        }
        imageCache.put(textureFile, image);
        return image;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return resized;
    }

    private static double rms(
        BufferedImage child,
        BufferedImage parent,
        int parentX,
        int parentY,
        int width,
        int height
    ) {
        double sum = 0.0;
        long samples = 0L;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int childRgb = child.getRGB(x, y);
                int parentRgb = parent.getRGB(parentX + x, parentY + y);
                for (int shift : new int[]{16, 8, 0}) {
                    int delta = ((childRgb >> shift) & 0xff) - ((parentRgb >> shift) & 0xff);
                    sum += (double) delta * delta;
                    samples++;
                }
            }
        }
        return samples == 0L ? Double.POSITIVE_INFINITY : Math.sqrt(sum / samples);
    }

    private static Map<String, List<TileOccurrence>> collectOccurrences(List<MatrixLayer> layers) {
        Map<String, List<TileOccurrence>> out = new LinkedHashMap<>();
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile != null && tile.getId() != null && !tile.getId().isBlank()) {
                    out.computeIfAbsent(tile.getId(), ignored -> new ArrayList<>())
                        .add(new TileOccurrence(layer, tile));
                }
            }
        }
        return out;
    }

    private static RelationshipKey keyOf(
        MatrixLayer layer,
        MatrixLayerTile tile,
        ToUncleRelationship relationship
    ) {
        return new RelationshipKey(
            layerName(layer),
            tile == null ? "" : tile.getId(),
            relationship == null ? null : relationship.direction(),
            relationship == null ? null : relationship.uncleContentId()
        );
    }

    private static String tileKey(MatrixLayer layer, MatrixLayerTile tile) {
        return layerName(layer) + "\u0000" + (tile == null ? "" : tile.getId());
    }

    private static String layerName(MatrixLayer layer) {
        return layer == null || layer.getSourceFolderName() == null
            ? "<unknown>"
            : layer.getSourceFolderName();
    }

    private static int quadrantDigit(UncleDirections direction) {
        return switch (direction) {
            case WEST_SOUTH, SOUTH_WEST -> 0;
            case EAST_SOUTH, SOUTH_EAST -> 1;
            case EAST_NORTH, NORTH_EAST -> 2;
            case WEST_NORTH, NORTH_WEST -> 3;
        };
    }
}
