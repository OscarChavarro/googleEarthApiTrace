package processing.uncles;

public record ToUncleRelationship(
    UncleDirections direction,
    Integer uncleId
) {}
