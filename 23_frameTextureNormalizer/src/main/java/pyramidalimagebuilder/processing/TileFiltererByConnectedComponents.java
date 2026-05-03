package pyramidalimagebuilder.processing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import pyramidalimagebuilder.model.TileInstance;

public final class TileFiltererByConnectedComponents {
    public List<TileInstance> filter(List<TileInstance> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return List.of();
        }

        Map<Integer, TileInstance> byId = new HashMap<>();
        Map<Integer, Integer> orderByTileId = new HashMap<>();
        int order = 0;
        for (TileInstance tile : tiles) {
            if (tile != null) {
                byId.put(tile.getTileId(), tile);
                orderByTileId.putIfAbsent(tile.getTileId(), order++);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }

        Graph<Integer, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);
        for (Integer tileId : byId.keySet()) {
            graph.addVertex(tileId);
        }

        for (TileInstance tile : byId.values()) {
            addUndirectedEdge(graph, tile.getTileId(), tile.getNorthNeighbor(), byId);
            addUndirectedEdge(graph, tile.getTileId(), tile.getSouthNeighbor(), byId);
            addUndirectedEdge(graph, tile.getTileId(), tile.getEastNeighbor(), byId);
            addUndirectedEdge(graph, tile.getTileId(), tile.getWestNeighbor(), byId);
        }

        ConnectivityInspector<Integer, DefaultEdge> inspector = new ConnectivityInspector<>(graph);
        List<Set<Integer>> components = inspector.connectedSets();
        if (components.isEmpty()) {
            return List.of();
        }

        List<Set<Integer>> biggestCandidates = new ArrayList<>();
        int max = -1;
        for (Set<Integer> c : components) {
            if (c == null) {
                continue;
            }
            int size = c.size();
            if (size > max) {
                max = size;
                biggestCandidates.clear();
                biggestCandidates.add(c);
            } else if (size == max) {
                biggestCandidates.add(c);
            }
        }
        if (biggestCandidates.isEmpty()) {
            return List.of();
        }

        // Tie-breaker: keep the first component by input tile order.
        Set<Integer> biggest = biggestCandidates.get(0);
        int bestOrder = minOrder(biggest, orderByTileId);
        for (int i = 1; i < biggestCandidates.size(); i++) {
            Set<Integer> candidate = biggestCandidates.get(i);
            int candidateOrder = minOrder(candidate, orderByTileId);
            if (candidateOrder < bestOrder) {
                bestOrder = candidateOrder;
                biggest = candidate;
            }
        }
        if (biggest == null || biggest.isEmpty()) {
            return List.of();
        }

        List<TileInstance> out = new ArrayList<>(biggest.size());
        for (TileInstance tile : tiles) {
            if (tile != null && biggest.contains(tile.getTileId())) {
                out.add(tile);
            }
        }
        return out;
    }

    private static void addUndirectedEdge(
        Graph<Integer, DefaultEdge> graph,
        int sourceId,
        Integer neighborId,
        Map<Integer, TileInstance> byId
    ) {
        if (neighborId == null || !byId.containsKey(neighborId) || sourceId == neighborId) {
            return;
        }
        TileInstance source = byId.get(sourceId);
        TileInstance neighbor = byId.get(neighborId);
        if (source == null || neighbor == null) {
            return;
        }
        if (!isReciprocalRelation(sourceId, source, neighborId, neighbor)) {
            return;
        }
        graph.addEdge(sourceId, neighborId);
    }

    private static boolean isReciprocalRelation(int sourceId, TileInstance source, int neighborId, TileInstance neighbor) {
        return pointsTo(source.getNorthNeighbor(), neighborId) && pointsTo(neighbor.getSouthNeighbor(), sourceId)
            || pointsTo(source.getSouthNeighbor(), neighborId) && pointsTo(neighbor.getNorthNeighbor(), sourceId)
            || pointsTo(source.getEastNeighbor(), neighborId) && pointsTo(neighbor.getWestNeighbor(), sourceId)
            || pointsTo(source.getWestNeighbor(), neighborId) && pointsTo(neighbor.getEastNeighbor(), sourceId);
    }

    private static boolean pointsTo(Integer relation, int expectedId) {
        return relation != null && relation.intValue() == expectedId;
    }

    private static int minOrder(Set<Integer> component, Map<Integer, Integer> orderByTileId) {
        int min = Integer.MAX_VALUE;
        if (component == null || component.isEmpty()) {
            return min;
        }
        for (Integer tileId : component) {
            Integer order = orderByTileId.get(tileId);
            if (order != null && order < min) {
                min = order;
            }
        }
        return min;
    }
}
