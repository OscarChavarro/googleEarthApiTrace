package pyramidalimageexporter.processing.toplevels;

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

/** Anchors a partial imported matrix by matching it to reconstructed top-level cells. */
final class TopLevelVisualAnchorResolver {
    private static final int MAX_PROBES = 16;
    private static final int SAMPLE_SIDE = 16;
    private static final int MIN_ANCHOR_VOTES = 3;
    private static final double MAX_RMSE = 25.0;
    private static final double MAX_BEST_TO_SECOND_RATIO = 0.65;

    private final Map<String, BufferedImage> imageCache = new HashMap<>();

    Map<String, String> resolve(List<MatrixLayer> topLayers, List<MatrixLayer> importedLayers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (topLayers == null || importedLayers == null) {
            return result;
        }
        List<MatrixLayerTile> allTopCells = topLayers.stream()
            .filter(layer -> layer != null && layer.getTiles() != null)
            .flatMap(layer -> layer.getTiles().stream())
            .filter(tile -> tile != null && isQuadPath(tile.getId()))
            .toList();
        int topLevel = allTopCells.stream().mapToInt(tile -> tile.getId().length() - 1).max().orElse(-1);
        List<MatrixLayerTile> topCells = allTopCells.stream()
            .filter(tile -> tile.getId().length() - 1 == topLevel)
            .toList();
        for (MatrixLayer imported : importedLayers) {
            if (imported.getParentMatrixIndex() != null) {
                continue;
            }
            AnchorChoice choice = chooseAnchor(imported, topCells, topLevel);
            if (choice == null) {
                continue;
            }
            int side = 1 << choice.anchor().level();
            int anchored = 0;
            for (MatrixLayerTile tile : imported.getTiles()) {
                int row = tile.getI() + choice.anchor().rowOffset();
                int col = Math.floorMod(tile.getJ() + choice.anchor().colOffset(), side);
                if (row < 0 || row >= side || tile.getId() == null || tile.getId().isBlank()) {
                    continue;
                }
                result.put(tile.getId(), encodeFullPath(choice.anchor().level(), row, col));
                anchored++;
            }
            System.out.println(
                "TopLevelVisualAnchorResolver: layer " + imported.getSourceFolderName()
                    + " anchored at [" + choice.anchor().level() + ", "
                    + choice.anchor().rowOffset() + ", " + choice.anchor().colOffset() + "]"
                    + " from " + choice.votes() + "/" + choice.acceptedProbes()
                    + " confident visual probes; assigned " + anchored + " tile(s)."
            );
        }
        imageCache.clear();
        return result;
    }

    private AnchorChoice chooseAnchor(MatrixLayer imported, List<MatrixLayerTile> topCells, int topLevel) {
        if (imported == null || imported.getTiles() == null || imported.getTiles().isEmpty()) {
            return null;
        }
        Map<Anchor, Integer> votes = new LinkedHashMap<>();
        int accepted = 0;
        double lowestRmse = Double.POSITIVE_INFINITY;
        double lowestRatio = Double.POSITIVE_INFINITY;
        for (MatrixLayerTile probe : spatiallyDistributed(imported.getTiles(), MAX_PROBES)) {
            MatchPair pair = bestTwoMatches(probe, topCells, topLevel);
            if (pair != null) {
                lowestRmse = Math.min(lowestRmse, pair.best().rmse());
                lowestRatio = Math.min(
                    lowestRatio,
                    pair.best().rmse() / Math.max(1.0e-9, pair.second().rmse())
                );
            }
            if (!confident(pair)) {
                continue;
            }
            int level = pair.best().level();
            int side = 1 << level;
            Anchor anchor = new Anchor(
                level,
                pair.best().row() - probe.getI(),
                Math.floorMod(pair.best().col() - probe.getJ(), side)
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
        if (best != null && bestVotes >= MIN_ANCHOR_VOTES && bestVotes * 2 > accepted) {
            return new AnchorChoice(best, bestVotes, accepted);
        }
        System.out.println(
            "TopLevelVisualAnchorResolver: layer " + imported.getSourceFolderName()
                + " not anchored; confident probes=" + accepted
                + ", best rigid vote=" + bestVotes
                + (Double.isFinite(lowestRmse)
                    ? ", lowest RMSE=" + String.format(java.util.Locale.ROOT, "%.2f", lowestRmse)
                        + ", lowest best/second ratio="
                        + String.format(java.util.Locale.ROOT, "%.3f", lowestRatio)
                    : ", no readable visual candidates")
        );
        return null;
    }

    private MatchPair bestTwoMatches(MatrixLayerTile probe, List<MatrixLayerTile> topCells, int topLevel) {
        BufferedImage probeImage = imageOf(probe);
        if (probeImage == null) {
            return null;
        }
        Match best = null;
        Match second = null;
        for (MatrixLayerTile cell : topCells) {
            BufferedImage cellImage = imageOf(cell);
            if (cellImage == null) {
                continue;
            }
            Match direct = new Match(
                cell,
                topLevel,
                cell.getI(),
                cell.getJ(),
                rmse(probeImage, cellImage, cell, 0, 0, 1)
            );
            MatchPair updated = insert(new MatchPair(best, second), direct);
            best = updated.best();
            second = updated.second();
            for (int subRow = 0; subRow < 2; subRow++) {
                for (int subCol = 0; subCol < 2; subCol++) {
                    Match child = new Match(
                        cell,
                        topLevel + 1,
                        2 * cell.getI() + subRow,
                        2 * cell.getJ() + subCol,
                        rmse(probeImage, cellImage, cell, subRow, subCol, 2)
                    );
                    updated = insert(new MatchPair(best, second), child);
                    best = updated.best();
                    second = updated.second();
                }
            }
        }
        return best == null || second == null ? null : new MatchPair(best, second);
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
            // An unreadable texture supplies no visual evidence.
        }
        imageCache.put(file, image);
        return image;
    }

    private static MatchPair insert(MatchPair pair, Match candidate) {
        Match best = pair.best();
        Match second = pair.second();
        if (best == null || candidate.rmse() < best.rmse()) {
            return new MatchPair(candidate, best);
        }
        if (second == null || candidate.rmse() < second.rmse()) {
            return new MatchPair(best, candidate);
        }
        return pair;
    }

    private static double rmse(
        BufferedImage probe,
        BufferedImage source,
        MatrixLayerTile cell,
        int subRow,
        int subCol,
        int subdivision
    ) {
        double sum = 0.0;
        long count = 0L;
        for (int y = 0; y < SAMPLE_SIDE; y++) {
            double fy = (y + 0.5) / SAMPLE_SIDE;
            int probeY = pixelAt(fy, probe.getHeight());
            double geographicY = (subRow + fy) / subdivision;
            double v = cell.getTexV1() - geographicY * (cell.getTexV1() - cell.getTexV0());
            int sourceY = pixelAt(1.0 - v, source.getHeight());
            for (int x = 0; x < SAMPLE_SIDE; x++) {
                double fx = (x + 0.5) / SAMPLE_SIDE;
                int probeX = pixelAt(fx, probe.getWidth());
                double geographicX = (subCol + fx) / subdivision;
                double u = cell.getTexU0() + geographicX * (cell.getTexU1() - cell.getTexU0());
                int sourceX = pixelAt(u, source.getWidth());
                int probeRgb = probe.getRGB(probeX, probeY);
                int sourceRgb = source.getRGB(sourceX, sourceY);
                for (int shift : new int[]{16, 8, 0}) {
                    int delta = ((probeRgb >> shift) & 0xff) - ((sourceRgb >> shift) & 0xff);
                    sum += delta * delta;
                    count++;
                }
            }
        }
        return Math.sqrt(sum / count);
    }

    private static int pixelAt(double coordinate, int size) {
        return Math.max(0, Math.min(size - 1, (int) Math.floor(coordinate * size)));
    }

    private static boolean confident(MatchPair pair) {
        return pair != null
            && pair.best().rmse() <= MAX_RMSE
            && pair.best().rmse() / Math.max(1.0e-9, pair.second().rmse()) <= MAX_BEST_TO_SECOND_RATIO;
    }

    private static List<MatrixLayerTile> spatiallyDistributed(List<MatrixLayerTile> values, int limit) {
        List<MatrixLayerTile> ordered = values.stream()
            .sorted(Comparator.comparingInt(MatrixLayerTile::getI).thenComparingInt(MatrixLayerTile::getJ))
            .toList();
        if (ordered.size() <= limit) {
            return ordered;
        }
        List<MatrixLayerTile> selected = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            selected.add(ordered.get(i * (ordered.size() - 1) / (limit - 1)));
        }
        return selected;
    }

    private static boolean isQuadPath(String id) {
        return id != null && id.matches("0[0-3]*");
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
    private record Match(MatrixLayerTile cell, int level, int row, int col, double rmse) {}
    private record MatchPair(Match best, Match second) {}
}
