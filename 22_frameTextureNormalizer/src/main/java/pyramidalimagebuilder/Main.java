package pyramidalimagebuilder;

import java.util.List;

import pyramidalimagebuilder.io.FrameReader;
import pyramidalimagebuilder.io.TraceSessionReader;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.processing.DuplicatedTextureFilenameMapper;
import pyramidalimagebuilder.processing.Sha256SignatureGenerator;
import pyramidalimagebuilder.processing.TileFiltererByGeometricNullNeighbors;
import pyramidalimagebuilder.processing.TileMatrixProcessor;
import pyramidalimagebuilder.processing.TileTextureNormalizer;

public class Main {
    public static void main(String[] args) {
        PyramidalImageModel model = new PyramidalImageModel();

        TraceSessionReader traceSessionReader = new TraceSessionReader();
        TileFiltererByGeometricNullNeighbors tileFilterer = new TileFiltererByGeometricNullNeighbors();

        System.out.print("Loading traced frames... ");
        Runnable reloadTileMatrices = FrameReader.loadTracedFrames(traceSessionReader, tileFilterer, model);
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
}
