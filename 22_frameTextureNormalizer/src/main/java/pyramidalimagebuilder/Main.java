package pyramidalimagebuilder;

import java.nio.file.Path;
import pyramidalimagebuilder.config.Configuration;
import pyramidalimagebuilder.io.TraceSessionReader;
import pyramidalimagebuilder.model.FrameData;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.processing.DuplicatedTextureFilenameMapper;
import pyramidalimagebuilder.processing.Sha256SignatureGenerator;
import pyramidalimagebuilder.processing.TileFiltererByGeometricNullNeighbors;
import pyramidalimagebuilder.processing.TileMatrixProcessor;
import pyramidalimagebuilder.processing.TileTextureNormalizer;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        PyramidalImageModel model = new PyramidalImageModel();

        TraceSessionReader traceSessionReader = new TraceSessionReader();
        TileFiltererByGeometricNullNeighbors tileFilterer = new TileFiltererByGeometricNullNeighbors();

        System.out.print("Loading traced frames... ");
        Runnable reloadTileMatrices = loadTracedFrames(traceSessionReader, tileFilterer, model);
        System.out.println("OK");

        System.out.print("SHA signature validation... ");
        Sha256SignatureGenerator.verifyTextureFilesHasSignatureFile(model.getFrames());
        System.out.println("OK");

        System.out.print("Duplicated texture filename mapping... ");
        List<List<String>> duplicatedTextureGroups = DuplicatedTextureFilenameMapper.loadOrCreate(model.getFrames());
        System.out.println("OK");

        System.out.print("Tile texture normalization and matrix conversion... ");
        TileMatrixProcessor tileMatrixProcessor = new TileMatrixProcessor();
        model.setFrames(tileMatrixProcessor.convertAndExportTileMatrices(
            TileTextureNormalizer.normalize(model.getFrames(), duplicatedTextureGroups)
        ));
        System.out.println("OK");

        InteractiveDebugger.runDesktopGui(model, reloadTileMatrices);
    }

    private static Runnable loadTracedFrames(
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
