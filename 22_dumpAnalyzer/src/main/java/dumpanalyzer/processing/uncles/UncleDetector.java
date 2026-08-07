package dumpanalyzer.processing.uncles;

import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.TriangleMeshVertexComparator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class UncleDetector {
    private static final TriangleMeshVertexComparator COMPARATOR = new TriangleMeshVertexComparator();
    private static final double HALF_SPAN = 0.5;
    private static final double UV_TOLERANCE = 0.05;
    private static final double GRID_UV_TOLERANCE = 1.0e-3;
    private static final int MAX_ANCESTOR_LEVEL_DELTA = 8;
    private static final int DEBUG_FRAME_ID = 50;
    private static final Set<String> DEBUG_TILE_IDS = Set.of("50_97", "50_53");

    public Object prepareCandidates(Frame frame) {
        if (frame == null || frame.getTiles().isEmpty()) {
            return List.of();
        }
        List<PreparedCandidate> preparedCandidates = new ArrayList<>();
        for (TileInstance candidate : frame.getTiles()) {
            CandidateProfile profile = classifyCandidate(candidate);
            List<CandidateCell> cells = classifyCandidateCells(candidate);
            if (profile != null || !cells.isEmpty()) {
                preparedCandidates.add(new PreparedCandidate(candidate, profile, cells));
            }
        }
        return List.copyOf(preparedCandidates);
    }

    public List<ToUncleRelationship> detect(Frame frame, TileInstance tile, Object preparedCandidatesRef) {
        if (frame == null || tile == null || tile.getTriangleStrip() == null) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<PreparedCandidate> preparedCandidates = preparedCandidatesRef instanceof List<?>
            ? (List<PreparedCandidate>) preparedCandidatesRef
            : List.of();

        List<ToUncleRelationship> relationships = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PreparedCandidate preparedCandidate : preparedCandidates) {
            TileInstance candidate = preparedCandidate.tile();
            CandidateProfile profile = preparedCandidate.profile();
            if (candidate == tile) {
                continue;
            }
            if (profile != null) {
                debug(frame, tile, candidate, "candidate profile: " + profile.debugSummary());
            }

            List<DetectedRelationship> gridRelationships = detectGridRelationships(tile, preparedCandidate.cells());
            for (DetectedRelationship detected : gridRelationships) {
                String key = candidate.getContentId()
                    + "|" + detected.levelDelta()
                    + "|" + detected.rowOffset()
                    + "|" + detected.columnOffset();
                if (seen.add(key)) {
                    candidate.addRelationshipGeometry(detected.referenceGeometry());
                    relationships.add(new ToUncleRelationship(
                        detected.direction(),
                        candidate.getContentId(),
                        detected.kind(),
                        detected.levelDelta(),
                        detected.rowOffset(),
                        detected.columnOffset()
                    ));
                }
            }

            if (!gridRelationships.isEmpty()) {
                continue;
            }

            for (DetectedRelationship detected : detectRelationshipsAgainstCandidate(tile, candidate, profile)) {
                String key = detected.direction() + "|" + candidate.getContentId();
                if (seen.add(key)) {
                    relationships.add(new ToUncleRelationship(
                        detected.direction(),
                        candidate.getContentId(),
                        detected.kind()
                    ));
                }
            }
        }
        return List.copyOf(relationships);
    }

    private static List<DetectedRelationship> detectRelationshipsAgainstCandidate(
        TileInstance tile,
        TileInstance candidate,
        CandidateProfile profile
    ) {
        if (profile == null) {
            return List.of();
        }
        if (profile.simpleBounds() != null) {
            TriangleMeshVertexComparator.ComparisonResult comparison =
                COMPARATOR.compare(tile.getTriangleStrip(), profile.geometry());
            if (!comparison.areNeighbors() || comparison.directionFromAtoB() == null) {
                return List.of();
            }
            UncleDirections direction = mapUncleDirection(comparison.directionFromAtoB(), profile.simpleBounds());
            return direction == null
                ? List.of()
                : List.of(DetectedRelationship.legacy(direction, UncleRelationshipKind.ADJACENT_BORDER));
        }

        if (profile.missingQuadrant() == null || profile.stripsByQuadrant() == null) {
            return List.of();
        }
        UncleDirections direction = detectLShapedRelationship(tile, profile);
        return direction == null
            ? List.of()
            : List.of(DetectedRelationship.legacy(direction, UncleRelationshipKind.CONTAINING_QUADRANT));
    }

    private static List<DetectedRelationship> detectGridRelationships(
        TileInstance tile,
        List<CandidateCell> cells
    ) {
        if (tile == null || tile.getTriangleStrip() == null || cells == null || cells.isEmpty()) {
            return List.of();
        }
        List<DetectedRelationship> out = new ArrayList<>();
        for (CandidateCell cell : cells) {
            TriangleMeshVertexComparator.ComparisonResult comparison =
                COMPARATOR.compare(tile.getTriangleStrip(), cell.geometry());
            if (!comparison.areNeighbors() || comparison.directionFromAtoB() == null) {
                continue;
            }
            int row = cell.rowOffset();
            int column = cell.columnOffset();
            switch (comparison.directionFromAtoB()) {
                case EAST -> column--;
                case WEST -> column++;
                case NORTH -> row++;
                case SOUTH -> row--;
            }
            int scale = 1 << cell.levelDelta();
            UncleDirections direction = relationshipDirection(
                comparison.directionFromAtoB(),
                row,
                column,
                scale
            );
            boolean contained = row >= 0 && row < scale && column >= 0 && column < scale;
            out.add(new DetectedRelationship(
                direction,
                contained ? UncleRelationshipKind.CONTAINING_QUADRANT : UncleRelationshipKind.ADJACENT_BORDER,
                cell.levelDelta(),
                row,
                column,
                cell.geometry()
            ));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static List<CandidateCell> classifyCandidateCells(TileInstance candidate) {
        if (candidate == null || candidate.getContentId() == null || candidate.isFullResolutionWithRespectToTexture()) {
            return List.of();
        }
        List<CandidateCell> out = new ArrayList<>();
        for (TileInstance.TriangleStripGeometry geometry : candidate.getTriangleStripGeometries()) {
            UvBounds bounds = computeUvBounds(geometry);
            CandidateCell cell = gridCell(geometry, bounds);
            if (cell != null) {
                out.add(cell);
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static CandidateCell gridCell(TileInstance.TriangleStripGeometry geometry, UvBounds bounds) {
        if (geometry == null || bounds == null) {
            return null;
        }
        double spanU = bounds.maxU() - bounds.minU();
        double spanV = bounds.maxV() - bounds.minV();
        for (int levelDelta = 1; levelDelta <= MAX_ANCESTOR_LEVEL_DELTA; levelDelta++) {
            int scale = 1 << levelDelta;
            double step = 1.0 / scale;
            if (Math.abs(spanU - step) > GRID_UV_TOLERANCE
                || Math.abs(spanV - step) > GRID_UV_TOLERANCE) {
                continue;
            }
            int column = (int)Math.round(bounds.minU() * scale);
            int southIndex = (int)Math.round(bounds.minV() * scale);
            if (column < 0 || column >= scale || southIndex < 0 || southIndex >= scale
                || Math.abs(bounds.minU() - column * step) > GRID_UV_TOLERANCE
                || Math.abs(bounds.maxU() - (column + 1) * step) > GRID_UV_TOLERANCE
                || Math.abs(bounds.minV() - southIndex * step) > GRID_UV_TOLERANCE
                || Math.abs(bounds.maxV() - (southIndex + 1) * step) > GRID_UV_TOLERANCE) {
                return null;
            }
            return new CandidateCell(geometry, levelDelta, scale - 1 - southIndex, column);
        }
        return null;
    }

    private static UvBounds computeUvBounds(TileInstance.TriangleStripGeometry geometry) {
        if (geometry == null || geometry.vertices() == null || geometry.vertices().isEmpty()) {
            return null;
        }
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (TileInstance.TriangleStripVertex vertex : geometry.vertices()) {
            if (vertex == null || !Double.isFinite(vertex.u()) || !Double.isFinite(vertex.v())) {
                continue;
            }
            minU = Math.min(minU, vertex.u());
            maxU = Math.max(maxU, vertex.u());
            minV = Math.min(minV, vertex.v());
            maxV = Math.max(maxV, vertex.v());
        }
        return Double.isFinite(minU) ? new UvBounds(minU, maxU, minV, maxV) : null;
    }

    private static UncleDirections relationshipDirection(
        TriangleMeshVertexComparator.Direction border,
        int row,
        int column,
        int scale
    ) {
        boolean north = row < scale / 2;
        boolean west = column < scale / 2;
        return switch (border) {
            case EAST -> north ? UncleDirections.WEST_NORTH : UncleDirections.WEST_SOUTH;
            case WEST -> north ? UncleDirections.EAST_NORTH : UncleDirections.EAST_SOUTH;
            case NORTH -> west ? UncleDirections.SOUTH_WEST : UncleDirections.SOUTH_EAST;
            case SOUTH -> west ? UncleDirections.NORTH_WEST : UncleDirections.NORTH_EAST;
        };
    }

    private static CandidateProfile classifyCandidate(TileInstance candidate) {
        if (candidate == null || candidate.getContentId() == null) {
            return null;
        }

        TileInstance.TriangleStripGeometry geometry = candidate.getTriangleStrip();
        UvBounds simpleBounds = computeUvBounds(candidate);
        if (geometry != null
            && simpleBounds != null
            && simpleBounds.isDirectUncleRange()
            && simpleBounds.hasAdequateDirectUncleScale()
            && !candidate.isFullResolutionWithRespectToTexture()) {
            return new CandidateProfile(geometry, simpleBounds, null, null);
        }

        List<StripQuadrantInfo> stripInfos = classifyStripQuadrants(candidate);
        if (stripInfos.size() < 2) {
            return null;
        }
        Map<UncleDirections, StripQuadrantInfo> stripsByQuadrant = new EnumMap<>(UncleDirections.class);
        for (StripQuadrantInfo info : stripInfos) {
            if (info == null || info.quadrant() == null || info.geometry() == null) {
                return null;
            }
            stripsByQuadrant.put(info.quadrant(), info);
        }
        Set<UncleDirections> allQuadrants = Set.of(
            UncleDirections.WEST_SOUTH,
            UncleDirections.WEST_NORTH,
            UncleDirections.EAST_SOUTH,
            UncleDirections.EAST_NORTH
        );
        if (stripsByQuadrant.size() != 3) {
            return null;
        }
        Set<UncleDirections> missing = new HashSet<>(allQuadrants);
        missing.removeAll(stripsByQuadrant.keySet());
        if (missing.size() != 1) {
            return null;
        }
        UncleDirections missingQuadrant = missing.iterator().next();
        return new CandidateProfile(geometry, null, missingQuadrant, stripsByQuadrant);
    }

    private static UvBounds computeUvBounds(TileInstance tile) {
        TileInstance.TriangleStripGeometry geometry = tile == null ? null : tile.getTriangleStrip();
        if (geometry == null || geometry.vertices() == null || geometry.vertices().isEmpty()) {
            return null;
        }
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        boolean hasAny = false;
        for (TileInstance.TriangleStripVertex vertex : geometry.vertices()) {
            if (vertex == null || !Double.isFinite(vertex.u()) || !Double.isFinite(vertex.v())) {
                continue;
            }
            hasAny = true;
            minU = Math.min(minU, vertex.u());
            maxU = Math.max(maxU, vertex.u());
            minV = Math.min(minV, vertex.v());
            maxV = Math.max(maxV, vertex.v());
        }
        if (!hasAny) {
            return null;
        }
        return new UvBounds(minU, maxU, minV, maxV);
    }

    private static List<StripQuadrantInfo> classifyStripQuadrants(TileInstance tile) {
        if (tile == null) {
            return List.of();
        }
        List<List<Vector3Dd>> strips = tile.getStrips();
        List<List<Vector3Dd>> texCoords = tile.getStripTexCoords();
        if (strips.isEmpty() || strips.size() != texCoords.size()) {
            return List.of();
        }
        List<StripQuadrantInfo> out = new ArrayList<>();
        for (int i = 0; i < strips.size(); i++) {
            List<Vector3Dd> strip = strips.get(i);
            List<Vector3Dd> uv = texCoords.get(i);
            UvBounds bounds = computeUvBounds(uv);
            UncleDirections quadrant = classifyQuadrant(bounds);
            TileInstance.TriangleStripGeometry geometry = TileInstance.buildTriangleStripGeometry(strip, uv);
            out.add(new StripQuadrantInfo(i, quadrant, bounds, geometry));
        }
        return out;
    }

    private static UvBounds computeUvBounds(List<Vector3Dd> uvValues) {
        if (uvValues == null || uvValues.isEmpty()) {
            return null;
        }
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        boolean hasAny = false;
        for (Vector3Dd uv : uvValues) {
            if (uv == null || !Double.isFinite(uv.x()) || !Double.isFinite(uv.y())) {
                continue;
            }
            hasAny = true;
            minU = Math.min(minU, uv.x());
            maxU = Math.max(maxU, uv.x());
            minV = Math.min(minV, uv.y());
            maxV = Math.max(maxV, uv.y());
        }
        if (!hasAny) {
            return null;
        }
        return new UvBounds(minU, maxU, minV, maxV);
    }

    private static UncleDirections detectLShapedRelationship(TileInstance tile, CandidateProfile profile) {
        UncleDirections missingQuadrant = profile.missingQuadrant();
        if (missingQuadrant == null || profile.stripsByQuadrant() == null) {
            return null;
        }
        return switch (missingQuadrant) {
            case WEST_SOUTH -> matches(tile, profile.stripsByQuadrant(), UncleDirections.WEST_NORTH, TriangleMeshVertexComparator.Direction.NORTH)
                && matches(tile, profile.stripsByQuadrant(), UncleDirections.EAST_SOUTH, TriangleMeshVertexComparator.Direction.EAST)
                ? UncleDirections.WEST_SOUTH : null;
            case WEST_NORTH -> matches(tile, profile.stripsByQuadrant(), UncleDirections.WEST_SOUTH, TriangleMeshVertexComparator.Direction.SOUTH)
                && matches(tile, profile.stripsByQuadrant(), UncleDirections.EAST_NORTH, TriangleMeshVertexComparator.Direction.EAST)
                ? UncleDirections.WEST_NORTH : null;
            case EAST_SOUTH -> matches(tile, profile.stripsByQuadrant(), UncleDirections.EAST_NORTH, TriangleMeshVertexComparator.Direction.NORTH)
                && matches(tile, profile.stripsByQuadrant(), UncleDirections.WEST_SOUTH, TriangleMeshVertexComparator.Direction.WEST)
                ? UncleDirections.EAST_SOUTH : null;
            case EAST_NORTH -> matches(tile, profile.stripsByQuadrant(), UncleDirections.EAST_SOUTH, TriangleMeshVertexComparator.Direction.SOUTH)
                && matches(tile, profile.stripsByQuadrant(), UncleDirections.WEST_NORTH, TriangleMeshVertexComparator.Direction.WEST)
                ? UncleDirections.EAST_NORTH : null;
            default -> null;
        };
    }

    private static boolean matches(
        TileInstance tile,
        Map<UncleDirections, StripQuadrantInfo> stripsByQuadrant,
        UncleDirections occupiedQuadrant,
        TriangleMeshVertexComparator.Direction expectedDirection
    ) {
        StripQuadrantInfo info = stripsByQuadrant.get(occupiedQuadrant);
        if (info == null || info.geometry() == null) {
            return false;
        }
        TriangleMeshVertexComparator.ComparisonResult comparison =
            COMPARATOR.compare(tile.getTriangleStrip(), info.geometry());
        return comparison.areNeighbors() && comparison.directionFromAtoB() == expectedDirection;
    }

    private static UncleDirections classifyQuadrant(UvBounds bounds) {
        if (bounds == null || !bounds.isDirectUncleRange()) {
            return null;
        }
        HalfRange uHalf = bounds.uHalf();
        HalfRange vHalf = bounds.vHalf();
        if (uHalf == HalfRange.LOW && vHalf == HalfRange.LOW) {
            return UncleDirections.WEST_SOUTH;
        }
        if (uHalf == HalfRange.LOW && vHalf == HalfRange.HIGH) {
            return UncleDirections.WEST_NORTH;
        }
        if (uHalf == HalfRange.HIGH && vHalf == HalfRange.LOW) {
            return UncleDirections.EAST_SOUTH;
        }
        if (uHalf == HalfRange.HIGH && vHalf == HalfRange.HIGH) {
            return UncleDirections.EAST_NORTH;
        }
        return null;
    }

    private static UncleDirections mapUncleDirection(
        TriangleMeshVertexComparator.Direction directionFromTileToCandidate,
        UvBounds uvBounds
    ) {
        if (directionFromTileToCandidate == null || uvBounds == null) {
            return null;
        }
        HalfRange uHalf = uvBounds.uHalf();
        HalfRange vHalf = uvBounds.vHalf();
        if (uHalf == null || vHalf == null) {
            return null;
        }
        return switch (directionFromTileToCandidate) {
            case EAST -> vHalf == HalfRange.LOW ? UncleDirections.WEST_SOUTH : UncleDirections.WEST_NORTH;
            case WEST -> vHalf == HalfRange.LOW ? UncleDirections.EAST_SOUTH : UncleDirections.EAST_NORTH;
            case NORTH -> uHalf == HalfRange.LOW ? UncleDirections.SOUTH_WEST : UncleDirections.SOUTH_EAST;
            case SOUTH -> uHalf == HalfRange.LOW ? UncleDirections.NORTH_WEST : UncleDirections.NORTH_EAST;
        };
    }

    private enum HalfRange {
        LOW,
        HIGH
    }

    private record DetectedRelationship(
        UncleDirections direction,
        UncleRelationshipKind kind,
        Integer levelDelta,
        Integer rowOffset,
        Integer columnOffset,
        TileInstance.TriangleStripGeometry referenceGeometry
    ) {
        private static DetectedRelationship legacy(UncleDirections direction, UncleRelationshipKind kind) {
            return new DetectedRelationship(direction, kind, null, null, null, null);
        }
    }

    private record CandidateCell(
        TileInstance.TriangleStripGeometry geometry,
        int levelDelta,
        int rowOffset,
        int columnOffset
    ) {}

    private record UvBounds(double minU, double maxU, double minV, double maxV) {
        private boolean isDirectUncleRange() {
            return spanLooksLikeDirectUncle(maxU - minU)
                && spanLooksLikeDirectUncle(maxV - minV)
                && uHalf() != null
                && vHalf() != null;
        }

        private boolean hasAdequateDirectUncleScale() {
            // Reject lower-left quarter mappings like 0..0.5 x 0..0.5, which can
            // create false uncle matches after texture normalization.
            return !(maxU <= HALF_SPAN + UV_TOLERANCE && maxV <= HALF_SPAN + UV_TOLERANCE);
        }

        private HalfRange uHalf() {
            return classifyHalf(minU, maxU);
        }

        private HalfRange vHalf() {
            return classifyHalf(minV, maxV);
        }

        private static boolean spanLooksLikeDirectUncle(double span) {
            return Math.abs(span - HALF_SPAN) <= UV_TOLERANCE;
        }

        private static HalfRange classifyHalf(double min, double max) {
            if (Math.abs(min - 0.0) <= UV_TOLERANCE && Math.abs(max - HALF_SPAN) <= UV_TOLERANCE) {
                return HalfRange.LOW;
            }
            if (Math.abs(min - HALF_SPAN) <= UV_TOLERANCE && Math.abs(max - 1.0) <= UV_TOLERANCE) {
                return HalfRange.HIGH;
            }
            return null;
        }
    }

    private static void debug(Frame frame, TileInstance tile, TileInstance candidate, String message) {
        if (frame == null || tile == null) {
            return;
        }
        if (frame.getId() != DEBUG_FRAME_ID) {
            return;
        }
        if (!DEBUG_TILE_IDS.contains(tile.getContentId()) && (candidate == null || !DEBUG_TILE_IDS.contains(candidate.getContentId()))) {
            return;
        }
    }

    private record CandidateProfile(
        TileInstance.TriangleStripGeometry geometry,
        UvBounds simpleBounds,
        UncleDirections missingQuadrant,
        Map<UncleDirections, StripQuadrantInfo> stripsByQuadrant
    ) {
        private String debugSummary() {
            if (simpleBounds != null) {
                return "simple bounds u=[" + simpleBounds.minU() + "," + simpleBounds.maxU()
                    + "] v=[" + simpleBounds.minV() + "," + simpleBounds.maxV() + "]";
            }
            return "missingQuadrant=" + missingQuadrant + " occupied=" + (stripsByQuadrant == null ? List.of() : stripsByQuadrant.keySet());
        }
    }

    private record StripQuadrantInfo(
        int stripIndex,
        UncleDirections quadrant,
        UvBounds bounds,
        TileInstance.TriangleStripGeometry geometry
    ) {}

    private record PreparedCandidate(
        TileInstance tile,
        CandidateProfile profile,
        List<CandidateCell> cells
    ) {}
}
