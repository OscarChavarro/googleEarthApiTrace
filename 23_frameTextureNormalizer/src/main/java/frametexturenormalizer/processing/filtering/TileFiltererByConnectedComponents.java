package frametexturenormalizer.processing.filtering;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import frametexturenormalizer.model.TileInstance;

public final class TileFiltererByConnectedComponents {
    public List<TileInstance> filter(List<TileInstance> tiles) {
        return flattenComponents(partitionReciprocalComponents(tiles));
    }

    public List<List<TileInstance>> partitionReciprocalComponents(List<TileInstance> tiles) {
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

        List<Set<Integer>> sortedComponents = new ArrayList<>();
        for (Set<Integer> component : components) {
            if (component != null && component.size() >= 2) {
                sortedComponents.add(component);
            }
        }
        sortedComponents.sort(
            Comparator.<Set<Integer>>comparingInt(Set::size).reversed()
                .thenComparingInt(component -> minOrder(component, orderByTileId))
        );

        List<List<TileInstance>> out = new ArrayList<>(sortedComponents.size());
        for (Set<Integer> component : sortedComponents) {
            List<TileInstance> members = new ArrayList<>(component.size());
            for (TileInstance tile : tiles) {
                if (tile != null && component.contains(tile.getTileId())) {
                    members.add(tile);
                }
            }
            if (members.size() >= 2) {
                out.add(List.copyOf(members));
            }
        }
        return out;
    }

    public List<TileInstance> flattenComponents(List<List<TileInstance>> components) {
        if (components == null || components.isEmpty()) {
            return List.of();
        }
        List<TileInstance> out = new ArrayList<>();
        for (List<TileInstance> component : components) {
            if (component == null || component.size() < 2) {
                continue;
            }
            for (TileInstance tile : component) {
                if (tile != null) {
                    out.add(tile);
                }
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
        return relation != null && relation == expectedId;
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
