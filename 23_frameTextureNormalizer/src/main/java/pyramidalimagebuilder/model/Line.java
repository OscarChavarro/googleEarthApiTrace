package pyramidalimagebuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class Line {
    private final String primitive;
    private final List<Vertex> points;
    private final double[] modelViewMatrix;

    public Line(String primitive, List<Vertex> points, double[] modelViewMatrix) {
        this.primitive = primitive == null ? "n/a" : primitive;
        this.points = points == null ? List.of() : List.copyOf(points);
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    public String getPrimitive() {
        return primitive;
    }

    @JsonIgnore
    public List<Vertex> getPoints() {
        return points;
    }

    @JsonIgnore
    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    @JsonProperty("lineStrip")
    public List<Vertex> getVertices() {
        return points;
    }

    public record Vertex(double x, double y, double z) {}
}
