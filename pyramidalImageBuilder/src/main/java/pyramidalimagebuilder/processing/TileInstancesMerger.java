package pyramidalimagebuilder.processing;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;

public final class TileInstancesMerger {
    public void execute(PyramidalImageModel model) {
        if (model == null || model.getTileInstances().isEmpty()) {
            return;
        }
        List<TileInstance> merged = buildMergedTileInstances(model.getTileInstances());
        model.setMergedTileInstances(merged);

        Graph<TileInstance, DefaultEdge> graph = buildMergedTileInstancesGraph(merged);
        model.setMergedTileGraph(graph);

        Set<Graph<TileInstance, DefaultEdge>> components = splitConnectedComponents(graph);
        List<TileMatrix> tileMatrices = buildTileMatrices(components);
        model.setTileMatrices(tileMatrices);
    }

    private List<TileInstance> buildMergedTileInstances(List<TileInstance> source) {
        Map<Integer, List<TileInstance>> byTileId = new HashMap<>();
        for (TileInstance tile : source) {
            if (tile == null || tile.getTileId() < 0) {
                continue;
            }
            byTileId.computeIfAbsent(tile.getTileId(), ignored -> new ArrayList<>()).add(tile);
        }

        List<TileInstance> merged = new ArrayList<>(byTileId.size());
        for (Map.Entry<Integer, List<TileInstance>> entry : byTileId.entrySet()) {
            int tileId = entry.getKey();
            List<TileInstance> appearances = entry.getValue();
            if (appearances.isEmpty()) {
                continue;
            }

            int representativeFrameId = appearances.stream()
                .min(Comparator.comparingInt(TileInstance::getFrameId))
                .map(TileInstance::getFrameId)
                .orElse(-1);

            Integer south = mergeNeighbor(appearances, Direction.SOUTH);
            Integer north = mergeNeighbor(appearances, Direction.NORTH);
            Integer east = mergeNeighbor(appearances, Direction.EAST);
            Integer west = mergeNeighbor(appearances, Direction.WEST);

            merged.add(new TileInstance(tileId, representativeFrameId, south, north, east, west));
        }

        merged.sort(Comparator.comparingInt(TileInstance::getTileId));
        return merged;
    }

    private Graph<TileInstance, DefaultEdge> buildMergedTileInstancesGraph(List<TileInstance> merged) {
        Graph<TileInstance, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        Map<Integer, TileInstance> nodeByTileId = new HashMap<>();
        for (TileInstance tile : merged) {
            graph.addVertex(tile);
            nodeByTileId.put(tile.getTileId(), tile);
        }
        for (TileInstance source : merged) {
            addEdgeIfPresent(graph, nodeByTileId, source, source.getSouthNeighbor());
            addEdgeIfPresent(graph, nodeByTileId, source, source.getNorthNeighbor());
            addEdgeIfPresent(graph, nodeByTileId, source, source.getEastNeighbor());
            addEdgeIfPresent(graph, nodeByTileId, source, source.getWestNeighbor());
        }
        return graph;
    }

    public Set<Graph<TileInstance, DefaultEdge>> splitConnectedComponents(
        Graph<TileInstance, DefaultEdge> graph
    ) {
        Set<Graph<TileInstance, DefaultEdge>> components = new LinkedHashSet<>();
        if (graph == null || graph.vertexSet().isEmpty()) {
            return components;
        }

        ConnectivityInspector<TileInstance, DefaultEdge> inspector =
            new ConnectivityInspector<>(new AsUndirectedGraph<>(graph));
        List<Set<TileInstance>> groups = inspector.connectedSets();
        for (Set<TileInstance> group : groups) {
            Graph<TileInstance, DefaultEdge> subgraph = new DefaultDirectedGraph<>(DefaultEdge.class);
            for (TileInstance vertex : group) {
                subgraph.addVertex(vertex);
            }
            for (TileInstance source : group) {
                for (DefaultEdge edge : graph.outgoingEdgesOf(source)) {
                    TileInstance target = graph.getEdgeTarget(edge);
                    if (group.contains(target) && !subgraph.containsEdge(source, target)) {
                        subgraph.addEdge(source, target);
                    }
                }
            }
            components.add(subgraph);
        }
        return components;
    }

    private List<TileMatrix> buildTileMatrices(Set<Graph<TileInstance, DefaultEdge>> components) {
        List<TileMatrix> out = new ArrayList<>();
        for (Graph<TileInstance, DefaultEdge> component : components) {
            out.add(buildTileMatrix(component));
        }
        out.sort((a, b) -> Integer.compare(
            b.getGraph() == null ? 0 : b.getGraph().vertexSet().size(),
            a.getGraph() == null ? 0 : a.getGraph().vertexSet().size()
        ));
        return out;
    }

    private TileMatrix buildTileMatrix(Graph<TileInstance, DefaultEdge> component) {
        TileInstance[][] empty = new TileInstance[0][0];
        if (component == null || component.vertexSet().isEmpty()) {
            return new TileMatrix(component, empty);
        }

        Map<TileInstance, Position> positions = assignPositions(component);
        if (positions.isEmpty()) {
            return new TileMatrix(component, empty);
        }

        int minRow = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxCol = Integer.MIN_VALUE;
        for (Position p : positions.values()) {
            minRow = Math.min(minRow, p.row());
            maxRow = Math.max(maxRow, p.row());
            minCol = Math.min(minCol, p.col());
            maxCol = Math.max(maxCol, p.col());
        }

        int rows = maxRow - minRow + 1;
        int cols = maxCol - minCol + 1;
        TileInstance[][] M = new TileInstance[rows][cols];
        for (Map.Entry<TileInstance, Position> e : positions.entrySet()) {
            int r = e.getValue().row() - minRow;
            int c = e.getValue().col() - minCol;
            if (M[r][c] == null) {
                M[r][c] = e.getKey();
            }
        }
        return new TileMatrix(component, M);
    }

    private Map<TileInstance, Position> assignPositions(Graph<TileInstance, DefaultEdge> component) {
        Map<TileInstance, Position> pos = new HashMap<>();
        Graph<TileInstance, DefaultEdge> undirected = new AsUndirectedGraph<>(component);
        ArrayDeque<TileInstance> stack = new ArrayDeque<>();

        TileInstance seed = component.vertexSet().iterator().next();
        pos.put(seed, new Position(0, 0));
        stack.push(seed);

        while (!stack.isEmpty()) {
            TileInstance current = stack.pop();
            Position cp = pos.get(current);
            for (TileInstance neighbor : Graphs.neighborSetOf(undirected, current)) {
                Offset delta = offsetBetween(current, neighbor);
                if (delta == null) {
                    continue;
                }
                Position candidate = new Position(cp.row() + delta.dRow(), cp.col() + delta.dCol());
                Position existing = pos.get(neighbor);
                if (existing == null) {
                    pos.put(neighbor, candidate);
                    stack.push(neighbor);
                }
            }
        }

        // Fallback for any remaining unassigned vertices (should be rare).
        for (TileInstance vertex : component.vertexSet()) {
            pos.putIfAbsent(vertex, new Position(0, 0));
        }
        return pos;
    }

    private Offset offsetBetween(TileInstance source, TileInstance target) {
        int sid = source.getTileId();
        int tid = target.getTileId();

        if (source.getNorthNeighbor() != null && source.getNorthNeighbor() == tid) return new Offset(-1, 0);
        if (source.getSouthNeighbor() != null && source.getSouthNeighbor() == tid) return new Offset(1, 0);
        if (source.getEastNeighbor() != null && source.getEastNeighbor() == tid) return new Offset(0, 1);
        if (source.getWestNeighbor() != null && source.getWestNeighbor() == tid) return new Offset(0, -1);

        // Reverse inference if relation was only encoded from target to source.
        if (target.getNorthNeighbor() != null && target.getNorthNeighbor() == sid) return new Offset(1, 0);
        if (target.getSouthNeighbor() != null && target.getSouthNeighbor() == sid) return new Offset(-1, 0);
        if (target.getEastNeighbor() != null && target.getEastNeighbor() == sid) return new Offset(0, -1);
        if (target.getWestNeighbor() != null && target.getWestNeighbor() == sid) return new Offset(0, 1);

        return null;
    }

    private static void addEdgeIfPresent(
        Graph<TileInstance, DefaultEdge> graph,
        Map<Integer, TileInstance> nodeByTileId,
        TileInstance source,
        Integer neighborId
    ) {
        if (neighborId == null) {
            return;
        }
        TileInstance target = nodeByTileId.get(neighborId);
        if (target == null) {
            return;
        }
        if (!graph.containsEdge(source, target)) {
            graph.addEdge(source, target);
        }
    }

    private static Integer mergeNeighbor(List<TileInstance> appearances, Direction direction) {
        Integer fixed = null;
        for (TileInstance tile : appearances) {
            Integer candidate = switch (direction) {
                case SOUTH -> tile.getSouthNeighbor();
                case NORTH -> tile.getNorthNeighbor();
                case EAST -> tile.getEastNeighbor();
                case WEST -> tile.getWestNeighbor();
            };
            if (candidate == null) {
                continue;
            }
            if (fixed == null) {
                fixed = candidate;
                continue;
            }
            if (!fixed.equals(candidate)) {
                return null;
            }
        }
        return fixed;
    }

    public enum Direction {
        SOUTH,
        NORTH,
        EAST,
        WEST
    }

    public record Position(int row, int col) {
    }

    public record Offset(int dRow, int dCol) {
    }
}
