package matrixmerger.processing;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import matrixmerger.io.WestCuttersJsonReader;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.model.contract.ParentGridTransform;
import matrixmerger.processing.uncles.ToUncleRelationship;
import matrixmerger.processing.uncles.UncleDirections;
import matrixmerger.processing.uncles.UncleRelationshipKind;

/** Resolves competing direct-parent alignments from complete 2x2 child image blocks. */
public final class VisualHierarchyAmbiguityResolver {
    private static final int TILE_SIDE = 256;
    private static final int CHILD_SIDE = TILE_SIDE / 2;
    private static final double MAX_RMSE = 25.0;
    private static final double MAX_BEST_TO_SECOND_RATIO = 0.65;

    public record Report(int ambiguousMatrices, int resolvedMatrices, int comparedMosaics) {}

    private record TileRef(FrameMatrixSet frame, FrameTileMatrix.TileCoord tile) {}
    private record Anchor(FrameMatrixSet parent, int rowOffset, int colOffset) {}
    private record ScoredAnchor(Anchor anchor, double rmse, int mosaics) {}

    private final Map<String, BufferedImage> imageCache = new HashMap<>();

    public Report resolve(List<FrameMatrixSet> frames, Set<FrameMatrixSet> brokenFrames) {
        if (frames == null || frames.isEmpty() || brokenFrames == null || brokenFrames.isEmpty()) {
            return new Report(0, 0, 0);
        }
        Map<String, TileRef> tileById = indexTiles(frames);
        int ambiguous = 0;
        int resolved = 0;
        int compared = 0;
        for (FrameMatrixSet child : frames) {
            if (child == null || child.getInferredParent() != null || !brokenFrames.contains(child)) {
                continue;
            }
            Map<Anchor, Integer> anchorVotes = collectDirectParentAnchors(child, tileById);
            long parentCount = anchorVotes.keySet().stream().map(Anchor::parent).distinct().count();
            if (parentCount < 2) {
                continue;
            }
            ambiguous++;
            List<ScoredAnchor> scores = anchorVotes.keySet().stream()
                .map(anchor -> score(child, anchor))
                .filter(candidate -> candidate != null)
                .sorted(Comparator.comparingDouble(ScoredAnchor::rmse))
                .toList();
            compared += scores.stream().mapToInt(ScoredAnchor::mosaics).sum();
            if (!confident(scores)) {
                continue;
            }
            ScoredAnchor winner = scores.get(0);
            child.setInferredParent(winner.anchor().parent());
            child.setParentLevelDelta(1);
            child.setParentGridTransform(new ParentGridTransform(
                winner.anchor().rowOffset(), winner.anchor().colOffset()
            ));
            resolved++;
            System.out.println(
                "VisualHierarchyAmbiguityResolver: frame " + child.getFrameId()
                    + " selected parent frame " + winner.anchor().parent().getFrameId()
                    + " from " + scores.size() + " image candidates; RMSE="
                    + String.format(java.util.Locale.ROOT, "%.2f", winner.rmse())
                    + ", mosaics=" + winner.mosaics() + "."
            );
        }
        imageCache.clear();
        return new Report(ambiguous, resolved, compared);
    }

    private Map<Anchor, Integer> collectDirectParentAnchors(
        FrameMatrixSet child,
        Map<String, TileRef> tileById
    ) {
        Map<Anchor, Integer> votes = new LinkedHashMap<>();
        FrameTileMatrix matrix = firstMatrix(child);
        if (matrix == null || matrix.getTiles() == null) {
            return votes;
        }
        Map<String, List<ToUncleRelationship>> inherited = child.getHierarchyRelationshipsByTileId();
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            List<ToUncleRelationship> relationships = inherited == null
                ? tile.getUncles()
                : inherited.getOrDefault(tile.getId(), tile.getUncles());
            if (relationships == null) {
                continue;
            }
            for (ToUncleRelationship relationship : relationships) {
                if (relationship == null || relationship.effectiveLevelDelta() != 1
                    || relationship.uncleContentId() == null) {
                    continue;
                }
                String id = WestCuttersJsonReader.normalizeScopedTileId(relationship.uncleContentId());
                TileRef parent = tileById.get(id);
                if (parent == null || parent.frame() == child) {
                    continue;
                }
                int[] childPosition = childPosition(parent.tile(), relationship);
                if (childPosition != null) {
                    votes.merge(new Anchor(
                        parent.frame(), childPosition[0] - tile.getI(), childPosition[1] - tile.getJ()
                    ), 1, Integer::sum);
                }
            }
        }
        return votes;
    }

    private ScoredAnchor score(FrameMatrixSet child, Anchor anchor) {
        FrameTileMatrix childMatrix = firstMatrix(child);
        FrameTileMatrix parentMatrix = firstMatrix(anchor.parent());
        if (childMatrix == null || parentMatrix == null) {
            return null;
        }
        Map<String, FrameTileMatrix.TileCoord> childByPosition = new HashMap<>();
        for (FrameTileMatrix.TileCoord tile : childMatrix.getTiles()) {
            childByPosition.put(tile.getI() + ":" + tile.getJ(), tile);
        }
        double sum = 0.0;
        int count = 0;
        for (FrameTileMatrix.TileCoord parent : parentMatrix.getTiles()) {
            FrameTileMatrix.TileCoord[] children = new FrameTileMatrix.TileCoord[4];
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    int localI = 2 * parent.getI() + row - anchor.rowOffset();
                    int localJ = 2 * parent.getJ() + col - anchor.colOffset();
                    children[2 * row + col] = childByPosition.get(localI + ":" + localJ);
                }
            }
            BufferedImage mosaic = mosaic(children);
            BufferedImage parentImage = imageOf(parent.getTextureFile());
            if (mosaic == null || parentImage == null) {
                continue;
            }
            sum += rmse(mosaic, resize(parentImage, TILE_SIDE, TILE_SIDE));
            count++;
        }
        return count == 0 ? null : new ScoredAnchor(anchor, sum / count, count);
    }

    private BufferedImage mosaic(FrameTileMatrix.TileCoord[] children) {
        BufferedImage[] images = new BufferedImage[4];
        for (int index = 0; index < images.length; index++) {
            FrameTileMatrix.TileCoord child = children[index];
            images[index] = child == null ? null : imageOf(child.getTextureFile());
            if (images[index] == null) {
                return null;
            }
        }
        BufferedImage result = new BufferedImage(TILE_SIDE, TILE_SIDE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int index = 0; index < images.length; index++) {
                int x = (index % 2) * CHILD_SIDE;
                int y = (index / 2) * CHILD_SIDE;
                graphics.drawImage(images[index], x, y, CHILD_SIDE, CHILD_SIDE, null);
            }
        }
        finally {
            graphics.dispose();
        }
        return result;
    }

    private BufferedImage imageOf(String file) {
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
            // Missing image evidence leaves the hierarchy ambiguous.
        }
        imageCache.put(file, image);
        return image;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        }
        finally {
            graphics.dispose();
        }
        return result;
    }

    private static double rmse(BufferedImage left, BufferedImage right) {
        double sum = 0.0;
        long count = 0L;
        for (int y = 0; y < TILE_SIDE; y++) {
            for (int x = 0; x < TILE_SIDE; x++) {
                int a = left.getRGB(x, y);
                int b = right.getRGB(x, y);
                for (int shift : new int[]{16, 8, 0}) {
                    int delta = ((a >> shift) & 0xff) - ((b >> shift) & 0xff);
                    sum += delta * delta;
                    count++;
                }
            }
        }
        return Math.sqrt(sum / count);
    }

    private static boolean confident(List<ScoredAnchor> scores) {
        if (scores.size() < 2 || scores.get(0).rmse() > MAX_RMSE) {
            return false;
        }
        if (scores.get(1).rmse() - scores.get(0).rmse() <= 1.0e-9) {
            return false;
        }
        return scores.get(0).rmse() / Math.max(1.0e-9, scores.get(1).rmse())
            <= MAX_BEST_TO_SECOND_RATIO;
    }

    private static Map<String, TileRef> indexTiles(List<FrameMatrixSet> frames) {
        Map<String, TileRef> result = new LinkedHashMap<>();
        for (FrameMatrixSet frame : frames) {
            FrameTileMatrix matrix = firstMatrix(frame);
            if (matrix == null || matrix.getTiles() == null) {
                continue;
            }
            for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile != null && tile.getId() != null && !tile.getId().isBlank()) {
                    result.putIfAbsent(tile.getId(), new TileRef(frame, tile));
                }
            }
        }
        return result;
    }

    private static FrameTileMatrix firstMatrix(FrameMatrixSet frame) {
        return frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()
            ? null : frame.getMatrices().get(0);
    }

    private static int[] childPosition(FrameTileMatrix.TileCoord parent, ToUncleRelationship relationship) {
        if (parent == null || relationship == null || relationship.direction() == null
            || relationship.relationshipKind() == null) {
            return null;
        }
        int parentI = parent.getI();
        int parentJ = parent.getJ();
        boolean south;
        boolean east;
        UncleDirections direction = relationship.direction();
        if (relationship.relationshipKind() == UncleRelationshipKind.CONTAINING_QUADRANT) {
            south = direction == UncleDirections.WEST_SOUTH || direction == UncleDirections.SOUTH_WEST
                || direction == UncleDirections.EAST_SOUTH || direction == UncleDirections.SOUTH_EAST;
            east = direction == UncleDirections.EAST_SOUTH || direction == UncleDirections.SOUTH_EAST
                || direction == UncleDirections.EAST_NORTH || direction == UncleDirections.NORTH_EAST;
        }
        else if (relationship.relationshipKind() == UncleRelationshipKind.ADJACENT_BORDER) {
            switch (direction) {
                case WEST_NORTH -> { parentJ--; south = false; east = true; }
                case WEST_SOUTH -> { parentJ--; south = true; east = true; }
                case EAST_NORTH -> { parentJ++; south = false; east = false; }
                case EAST_SOUTH -> { parentJ++; south = true; east = false; }
                case NORTH_WEST -> { parentI--; south = true; east = false; }
                case NORTH_EAST -> { parentI--; south = true; east = true; }
                case SOUTH_WEST -> { parentI++; south = false; east = false; }
                case SOUTH_EAST -> { parentI++; south = false; east = true; }
                default -> { return null; }
            }
        }
        else {
            return null;
        }
        return new int[]{2 * parentI + (south ? 1 : 0), 2 * parentJ + (east ? 1 : 0)};
    }
}
