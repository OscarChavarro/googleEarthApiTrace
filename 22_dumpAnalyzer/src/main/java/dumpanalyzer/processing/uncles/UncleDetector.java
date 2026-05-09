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
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class UncleDetector {
    private static final TriangleMeshVertexComparator COMPARATOR = new TriangleMeshVertexComparator();
    private static final double HALF_SPAN = 0.5;
    private static final double UV_TOLERANCE = 0.05;
    private static final int DEBUG_FRAME_ID = 50;
    private static final Set<String> DEBUG_TILE_IDS = Set.of("50_97", "50_53");

    public List<ToUncleRelationship> detect(Frame frame, TileInstance tile) {
        if (frame == null || tile == null || tile.getTriangleStrip() == null) {
            return List.of();
        }

        List<ToUncleRelationship> relationships = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TileInstance candidate : frame.getTiles()) {
            CandidateProfile profile = classifyCandidate(candidate);
            if (profile == null) {
                continue;
            }
            debug(frame, tile, candidate, "candidate profile: " + profile.debugSummary());

            for (UncleDirections direction : detectRelationshipsAgainstCandidate(tile, candidate, profile)) {
                String key = direction + "|" + candidate.getContentId();
                if (seen.add(key)) {
                    relationships.add(new ToUncleRelationship(direction, candidate.getContentId()));
                }
            }
        }
        return List.copyOf(relationships);
    }

    private static List<UncleDirections> detectRelationshipsAgainstCandidate(
        TileInstance tile,
        TileInstance candidate,
        CandidateProfile profile
    ) {
        if (profile.simpleBounds() != null) {
            TriangleMeshVertexComparator.ComparisonResult comparison = COMPARATOR.compare(
                tile,
                candidate,
                null,
                0,
                0,
                null,
                false
            );
            if (!comparison.areNeighbors() || comparison.directionFromAtoB() == null) {
                return List.of();
            }
            UncleDirections direction = mapUncleDirection(comparison.directionFromAtoB(), profile.simpleBounds());
            return direction == null ? List.of() : List.of(direction);
        }

        if (profile.missingQuadrant() == null || profile.stripsByQuadrant() == null) {
            return List.of();
        }
        UncleDirections direction = detectLShapedRelationship(tile, profile);
        return direction == null ? List.of() : List.of(direction);
    }

    private static CandidateProfile classifyCandidate(TileInstance candidate) {
        if (candidate == null || candidate.getContentId() == null) {
            return null;
        }

        UvBounds simpleBounds = computeUvBounds(candidate);
        if (candidate.getTriangleStrip() != null
            && simpleBounds != null
            && simpleBounds.isDirectUncleRange()
            && !candidate.isFullResolutionWithRespectToTexture()) {
            return new CandidateProfile(simpleBounds, null, null);
        }

        List<StripQuadrantInfo> stripInfos = classifyStripQuadrants(candidate);
        if (stripInfos.size() < 2) {
            return null;
        }
        Map<UncleDirections, StripQuadrantInfo> stripsByQuadrant = new EnumMap<>(UncleDirections.class);
        for (StripQuadrantInfo info : stripInfos) {
            if (info == null || info.quadrant() == null || info.proxyTile() == null) {
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
        return new CandidateProfile(null, missingQuadrant, stripsByQuadrant);
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
        List<List<Vector3D>> strips = tile.getStrips();
        List<List<Vector3D>> texCoords = tile.getStripTexCoords();
        if (strips.isEmpty() || strips.size() != texCoords.size()) {
            return List.of();
        }
        List<StripQuadrantInfo> out = new ArrayList<>();
        for (int i = 0; i < strips.size(); i++) {
            List<Vector3D> strip = strips.get(i);
            List<Vector3D> uv = texCoords.get(i);
            UvBounds bounds = computeUvBounds(uv);
            UncleDirections quadrant = classifyQuadrant(bounds);
            TileInstance proxy = buildProxyTile(tile, strip, uv, i);
            out.add(new StripQuadrantInfo(i, quadrant, bounds, proxy));
        }
        return out;
    }

    private static UvBounds computeUvBounds(List<Vector3D> uvValues) {
        if (uvValues == null || uvValues.isEmpty()) {
            return null;
        }
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        boolean hasAny = false;
        for (Vector3D uv : uvValues) {
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
        if (info == null || info.proxyTile() == null) {
            return false;
        }
        TriangleMeshVertexComparator.ComparisonResult comparison = COMPARATOR.compare(
            tile,
            info.proxyTile(),
            null,
            0,
            0,
            null,
            false
        );
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

    private static TileInstance buildProxyTile(
        TileInstance source,
        List<Vector3D> strip,
        List<Vector3D> uv,
        int stripIndex
    ) {
        if (source == null || strip == null || uv == null || strip.isEmpty() || strip.size() != uv.size()) {
            return null;
        }
        return new TileInstance(
            source.getContentId() + "#strip" + stripIndex,
            source.getTextureFile(),
            null,
            null,
            null,
            null,
            strip.get(0),
            strip.get(0),
            strip,
            List.of(strip),
            List.of(uv),
            "GL_TRIANGLE_STRIP",
            0,
            0L,
            strip.size(),
            strip.size(),
            false,
            "",
            source.getProjectionMatrix(),
            source.getModelViewMatrix()
        );
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

    private record UvBounds(double minU, double maxU, double minV, double maxV) {
        private boolean isDirectUncleRange() {
            return spanLooksLikeDirectUncle(maxU - minU)
                && spanLooksLikeDirectUncle(maxV - minV)
                && uHalf() != null
                && vHalf() != null;
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
        TileInstance proxyTile
    ) {}
}
