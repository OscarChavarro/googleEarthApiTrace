package dumpanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class Line {
    private final String primitive;
    private final List<Vector3Dd> points;
    private final double[] modelViewMatrix;

    public Line(String primitive, List<Vector3Dd> points) {
        this(primitive, points, null);
    }

    public Line(String primitive, List<Vector3Dd> points, double[] modelViewMatrix) {
        this.primitive = primitive == null ? "n/a" : primitive;
        this.points = points == null ? List.of() : List.copyOf(points);
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    public String getPrimitive() {
        return primitive;
    }

    @JsonIgnore
    public List<Vector3Dd> getPoints() {
        return points;
    }

    @JsonIgnore
    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    @JsonProperty("modelViewMatrix")
    public double[] getModelViewMatrixJson() {
        return getModelViewMatrix();
    }

    public record Vertex(double x, double y, double z) {}

    @JsonProperty("lineStrip")
    public List<Vertex> getVertices() {
        if (points.isEmpty()) {
            return List.of();
        }
        return points.stream().map(p -> new Vertex(p.x(), p.y(), p.z())).toList();
    }
}
