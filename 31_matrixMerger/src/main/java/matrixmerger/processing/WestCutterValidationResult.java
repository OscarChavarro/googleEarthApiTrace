package matrixmerger.processing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WestCutterValidationResult {
    private final Map<Integer, String> invalidReasonByFrameId;

    public WestCutterValidationResult(Map<Integer, String> invalidReasonByFrameId) {
        this.invalidReasonByFrameId = invalidReasonByFrameId == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(invalidReasonByFrameId));
    }

    public Map<Integer, String> getInvalidReasonByFrameId() {
        return invalidReasonByFrameId;
    }

    public boolean hasInvalidFrames() {
        return !invalidReasonByFrameId.isEmpty();
    }
}
