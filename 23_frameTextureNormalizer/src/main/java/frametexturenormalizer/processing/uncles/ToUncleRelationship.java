package frametexturenormalizer.processing.uncles;

public record ToUncleRelationship(
    UncleDirections direction,
    String uncleContentId,
    UncleRelationshipKind relationshipKind
) {
    public ToUncleRelationship(UncleDirections direction, String uncleContentId) {
        this(direction, uncleContentId, UncleRelationshipKind.CONTAINING_QUADRANT);
    }
}
