package pyramidalimagebuilder.processing;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;

public final class HintGraphToTileMatrixConvertor {
    public TileMatrix convert(Graph<TileInstance, DefaultEdge> component) {
        TileInstance[][] empty = new TileInstance[0][0];
        if (component == null || component.vertexSet().isEmpty()) {
            return new TileMatrix(component, empty);
        }

        Map<TileInstance, NeighborPosition> positions = assignPositions(component);
        if (positions.isEmpty()) {
            return new TileMatrix(component, empty);
        }

        int minRow = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxCol = Integer.MIN_VALUE;
        for (NeighborPosition p : positions.values()) {
            minRow = Math.min(minRow, p.row());
            maxRow = Math.max(maxRow, p.row());
            minCol = Math.min(minCol, p.col());
            maxCol = Math.max(maxCol, p.col());
        }

        int rows = maxRow - minRow + 1;
        int cols = maxCol - minCol + 1;
        TileInstance[][] M = new TileInstance[rows][cols];
        for (Map.Entry<TileInstance, NeighborPosition> e : positions.entrySet()) {
            int r = e.getValue().row() - minRow;
            int c = e.getValue().col() - minCol;
            if (M[r][c] == null) {
                M[r][c] = e.getKey();
            }
        }
        return new TileMatrix(component, M);
    }

    private Map<TileInstance, NeighborPosition> assignPositions(Graph<TileInstance, DefaultEdge> component) {
        Map<TileInstance, NeighborPosition> pos = new HashMap<>();
        Graph<TileInstance, DefaultEdge> undirected = new AsUndirectedGraph<>(component);
        ArrayDeque<TileInstance> stack = new ArrayDeque<>();

        TileInstance seed = component.vertexSet().iterator().next();
        pos.put(seed, new NeighborPosition(0, 0));
        stack.push(seed);

        while (!stack.isEmpty()) {
            TileInstance current = stack.pop();
            NeighborPosition cp = pos.get(current);
            for (TileInstance neighbor : Graphs.neighborSetOf(undirected, current)) {
                NeighborOffset delta = offsetBetween(current, neighbor);
                if (delta == null) {
                    continue;
                }
                NeighborPosition candidate = new NeighborPosition(cp.row() + delta.dRow(), cp.col() + delta.dCol());
                NeighborPosition existing = pos.get(neighbor);
                if (existing == null) {
                    pos.put(neighbor, candidate);
                    stack.push(neighbor);
                }
            }
        }

        for (TileInstance vertex : component.vertexSet()) {
            pos.putIfAbsent(vertex, new NeighborPosition(0, 0));
        }
        return pos;
    }

    private NeighborOffset offsetBetween(TileInstance source, TileInstance target) {
        int sid = source.getTileId();
        int tid = target.getTileId();

        if (source.getNorthNeighbor() != null && source.getNorthNeighbor() == tid) return new NeighborOffset(-1, 0);
        if (source.getSouthNeighbor() != null && source.getSouthNeighbor() == tid) return new NeighborOffset(1, 0);
        if (source.getEastNeighbor() != null && source.getEastNeighbor() == tid) return new NeighborOffset(0, 1);
        if (source.getWestNeighbor() != null && source.getWestNeighbor() == tid) return new NeighborOffset(0, -1);

        if (target.getNorthNeighbor() != null && target.getNorthNeighbor() == sid) return new NeighborOffset(1, 0);
        if (target.getSouthNeighbor() != null && target.getSouthNeighbor() == sid) return new NeighborOffset(-1, 0);
        if (target.getEastNeighbor() != null && target.getEastNeighbor() == sid) return new NeighborOffset(0, -1);
        if (target.getWestNeighbor() != null && target.getWestNeighbor() == sid) return new NeighborOffset(0, 1);

        return null;
    }
}
