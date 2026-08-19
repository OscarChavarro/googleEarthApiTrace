package pyramidalimageexporter.model.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pyramidalimageexporter.processing.uncles.UncleRmsAnalyzer;

final class PyramidalImageExporterStateTest {
    @Test
    void loadsRmsAnalysisOnlyWhenTheOverlayIsEnabled() {
        PyramidalImageExporterState state = new PyramidalImageExporterState();
        AtomicInteger loads = new AtomicInteger();
        state.setUncleRmsAnalysisLoader(() -> {
            loads.incrementAndGet();
            return UncleRmsAnalyzer.Analysis.empty();
        });

        assertEquals(0, state.getComparedUncleRelationshipCount());
        assertEquals(0, loads.get());

        state.setRmsHeatMapEnabled(true);
        state.setRmsHeatMapEnabled(true);

        assertEquals(1, loads.get());
    }
}
