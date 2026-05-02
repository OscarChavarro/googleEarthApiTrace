package pyramidalimagebuilder.io;

import java.nio.file.Path;
import java.util.List;
import pyramidalimagebuilder.config.Configuration;
import pyramidalimagebuilder.model.FrameData;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.processing.TileFiltererByGeometricNullNeighbors;

public final class FrameReader {
    private FrameReader() {
    }

    public static Runnable loadTracedFrames(
        TraceSessionReader traceSessionReader,
        TileFiltererByGeometricNullNeighbors tileFilterer,
        PyramidalImageModel model
    ) {
        Runnable reloadTileMatrices = () -> {
            List<FrameData> loaded = traceSessionReader.readSession(Path.of(Configuration.INPUT_PATH));
            List<FrameData> filtered = loaded.stream()
                .map(frame -> new FrameData(
                    frame.getId(),
                    tileFilterer.filter(frame.getTiles(), model.getViewingCamera()),
                    frame.getCameraState()
                ))
                .toList();
            model.setFrames(filtered);
            System.out.println("Loaded frames: " + model.getFrames().size());
        };
        reloadTileMatrices.run();
        return reloadTileMatrices;
    }
}
