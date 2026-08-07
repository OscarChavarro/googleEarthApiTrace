package frametexturenormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.processing.uncles.ToUncleRelationship;
import frametexturenormalizer.processing.uncles.UncleRelationshipKind;

class FrameNormalizationPipelineTest {
    @Test
    void duplicateMatricesMergeRelationshipMetadataIntoRepresentative() {
        TileMatrix representative = matrix(100, List.of());
        ToUncleRelationship relationship = new ToUncleRelationship(
            null,
            "01693_1060",
            UncleRelationshipKind.ADJACENT_BORDER,
            2,
            4,
            -1
        );
        TileMatrix duplicate = matrix(1693, List.of(relationship));

        List<TileMatrix> merged = FrameNormalizationPipeline.deduplicateMatricesByTileIds(
            List.of(representative, duplicate)
        );

        assertEquals(1, merged.size());
        assertEquals(100, merged.get(0).getFrameId());
        assertEquals(List.of(relationship), merged.get(0).getTiles().get(0).uncles());
    }

    private static TileMatrix matrix(int frameId, List<ToUncleRelationship> relationships) {
        return new TileMatrix(
            frameId,
            1,
            1,
            List.of(new TileMatrix.TileCoord(7, 0, 0, "/tmp/7.png", relationships))
        );
    }
}
