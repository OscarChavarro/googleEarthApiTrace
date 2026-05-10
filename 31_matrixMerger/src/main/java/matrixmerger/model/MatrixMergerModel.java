package matrixmerger.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import matrixmerger.io.FrameMatrices;
import matrixmerger.io.TileMatrix;
import matrixmerger.io.WestCutterReader;
import matrixmerger.processing.MatrixByWestCutterSplitter;
import matrixmerger.processing.MatrixMerger;
import vsdk.toolkit.common.RendererConfiguration;
import vsdk.toolkit.environment.Camera;

public final class MatrixMergerModel {
    private final Camera viewingCamera = new Camera();
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private final List<FrameMatrices> frameMatrices = new ArrayList<>();
    private final Set<String> residentTexturePaths = new HashSet<>();
    private final Set<String> westCutterTileIds = new HashSet<>();
    private final Map<Integer, String> invalidReasonByFrameId = new LinkedHashMap<>();
    private final ArrayDeque<String> residentTexturesFifo = new ArrayDeque<>();
    private final MatrixMerger matrixMerger = new MatrixMerger();
    private final MatrixByWestCutterSplitter matrixByWestCutterSplitter = new MatrixByWestCutterSplitter();
    private final Map<Integer, Integer> hierarchyLevelByFrameId = new LinkedHashMap<>();
    private long gpuTextureBytesAssigned = 0L;
    private int selectedFrameIndex = 0;
    private int maximumRetryCount = 0;
    private boolean lastMergeFailedForCurrentSelection = false;

    public MatrixMergerModel() {
        viewingCamera.setName("OrbiterCamera");
        renderingConfiguration.setWires(false);
    }

    public Camera getViewingCamera() {
        return viewingCamera;
    }

    public RendererConfiguration getRenderingConfiguration() {
        return renderingConfiguration;
    }

    public void setFrameMatrices(List<FrameMatrices> frames) {
        frameMatrices.clear();
        if (frames != null) {
            for (FrameMatrices frame : frames) {
                FrameMatrices normalized = normalizeFrame(frame);
                if (normalized != null) {
                    frameMatrices.add(normalized);
                }
            }
        }
        maximumRetryCount = frameMatrices.size();
        selectedFrameIndex = 0;
        lastMergeFailedForCurrentSelection = false;
        normalizeSelection();
        refreshHierarchyOrdering();
    }

    public List<FrameMatrices> getFrameMatrices() {
        return Collections.unmodifiableList(frameMatrices);
    }

    public void setWestCutterTileIds(Set<String> westCutterTileIds) {
        this.westCutterTileIds.clear();
        if (westCutterTileIds != null) {
            for (String id : westCutterTileIds) {
                String normalized = WestCutterReader.normalizeScopedTileId(id);
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
        refreshHierarchyOrdering();
    }

    public boolean isWestCutterTileId(String tileId) {
        String normalized = WestCutterReader.normalizeScopedTileId(tileId);
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
        TileMatrix selected = getSelectedMatrix();
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
        for (FrameMatrices frame : frameMatrices) {
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                continue;
            }
            TileMatrix matrix = frame.getMatrices().get(0);
            UncleHudStatus status = buildUncleHudStatus(matrix, frameIndexByTileId);
            if (status.state() != UncleHudState.TOPLEVEL || status.missingUncleIds().isEmpty()) {
                continue;
            }
            out.addAll(status.missingUncleIds());
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private UncleHudStatus buildUncleHudStatus(TileMatrix selected, Map<String, Integer> frameIndexByTileId) {
        if (selected == null || selected.getTiles() == null || selected.getTiles().isEmpty()) {
            return new UncleHudStatus(0, UncleHudState.NORMAL, List.of(), List.of(), Map.of());
        }

        Map<String, Integer> uncleCountsByTileId = new LinkedHashMap<>();
        int relationCount = 0;
        for (TileMatrix.TileCoord tile : selected.getTiles()) {
            if (tile == null || tile.getUncles() == null) {
                continue;
            }
            relationCount += tile.getUncles().size();
            for (var relationship : tile.getUncles()) {
                if (relationship == null || relationship.uncleContentId() == null) {
                    continue;
                }
                String normalizedUncleId = WestCutterReader.normalizeScopedTileId(relationship.uncleContentId());
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
            state = UncleHudState.BROKEN;
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

    public TileMatrix getSelectedMatrix() {
        FrameMatrices frame = getSelectedFrameMatrices();
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
        FrameMatrices frame = getSelectedFrameMatrices();
        return frame == null ? -1 : frame.getFrameId();
    }

    public String getSelectedFrameLabel() {
        return formatFrameLabel(getSelectedFrameId());
    }

    public String getSelectedHierarchyLabel() {
        Integer level = hierarchyLevelByFrameId.get(getSelectedFrameId());
        if (level == null || level < 0) {
            return null;
        }
        return level == 0 ? "l" : "l + " + level;
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

    public boolean mergeSelectedMatrixWithNext() {
        if (isSelectedFrameInvalid()) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }
        FrameMatrices current = getSelectedFrameMatrices();
        Integer nextFrameIndex = nextFrameIndexAfter(selectedFrameIndex);
        if (current == null || nextFrameIndex == null) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }

        TileMatrix a = current.getMatrices().get(0);
        TileMatrix b = frameMatrices.get(nextFrameIndex).getMatrices().get(0);
        if (!matrixMerger.merge(a, b)) {
            pruneTinyFramesAfterFailedMerge(selectedFrameIndex, nextFrameIndex);
            normalizeSelection();
            lastMergeFailedForCurrentSelection = true;
            return false;
        }

        frameMatrices.remove(nextFrameIndex.intValue());
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering();
        lastMergeFailedForCurrentSelection = false;
        return true;
    }

    public boolean mergeSelectedMatrixWithNextFrameAggressively() {
        if (isSelectedFrameInvalid()) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }
        FrameMatrices current = getSelectedFrameMatrices();
        Integer nextFrameIndex = nextFrameIndexAfter(selectedFrameIndex);
        if (current == null || nextFrameIndex == null) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }

        TileMatrix a = current.getMatrices().get(0);
        TileMatrix b = frameMatrices.get(nextFrameIndex).getMatrices().get(0);
        if (matrixMerger.merge(a, b)) {
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
        refreshHierarchyOrdering();
        lastMergeFailedForCurrentSelection = !mergedAny;
        return changedAny;
    }

    public boolean hasNextMatrixForSelection() {
        return nextFrameIndexAfter(selectedFrameIndex) != null;
    }

    public TileMatrix getNextMatrixForSelection() {
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
        FrameMatrices selectedFrame = getSelectedFrameMatrices();
        if (selectedFrame == null || isSelectedFrameInvalid()) {
            return false;
        }
        MatrixByWestCutterSplitter.FrameSplitResult split = matrixByWestCutterSplitter.splitFrame(selectedFrame, westCutterTileIds);
        if (!split.changed() || split.mainFrame() == null) {
            return false;
        }
        frameMatrices.set(selectedFrameIndex, split.mainFrame());
        if (split.transientFrame() != null) {
            frameMatrices.add(split.transientFrame());
        }
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering();
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
                TileMatrix a = frameMatrices.get(i).getMatrices().get(0);
                for (int j = i + 1; j < frameMatrices.size(); j++) {
                    TileMatrix b = frameMatrices.get(j).getMatrices().get(0);
                    if (!matrixMerger.merge(a, b)) {
                        continue;
                    }
                    frameMatrices.remove(j);
                    maximumRetryCount = frameMatrices.size();
                    mergedAny = true;
                    changed = true;
                    normalizeSelection();
                    refreshHierarchyOrdering();
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

    public void sortFramesByTileCountAscending() {
        frameMatrices.sort(Comparator.comparingInt(MatrixMergerModel::tileCountOfFrame));
        maximumRetryCount = frameMatrices.size();
        normalizeSelection();
        refreshHierarchyOrdering();
        lastMergeFailedForCurrentSelection = false;
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

    private FrameMatrices getSelectedFrameMatrices() {
        normalizeSelection();
        if (frameMatrices.isEmpty()) {
            return null;
        }
        return frameMatrices.get(selectedFrameIndex);
    }

    private void normalizeSelection() {
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
        FrameMatrices frame = frameMatrices.remove(frameIndex);
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
        FrameMatrices frame = frameMatrices.get(frameIndex);
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

    private static boolean isTinySingleMatrixFrame(FrameMatrices frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().size() != 1) {
            return false;
        }
        TileMatrix matrix = frame.getMatrices().get(0);
        return matrix != null && matrix.getTiles() != null && matrix.getTiles().size() <= 2;
    }

    private static FrameMatrices normalizeFrame(FrameMatrices frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
            return null;
        }
        TileMatrix matrix = frame.getMatrices().get(0);
        if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
            return null;
        }
        matrix.setFrameId(frame.getFrameId());
        FrameMatrices normalized = new FrameMatrices();
        normalized.setFrameId(frame.getFrameId());
        normalized.setMatrices(List.of(matrix));
        return normalized;
    }

    private static boolean isEmpty(FrameMatrices frame) {
        return frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty();
    }

    private static int tileCountOfFrame(FrameMatrices frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
            return 0;
        }
        TileMatrix matrix = frame.getMatrices().get(0);
        return matrix == null || matrix.getTiles() == null ? 0 : matrix.getTiles().size();
    }

    private static String formatFrameLabel(int frameId) {
        return frameId == -1 ? "transient" : Integer.toString(frameId);
    }

    private void refreshHierarchyOrdering() {
        hierarchyLevelByFrameId.clear();
        if (frameMatrices.isEmpty()) {
            return;
        }

        int selectedFrameId = getSelectedFrameId();
        Map<String, Integer> frameIdByTileId = buildFrameIdByTileId();
        List<FrameHierarchyNode> hierarchyNodes = new ArrayList<>(frameMatrices.size());
        for (int frameIndex = 0; frameIndex < frameMatrices.size(); frameIndex++) {
            FrameMatrices frame = frameMatrices.get(frameIndex);
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                return;
            }

            TileMatrix matrix = frame.getMatrices().get(0);
            if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
                return;
            }

            LinkedHashSet<Integer> resolvedUncleFrameIds = new LinkedHashSet<>();
            for (TileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile == null || tile.getUncles() == null) {
                    continue;
                }
                for (var relationship : tile.getUncles()) {
                    if (relationship == null || relationship.uncleContentId() == null) {
                        continue;
                    }
                    String normalizedUncleId = WestCutterReader.normalizeScopedTileId(relationship.uncleContentId());
                    if (normalizedUncleId == null || normalizedUncleId.isBlank()) {
                        continue;
                    }
                    Integer uncleFrameId = frameIdByTileId.get(normalizedUncleId);
                    if (uncleFrameId != null && uncleFrameId != frame.getFrameId()) {
                        resolvedUncleFrameIds.add(uncleFrameId);
                    }
                }
            }

            UncleHudState state;
            Integer parentFrameId = null;
            if (resolvedUncleFrameIds.isEmpty()) {
                state = UncleHudState.TOPLEVEL;
            }
            else if (resolvedUncleFrameIds.size() == 1) {
                state = UncleHudState.NORMAL;
                parentFrameId = resolvedUncleFrameIds.iterator().next();
            }
            else {
                return;
            }
            hierarchyNodes.add(new FrameHierarchyNode(frame, frameIndex, state, parentFrameId));
        }

        List<FrameHierarchyNode> ordered = orderHierarchyNodes(hierarchyNodes);
        if (ordered == null || ordered.size() != frameMatrices.size()) {
            hierarchyLevelByFrameId.clear();
            return;
        }

        boolean changedOrder = false;
        for (int i = 0; i < ordered.size(); i++) {
            if (frameMatrices.get(i) != ordered.get(i).frame()) {
                changedOrder = true;
                break;
            }
        }
        if (changedOrder) {
            frameMatrices.clear();
            for (FrameHierarchyNode node : ordered) {
                frameMatrices.add(node.frame());
            }
        }

        for (FrameHierarchyNode node : ordered) {
            hierarchyLevelByFrameId.put(node.frame().getFrameId(), node.level());
        }

        if (selectedFrameId != -1) {
            for (int i = 0; i < frameMatrices.size(); i++) {
                if (frameMatrices.get(i).getFrameId() == selectedFrameId) {
                    selectedFrameIndex = i;
                    break;
                }
            }
        }
        normalizeSelection();
    }

    private List<FrameHierarchyNode> orderHierarchyNodes(List<FrameHierarchyNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        Map<Integer, FrameHierarchyNode> byFrameId = new LinkedHashMap<>();
        for (FrameHierarchyNode node : nodes) {
            if (node == null || node.frame() == null) {
                return null;
            }
            byFrameId.put(node.frame().getFrameId(), node);
        }

        List<FrameHierarchyNode> roots = new ArrayList<>();
        for (FrameHierarchyNode node : nodes) {
            if (node.state() == UncleHudState.TOPLEVEL) {
                roots.add(node.withLevel(0));
            }
        }
        if (roots.isEmpty()) {
            return null;
        }

        Map<Integer, Integer> levelByFrameId = new LinkedHashMap<>();
        for (FrameHierarchyNode root : roots) {
            levelByFrameId.put(root.frame().getFrameId(), 0);
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (FrameHierarchyNode node : nodes) {
                if (node == null || node.state() != UncleHudState.NORMAL || node.parentFrameId() == null) {
                    continue;
                }
                Integer parentLevel = levelByFrameId.get(node.parentFrameId());
                if (parentLevel == null) {
                    continue;
                }
                int candidateLevel = parentLevel + 1;
                Integer currentLevel = levelByFrameId.get(node.frame().getFrameId());
                if (currentLevel == null || candidateLevel < currentLevel) {
                    levelByFrameId.put(node.frame().getFrameId(), candidateLevel);
                    changed = true;
                }
            }
        }

        if (levelByFrameId.size() != nodes.size()) {
            return null;
        }

        List<FrameHierarchyNode> ordered = new ArrayList<>(nodes.size());
        for (FrameHierarchyNode node : nodes) {
            Integer level = levelByFrameId.get(node.frame().getFrameId());
            if (level == null) {
                return null;
            }
            ordered.add(node.withLevel(level));
        }
        ordered.sort(Comparator.comparingInt(FrameHierarchyNode::level).thenComparingInt(FrameHierarchyNode::originalIndex));
        return ordered;
    }

    private Map<String, Integer> buildFrameIndexByTileId() {
        Map<String, Integer> frameIndexByTileId = new LinkedHashMap<>();
        for (int frameIndex = 0; frameIndex < frameMatrices.size(); frameIndex++) {
            FrameMatrices frame = frameMatrices.get(frameIndex);
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                continue;
            }
            TileMatrix matrix = frame.getMatrices().get(0);
            if (matrix == null || matrix.getTiles() == null) {
                continue;
            }
            for (TileMatrix.TileCoord tile : matrix.getTiles()) {
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

    private Map<String, Integer> buildFrameIdByTileId() {
        Map<String, Integer> frameIdByTileId = new LinkedHashMap<>();
        for (FrameMatrices frame : frameMatrices) {
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                continue;
            }
            TileMatrix matrix = frame.getMatrices().get(0);
            if (matrix == null || matrix.getTiles() == null) {
                continue;
            }
            for (TileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String tileId = tile.getId();
                if (tileId != null && !tileId.isBlank()) {
                    frameIdByTileId.put(tileId, frame.getFrameId());
                }
            }
        }
        return frameIdByTileId;
    }

    private Map<String, UncleTileLocation> buildLocatedUncleTiles(Set<String> uncleTileIds) {
        Map<String, UncleTileLocation> out = new LinkedHashMap<>();
        if (uncleTileIds == null || uncleTileIds.isEmpty()) {
            return out;
        }
        for (int frameIndex = 0; frameIndex < frameMatrices.size(); frameIndex++) {
            FrameMatrices frame = frameMatrices.get(frameIndex);
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                continue;
            }
            TileMatrix matrix = frame.getMatrices().get(0);
            if (matrix == null || matrix.getTiles() == null) {
                continue;
            }
            for (TileMatrix.TileCoord tile : matrix.getTiles()) {
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
            FrameMatrices frame = frameMatrices.get(i);
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

    public enum UncleHudState {
        NORMAL,
        BROKEN,
        TOPLEVEL
    }

    private record FrameHierarchyNode(
        FrameMatrices frame,
        int originalIndex,
        UncleHudState state,
        Integer parentFrameId,
        int level
    ) {
        private FrameHierarchyNode(FrameMatrices frame, int originalIndex, UncleHudState state, Integer parentFrameId) {
            this(frame, originalIndex, state, parentFrameId, -1);
        }

        private FrameHierarchyNode withLevel(int newLevel) {
            return new FrameHierarchyNode(frame, originalIndex, state, parentFrameId, newLevel);
        }
    }
}
