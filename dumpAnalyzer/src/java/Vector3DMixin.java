package dumpanalyzer;

import com.fasterxml.jackson.annotation.JsonProperty;

public abstract class Vector3DMixin {
    @JsonProperty("x")
    abstract double x();

    @JsonProperty("y")
    abstract double y();

    @JsonProperty("z")
    abstract double z();
}
