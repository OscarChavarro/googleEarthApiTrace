package planetviewer.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetviewer.io.PyramidalImageFolderReader;
import planetviewer.model.PyramidalImage;

class PyramidalImageMergerTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void writesNewTilesUsingPerDigitLayout() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("destination");
        Path deltaRoot = temporaryFolder.resolve("delta");
        createPerDigitTile(destinationRoot, "0");
        createPerDigitTile(destinationRoot, "02");
        createPerDigitTile(deltaRoot, "0");
        createPerDigitTile(deltaRoot, "02010020");

        PyramidalImage destination = read(destinationRoot);
        PyramidalImage delta = read(deltaRoot);
        PyramidalImageMerger merger = new PyramidalImageMerger();

        PyramidalImageMerger.MergeResult result = merger.mergeTiles(destination, delta, Set.of());
        PyramidalImage refreshed = read(destinationRoot);

        assertEquals(1, result.copiedTiles());
        assertTrue(Files.isRegularFile(destinationRoot.resolve("2/0/1/0/0/2/0/02010020.png")));
        assertFalse(Files.exists(destinationRoot.resolve("02/020/0201")));
        assertTrue(merger.findMissingDeltaTileIds(refreshed, delta).isEmpty());
    }

    @Test
    void prefersPerDigitLayoutForAnAlreadyHybridDestination() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("hybrid-destination");
        Path deltaRoot = temporaryFolder.resolve("hybrid-delta");
        createPerDigitTile(destinationRoot, "0");
        createPerDigitTile(destinationRoot, "02");
        createLegacyTile(destinationRoot, "03");
        createPerDigitTile(deltaRoot, "0");
        createPerDigitTile(deltaRoot, "0301");

        PyramidalImageMerger merger = new PyramidalImageMerger();
        merger.mergeTiles(read(destinationRoot), read(deltaRoot), Set.of());
        PyramidalImage refreshed = read(destinationRoot);

        assertTrue(Files.isRegularFile(destinationRoot.resolve("3/0/1/0301.png")));
        assertTrue(merger.findMissingDeltaTileIds(refreshed, read(deltaRoot)).isEmpty());
    }

    @Test
    void preservesLegacyLayoutForALegacyOnlyDestination() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("legacy-destination");
        Path deltaRoot = temporaryFolder.resolve("legacy-delta");
        createPerDigitTile(destinationRoot, "0");
        createLegacyTile(destinationRoot, "02");
        createPerDigitTile(deltaRoot, "0");
        createPerDigitTile(deltaRoot, "0201");

        PyramidalImageMerger merger = new PyramidalImageMerger();
        merger.mergeTiles(read(destinationRoot), read(deltaRoot), Set.of());
        PyramidalImage refreshed = read(destinationRoot);

        assertTrue(Files.isRegularFile(destinationRoot.resolve("02/020/0201/0201.png")));
        assertFalse(Files.exists(destinationRoot.resolve("2")));
        assertTrue(merger.findMissingDeltaTileIds(refreshed, read(deltaRoot)).isEmpty());
    }

    @Test
    void postconditionReportsTilesNotVisibleInDestination() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("incomplete-destination");
        Path deltaRoot = temporaryFolder.resolve("incomplete-delta");
        createPerDigitTile(destinationRoot, "0");
        createPerDigitTile(deltaRoot, "0");
        createPerDigitTile(deltaRoot, "0201");

        assertEquals(
            java.util.List.of("0201"),
            new PyramidalImageMerger().findMissingDeltaTileIds(read(destinationRoot), read(deltaRoot))
        );
    }

    @Test
    void retainsDifferentSupportAncestorsWhenAddingADeeperRefinement() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("refinement-destination");
        Path deltaRoot = temporaryFolder.resolve("refinement-delta");
        writePerDigitTile(destinationRoot, "0", Color.GRAY);
        writePerDigitTile(destinationRoot, "03", Color.RED);
        writePerDigitTile(destinationRoot, "030", Color.RED);
        writePerDigitTile(deltaRoot, "0", Color.GRAY);
        writePerDigitTile(deltaRoot, "03", Color.BLUE);
        writePerDigitTile(deltaRoot, "030", Color.BLUE);
        writePerDigitTile(deltaRoot, "0301", Color.BLUE);
        byte[] originalParent = Files.readAllBytes(destinationRoot.resolve("3/03.png"));

        PyramidalImage destination = read(destinationRoot);
        PyramidalImage delta = read(deltaRoot);
        MergeAnalysis analysis = new PyramidalImageMergeAnalyzer().analyze(destination, delta);

        assertTrue(analysis.isMergePossible());
        assertEquals(Set.of("03", "030"), analysis.getRetainedRefinementAncestorNodeIds());

        PyramidalImageMerger.MergeResult result = new PyramidalImageMerger().mergeTiles(
            destination,
            delta,
            analysis.getHigherResolutionDeltaNodeIds()
        );
        assertEquals(1, result.copiedTiles());
        assertEquals(0, result.replacedTiles());
        assertTrue(Files.isRegularFile(destinationRoot.resolve("3/0/1/0301.png")));
        assertTrue(java.util.Arrays.equals(originalParent, Files.readAllBytes(destinationRoot.resolve("3/03.png"))));
    }

    @Test
    void retainsAConflictingSupportAncestorThreeLevelsAboveTheNewRefinement() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("three-level-destination");
        Path deltaRoot = temporaryFolder.resolve("three-level-delta");
        writePerDigitTile(destinationRoot, "0", Color.GRAY);
        writePerDigitTile(destinationRoot, "03", Color.RED);
        writePerDigitTile(deltaRoot, "0", Color.GRAY);
        writePerDigitTile(deltaRoot, "03", Color.BLUE);
        writePerDigitTile(deltaRoot, "03012", Color.BLUE);

        MergeAnalysis analysis = new PyramidalImageMergeAnalyzer().analyze(
            read(destinationRoot),
            read(deltaRoot)
        );

        assertTrue(analysis.isMergePossible());
        assertTrue(analysis.getConflictingNodeIds().isEmpty());
        assertEquals(Set.of("03"), analysis.getRetainedRefinementAncestorNodeIds());
    }

    @Test
    void retainsConflictingUncleContextNearANewDeepestRefinement() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("uncle-context-destination");
        Path deltaRoot = temporaryFolder.resolve("uncle-context-delta");
        writePerDigitTile(destinationRoot, "0", Color.GRAY);
        writePerDigitTile(destinationRoot, "03", Color.RED);
        writePerDigitTile(deltaRoot, "0", Color.GRAY);
        writePerDigitTile(deltaRoot, "03", Color.BLUE);
        writePerDigitTile(deltaRoot, "0123", Color.BLUE);

        MergeAnalysis analysis = new PyramidalImageMergeAnalyzer().analyze(
            read(destinationRoot),
            read(deltaRoot)
        );

        assertTrue(analysis.isMergePossible());
        assertEquals(Set.of("03"), analysis.getRetainedRefinementAncestorNodeIds());
    }

    @Test
    void retainsConflictingDeepestTileContextWhenAddingANeighborAtThatLevel() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("leaf-context-destination");
        Path deltaRoot = temporaryFolder.resolve("leaf-context-delta");
        writePerDigitTile(destinationRoot, "0", Color.GRAY);
        writePerDigitTile(destinationRoot, "030", Color.RED);
        writePerDigitTile(deltaRoot, "0", Color.GRAY);
        writePerDigitTile(deltaRoot, "030", Color.BLUE);
        writePerDigitTile(deltaRoot, "031", Color.BLUE);
        byte[] originalLeaf = Files.readAllBytes(destinationRoot.resolve("3/0/030.png"));

        PyramidalImage destination = read(destinationRoot);
        PyramidalImage delta = read(deltaRoot);
        MergeAnalysis analysis = new PyramidalImageMergeAnalyzer().analyze(destination, delta);

        assertTrue(analysis.isMergePossible());
        assertEquals(Set.of("030"), analysis.getRetainedRefinementAncestorNodeIds());

        PyramidalImageMerger.MergeResult result = new PyramidalImageMerger().mergeTiles(
            destination,
            delta,
            analysis.getHigherResolutionDeltaNodeIds()
        );
        assertEquals(1, result.copiedTiles());
        assertEquals(0, result.replacedTiles());
        assertTrue(Files.isRegularFile(destinationRoot.resolve("3/1/031.png")));
        assertTrue(java.util.Arrays.equals(originalLeaf, Files.readAllBytes(destinationRoot.resolve("3/0/030.png"))));
    }

    @Test
    void treatsAFullyRedundantConflictingDeltaAsAnIdempotentNoOp() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("isolated-conflict-destination");
        Path deltaRoot = temporaryFolder.resolve("isolated-conflict-delta");
        writePerDigitTile(destinationRoot, "0", Color.GRAY);
        writePerDigitTile(destinationRoot, "03", Color.RED);
        writePerDigitTile(deltaRoot, "0", Color.GRAY);
        writePerDigitTile(deltaRoot, "03", Color.BLUE);

        PyramidalImage destination = read(destinationRoot);
        PyramidalImage delta = read(deltaRoot);
        MergeAnalysis analysis = new PyramidalImageMergeAnalyzer().analyze(destination, delta);

        assertTrue(analysis.isMergePossible());
        assertTrue(analysis.getConflictingNodeIds().isEmpty());
        assertEquals(Set.of("03"), analysis.getRetainedRefinementAncestorNodeIds());
        assertTrue(analysis.getHigherResolutionDeltaNodeIds().isEmpty());

        PyramidalImageMerger.MergeResult result = new PyramidalImageMerger().mergeTiles(
            destination,
            delta,
            analysis.getHigherResolutionDeltaNodeIds()
        );
        assertEquals(0, result.copiedTiles());
        assertEquals(0, result.replacedTiles());
        MergeAnalysis merged = new PyramidalImageMergeAnalyzer().markMerged(analysis, 0, 0);
        assertEquals(
            "Merge completed as no-op. Copied 0 new tile(s) and replaced 0 lower-resolution tile(s) in destination.",
            merged.mergeCompletedSummary()
        );
    }

    @Test
    void retainsAConflictingSupportAncestorFourLevelsAboveTheNewRefinement() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("unsafe-destination");
        Path deltaRoot = temporaryFolder.resolve("unsafe-delta");
        writePerDigitTile(destinationRoot, "0", Color.GRAY);
        writePerDigitTile(destinationRoot, "03", Color.RED);
        writePerDigitTile(deltaRoot, "0", Color.GRAY);
        writePerDigitTile(deltaRoot, "03", Color.BLUE);
        writePerDigitTile(deltaRoot, "030123", Color.BLUE);

        MergeAnalysis analysis = new PyramidalImageMergeAnalyzer().analyze(
            read(destinationRoot),
            read(deltaRoot)
        );

        assertTrue(analysis.isMergePossible());
        assertTrue(analysis.getConflictingNodeIds().isEmpty());
        assertEquals(Set.of("03"), analysis.getRetainedRefinementAncestorNodeIds());
    }

    @Test
    void blocksAConflictMoreThanFourLevelsAboveTheNewRefinement() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("five-level-destination");
        Path deltaRoot = temporaryFolder.resolve("five-level-delta");
        writePerDigitTile(destinationRoot, "0", Color.GRAY);
        writePerDigitTile(destinationRoot, "03", Color.RED);
        writePerDigitTile(deltaRoot, "0", Color.GRAY);
        writePerDigitTile(deltaRoot, "03", Color.BLUE);
        writePerDigitTile(deltaRoot, "0301230", Color.BLUE);

        MergeAnalysis analysis = new PyramidalImageMergeAnalyzer().analyze(
            read(destinationRoot),
            read(deltaRoot)
        );

        assertFalse(analysis.isMergePossible());
        assertEquals(Set.of("03"), analysis.getConflictingNodeIds());
        assertTrue(analysis.getRetainedRefinementAncestorNodeIds().isEmpty());
    }

    private PyramidalImage read(Path root) {
        return new PyramidalImageFolderReader().read(root).orElseThrow();
    }

    private void createPerDigitTile(Path root, String id) throws IOException {
        Path directory = root;
        for (int index = 1; index < id.length(); index++) {
            directory = directory.resolve(String.valueOf(id.charAt(index)));
        }
        Files.createDirectories(directory);
        Files.createFile(directory.resolve(id + ".png"));
    }

    private void createLegacyTile(Path root, String id) throws IOException {
        Path directory = root;
        for (int index = 1; index < id.length(); index++) {
            directory = directory.resolve(id.substring(0, index + 1));
        }
        Files.createDirectories(directory);
        Files.createFile(directory.resolve(id + ".png"));
    }

    private void writePerDigitTile(Path root, String id, Color color) throws IOException {
        Path directory = root;
        for (int index = 1; index < id.length(); index++) {
            directory = directory.resolve(String.valueOf(id.charAt(index)));
        }
        Files.createDirectories(directory);
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ImageIO.write(image, "png", directory.resolve(id + ".png").toFile());
    }
}
