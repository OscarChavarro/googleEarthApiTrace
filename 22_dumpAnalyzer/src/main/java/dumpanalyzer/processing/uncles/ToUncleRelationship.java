package dumpanalyzer.processing.uncles;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record ToUncleRelationship(
    UncleDirections direction,
    @JsonAlias("uncleContentId") String referenceContentId,
    UncleRelationshipKind relationshipKind,
    Integer levelDelta,
    Integer rowOffset,
    Integer columnOffset
) {
    public ToUncleRelationship(UncleDirections direction, String uncleContentId) {
        this(direction, uncleContentId, UncleRelationshipKind.CONTAINING_QUADRANT, null, null, null);
    }

    public ToUncleRelationship(
        UncleDirections direction,
        String uncleContentId,
        UncleRelationshipKind relationshipKind
    ) {
        this(direction, uncleContentId, relationshipKind, null, null, null);
    }

    @JsonIgnore
    public String uncleContentId() {
        return referenceContentId;
    }

    @JsonIgnore
    public boolean hasGridOffset() {
        return levelDelta != null && levelDelta > 0 && rowOffset != null && columnOffset != null;
    }
}
