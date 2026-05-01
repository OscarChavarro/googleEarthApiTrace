package pyramidalimagebuilder.processing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;

public final class TileInstancesMerger {
    private final HintGraphFromTileInstancesBuilder hintsGraphBuilder = new HintGraphFromTileInstancesBuilder();
    private final HintGraphToTileMatrixConvertor graphToTileMatrixConvertor = new HintGraphToTileMatrixConvertor();

    public void execute(PyramidalImageModel model) {
        if (model == null || model.getTileInstances().isEmpty()) {
            return;
        }
        List<TileInstance> tilesWithNoAmbiguousNeighbors = buildMergedTileInstancesFilteredByAmbigousNeighbors(model.getTileInstances());
        model.setMergedTileInstances(tilesWithNoAmbiguousNeighbors);

        ImageBorderFilterer.filter(tilesWithNoAmbiguousNeighbors, model.getImageBorderThreshold());

        Graph<TileInstance, DefaultEdge> hintsGraph = hintsGraphBuilder.build(tilesWithNoAmbiguousNeighbors);
        model.setMergedTileGraph(hintsGraph);

        Set<Graph<TileInstance, DefaultEdge>> components = splitConnectedComponents(hintsGraph);
        List<TileMatrix> tileMatrices = buildTileMatrices(components);
        model.setTileMatrices(tileMatrices);
    }

    private List<TileInstance> buildMergedTileInstancesFilteredByAmbigousNeighbors(List<TileInstance> source) {
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
            String textureFile = mergeTextureFile(appearances);

            Integer south = mergeNeighbor(appearances, NeighborDirection.SOUTH);
            Integer north = mergeNeighbor(appearances, NeighborDirection.NORTH);
            Integer east = mergeNeighbor(appearances, NeighborDirection.EAST);
            Integer west = mergeNeighbor(appearances, NeighborDirection.WEST);

            merged.add(new TileInstance(tileId, representativeFrameId, textureFile, south, north, east, west));
        }

        merged.sort(Comparator.comparingInt(TileInstance::getTileId));
        return merged;
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
            out.add(graphToTileMatrixConvertor.convert(component));
        }
        out.sort((a, b) -> Integer.compare(
            b.getGraph() == null ? 0 : b.getGraph().vertexSet().size(),
            a.getGraph() == null ? 0 : a.getGraph().vertexSet().size()
        ));
        return out;
    }

    private static Integer mergeNeighbor(List<TileInstance> appearances, NeighborDirection neighborDirection) {
        Set<Integer> distinctByDirection = new HashSet<>();
        for (TileInstance tile : appearances) {
            Integer candidate = switch (neighborDirection) {
                case SOUTH -> tile.getSouthNeighbor();
                case NORTH -> tile.getNorthNeighbor();
                case EAST -> tile.getEastNeighbor();
                case WEST -> tile.getWestNeighbor();
            };
            if (candidate != null) {
                distinctByDirection.add(candidate);
            }
        }
        if (distinctByDirection.size() != 1) {
            return null;
        }
        return distinctByDirection.iterator().next();
    }

    private static String mergeTextureFile(List<TileInstance> appearances) {
        for (TileInstance tile : appearances) {
            String path = tile.getTextureFile();
            if (path != null && !path.isBlank()) {
                return path;
            }
        }
        return null;
    }
}
