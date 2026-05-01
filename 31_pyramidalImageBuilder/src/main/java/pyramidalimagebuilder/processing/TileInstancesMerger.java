package pyramidalimagebuilder.processing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
        Map<Integer, List<TileInstance>> byFrame = groupByFrame(model.getTileInstances());
        List<TileMatrix> tileMatrices = new ArrayList<>();
        List<TileInstance> mergedFromSelectedComponents = new ArrayList<>();
        Graph<TileInstance, DefaultEdge> mergedTileGraph = new DefaultDirectedGraph<>(DefaultEdge.class);

        for (Map.Entry<Integer, List<TileInstance>> frameEntry : byFrame.entrySet()) {
            List<TileInstance> mergedFrameTiles =
                buildMergedTileInstancesFilteredByAmbigousNeighbors(frameEntry.getValue());
            Graph<TileInstance, DefaultEdge> frameHintsGraph = hintsGraphBuilder.build(mergedFrameTiles);
            Graph<TileInstance, DefaultEdge> firstConnectedComponent = firstConnectedComponent(frameHintsGraph);
            if (firstConnectedComponent == null || firstConnectedComponent.vertexSet().isEmpty()) {
                continue;
            }
            tileMatrices.add(graphToTileMatrixConvertor.convert(firstConnectedComponent));
            appendSubgraph(firstConnectedComponent, mergedFromSelectedComponents, mergedTileGraph);
        }

        tileMatrices.sort(Comparator.comparingInt(TileInstancesMerger::frameIdOf));
        model.setMergedTileInstances(mergedFromSelectedComponents);
        model.setMergedTileGraph(mergedTileGraph);
        model.setTileMatrices(tileMatrices);
    }

    private static Map<Integer, List<TileInstance>> groupByFrame(List<TileInstance> source) {
        Map<Integer, List<TileInstance>> byFrame = new HashMap<>();
        for (TileInstance tile : source) {
            if (tile == null || tile.getTileId() < 0 || tile.getFrameId() < 0) {
                continue;
            }
            byFrame.computeIfAbsent(tile.getFrameId(), ignored -> new ArrayList<>()).add(tile);
        }
        return byFrame;
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

    private Graph<TileInstance, DefaultEdge> firstConnectedComponent(Graph<TileInstance, DefaultEdge> graph) {
        if (graph == null || graph.vertexSet().isEmpty()) {
            return null;
        }

        ConnectivityInspector<TileInstance, DefaultEdge> inspector =
            new ConnectivityInspector<>(new AsUndirectedGraph<>(graph));
        List<Set<TileInstance>> groups = inspector.connectedSets();
        if (groups.isEmpty()) {
            return null;
        }
        return buildSubgraph(graph, groups.get(0));
    }

    private static Graph<TileInstance, DefaultEdge> buildSubgraph(
        Graph<TileInstance, DefaultEdge> graph,
        Set<TileInstance> group
    ) {
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
        return subgraph;
    }

    private static void appendSubgraph(
        Graph<TileInstance, DefaultEdge> source,
        List<TileInstance> mergedInstancesOutput,
        Graph<TileInstance, DefaultEdge> mergedGraphOutput
    ) {
        for (TileInstance vertex : source.vertexSet()) {
            mergedInstancesOutput.add(vertex);
            mergedGraphOutput.addVertex(vertex);
        }
        for (DefaultEdge edge : source.edgeSet()) {
            TileInstance from = source.getEdgeSource(edge);
            TileInstance to = source.getEdgeTarget(edge);
            if (!mergedGraphOutput.containsEdge(from, to)) {
                mergedGraphOutput.addEdge(from, to);
            }
        }
    }

    private static int frameIdOf(TileMatrix matrix) {
        if (matrix == null || matrix.getGraph() == null || matrix.getGraph().vertexSet().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return matrix.getGraph()
            .vertexSet()
            .stream()
            .mapToInt(TileInstance::getFrameId)
            .min()
            .orElse(Integer.MAX_VALUE);
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
