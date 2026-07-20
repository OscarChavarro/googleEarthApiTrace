package planetviewer.merge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import planetviewer.io.PyramidalImageFolderReader;
import planetviewer.model.PyramidalImage;

public final class PyramidalImageMergeService {
    private final PyramidalImageMergeAnalyzer analyzer = new PyramidalImageMergeAnalyzer();
    private final PyramidalImageMerger merger = new PyramidalImageMerger();
    private final PyramidalImageFolderReader folderReader = new PyramidalImageFolderReader();

    public MergeAnalysis validate(PyramidalImage destination, PyramidalImage delta) {
        return analyzer.analyze(destination, delta);
    }

    public Result validateAndMerge(PyramidalImage destination, PyramidalImage delta) throws IOException {
        MergeAnalysis analysis = validate(destination, delta);
        if (!analysis.isMergePossible()) {
            return new Result(analysis, List.of());
        }

        PyramidalImageMerger.MergeResult mergeResult = merger.mergeTiles(
            destination,
            delta,
            analysis.getHigherResolutionDeltaNodeIds()
        );
        PyramidalImage refreshedDestination = folderReader
            .read(Path.of(destination.getSourceFolder()))
            .orElse(destination);
        List<String> missingTileIds = merger.findMissingDeltaTileIds(refreshedDestination, delta);
        if (!missingTileIds.isEmpty()) {
            String sample = String.join(", ", missingTileIds.stream().limit(8).toList());
            throw new IOException(
                "post-merge verification found " + missingTileIds.size()
                    + " missing delta tile(s); first ids: " + sample
            );
        }
        return new Result(
            analyzer.markMerged(analysis, mergeResult.copiedTiles(), mergeResult.replacedTiles()),
            missingTileIds
        );
    }

    public record Result(MergeAnalysis analysis, List<String> missingTileIds) {
    }
}
