package matrixmerger.model.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.model.contract.ParentGridTransform;
import matrixmerger.io.WestCuttersJsonReader;
import matrixmerger.processing.WestCutterMatrixSplitter;
import matrixmerger.processing.PairwiseMatrixMerger;
import matrixmerger.processing.uncles.ToUncleRelationship;
import matrixmerger.processing.uncles.UncleDirections;
import matrixmerger.processing.uncles.UncleRelationshipKind;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.material.RendererConfiguration;

public final class MatrixMergerState {
    private final Camera viewingCamera = new Camera();
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private final List<FrameMatrixSet> frameMatrices = new ArrayList<>();
    private final Set<String> residentTexturePaths = new HashSet<>();
    private final Set<String> westCutterTileIds = new HashSet<>();
    private final Map<Integer, String> invalidReasonByFrameId = new LinkedHashMap<>();
    private final ArrayDeque<String> residentTexturesFifo = new ArrayDeque<>();
    private final PairwiseMatrixMerger matrixMerger = new PairwiseMatrixMerger();
    private final WestCutterMatrixSplitter matrixByWestCutterSplitter = new WestCutterMatrixSplitter();
    private final Map<FrameMatrixSet, Integer> hierarchyLevelByFrame = new IdentityHashMap<>();
    private String outputFolder;
    private long gpuTextureBytesAssigned = 0L;
    private int selectedFrameIndex = 0;
    private int maximumRetryCount = 0;
    private boolean lastMergeFailedForCurrentSelection = false;

    public MatrixMergerState() {
        viewingCamera.setName("OrbiterCamera");
        renderingConfiguration.setWires(false);
    }

    public Camera getViewingCamera() {
        return viewingCamera;
    }

    public RendererConfiguration getRenderingConfiguration() {
        return renderingConfiguration;
    }

    public String getOutputFolder() {
        return outputFolder;
    }

    public void setOutputFolder(String outputFolder) {
        this.outputFolder = (outputFolder == null || outputFolder.isBlank()) ? null : outputFolder;
    }

    public void setFrameMatrices(List<FrameMatrixSet> frames) {
        frameMatrices.clear();
        if (frames != null) {
            for (FrameMatrixSet frame : frames) {
                frameMatrices.addAll(normalizeFrame(frame));
            }
        }
        maximumRetryCount = frameMatrices.size();
        selectedFrameIndex = 0;
        lastMergeFailedForCurrentSelection = false;
        normalizeSelection();
        refreshHierarchyOrdering(false);
    }

    public List<FrameMatrixSet> getFrameMatrices() {
        return Collections.unmodifiableList(frameMatrices);
    }

    public void setWestCutterTileIds(Set<String> westCutterTileIds) {
        this.westCutterTileIds.clear();
        if (westCutterTileIds != null) {
            for (String id : westCutterTileIds) {
                String normalized = WestCuttersJsonReader.normalizeScopedTileId(id);
                if (normalized != null) {
                    this.westCutterTileIds.add(normalized);
                }
            }
        }
        matrixMerger.setWestCutterTileIds(this.westCutterTileIds);
    }

    public Set<String> getWestCutterTileIds() {
        return Collections.unmodifiableSet(new HashSet<>(westCutterTileIds));
    }

    public void setInvalidFrames(Map<Integer, String> invalidReasonByFrameId) {
        this.invalidReasonByFrameId.clear();
        if (invalidReasonByFrameId != null) {
            this.invalidReasonByFrameId.putAll(invalidReasonByFrameId);
        }
        if (!this.invalidReasonByFrameId.isEmpty()) {
            selectFirstInvalidFrame();
        }
        normalizeSelection();
        refreshHierarchyOrdering(false);
    }

    public boolean isWestCutterTileId(String tileId) {
        String normalized = WestCuttersJsonReader.normalizeScopedTileId(tileId);
        return normalized != null && westCutterTileIds.contains(normalized);
    }

    public boolean hasInvalidFrames() {
        return !invalidReasonByFrameId.isEmpty();
    }

    public boolean isSelectedFrameInvalid() {
        return invalidReasonByFrameId.containsKey(getSelectedFrameId());
    }

    public String getSelectedFrameInvalidReason() {
        return invalidReasonByFrameId.getOrDefault(getSelectedFrameId(), "");
    }

    public UncleHudStatus getSelectedMatrixUncleHudStatus() {
        FrameTileMatrix selected = getSelectedMatrix();
        if (selected == null || selected.getTiles() == null || selected.getTiles().isEmpty()) {
            return new UncleHudStatus(0, UncleHudState.NORMAL, List.of(), List.of(), Map.of());
        }
        return buildUncleHudStatus(selected, buildFrameIndexByTileId());
    }

    public List<String> getMissingTopLevelUncleTileIds() {
        if (frameMatrices.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> frameIndexByTileId = buildFrameIndexByTileId();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (FrameMatrixSet frame : frameMatrices) {
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                continue;
            }
            FrameTileMatrix matrix = frame.getMatrices().get(0);
            UncleHudStatus status = buildUncleHudStatus(matrix, frameIndexByTileId);
            if (status.state() != UncleHudState.TOPLEVEL || status.missingUncleIds().isEmpty()) {
                continue;
            }
            out.addAll(status.missingUncleIds());
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private UncleHudStatus buildUncleHudStatus(FrameTileMatrix selected, Map<String, Integer> frameIndexByTileId) {
        if (selected == null || selected.getTiles() == null || selected.getTiles().isEmpty()) {
            return new UncleHudStatus(0, UncleHudState.NORMAL, List.of(), List.of(), Map.of());
        }

        Map<String, Integer> uncleCountsByTileId = new LinkedHashMap<>();
        int relationCount = 0;
        for (FrameTileMatrix.TileCoord tile : selected.getTiles()) {
            if (tile == null || tile.getUncles() == null) {
                continue;
            }
            relationCount += tile.getUncles().size();
            for (var relationship : tile.getUncles()) {
                if (relationship == null || relationship.uncleContentId() == null) {
                    continue;
                }
                String normalizedUncleId = WestCuttersJsonReader.normalizeScopedTileId(relationship.uncleContentId());
                if (normalizedUncleId == null || normalizedUncleId.isBlank()) {
                    continue;
                }
                uncleCountsByTileId.merge(normalizedUncleId, 1, Integer::sum);
            }
        }

        if (uncleCountsByTileId.isEmpty()) {
            return new UncleHudStatus(relationCount, UncleHudState.NORMAL, List.of(), List.of(), Map.of());
        }

        LinkedHashSet<Integer> uncleFrameIndexes = new LinkedHashSet<>();
        List<String> missingUncleIds = new ArrayList<>();
        for (String uncleTileId : uncleCountsByTileId.keySet()) {
            Integer frameIndex = frameIndexByTileId.get(uncleTileId);
            if (frameIndex == null) {
                missingUncleIds.add(uncleTileId);
                continue;
            }
            uncleFrameIndexes.add(frameIndex);
        }

        UncleHudState state;
        if (uncleFrameIndexes.isEmpty()) {
            state = UncleHudState.TOPLEVEL;
        }
        else if (uncleFrameIndexes.size() > 1) {
            state = parentMatricesShareHierarchyLevel(uncleFrameIndexes)
                ? UncleHudState.NORMAL
                : UncleHudState.BROKEN;
        }
        else {
            state = UncleHudState.NORMAL;
        }
        return new UncleHudStatus(
            relationCount,
            state,
            new ArrayList<>(uncleCountsByTileId.keySet()),
            missingUncleIds,
            buildLocatedUncleTiles(uncleCountsByTileId.keySet())
        );
    }

    private boolean parentMatricesShareHierarchyLevel(Set<Integer> frameIndexes) {
        Integer sharedLevel = null;
        for (Integer frameIndex : frameIndexes) {
            if (frameIndex == null || frameIndex < 0 || frameIndex >= frameMatrices.size()) {
                return false;
            }
            Integer level = hierarchyLevelByFrame.get(frameMatrices.get(frameIndex));
            if (level == null || level < 0) {
                return false;
            }
            if (sharedLevel == null) {
                sharedLevel = level;
            }
            else if (!sharedLevel.equals(level)) {
                return false;
            }
        }
        return sharedLevel != null;
    }

    public FrameTileMatrix getSelectedMatrix() {
        FrameMatrixSet frame = getSelectedFrameMatrices();
        return frame == null ? null : frame.getMatrices().get(0);
    }

    public int getSelectedMatrixOrdinal() {
        return frameMatrices.isEmpty() ? 0 : selectedFrameIndex + 1;
    }

    public int getMatrixCount() {
        return frameMatrices.size();
    }

    public int getFrameCount() {
        return frameMatrices.size();
    }

    public int getMaximumRetryCount() {
        return maximumRetryCount;
    }

    public int getSelectedFrameId() {
        FrameMatrixSet frame = getSelectedFrameMatrices();
        return frame == null ? -1 : frame.getFrameId();
    }

    public String getSelectedFrameLabel() {
        return formatFrameLabel(getSelectedFrameId());
    }

    public String getSelectedHierarchyLabel() {
        Integer level = hierarchyLevelByFrame.get(getSelectedFrameMatrices());
        if (level == null || level < 0) {
            return null;
        }
        return level == 0 ? "l" : "l + " + level;
    }

    public List<HierarchyOrderDiagnostic> getHierarchyOrderDiagnostics() {
        Map<String, Integer> frameIndexByTileId = buildFrameIndexByTileId();
        List<HierarchyOrderDiagnostic> out = new ArrayList<>(frameMatrices.size());
        for (int frameIndex = 0; frameIndex < frameMatrices.size(); frameIndex++) {
            FrameMatrixSet frame = frameMatrices.get(frameIndex);
            LinkedHashSet<String> uncleIds = collectHierarchyUncleIds(frame);
            LinkedHashSet<Integer> parentIndexes = new LinkedHashSet<>();
            int unresolvedUncleCount = 0;
            for (String uncleId : uncleIds) {
                Integer parentIndex = frameIndexByTileId.get(uncleId);
                if (parentIndex == null) {
                    unresolvedUncleCount++;
                }
                else if (parentIndex != frameIndex) {
                    parentIndexes.add(parentIndex);
                }
            }
            Integer explicitParentIndex = indexOfFrameIdentity(frame.getInferredParent());
            if (explicitParentIndex != null && explicitParentIndex != frameIndex) {
                parentIndexes.clear();
                parentIndexes.add(explicitParentIndex);
            }
            out.add(new HierarchyOrderDiagnostic(
                frameIndex,
                hierarchyLevelByFrame.getOrDefault(frame, -1),
                findLastCaptureFrameId(frame),
                uncleIds.size(),
                List.copyOf(parentIndexes),
                unresolvedUncleCount,
                tileCountOfFrame(frame)
            ));
        }
        return List.copyOf(out);
    }

    public boolean selectPreviousMatrix() {
        if (frameMatrices.isEmpty() || selectedFrameIndex <= 0) {
            return false;
        }
        selectedFrameIndex--;
        lastMergeFailedForCurrentSelection = false;
        return true;
    }

    public boolean selectNextMatrix() {
        if (frameMatrices.isEmpty() || selectedFrameIndex >= frameMatrices.size() - 1) {
            return false;
        }
        selectedFrameIndex++;
        lastMergeFailedForCurrentSelection = false;
        return true;
    }

    public boolean selectFrameIndex(int index) {
        if (frameMatrices.isEmpty() || index < 0 || index >= frameMatrices.size()) {
            return false;
        }
        selectedFrameIndex = index;
        lastMergeFailedForCurrentSelection = false;
        normalizeSelection();
        return true;
    }

    public boolean deleteSelectedMatrix() {
        FrameMatrixSet selected = getSelectedFrameMatrices();
        if (selected == null || frameMatrices.size() <= 1) {
            return false;
        }
        invalidReasonByFrameId.remove(selected.getFrameId());
        hierarchyLevelByFrame.remove(selected);
        frameMatrices.remove(selectedFrameIndex);
        if (selectedFrameIndex >= frameMatrices.size()) {
            selectedFrameIndex = frameMatrices.size() - 1;
        }
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering(false);
        lastMergeFailedForCurrentSelection = false;
        return true;
    }

    public SmallMatrixDiscardReport discardMatricesWithFewerThanTiles(int minimumTileCount) {
        int threshold = Math.max(0, minimumTileCount);
        int discardedMatrices = 0;
        int discardedTiles = 0;
        List<String> discardedTileIds = new ArrayList<>();
        List<FrameMatrixSet> discardedFrames = new ArrayList<>();
        for (FrameMatrixSet frame : frameMatrices) {
            int tileCount = tileCountOfFrame(frame);
            if (tileCount >= threshold) {
                continue;
            }
            discardedFrames.add(frame);
            discardedMatrices++;
            discardedTiles += tileCount;
            if (frame == null || frame.getMatrices() == null) {
                continue;
            }
            for (FrameTileMatrix matrix : frame.getMatrices()) {
                if (matrix == null || matrix.getTiles() == null) {
                    continue;
                }
                for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                    if (tile != null && tile.getId() != null && !tile.getId().isBlank()) {
                        discardedTileIds.add(tile.getId());
                    }
                }
            }
        }
        for (FrameMatrixSet frame : discardedFrames) {
            invalidReasonByFrameId.remove(frame.getFrameId());
            hierarchyLevelByFrame.remove(frame);
        }
        frameMatrices.removeAll(discardedFrames);
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering(false);
        lastMergeFailedForCurrentSelection = false;
        return new SmallMatrixDiscardReport(
            discardedMatrices,
            discardedTiles,
            List.copyOf(discardedTileIds)
        );
    }

    /**
     * Rebuilds every matrix from its orthogonally connected tile components and drops
     * components too small to carry useful spatial information. This must run after
     * exclusive ownership, because removing a duplicated tile can split a formerly
     * connected matrix into several islands.
     */
    public TopologyFilterReport discardSmallFourConnectedComponents(int minimumComponentTileCount) {
        int threshold = Math.max(1, minimumComponentTileCount);
        int inputMatrices = frameMatrices.size();
        int discardedComponents = 0;
        int discardedTiles = 0;
        int splitMatrices = 0;
        List<String> discardedTileIds = new ArrayList<>();
        List<FrameMatrixSet> rebuiltFrames = new ArrayList<>();

        for (FrameMatrixSet frame : frameMatrices) {
            FrameTileMatrix matrix = firstMatrix(frame);
            if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
                continue;
            }
            List<List<FrameTileMatrix.TileCoord>> components = fourConnectedComponents(matrix.getTiles());
            int retainedFromFrame = 0;
            for (List<FrameTileMatrix.TileCoord> component : components) {
                if (component.size() < threshold) {
                    discardedComponents++;
                    discardedTiles += component.size();
                    for (FrameTileMatrix.TileCoord tile : component) {
                        if (tile != null && tile.getId() != null && !tile.getId().isBlank()) {
                            discardedTileIds.add(tile.getId());
                        }
                    }
                    continue;
                }
                rebuiltFrames.add(copyFrameForComponent(frame, component));
                retainedFromFrame++;
            }
            if (retainedFromFrame > 1) {
                splitMatrices++;
            }
        }

        frameMatrices.clear();
        frameMatrices.addAll(rebuiltFrames);
        invalidReasonByFrameId.keySet().removeIf(frameId -> frameMatrices.stream()
            .noneMatch(frame -> frame != null && frame.getFrameId() == frameId));
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering(false);
        lastMergeFailedForCurrentSelection = false;
        return new TopologyFilterReport(
            inputMatrices,
            frameMatrices.size(),
            discardedComponents,
            discardedTiles,
            splitMatrices,
            List.copyOf(discardedTileIds)
        );
    }

    private static List<List<FrameTileMatrix.TileCoord>> fourConnectedComponents(
        List<FrameTileMatrix.TileCoord> tiles
    ) {
        if (tiles == null || tiles.isEmpty()) {
            return List.of();
        }
        Map<String, FrameTileMatrix.TileCoord> byPosition = new LinkedHashMap<>();
        for (FrameTileMatrix.TileCoord tile : tiles) {
            if (tile != null) {
                byPosition.put(tile.getI() + ":" + tile.getJ(), tile);
            }
        }
        Set<String> visited = new LinkedHashSet<>();
        List<List<FrameTileMatrix.TileCoord>> components = new ArrayList<>();
        for (FrameTileMatrix.TileCoord seed : tiles) {
            if (seed == null) {
                continue;
            }
            String seedKey = seed.getI() + ":" + seed.getJ();
            if (!visited.add(seedKey)) {
                continue;
            }
            ArrayDeque<FrameTileMatrix.TileCoord> pending = new ArrayDeque<>();
            List<FrameTileMatrix.TileCoord> component = new ArrayList<>();
            pending.addLast(seed);
            while (!pending.isEmpty()) {
                FrameTileMatrix.TileCoord current = pending.removeFirst();
                component.add(current);
                enqueueNeighbor(current.getI() - 1, current.getJ(), byPosition, visited, pending);
                enqueueNeighbor(current.getI() + 1, current.getJ(), byPosition, visited, pending);
                enqueueNeighbor(current.getI(), current.getJ() - 1, byPosition, visited, pending);
                enqueueNeighbor(current.getI(), current.getJ() + 1, byPosition, visited, pending);
            }
            components.add(component);
        }
        return components;
    }

    private static FrameMatrixSet copyFrameForComponent(
        FrameMatrixSet source,
        List<FrameTileMatrix.TileCoord> component
    ) {
        int minI = component.stream().mapToInt(FrameTileMatrix.TileCoord::getI).min().orElse(0);
        int minJ = component.stream().mapToInt(FrameTileMatrix.TileCoord::getJ).min().orElse(0);
        int maxI = component.stream().mapToInt(FrameTileMatrix.TileCoord::getI).max().orElse(minI);
        int maxJ = component.stream().mapToInt(FrameTileMatrix.TileCoord::getJ).max().orElse(minJ);
        FrameTileMatrix matrix = new FrameTileMatrix();
        matrix.setFrameId(source.getFrameId());
        matrix.setRows(maxI - minI + 1);
        matrix.setCols(maxJ - minJ + 1);
        List<FrameTileMatrix.TileCoord> copiedTiles = new ArrayList<>(component.size());
        for (FrameTileMatrix.TileCoord original : component) {
            FrameTileMatrix.TileCoord copy = new FrameTileMatrix.TileCoord();
            copy.setId(original.getId());
            copy.setI(original.getI() - minI);
            copy.setJ(original.getJ() - minJ);
            copy.setTextureFile(original.getTextureFile());
            copy.setUncles(original.getUncles());
            copiedTiles.add(copy);
        }
        matrix.setTiles(copiedTiles);

        FrameMatrixSet out = new FrameMatrixSet();
        out.setContractVersion(source.getContractVersion());
        out.setHierarchyLevel(source.getHierarchyLevel());
        out.setParentMatrixIndex(source.getParentMatrixIndex());
        out.setParentLevelDelta(source.getParentLevelDelta());
        out.setParentGridTransform(source.getParentGridTransform());
        out.setInferredParent(source.getInferredParent());
        out.setFrameId(source.getFrameId());
        out.setMatrices(List.of(matrix));
        out.setHierarchyUnclesByTileId(buildHierarchyUnclesByTileId(source, matrix));
        out.setHierarchyRelationshipsByTileId(buildHierarchyRelationshipsByTileId(source, matrix));
        return out;
    }

    /**
     * Makes final matrix membership exclusive while preserving first-assignment order.
     * Shared IDs are still available as alignment anchors during merging; this method is
     * intentionally called only after the automatic merge/cut sweeps have converged.
     */
    public ExclusiveTileOwnershipReport enforceExclusiveTileOwnership() {
        Set<String> assignedTileIds = new LinkedHashSet<>();
        int duplicateOccurrencesRemoved = 0;
        int affectedMatrices = 0;
        int emptyMatricesRemoved = 0;
        List<FrameMatrixSet> emptyFrames = new ArrayList<>();

        for (FrameMatrixSet frame : frameMatrices) {
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                continue;
            }
            FrameTileMatrix matrix = frame.getMatrices().get(0);
            if (matrix == null || matrix.getTiles() == null) {
                continue;
            }
            Set<String> removedFromMatrix = new LinkedHashSet<>();
            matrix.getTiles().removeIf(tile -> {
                if (tile == null || tile.getId() == null || tile.getId().isBlank()) {
                    return false;
                }
                if (assignedTileIds.add(tile.getId())) {
                    return false;
                }
                removedFromMatrix.add(tile.getId());
                return true;
            });
            if (!removedFromMatrix.isEmpty()) {
                duplicateOccurrencesRemoved += removedFromMatrix.size();
                affectedMatrices++;
                removeHierarchyEntries(frame, removedFromMatrix);
            }
            if (matrix.getTiles().isEmpty()) {
                emptyFrames.add(frame);
            }
        }

        for (FrameMatrixSet frame : emptyFrames) {
            invalidReasonByFrameId.remove(frame.getFrameId());
            hierarchyLevelByFrame.remove(frame);
            if (frameMatrices.remove(frame)) {
                emptyMatricesRemoved++;
            }
        }
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering(false);
        lastMergeFailedForCurrentSelection = false;
        return new ExclusiveTileOwnershipReport(
            duplicateOccurrencesRemoved,
            affectedMatrices,
            emptyMatricesRemoved
        );
    }

    private static void removeHierarchyEntries(FrameMatrixSet frame, Set<String> removedTileIds) {
        Map<String, List<String>> uncles = new LinkedHashMap<>(frame.getHierarchyUnclesByTileId());
        Map<String, List<ToUncleRelationship>> relationships =
            new LinkedHashMap<>(frame.getHierarchyRelationshipsByTileId());
        for (String tileId : removedTileIds) {
            uncles.remove(tileId);
            relationships.remove(tileId);
        }
        frame.setHierarchyUnclesByTileId(uncles);
        frame.setHierarchyRelationshipsByTileId(relationships);
    }

    public boolean mergeSelectedMatrixWithNext() {
        if (isSelectedFrameInvalid()) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }
        FrameMatrixSet current = getSelectedFrameMatrices();
        Integer nextFrameIndex = nextFrameIndexAfter(selectedFrameIndex);
        if (current == null || nextFrameIndex == null) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }

        FrameTileMatrix a = current.getMatrices().get(0);
        FrameTileMatrix b = frameMatrices.get(nextFrameIndex).getMatrices().get(0);
        if (!matrixMerger.merge(a, b)) {
            pruneTinyFramesAfterFailedMerge(selectedFrameIndex, nextFrameIndex);
            normalizeSelection();
            lastMergeFailedForCurrentSelection = true;
            return false;
        }

        mergeHierarchyUncles(current, frameMatrices.get(nextFrameIndex));
        frameMatrices.remove(nextFrameIndex.intValue());
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering(false);
        lastMergeFailedForCurrentSelection = false;
        return true;
    }

    public boolean mergeSelectedMatrixWithNextFrameAggressively() {
        if (isSelectedFrameInvalid()) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }
        FrameMatrixSet current = getSelectedFrameMatrices();
        Integer nextFrameIndex = nextFrameIndexAfter(selectedFrameIndex);
        if (current == null || nextFrameIndex == null) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }

        FrameTileMatrix a = current.getMatrices().get(0);
        FrameTileMatrix b = frameMatrices.get(nextFrameIndex).getMatrices().get(0);
        if (matrixMerger.merge(a, b)) {
            mergeHierarchyUncles(current, frameMatrices.get(nextFrameIndex));
            frameMatrices.remove(nextFrameIndex.intValue());
            maximumRetryCount = frameMatrices.size();
            normalizeSelection();
            lastMergeFailedForCurrentSelection = false;
            return true;
        }

        moveFrameToEnd(nextFrameIndex);
        normalizeSelection();
        lastMergeFailedForCurrentSelection = true;
        return true;
    }

    public boolean retryMergeSelectedMatrixWithNextFrames() {
        if (isSelectedFrameInvalid()) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }
        if (getSelectedFrameMatrices() == null) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }
        boolean changedAny = false;
        boolean mergedAny = false;
        for (int attempt = 0; attempt < maximumRetryCount; attempt++) {
            Integer nextFrameIndex = nextFrameIndexAfter(selectedFrameIndex);
            if (nextFrameIndex == null) {
                break;
            }
            boolean changed = mergeSelectedMatrixWithNextFrameAggressively();
            if (!changed) {
                break;
            }
            changedAny = true;
            if (!lastMergeFailedForCurrentSelection) {
                mergedAny = true;
            }
        }
        refreshHierarchyOrdering(false);
        lastMergeFailedForCurrentSelection = !mergedAny;
        return changedAny;
    }

    public boolean hasNextMatrixForSelection() {
        return nextFrameIndexAfter(selectedFrameIndex) != null;
    }

    public FrameTileMatrix getNextMatrixForSelection() {
        Integer nextFrameIndex = nextFrameIndexAfter(selectedFrameIndex);
        return nextFrameIndex == null ? null : frameMatrices.get(nextFrameIndex).getMatrices().get(0);
    }

    public int getNextMatrixFrameIdForSelection() {
        Integer nextFrameIndex = nextFrameIndexAfter(selectedFrameIndex);
        return nextFrameIndex == null ? -1 : frameMatrices.get(nextFrameIndex).getFrameId();
    }

    public String getNextFrameLabelForSelection() {
        return formatFrameLabel(getNextMatrixFrameIdForSelection());
    }

    public boolean hasLastMergeFailedForCurrentSelection() {
        return lastMergeFailedForCurrentSelection;
    }

    public boolean splitSelectedFrameByWestCutters() {
        FrameMatrixSet selectedFrame = getSelectedFrameMatrices();
        if (selectedFrame == null || isSelectedFrameInvalid()) {
            return false;
        }
        WestCutterMatrixSplitter.FrameSplitResult split = matrixByWestCutterSplitter.splitFrame(selectedFrame, westCutterTileIds);
        if (!split.changed() || split.mainFrame() == null) {
            return false;
        }
        frameMatrices.set(selectedFrameIndex, split.mainFrame());
        if (split.transientFrame() != null) {
            frameMatrices.add(split.transientFrame());
        }
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering(false);
        return true;
    }

    public boolean mergeFullSet() {
        if (isSelectedFrameInvalid()) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }
        boolean mergedAny = false;
        boolean changed = true;
        while (changed && frameMatrices.size() > 1) {
            changed = false;
            for (int i = 0; i < frameMatrices.size(); i++) {
                FrameTileMatrix a = frameMatrices.get(i).getMatrices().get(0);
                for (int j = i + 1; j < frameMatrices.size(); j++) {
                    FrameTileMatrix b = frameMatrices.get(j).getMatrices().get(0);
                    if (!matrixMerger.merge(a, b)) {
                        continue;
                    }
                    frameMatrices.remove(j);
                    maximumRetryCount = frameMatrices.size();
                    mergedAny = true;
                    changed = true;
                    normalizeSelection();
                    refreshHierarchyOrdering(false);
                    break;
                }
                if (changed) {
                    break;
                }
            }
        }
        lastMergeFailedForCurrentSelection = false;
        return mergedAny;
    }

    /**
     * Performs the output-layer merge pass. Only adjacent matrices in the resolved
     * hierarchy order and at the same level are considered. Alignment must come from
     * shared native tile IDs or from consistent observed uncle relationships to one
     * common parent matrix.
     */
    public SameLevelCollapseReport collapseAdjacentMatricesAtSameHierarchyLevel() {
        sortFramesByUncleHierarchy();
        int inputCount = frameMatrices.size();
        int sharedTileMerges = 0;
        int relationshipClueMerges = 0;
        int compatibleGridMerges = 0;
        boolean changed;
        do {
            changed = false;
            int index = 0;
            while (index + 1 < frameMatrices.size()) {
                FrameMatrixSet current = frameMatrices.get(index);
                FrameMatrixSet next = frameMatrices.get(index + 1);
                int currentLevel = hierarchyLevelByFrame.getOrDefault(current, -1);
                int nextLevel = hierarchyLevelByFrame.getOrDefault(next, -1);
                if (currentLevel < 0 || currentLevel != nextLevel) {
                    index++;
                    continue;
                }

                FrameTileMatrix currentMatrix = firstMatrix(current);
                FrameTileMatrix nextMatrix = firstMatrix(next);
                boolean merged = matrixMerger.merge(currentMatrix, nextMatrix);
                if (merged) {
                    sharedTileMerges++;
                }
                else {
                    ParentSpaceAnchor currentAnchor = observedParentSpaceAnchor(current, currentLevel);
                    ParentSpaceAnchor nextAnchor = observedParentSpaceAnchor(next, nextLevel);
                    if (currentAnchor != null
                        && nextAnchor != null
                        && currentAnchor.parent() == nextAnchor.parent()) {
                        int deltaI = nextAnchor.rowOffset() - currentAnchor.rowOffset();
                        int deltaJ = nextAnchor.colOffset() - currentAnchor.colOffset();
                        int minI = Math.min(minCoordinate(currentMatrix, true), minCoordinate(nextMatrix, true) + deltaI);
                        int minJ = Math.min(minCoordinate(currentMatrix, false), minCoordinate(nextMatrix, false) + deltaJ);
                        merged = matrixMerger.mergeWithOffset(currentMatrix, nextMatrix, deltaI, deltaJ);
                        if (merged) {
                            current.setParentGridTransform(new ParentGridTransform(
                                currentAnchor.rowOffset() + minI,
                                currentAnchor.colOffset() + minJ
                            ));
                            relationshipClueMerges++;
                        }
                    }
                    if (!merged && haveSameDeclaredGrid(currentMatrix, nextMatrix)) {
                        merged = matrixMerger.mergeWithOffset(currentMatrix, nextMatrix, 0, 0);
                        if (merged) {
                            compatibleGridMerges++;
                        }
                    }
                }
                if (!merged) {
                    index++;
                    continue;
                }

                mergeHierarchyUncles(current, next);
                frameMatrices.remove(index + 1);
                changed = true;
                maximumRetryCount = frameMatrices.size();
                refreshHierarchyOrdering(false);
            }
        }
        while (changed);
        sortFramesByUncleHierarchy();
        normalizeSelection();
        lastMergeFailedForCurrentSelection = false;
        return new SameLevelCollapseReport(
            inputCount,
            frameMatrices.size(),
            sharedTileMerges,
            relationshipClueMerges,
            compatibleGridMerges
        );
    }

    /**
     * A zero-offset merge has no positional evidence of its own. It is only
     * meaningful for two partial observations of the same declared grid. In
     * particular, do not place a small disconnected matrix at the origin of a
     * larger matrix merely because their occupied cells happen not to clash.
     */
    private static boolean haveSameDeclaredGrid(FrameTileMatrix a, FrameTileMatrix b) {
        return a != null
            && b != null
            && a.getRows() == b.getRows()
            && a.getCols() == b.getCols();
    }

    private ParentSpaceAnchor observedParentSpaceAnchor(FrameMatrixSet child, int childLevel) {
        if (child == null || childLevel <= 0) {
            return null;
        }
        Map<String, ParentTileRef> parentTileById = new LinkedHashMap<>();
        for (FrameMatrixSet candidateParent : frameMatrices) {
            if (candidateParent == null
                || hierarchyLevelByFrame.getOrDefault(candidateParent, -1) >= childLevel) {
                continue;
            }
            FrameTileMatrix parentMatrix = firstMatrix(candidateParent);
            if (parentMatrix == null || parentMatrix.getTiles() == null) {
                continue;
            }
            for (FrameTileMatrix.TileCoord tile : parentMatrix.getTiles()) {
                if (tile != null && tile.getId() != null && !tile.getId().isBlank()) {
                    parentTileById.putIfAbsent(tile.getId(), new ParentTileRef(candidateParent, tile));
                }
            }
        }

        Map<ParentSpaceAnchor, Integer> votes = new LinkedHashMap<>();
        int acceptedClues = 0;
        FrameTileMatrix childMatrix = firstMatrix(child);
        if (childMatrix == null || childMatrix.getTiles() == null) {
            return null;
        }
        for (FrameTileMatrix.TileCoord tile : childMatrix.getTiles()) {
            if (tile == null || tile.getUncles() == null) {
                continue;
            }
            for (ToUncleRelationship relationship : tile.getUncles()) {
                if (relationship == null
                    || relationship.direction() == null
                    || relationship.uncleContentId() == null
                    || relationship.relationshipKind() == null) {
                    continue;
                }
                String uncleId = WestCuttersJsonReader.normalizeScopedTileId(relationship.uncleContentId());
                ParentTileRef parent = parentTileById.get(uncleId);
                int levelDelta = relationship.effectiveLevelDelta();
                if (parent == null
                    || hierarchyLevelByFrame.getOrDefault(parent.frame(), -1) + levelDelta != childLevel) {
                    continue;
                }
                int[] childPosition = childPositionInRefinedParentGrid(parent, relationship);
                if (parent == null || childPosition == null) {
                    continue;
                }
                ParentSpaceAnchor anchor = new ParentSpaceAnchor(
                    parent.frame(),
                    childPosition[0] - tile.getI(),
                    childPosition[1] - tile.getJ()
                );
                votes.merge(anchor, 1, Integer::sum);
                acceptedClues++;
            }
        }
        ParentSpaceAnchor best = null;
        int bestVotes = 0;
        for (Map.Entry<ParentSpaceAnchor, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestVotes) {
                best = vote.getKey();
                bestVotes = vote.getValue();
            }
        }
        return best != null && bestVotes * 2 > acceptedClues ? best : null;
    }

    private static int[] childPositionInRefinedParentGrid(
        ParentTileRef parent,
        ToUncleRelationship relationship
    ) {
        if (parent == null || parent.tile() == null || relationship == null) {
            return null;
        }
        int parentI = parent.tile().getI();
        int parentJ = parent.tile().getJ();
        if (relationship.hasGridOffset()) {
            if (relationship.levelDelta() >= 30) {
                return null;
            }
            int scale = 1 << relationship.levelDelta();
            return new int[]{
                scale * parentI + relationship.rowOffset(),
                scale * parentJ + relationship.columnOffset()
            };
        }
        boolean south;
        boolean east;
        UncleDirections direction = relationship.direction();
        if (relationship.relationshipKind() == UncleRelationshipKind.CONTAINING_QUADRANT) {
            south = direction == UncleDirections.WEST_SOUTH
                || direction == UncleDirections.SOUTH_WEST
                || direction == UncleDirections.EAST_SOUTH
                || direction == UncleDirections.SOUTH_EAST;
            east = direction == UncleDirections.EAST_SOUTH
                || direction == UncleDirections.SOUTH_EAST
                || direction == UncleDirections.EAST_NORTH
                || direction == UncleDirections.NORTH_EAST;
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

    private static int minCoordinate(FrameTileMatrix matrix, boolean row) {
        if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
            return 0;
        }
        return matrix.getTiles().stream()
            .mapToInt(tile -> row ? tile.getI() : tile.getJ())
            .min()
            .orElse(0);
    }

    public void sortFramesByTileCountAscending() {
        frameMatrices.sort(Comparator.comparingInt(MatrixMergerState::tileCountOfFrame));
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering(false);
        lastMergeFailedForCurrentSelection = false;
    }

    /** Orders the final matrices from the top quadtree level to the deepest one. */
    public void sortFramesByUncleHierarchy() {
        refreshHierarchyOrdering(true);
        selectedFrameIndex = 0;
        lastMergeFailedForCurrentSelection = false;
        normalizeSelection();
    }

    public synchronized long getGpuTextureBytesAssigned() {
        return gpuTextureBytesAssigned;
    }

    public synchronized boolean markTextureResident(String texturePath, long bytes) {
        if (texturePath == null || texturePath.isBlank() || bytes <= 0L || residentTexturePaths.contains(texturePath)) {
            return false;
        }
        residentTexturePaths.add(texturePath);
        residentTexturesFifo.addLast(texturePath);
        gpuTextureBytesAssigned += bytes;
        return true;
    }

    public synchronized String popOldestResidentTexturePath() {
        while (!residentTexturesFifo.isEmpty()) {
            String texturePath = residentTexturesFifo.pollFirst();
            if (residentTexturePaths.contains(texturePath)) {
                return texturePath;
            }
        }
        return null;
    }

    public synchronized void unmarkTextureResident(String texturePath, long bytes) {
        if (texturePath == null || !residentTexturePaths.remove(texturePath)) {
            return;
        }
        residentTexturesFifo.removeFirstOccurrence(texturePath);
        gpuTextureBytesAssigned = Math.max(0L, gpuTextureBytesAssigned - Math.max(0L, bytes));
    }

    private FrameMatrixSet getSelectedFrameMatrices() {
        normalizeSelection();
        if (frameMatrices.isEmpty()) {
            return null;
        }
        return frameMatrices.get(selectedFrameIndex);
    }

    private void normalizeSelection() {
        if (frameMatrices.isEmpty()) {
            selectedFrameIndex = 0;
            return;
        }
        selectedFrameIndex = Math.max(0, Math.min(selectedFrameIndex, frameMatrices.size() - 1));
        while (!frameMatrices.isEmpty() && isEmpty(frameMatrices.get(selectedFrameIndex))) {
            frameMatrices.remove(selectedFrameIndex);
            if (selectedFrameIndex >= frameMatrices.size()) {
                selectedFrameIndex = Math.max(0, frameMatrices.size() - 1);
            }
        }
        if (frameMatrices.isEmpty()) {
            selectedFrameIndex = 0;
            return;
        }
        selectedFrameIndex = Math.max(0, Math.min(selectedFrameIndex, frameMatrices.size() - 1));
    }

    private Integer nextFrameIndexAfter(int frameIndex) {
        for (int candidate = frameIndex + 1; candidate < frameMatrices.size(); candidate++) {
            if (!isEmpty(frameMatrices.get(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private void moveFrameToEnd(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= frameMatrices.size() || frameMatrices.size() <= 1) {
            return;
        }
        FrameMatrixSet frame = frameMatrices.remove(frameIndex);
        frameMatrices.add(frame);
        if (frameIndex < selectedFrameIndex) {
            selectedFrameIndex--;
        }
    }

    private void pruneTinyFramesAfterFailedMerge(int currentFrameIndex, int nextFrameIndex) {
        removeTinyFrame(nextFrameIndex, currentFrameIndex);
        removeTinyFrame(currentFrameIndex, currentFrameIndex);
        maximumRetryCount = frameMatrices.size();
    }

    private void removeTinyFrame(int frameIndex, int currentFrameIndex) {
        if (frameIndex < 0 || frameIndex >= frameMatrices.size()) {
            return;
        }
        FrameMatrixSet frame = frameMatrices.get(frameIndex);
        if (!isTinySingleMatrixFrame(frame)) {
            return;
        }
        frameMatrices.remove(frameIndex);
        if (frameIndex < selectedFrameIndex) {
            selectedFrameIndex--;
        } else if (frameIndex == currentFrameIndex) {
            selectedFrameIndex = Math.max(0, selectedFrameIndex - 1);
        }
    }

    private static boolean isTinySingleMatrixFrame(FrameMatrixSet frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().size() != 1) {
            return false;
        }
        FrameTileMatrix matrix = frame.getMatrices().get(0);
        return matrix != null && matrix.getTiles() != null && matrix.getTiles().size() <= 2;
    }

    private static List<FrameMatrixSet> normalizeFrame(FrameMatrixSet frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
            return List.of();
        }
        List<FrameMatrixSet> normalized = new ArrayList<>();
        for (FrameTileMatrix sourceMatrix : frame.getMatrices()) {
            if (!isValidMatrix(sourceMatrix)) {
                continue;
            }
            FrameTileMatrix matrix = copyMatrix(sourceMatrix, frame.getFrameId());
            FrameMatrixSet normalizedFrame = new FrameMatrixSet();
            normalizedFrame.setContractVersion(frame.getContractVersion());
            normalizedFrame.setHierarchyLevel(frame.getHierarchyLevel());
            normalizedFrame.setParentMatrixIndex(frame.getParentMatrixIndex());
            normalizedFrame.setParentLevelDelta(frame.getParentLevelDelta());
            normalizedFrame.setParentGridTransform(frame.getParentGridTransform());
            normalizedFrame.setInferredParent(frame.getInferredParent());
            normalizedFrame.setFrameId(frame.getFrameId());
            normalizedFrame.setMatrices(List.of(matrix));
            normalizedFrame.setHierarchyUnclesByTileId(buildHierarchyUnclesByTileId(frame, matrix));
            normalizedFrame.setHierarchyRelationshipsByTileId(buildHierarchyRelationshipsByTileId(frame, matrix));
            normalized.add(normalizedFrame);
        }
        return normalized;
    }

    private static boolean isEmpty(FrameMatrixSet frame) {
        return frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty();
    }

    private static int tileCountOfFrame(FrameMatrixSet frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
            return 0;
        }
        FrameTileMatrix matrix = frame.getMatrices().get(0);
        return matrix == null || matrix.getTiles() == null ? 0 : matrix.getTiles().size();
    }

    private static FrameTileMatrix firstMatrix(FrameMatrixSet frame) {
        return frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()
            ? null
            : frame.getMatrices().get(0);
    }

    private static boolean isValidMatrix(FrameTileMatrix matrix) {
        if (matrix == null || matrix.getTiles() == null || matrix.getTiles().size() < 2) {
            return false;
        }
        Set<String> tileIds = new LinkedHashSet<>();
        Set<String> coordinates = new LinkedHashSet<>();
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null || tile.getId() == null || tile.getId().isBlank()) {
                return false;
            }
            if (tile.getI() < 0 || tile.getJ() < 0 || tile.getI() >= matrix.getRows() || tile.getJ() >= matrix.getCols()) {
                return false;
            }
            if (!tileIds.add(tile.getId())) {
                return false;
            }
            if (!coordinates.add(tile.getI() + ":" + tile.getJ())) {
                return false;
            }
        }
        return isOrthogonallyConnected(matrix);
    }

    private static boolean isOrthogonallyConnected(FrameTileMatrix matrix) {
        Map<String, FrameTileMatrix.TileCoord> byPosition = new LinkedHashMap<>();
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            byPosition.put(tile.getI() + ":" + tile.getJ(), tile);
        }
        FrameTileMatrix.TileCoord start = matrix.getTiles().get(0);
        ArrayDeque<FrameTileMatrix.TileCoord> pending = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        pending.add(start);
        visited.add(start.getI() + ":" + start.getJ());
        while (!pending.isEmpty()) {
            FrameTileMatrix.TileCoord current = pending.removeFirst();
            enqueueNeighbor(current.getI() - 1, current.getJ(), byPosition, visited, pending);
            enqueueNeighbor(current.getI() + 1, current.getJ(), byPosition, visited, pending);
            enqueueNeighbor(current.getI(), current.getJ() - 1, byPosition, visited, pending);
            enqueueNeighbor(current.getI(), current.getJ() + 1, byPosition, visited, pending);
        }
        return visited.size() == byPosition.size();
    }

    private static void enqueueNeighbor(
        int i,
        int j,
        Map<String, FrameTileMatrix.TileCoord> byPosition,
        Set<String> visited,
        ArrayDeque<FrameTileMatrix.TileCoord> pending
    ) {
        String key = i + ":" + j;
        FrameTileMatrix.TileCoord neighbor = byPosition.get(key);
        if (neighbor != null && visited.add(key)) {
            pending.addLast(neighbor);
        }
    }

    private static FrameTileMatrix copyMatrix(FrameTileMatrix source, int frameId) {
        FrameTileMatrix copy = new FrameTileMatrix();
        copy.setFrameId(frameId);
        copy.setRows(source.getRows());
        copy.setCols(source.getCols());
        List<FrameTileMatrix.TileCoord> tiles = new ArrayList<>();
        for (FrameTileMatrix.TileCoord sourceTile : source.getTiles()) {
            if (sourceTile == null) {
                continue;
            }
            FrameTileMatrix.TileCoord tile = new FrameTileMatrix.TileCoord();
            tile.setId(sourceTile.getId());
            tile.setI(sourceTile.getI());
            tile.setJ(sourceTile.getJ());
            tile.setTextureFile(sourceTile.getTextureFile());
            tile.setUncles(sourceTile.getUncles());
            tiles.add(tile);
        }
        copy.setTiles(tiles);
        return copy;
    }

    private static String formatFrameLabel(int frameId) {
        return frameId == -1 ? "transient" : Integer.toString(frameId);
    }

    private void refreshHierarchyOrdering(boolean reorderFrames) {
        hierarchyLevelByFrame.clear();
        if (frameMatrices.isEmpty()) {
            return;
        }

        FrameMatrixSet selectedFrame = getSelectedFrameMatrices();
        Map<String, Integer> frameIndexByTileId = buildFrameIndexByTileId();
        List<FrameHierarchyNode> hierarchyNodes = new ArrayList<>(frameMatrices.size());
        for (int frameIndex = 0; frameIndex < frameMatrices.size(); frameIndex++) {
            FrameMatrixSet frame = frameMatrices.get(frameIndex);
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                return;
            }

            FrameTileMatrix matrix = frame.getMatrices().get(0);
            if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
                return;
            }

            LinkedHashSet<String> hierarchyUncleIds = collectHierarchyUncleIds(frame);
            LinkedHashSet<Integer> resolvedUncleFrameIndexes = new LinkedHashSet<>();
            Map<Integer, Integer> levelDeltaByParentIndex = new LinkedHashMap<>();
            for (String normalizedUncleId : hierarchyUncleIds) {
                Integer uncleFrameIndex = frameIndexByTileId.get(normalizedUncleId);
                if (uncleFrameIndex != null && uncleFrameIndex != frameIndex) {
                    resolvedUncleFrameIndexes.add(uncleFrameIndex);
                }
            }
            collectParentLevelDeltas(frame, frameIndexByTileId, frameIndex, levelDeltaByParentIndex);

            Integer explicitParentFrameIndex = indexOfFrameIdentity(frame.getInferredParent());

            UncleHudState state;
            if (explicitParentFrameIndex != null && explicitParentFrameIndex != frameIndex) {
                state = UncleHudState.NORMAL;
                resolvedUncleFrameIndexes.clear();
                resolvedUncleFrameIndexes.add(explicitParentFrameIndex);
                levelDeltaByParentIndex.clear();
                levelDeltaByParentIndex.put(explicitParentFrameIndex, 1);
            }
            else if (resolvedUncleFrameIndexes.isEmpty()) {
                state = UncleHudState.TOPLEVEL;
            }
            else if (resolvedUncleFrameIndexes.size() == 1) {
                state = UncleHudState.NORMAL;
            }
            else {
                // A sparse level may be split into several matrices.  Uncle tiles
                // from one child matrix can therefore live in several matrices at
                // the same parent depth without making the hierarchy unusable.
                state = UncleHudState.BROKEN;
            }
            hierarchyNodes.add(new FrameHierarchyNode(
                frame,
                frameIndex,
                state,
                List.copyOf(resolvedUncleFrameIndexes),
                Map.copyOf(levelDeltaByParentIndex),
                findLastCaptureFrameId(frame),
                hierarchyUncleIds.size()
            ));
        }

        List<FrameHierarchyNode> ordered = orderHierarchyNodes(hierarchyNodes);
        if (ordered == null || ordered.size() != frameMatrices.size()) {
            hierarchyLevelByFrame.clear();
            return;
        }

        boolean changedOrder = false;
        for (int i = 0; i < ordered.size(); i++) {
            if (frameMatrices.get(i) != ordered.get(i).frame()) {
                changedOrder = true;
                break;
            }
        }
        if (changedOrder && reorderFrames) {
            frameMatrices.clear();
            for (FrameHierarchyNode node : ordered) {
                frameMatrices.add(node.frame());
            }
        }

        for (FrameHierarchyNode node : ordered) {
            hierarchyLevelByFrame.put(node.frame(), node.level());
            node.frame().setParentLevelDelta(
                node.parentFrameIndexes().size() == 1
                    ? node.levelDeltaByParentIndex().getOrDefault(node.parentFrameIndexes().get(0), 1)
                    : null
            );
        }

        if (selectedFrame != null) {
            for (int i = 0; i < frameMatrices.size(); i++) {
                if (frameMatrices.get(i) == selectedFrame) {
                    selectedFrameIndex = i;
                    break;
                }
            }
        }
        normalizeSelection();
    }

    private Integer indexOfFrameIdentity(FrameMatrixSet sought) {
        if (sought == null) {
            return null;
        }
        for (int index = 0; index < frameMatrices.size(); index++) {
            if (frameMatrices.get(index) == sought) {
                return index;
            }
        }
        return null;
    }

    private List<FrameHierarchyNode> orderHierarchyNodes(List<FrameHierarchyNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<FrameHierarchyNode>> childrenByParentIndex = new LinkedHashMap<>();
        Map<Integer, Integer> remainingParentsByFrameIndex = new LinkedHashMap<>();
        Comparator<FrameHierarchyNode> localityOrder = Comparator
            .comparingInt(MatrixMergerState::topLevelEvidenceRank)
            .thenComparingInt(FrameHierarchyNode::lastCaptureFrameId)
            .thenComparingInt(FrameHierarchyNode::originalIndex);
        PriorityQueue<FrameHierarchyNode> available = new PriorityQueue<>(localityOrder);
        for (FrameHierarchyNode node : nodes) {
            int parentCount = node.parentFrameIndexes().size();
            remainingParentsByFrameIndex.put(node.originalIndex(), parentCount);
            if (node.parentFrameIndexes().isEmpty()) {
                available.add(node);
            }
            else {
                for (Integer parentFrameIndex : node.parentFrameIndexes()) {
                    childrenByParentIndex.computeIfAbsent(parentFrameIndex, unused -> new ArrayList<>()).add(node);
                }
            }
        }

        List<FrameHierarchyNode> resolved = new ArrayList<>(nodes.size());
        Map<Integer, Integer> levelByOriginalIndex = new HashMap<>();
        while (!available.isEmpty()) {
            FrameHierarchyNode next = available.remove();
            int level = 0;
            for (Integer parentFrameIndex : next.parentFrameIndexes()) {
                Integer parentLevel = levelByOriginalIndex.get(parentFrameIndex);
                if (parentLevel == null) {
                    return null;
                }
                level = Math.max(
                    level,
                    parentLevel + next.levelDeltaByParentIndex().getOrDefault(parentFrameIndex, 1)
                );
            }
            resolved.add(next.withLevel(level));
            levelByOriginalIndex.put(next.originalIndex(), level);
            for (FrameHierarchyNode child : childrenByParentIndex.getOrDefault(next.originalIndex(), List.of())) {
                int remaining = remainingParentsByFrameIndex.get(child.originalIndex()) - 1;
                remainingParentsByFrameIndex.put(child.originalIndex(), remaining);
                if (remaining == 0) {
                    available.add(child);
                }
            }
        }
        if (resolved.size() != nodes.size()) {
            return null;
        }

        // Topological traversal guarantees that a parent is resolved before its
        // children, but it does not guarantee that every matrix at depth N appears
        // before matrices at depth N+1 when there are multiple hierarchy roots.
        // Keep level resolution and presentation order as separate phases.
        resolved.sort(
            Comparator.comparingInt(FrameHierarchyNode::level)
                .thenComparing(localityOrder)
        );
        return resolved;
    }

    private static int topLevelEvidenceRank(FrameHierarchyNode node) {
        return node.state() == UncleHudState.TOPLEVEL && node.hierarchyUncleCount() > 0 ? 0 : 1;
    }

    private static void collectParentLevelDeltas(
        FrameMatrixSet frame,
        Map<String, Integer> frameIndexByTileId,
        int childFrameIndex,
        Map<Integer, Integer> out
    ) {
        if (frame == null || out == null) {
            return;
        }
        List<ToUncleRelationship> relationships = new ArrayList<>();
        if (frame.getHierarchyRelationshipsByTileId() != null) {
            frame.getHierarchyRelationshipsByTileId().values().forEach(relationships::addAll);
        }
        if (relationships.isEmpty() && frame.getMatrices() != null) {
            for (FrameTileMatrix matrix : frame.getMatrices()) {
                if (matrix == null || matrix.getTiles() == null) {
                    continue;
                }
                for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                    if (tile != null && tile.getUncles() != null) {
                        relationships.addAll(tile.getUncles());
                    }
                }
            }
        }
        for (ToUncleRelationship relationship : relationships) {
            if (relationship == null || relationship.referenceContentId() == null) {
                continue;
            }
            String referenceId = WestCuttersJsonReader.normalizeScopedTileId(relationship.referenceContentId());
            Integer parentIndex = frameIndexByTileId.get(referenceId);
            if (parentIndex != null && parentIndex != childFrameIndex) {
                out.merge(parentIndex, relationship.effectiveLevelDelta(), Math::max);
            }
        }
    }

    private static int findLastCaptureFrameId(FrameMatrixSet frame) {
        int lastFrameId = -1;
        if (frame == null) {
            return lastFrameId;
        }
        if (frame.getMatrices() != null && !frame.getMatrices().isEmpty()) {
            FrameTileMatrix matrix = frame.getMatrices().get(0);
            if (matrix != null && matrix.getTiles() != null) {
                for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                    lastFrameId = Math.max(lastFrameId, captureFrameIdFromTileId(tile == null ? null : tile.getId()));
                }
            }
        }
        Map<String, List<String>> hierarchy = frame.getHierarchyUnclesByTileId();
        if (hierarchy != null) {
            for (String tileId : hierarchy.keySet()) {
                lastFrameId = Math.max(lastFrameId, captureFrameIdFromTileId(tileId));
            }
        }
        return lastFrameId;
    }

    private static int captureFrameIdFromTileId(String tileId) {
        String normalized = WestCuttersJsonReader.normalizeScopedTileId(tileId);
        if (normalized == null || normalized.isBlank()) {
            return -1;
        }
        int separator = normalized.indexOf('_');
        if (separator <= 0) {
            return -1;
        }
        try {
            return Integer.parseInt(normalized.substring(0, separator));
        }
        catch (NumberFormatException ex) {
            return -1;
        }
    }

    private Map<String, Integer> buildFrameIndexByTileId() {
        Map<String, Integer> frameIndexByTileId = new LinkedHashMap<>();
        for (int frameIndex = 0; frameIndex < frameMatrices.size(); frameIndex++) {
            FrameMatrixSet frame = frameMatrices.get(frameIndex);
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                continue;
            }
            FrameTileMatrix matrix = frame.getMatrices().get(0);
            if (matrix == null || matrix.getTiles() == null) {
                continue;
            }
            for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String tileId = tile.getId();
                if (tileId != null && !tileId.isBlank()) {
                    frameIndexByTileId.put(tileId, frameIndex);
                }
            }
        }
        return frameIndexByTileId;
    }

    private static Map<String, List<String>> buildHierarchyUnclesByTileId(FrameMatrixSet frame, FrameTileMatrix matrix) {
        Map<String, List<String>> inherited = frame == null ? null : frame.getHierarchyUnclesByTileId();
        if (inherited != null && !inherited.isEmpty()) {
            Map<String, List<String>> filtered = new LinkedHashMap<>();
            for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile == null || tile.getId() == null || tile.getId().isBlank()) {
                    continue;
                }
                List<String> uncleIds = inherited.get(tile.getId());
                if (uncleIds != null) {
                    filtered.put(tile.getId(), new ArrayList<>(uncleIds));
                }
            }
            if (!filtered.isEmpty()) {
                return filtered;
            }
        }

        Map<String, List<String>> out = new LinkedHashMap<>();
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null || tile.getId() == null || tile.getId().isBlank()) {
                continue;
            }
            LinkedHashSet<String> uncleIds = new LinkedHashSet<>();
            if (tile.getUncles() != null) {
                for (var relationship : tile.getUncles()) {
                    if (relationship == null || relationship.uncleContentId() == null) {
                        continue;
                    }
                    String normalizedUncleId = WestCuttersJsonReader.normalizeScopedTileId(relationship.uncleContentId());
                    if (normalizedUncleId != null && !normalizedUncleId.isBlank()) {
                        uncleIds.add(normalizedUncleId);
                    }
                }
            }
            out.put(tile.getId(), new ArrayList<>(uncleIds));
        }
        return out;
    }

    private static Map<String, List<ToUncleRelationship>> buildHierarchyRelationshipsByTileId(
        FrameMatrixSet frame,
        FrameTileMatrix matrix
    ) {
        Map<String, List<ToUncleRelationship>> inherited =
            frame == null ? null : frame.getHierarchyRelationshipsByTileId();
        if (inherited != null && !inherited.isEmpty()) {
            Map<String, List<ToUncleRelationship>> filtered = new LinkedHashMap<>();
            for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile == null || tile.getId() == null || tile.getId().isBlank()) {
                    continue;
                }
                List<ToUncleRelationship> relationships = inherited.get(tile.getId());
                if (relationships != null) {
                    filtered.put(tile.getId(), new ArrayList<>(relationships));
                }
            }
            if (!filtered.isEmpty()) {
                return filtered;
            }
        }

        Map<String, List<ToUncleRelationship>> out = new LinkedHashMap<>();
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null || tile.getId() == null || tile.getId().isBlank()) {
                continue;
            }
            out.put(tile.getId(), tile.getUncles() == null ? List.of() : new ArrayList<>(tile.getUncles()));
        }
        return out;
    }

    private static void mergeHierarchyUncles(FrameMatrixSet current, FrameMatrixSet mergedAway) {
        if (current == null || mergedAway == null) {
            return;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>();
        mergeHierarchyUncleMap(merged, current.getHierarchyUnclesByTileId());
        mergeHierarchyUncleMap(merged, mergedAway.getHierarchyUnclesByTileId());
        current.setHierarchyUnclesByTileId(merged);

        Map<String, List<ToUncleRelationship>> relationships = new LinkedHashMap<>();
        mergeHierarchyRelationshipMap(relationships, current.getHierarchyRelationshipsByTileId());
        mergeHierarchyRelationshipMap(relationships, mergedAway.getHierarchyRelationshipsByTileId());
        current.setHierarchyRelationshipsByTileId(relationships);
    }

    private static void mergeHierarchyRelationshipMap(
        Map<String, List<ToUncleRelationship>> target,
        Map<String, List<ToUncleRelationship>> source
    ) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<ToUncleRelationship>> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            LinkedHashSet<ToUncleRelationship> merged =
                new LinkedHashSet<>(target.getOrDefault(entry.getKey(), List.of()));
            if (entry.getValue() != null) {
                merged.addAll(entry.getValue());
            }
            target.put(entry.getKey(), new ArrayList<>(merged));
        }
    }

    private static void mergeHierarchyUncleMap(
        Map<String, List<String>> target,
        Map<String, List<String>> source
    ) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            LinkedHashSet<String> uncleIds = new LinkedHashSet<>(target.getOrDefault(entry.getKey(), List.of()));
            if (entry.getValue() != null) {
                uncleIds.addAll(entry.getValue());
            }
            target.put(entry.getKey(), new ArrayList<>(uncleIds));
        }
    }

    private static LinkedHashSet<String> collectHierarchyUncleIds(FrameMatrixSet frame) {
        LinkedHashSet<String> uncleIds = new LinkedHashSet<>();
        if (frame == null) {
            return uncleIds;
        }
        Map<String, List<String>> hierarchyUnclesByTileId = frame.getHierarchyUnclesByTileId();
        if (hierarchyUnclesByTileId != null && !hierarchyUnclesByTileId.isEmpty()) {
            for (List<String> ids : hierarchyUnclesByTileId.values()) {
                if (ids == null) {
                    continue;
                }
                for (String id : ids) {
                    String normalized = WestCuttersJsonReader.normalizeScopedTileId(id);
                    if (normalized != null && !normalized.isBlank()) {
                        uncleIds.add(normalized);
                    }
                }
            }
            if (!uncleIds.isEmpty()) {
                return uncleIds;
            }
        }

        FrameTileMatrix matrix = frame.getMatrices().get(0);
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null || tile.getUncles() == null) {
                continue;
            }
            for (var relationship : tile.getUncles()) {
                if (relationship == null || relationship.uncleContentId() == null) {
                    continue;
                }
                String normalized = WestCuttersJsonReader.normalizeScopedTileId(relationship.uncleContentId());
                if (normalized != null && !normalized.isBlank()) {
                    uncleIds.add(normalized);
                }
            }
        }
        return uncleIds;
    }

    private Map<String, UncleTileLocation> buildLocatedUncleTiles(Set<String> uncleTileIds) {
        Map<String, UncleTileLocation> out = new LinkedHashMap<>();
        if (uncleTileIds == null || uncleTileIds.isEmpty()) {
            return out;
        }
        for (int frameIndex = 0; frameIndex < frameMatrices.size(); frameIndex++) {
            FrameMatrixSet frame = frameMatrices.get(frameIndex);
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                continue;
            }
            FrameTileMatrix matrix = frame.getMatrices().get(0);
            if (matrix == null || matrix.getTiles() == null) {
                continue;
            }
            for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String scopedTileId = tile.getId();
                if (scopedTileId != null && uncleTileIds.contains(scopedTileId)) {
                    out.put(scopedTileId, new UncleTileLocation(scopedTileId, frameIndex));
                }
            }
        }
        return out;
    }

    private void selectFirstInvalidFrame() {
        for (int i = 0; i < frameMatrices.size(); i++) {
            FrameMatrixSet frame = frameMatrices.get(i);
            if (frame != null && invalidReasonByFrameId.containsKey(frame.getFrameId())) {
                selectedFrameIndex = i;
                return;
            }
        }
    }

    public record UncleHudStatus(
        int relationCount,
        UncleHudState state,
        List<String> uncleTileIds,
        List<String> missingUncleIds,
        Map<String, UncleTileLocation> locatedUncleTiles
    ) {
        public boolean broken() {
            return state == UncleHudState.BROKEN;
        }

        public boolean topLevel() {
            return state == UncleHudState.TOPLEVEL;
        }
    }

    public record UncleTileLocation(
        String tileId,
        int frameIndex
    ) {
    }

    public record HierarchyOrderDiagnostic(
        int index,
        int level,
        int lastCaptureFrameId,
        int uncleCount,
        List<Integer> resolvedParentIndexes,
        int unresolvedUncleCount,
        int tileCount
    ) {
    }

    public record SmallMatrixDiscardReport(
        int matrixCount,
        int tileCount,
        List<String> tileIds
    ) {
    }

    public record ExclusiveTileOwnershipReport(
        int duplicateOccurrencesRemoved,
        int affectedMatrices,
        int emptyMatricesRemoved
    ) {
    }

    public record TopologyFilterReport(
        int inputMatrixCount,
        int retainedMatrixCount,
        int discardedComponentCount,
        int discardedTileCount,
        int splitMatrixCount,
        List<String> discardedTileIds
    ) {
    }

    public record SameLevelCollapseReport(
        int inputMatrixCount,
        int retainedMatrixCount,
        int sharedTileMergeCount,
        int relationshipClueMergeCount,
        int compatibleGridMergeCount
    ) {
    }

    private record ParentTileRef(FrameMatrixSet frame, FrameTileMatrix.TileCoord tile) {
    }

    private record ParentSpaceAnchor(FrameMatrixSet parent, int rowOffset, int colOffset) {
    }

    public enum UncleHudState {
        NORMAL,
        BROKEN,
        TOPLEVEL
    }

    private record FrameHierarchyNode(
        FrameMatrixSet frame,
        int originalIndex,
        UncleHudState state,
        List<Integer> parentFrameIndexes,
        Map<Integer, Integer> levelDeltaByParentIndex,
        int lastCaptureFrameId,
        int hierarchyUncleCount,
        int level
    ) {
        private FrameHierarchyNode(
            FrameMatrixSet frame,
            int originalIndex,
            UncleHudState state,
            List<Integer> parentFrameIndexes,
            Map<Integer, Integer> levelDeltaByParentIndex,
            int lastCaptureFrameId,
            int hierarchyUncleCount
        ) {
            this(
                frame,
                originalIndex,
                state,
                parentFrameIndexes,
                levelDeltaByParentIndex,
                lastCaptureFrameId,
                hierarchyUncleCount,
                -1
            );
        }

        private FrameHierarchyNode withLevel(int newLevel) {
            return new FrameHierarchyNode(
                frame,
                originalIndex,
                state,
                parentFrameIndexes,
                levelDeltaByParentIndex,
                lastCaptureFrameId,
                hierarchyUncleCount,
                newLevel
            );
        }
    }
}
