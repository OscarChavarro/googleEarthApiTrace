package frametexturenormalizer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

public final class Line {
    private final List<Vertex> points;
    private final double[] modelViewMatrix;

    public Line(List<Vertex> points, double[] modelViewMatrix) {
        this.points = points == null ? List.of() : List.copyOf(points);
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    @JsonIgnore
    public List<Vertex> getPoints() {
        return points;
    }

    @JsonIgnore
    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    public record Vertex(double x, double y, double z) {}
}
