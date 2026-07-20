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
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;

/**
 * Anchors an unresolved imported layer by comparing its native tiles with
 * quadrants of an already canonicalized imported layer from the same session.
 */
final class ImportedLayerVisualAnchorResolver {
    private static final int MAX_PROBES = 16;
    private static final int SAMPLE_STEP = 8;
    private static final int MIN_ANCHOR_VOTES = 3;
    private static final double MAX_RMSE = 35.0;
    private static final double MAX_BEST_TO_SECOND_RATIO = 0.75;

    private final Map<String, BufferedImage> imageCache = new HashMap<>();

    Map<String, String> resolve(
        List<MatrixLayer> layers,
        TileRootPathResolver.Resolution resolution
    ) {
        Map<String, String> anchors = new LinkedHashMap<>();
        if (layers == null || resolution == null) {
            return anchors;
        }
        List<ParentTile> parents = stronglyAnchoredParents(layers, resolution);
        for (MatrixLayer childLayer : layers) {
            if (childLayer == null || childLayer.getTiles() == null || childLayer.getTiles().isEmpty()) {
                continue;
            }
            if (isStronglyResolved(childLayer, resolution)) {
                continue;
            }
            AnchorChoice choice = chooseAnchor(childLayer, layers, parents);
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
        return anchors;
    }

    private AnchorChoice chooseAnchor(
        MatrixLayer childLayer,
        List<MatrixLayer> layers,
        List<ParentTile> parents
    ) {
        Map<Anchor, Integer> votes = new LinkedHashMap<>();
        int accepted = 0;
        MatrixLayer designatedParent = designatedParentOf(childLayer, layers);
        for (MatrixLayerTile child : evenlySpaced(childLayer.getTiles(), MAX_PROBES)) {
            MatchPair pair = bestTwoMatches(child, childLayer, designatedParent, parents);
            if (!confident(pair)) {
                continue;
            }
            ParentTile parent = pair.best().parent();
            int level = parent.level() + 1;
            int side = 1 << level;
            boolean south = pair.best().quadrant() == 0 || pair.best().quadrant() == 1;
            boolean east = pair.best().quadrant() == 1 || pair.best().quadrant() == 2;
            int childRow = 2 * parent.row() + (south ? 1 : 0);
            int childCol = 2 * parent.col() + (east ? 1 : 0);
            Anchor anchor = new Anchor(
                level,
                childRow - child.getI(),
                Math.floorMod(childCol - child.getJ(), side)
            );
            votes.merge(anchor, 1, Integer::sum);
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
        return best != null && bestVotes >= MIN_ANCHOR_VOTES && bestVotes * 2 > accepted
            ? new AnchorChoice(best, bestVotes, accepted)
            : null;
    }

    private MatchPair bestTwoMatches(
        MatrixLayerTile child,
        MatrixLayer childLayer,
        MatrixLayer designatedParent,
        List<ParentTile> parents
    ) {
        BufferedImage childImage = imageOf(child);
        if (childImage == null) {
            return null;
        }
        Match best = null;
        Match second = null;
        for (ParentTile parent : parents) {
            if (parent.layer() == childLayer
                || (designatedParent != null && parent.layer() != designatedParent)) {
                continue;
            }
            BufferedImage parentImage = imageOf(parent.tile());
            if (parentImage == null) {
                continue;
            }
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                Match candidate = new Match(parent, quadrant, rmse(parentImage, childImage, quadrant));
                if (best == null || candidate.rmse() < best.rmse()) {
                    second = best;
                    best = candidate;
                }
                else if (second == null || candidate.rmse() < second.rmse()) {
                    second = candidate;
                }
            }
        }
        return best == null || second == null ? null : new MatchPair(best, second);
    }

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

    private BufferedImage imageOf(MatrixLayerTile tile) {
        String file = tile == null ? null : tile.getTextureFile();
        if (file == null || file.isBlank()) {
            return null;
        }
        if (imageCache.containsKey(file)) {
            return imageCache.get(file);
        }
        BufferedImage image = null;
        try {
            image = ImageIO.read(Path.of(file).toFile());
        }
        catch (IOException | RuntimeException ignored) {
            // An unreadable texture provides no visual anchor evidence.
        }
        imageCache.put(file, image);
        return image;
    }

    private static double rmse(BufferedImage parent, BufferedImage child, int quadrant) {
        if (parent.getWidth() < 256 || parent.getHeight() < 256
            || child.getWidth() < 256 || child.getHeight() < 256) {
            return Double.POSITIVE_INFINITY;
        }
        int x0 = quadrant == 1 || quadrant == 2 ? 128 : 0;
        int y0 = quadrant == 0 || quadrant == 1 ? 128 : 0;
        double sum = 0.0;
        long count = 0L;
        for (int y = 0; y < 128; y += SAMPLE_STEP) {
            for (int x = 0; x < 128; x += SAMPLE_STEP) {
                int parentRgb = parent.getRGB(x0 + x, y0 + y);
                int childRgb = child.getRGB(x * 2, y * 2);
                for (int shift : new int[]{16, 8, 0}) {
                    int delta = ((parentRgb >> shift) & 0xff) - ((childRgb >> shift) & 0xff);
                    sum += delta * delta;
                    count++;
                }
            }
        }
        return Math.sqrt(sum / count);
    }

    private static boolean confident(MatchPair pair) {
        return pair != null
            && pair.best().rmse() <= MAX_RMSE
            && pair.best().rmse() / Math.max(1.0e-9, pair.second().rmse()) <= MAX_BEST_TO_SECOND_RATIO;
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
    private record Match(ParentTile parent, int quadrant, double rmse) {}
    private record MatchPair(Match best, Match second) {}
}
