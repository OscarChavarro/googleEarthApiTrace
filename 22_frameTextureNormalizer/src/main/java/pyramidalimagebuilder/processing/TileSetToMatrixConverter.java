package pyramidalimagebuilder.processing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pyramidalimagebuilder.model.FrameData;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;
import pyramidalimagebuilder.model.TileMatrix.TileCoord;

public final class TileSetToMatrixConverter {
    private static final MatrixTileCoordinate UNASSIGNED = new MatrixTileCoordinate(-1, -1);
    private final Set<Integer> lastConflictingTileIds = new HashSet<>();
    private final Map<Integer, MatrixTileCoordinate> lastCoordinatesByTileId = new HashMap<>();

    private enum Direction {
        NORTH(-1, 0),
        SOUTH(1, 0),
        EAST(0, 1),
        WEST(0, -1);

        private final int di;
        private final int dj;

        Direction(int di, int dj) {
            this.di = di;
            this.dj = dj;
        }

        MatrixTileCoordinate move(MatrixTileCoordinate from) {
            return new MatrixTileCoordinate(from.i() + di, from.j() + dj);
        }
    }

    public TileMatrix convert(FrameData frameData) {
        lastConflictingTileIds.clear();
        lastCoordinatesByTileId.clear();
        if (frameData == null || frameData.getTiles() == null || frameData.getTiles().isEmpty()) {
            return new TileMatrix(frameData == null ? -1 : frameData.getId(), 0, 0, List.of());
        }

        Map<Integer, TileInstance> byId = new HashMap<>();
        for (TileInstance tile : frameData.getTiles()) {
            if (tile != null) {
                byId.put(tile.getTileId(), tile);
            }
        }

        Map<Integer, MatrixTileCoordinate> coordsByTileId = new HashMap<>();
        Map<MatrixTileCoordinate, Integer> ownerByCoord = new HashMap<>();
        for (Integer tileId : byId.keySet()) {
            coordsByTileId.put(tileId, UNASSIGNED);
        }
        int nextComponentStartJ = 0;

        for (TileInstance seed : frameData.getTiles()) {
            if (seed == null || !isUnassigned(coordsByTileId.get(seed.getTileId()))) {
                continue;
            }
            MatrixTileCoordinate componentOrigin = new MatrixTileCoordinate(0, nextComponentStartJ);
            if (!assignComponent(seed.getTileId(), componentOrigin, byId, coordsByTileId, ownerByCoord)) {
                lastCoordinatesByTileId.putAll(coordsByTileId);
                return null;
            }
            nextComponentStartJ = componentMaxJ(coordsByTileId) + 2;
        }
        lastCoordinatesByTileId.putAll(coordsByTileId);

        int minI = Integer.MAX_VALUE;
        int minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE;
        int maxJ = Integer.MIN_VALUE;
        for (MatrixTileCoordinate c : coordsByTileId.values()) {
            minI = Math.min(minI, c.i());
            minJ = Math.min(minJ, c.j());
            maxI = Math.max(maxI, c.i());
            maxJ = Math.max(maxJ, c.j());
        }

        int shiftI = minI < 0 ? -minI : 0;
        int shiftJ = minJ < 0 ? -minJ : 0;

        List<TileCoord> result = new ArrayList<>(frameData.getTiles().size());
        for (TileInstance tile : frameData.getTiles()) {
            if (tile == null) {
                continue;
            }
            MatrixTileCoordinate c = coordsByTileId.get(tile.getTileId());
            if (c == null) {
                return null;
            }
            result.add(new TileCoord(tile.getTileId(), c.i() + shiftI, c.j() + shiftJ, tile.getTextureFile()));
        }

        return new TileMatrix(frameData.getId(), maxI - minI + 1, maxJ - minJ + 1, result);
    }

    private boolean assignComponent(
        int seedTileId,
        MatrixTileCoordinate seedMatrixTileCoordinate,
        Map<Integer, TileInstance> byId,
        Map<Integer, MatrixTileCoordinate> coordsByTileId,
        Map<MatrixTileCoordinate, Integer> ownerByCoord
    ) {
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        coordsByTileId.put(seedTileId, seedMatrixTileCoordinate);
        ownerByCoord.put(seedMatrixTileCoordinate, seedTileId);
        stack.push(seedTileId);

        while (!stack.isEmpty()) {
            int currentId = stack.pop();
            TileInstance tile = byId.get(currentId);
            if (tile == null) {
                continue;
            }
            MatrixTileCoordinate base = coordsByTileId.get(currentId);
            if (base == null) {
                return false;
            }

            if (!assignNeighbor(currentId, tile.getNorthNeighbor(), Direction.NORTH, base, byId, coordsByTileId, ownerByCoord, stack)) {
                return false;
            }
            if (!assignNeighbor(currentId, tile.getSouthNeighbor(), Direction.SOUTH, base, byId, coordsByTileId, ownerByCoord, stack)) {
                return false;
            }
            if (!assignNeighbor(currentId, tile.getEastNeighbor(), Direction.EAST, base, byId, coordsByTileId, ownerByCoord, stack)) {
                return false;
            }
            if (!assignNeighbor(currentId, tile.getWestNeighbor(), Direction.WEST, base, byId, coordsByTileId, ownerByCoord, stack)) {
                return false;
            }
        }
        return true;
    }

    private boolean assignNeighbor(
        int currentId,
        Integer neighborId,
        Direction direction,
        MatrixTileCoordinate from,
        Map<Integer, TileInstance> byId,
        Map<Integer, MatrixTileCoordinate> coordsByTileId,
        Map<MatrixTileCoordinate, Integer> ownerByCoord,
        ArrayDeque<Integer> stack
    ) {
        if (neighborId == null || !byId.containsKey(neighborId)) {
            return true;
        }
        MatrixTileCoordinate expected = direction.move(from);
        MatrixTileCoordinate assigned = coordsByTileId.get(neighborId);
        if (isUnassigned(assigned)) {
            Integer owner = ownerByCoord.get(expected);
            if (owner != null && owner != neighborId) {
                registerConflict(neighborId, owner);
                return false;
            }
            coordsByTileId.put(neighborId, expected);
            ownerByCoord.put(expected, neighborId);
            stack.push(neighborId);
            return true;
        }
        if (assigned.i() != expected.i() || assigned.j() != expected.j()) {
            registerConflict(currentId, neighborId);
            return false;
        }
        return true;
    }

    private static int componentMaxJ(Map<Integer, MatrixTileCoordinate> coordsByTileId) {
        int maxJ = Integer.MIN_VALUE;
        for (MatrixTileCoordinate c : coordsByTileId.values()) {
            if (!isUnassigned(c)) {
                maxJ = Math.max(maxJ, c.j());
            }
        }
        return maxJ == Integer.MIN_VALUE ? 0 : maxJ;
    }

    private static boolean isUnassigned(MatrixTileCoordinate c) {
        return c == null || (c.i() == -1 && c.j() == -1);
    }

    public Set<Integer> getLastConflictingTileIds() {
        return Set.copyOf(lastConflictingTileIds);
    }

    public Map<Integer, MatrixTileCoordinate> getLastCoordinatesByTileId() {
        return Map.copyOf(lastCoordinatesByTileId);
    }

    private void registerConflict(Integer a, Integer b) {
        if (a != null) {
            lastConflictingTileIds.add(a);
        }
        if (b != null) {
            lastConflictingTileIds.add(b);
        }
    }
}
