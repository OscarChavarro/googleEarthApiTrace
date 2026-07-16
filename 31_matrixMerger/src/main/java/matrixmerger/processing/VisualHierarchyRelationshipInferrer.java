package matrixmerger.processing;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.ParentGridTransform;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.model.state.MatrixMergerState;

/**
 * Reconstructs hierarchy edges that disappeared from the trace metadata.
 *
 * A quadtree child image is the same terrain as one quadrant of its parent,
 * at twice the linear resolution.  For every disconnected hierarchy root
 * after the first one, this class compares a sample of its tiles with all
 * earlier matrices, votes for one rigid (parent matrix, row offset, column
 * offset), and persists only a strict, visually confident majority.
 */
public final class VisualHierarchyRelationshipInferrer {
    private static final double MAX_RMSE = 35.0;
    private static final double MAX_BEST_TO_SECOND_RATIO = 0.75;
    private static final int MAX_PROBE_TILES = 16;
    private static final int SAMPLE_STEP = 8;
    private static final int MIN_ANCHOR_VOTES = 3;

    private final Map<String, BufferedImage> imageCache = new HashMap<>();

    public int inferMissingParents(MatrixMergerState model) {
        if (model == null) {
            return 0;
        }
        model.sortFramesByUncleHierarchy();
        List<FrameMatrixSet> frames = model.getFrameMatrices();
        List<MatrixMergerState.HierarchyOrderDiagnostic> diagnostics = model.getHierarchyOrderDiagnostics();
        int inferred = 0;
        for (int childIndex = 1; childIndex < frames.size(); childIndex++) {
            MatrixMergerState.HierarchyOrderDiagnostic diagnostic = diagnostics.get(childIndex);
            if (!diagnostic.resolvedParentIndexes().isEmpty()) {
                continue;
            }
            FrameTileMatrix child = firstMatrix(frames.get(childIndex));
            if (child == null) {
                continue;
            }
            AnchorChoice choice = chooseAnchor(frames, childIndex, child);
            if (choice == null) {
                System.out.println(
                    "VisualHierarchyRelationshipInferrer: matrix " + childIndex
                        + " remains a disconnected root; no confident visual parent."
                );
                continue;
            }
            attachParentTransform(frames.get(childIndex), frames.get(choice.anchor.parentIndex), choice);
            inferred++;
            System.out.println(
                "VisualHierarchyRelationshipInferrer: matrix " + childIndex
                    + " -> parent matrix " + choice.anchor.parentIndex
                    + ", grid offset=(" + choice.anchor.rowOffset + "," + choice.anchor.colOffset + ")"
                    + ", votes=" + choice.votes + "/" + choice.acceptedProbes
                    + ", persisted parent-grid transform"
            );
        }
        if (inferred > 0) {
            model.sortFramesByUncleHierarchy();
        }
        imageCache.clear();
        return inferred;
    }

    private AnchorChoice chooseAnchor(List<FrameMatrixSet> frames, int childIndex, FrameTileMatrix child) {
        List<FrameTileMatrix.TileCoord> probes = evenlySpaced(child.getTiles(), MAX_PROBE_TILES);
        Map<Anchor, Integer> votes = new LinkedHashMap<>();
        int accepted = 0;
        for (FrameTileMatrix.TileCoord childTile : probes) {
            MatchPair pair = bestTwoMatches(frames, childIndex, childTile);
            if (!confident(pair)) {
                continue;
            }
            accepted++;
            votes.merge(anchorOf(pair.best, childTile), 1, Integer::sum);
        }
        Anchor best = null;
        int bestVotes = 0;
        for (Map.Entry<Anchor, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestVotes) {
                best = vote.getKey();
                bestVotes = vote.getValue();
            }
        }
        if (best == null || bestVotes < MIN_ANCHOR_VOTES || bestVotes * 2 <= accepted) {
            return null;
        }
        return new AnchorChoice(best, bestVotes, accepted);
    }

    private void attachParentTransform(
        FrameMatrixSet childFrame,
        FrameMatrixSet parentFrame,
        AnchorChoice choice
    ) {
        childFrame.setInferredParent(parentFrame);
        childFrame.setParentGridTransform(
            new ParentGridTransform(choice.anchor.rowOffset, choice.anchor.colOffset)
        );
    }

    private MatchPair bestTwoMatches(List<FrameMatrixSet> frames, int parentLimit, FrameTileMatrix.TileCoord childTile) {
        BufferedImage childImage = imageOf(childTile);
        if (childImage == null) {
            return null;
        }
        Match best = null;
        Match second = null;
        for (int parentIndex = 0; parentIndex < parentLimit; parentIndex++) {
            FrameTileMatrix parent = firstMatrix(frames.get(parentIndex));
            if (parent == null) {
                continue;
            }
            for (FrameTileMatrix.TileCoord parentTile : parent.getTiles()) {
                BufferedImage parentImage = imageOf(parentTile);
                if (parentImage == null) {
                    continue;
                }
                for (int quadrant = 0; quadrant < 4; quadrant++) {
                    Match candidate = new Match(parentIndex, parentTile, quadrant, rmse(parentImage, childImage, quadrant));
                    if (best == null || candidate.rmse < best.rmse) {
                        second = best;
                        best = candidate;
                    }
                    else if (second == null || candidate.rmse < second.rmse) {
                        second = candidate;
                    }
                }
            }
        }
        return best == null || second == null ? null : new MatchPair(best, second);
    }

    private BufferedImage imageOf(FrameTileMatrix.TileCoord tile) {
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

    private static boolean confident(MatchPair pair) {
        return pair != null
            && pair.best.rmse <= MAX_RMSE
            && pair.best.rmse / Math.max(1.0e-9, pair.second.rmse) <= MAX_BEST_TO_SECOND_RATIO;
    }

    private static Anchor anchorOf(Match match, FrameTileMatrix.TileCoord childTile) {
        boolean south = match.quadrant == 0 || match.quadrant == 1;
        boolean east = match.quadrant == 1 || match.quadrant == 2;
        int parentRow = 2 * match.parentTile.getI() + (south ? 1 : 0);
        int parentCol = 2 * match.parentTile.getJ() + (east ? 1 : 0);
        return new Anchor(match.parentIndex, parentRow - childTile.getI(), parentCol - childTile.getJ());
    }

    private static double rmse(BufferedImage parent, BufferedImage child, int quadrant) {
        if (parent.getWidth() < 256 || parent.getHeight() < 256 || child.getWidth() < 256 || child.getHeight() < 256) {
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

    private static <T> List<T> evenlySpaced(List<T> values, int limit) {
        if (values == null || values.size() <= limit) {
            return values == null ? List.of() : values;
        }
        List<T> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            out.add(values.get(i * (values.size() - 1) / (limit - 1)));
        }
        return out;
    }

    private static FrameTileMatrix firstMatrix(FrameMatrixSet frame) {
        return frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()
            ? null
            : frame.getMatrices().get(0);
    }

    private record Anchor(int parentIndex, int rowOffset, int colOffset) {}
    private record AnchorChoice(Anchor anchor, int votes, int acceptedProbes) {}
    private record Match(int parentIndex, FrameTileMatrix.TileCoord parentTile, int quadrant, double rmse) {}
    private record MatchPair(Match best, Match second) {}
}
