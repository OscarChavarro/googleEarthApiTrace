package dumpanalyzer.processing.uncles;

public record ToUncleRelationship(
    UncleDirections direction,
    String uncleContentId
) {}
