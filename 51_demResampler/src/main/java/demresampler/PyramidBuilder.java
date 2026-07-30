package demresampler;

import demresampler.gdal.GdalDataset;
import demresampler.gdal.GdalVrtBuilder;
import demresampler.gdal.PendingTiff;
import demresampler.gdal.TiffHeaderScanResult;
import demresampler.gdal.TiffHeaderScanner;
import demresampler.io.FabdemScanner;
import demresampler.io.FabdemSourceTile;
import demresampler.io.RawTileIO;
import demresampler.manifest.LevelManifest;
import demresampler.manifest.ManifestStore;
import demresampler.model.RasterMetadata;
import demresampler.options.CliOptions;
import demresampler.processing.LeafLevelGenerator;
import demresampler.processing.ExistingTileIndexer;
import demresampler.processing.ParentLevelGenerator;
import demresampler.processing.TargetLevelSelector;
import demresampler.processing.TileHaloGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class PyramidBuilder {
    private static final int GDAL_FLOAT32 = 6;
    private static final String PENDING_TIFF_REPORT = "pending-tiffs.txt";

    public void build(CliOptions options) throws Exception {
        validatePaths(options);
        List<FabdemSourceTile> sources = FabdemScanner.scan(options.inputRoot());
        System.out.printf("FABDEM inputs: %,d GeoTIFF files%n", sources.size());
        Files.createDirectories(options.outputRoot());
        TiffHeaderScanResult headerScan = TiffHeaderScanner.scan(sources);
        try {
            sources = headerScan.readableSources();
            if (sources.isEmpty()) {
                throw new IOException("No TIFF file passed the header check");
            }
            RasterMetadata sourceMetadata = headerScan.representativeHeader().raster();
            if (!headerScan.representativeHeader().wgs84Geographic()) {
                throw new IOException(
                    "FABDEM source is not WGS84 geographic: " + sources.get(0).path());
            }
            validateFabdemMetadata(sources.get(0).path(), sourceMetadata);
            int leafLevel = TargetLevelSelector.finestLevelWithoutUpsampling(
                sourceMetadata.angularResolution());

            System.out.printf(
                "Source resolution: %.12f degrees/pixel; selected leaf level: %d "
                    + "(%.12f degrees/pixel)%n",
                sourceMetadata.angularResolution(),
                leafLevel,
                TargetLevelSelector.pixelDegrees(leafLevel));
            System.out.printf("Parallel workers: %d%n", options.threads());
            System.out.printf(
                "Concurrent tile writers: %d%n",
                RawTileIO.MAX_CONCURRENT_WRITERS);

            Set<Long> leafCandidates = FabdemScanner.candidateTiles(sources, leafLevel);
            System.out.printf("Leaf coverage candidates: %,d tiles%n", leafCandidates.size());
            ManifestStore manifests =
                ManifestStore.create(options.outputRoot(), sources, leafLevel);

            Path workDirectory = Files.createTempDirectory("51-demResampler-");
            try (PyramidSizeTracker sizeTracker =
                     new PyramidSizeTracker(options.outputRoot())) {
                Path vrt = GdalVrtBuilder.build(workDirectory, sources);
                validateVrt(vrt);
                Set<Long> current;
                try (LevelManifest leafManifest =
                         manifests.openLevel(leafLevel, leafCandidates)) {
                    sizeTracker.recordTileFiles(leafManifest.presentCount());
                    sizeTracker.setStage("Index existing level " + leafLevel);
                    ExistingTileIndexer.index(
                        options.outputRoot(),
                        leafManifest,
                        sizeTracker::recordTileFile);
                    sizeTracker.setStage("Leaf level " + leafLevel);
                    current = LeafLevelGenerator.generate(
                        vrt,
                        options.outputRoot(),
                        leafLevel,
                        options.threads(),
                        sourceMetadata.noData(),
                        leafManifest,
                        sizeTracker::recordTileFile);
                    if (current.isEmpty()) {
                        throw new IOException("No valid elevation samples were produced");
                    }
                    sizeTracker.checkpoint("Leaf level " + leafLevel + " cores complete");
                    sizeTracker.setStage("Halo level " + leafLevel);
                    current = TileHaloGenerator.generate(
                        options.outputRoot(),
                        leafLevel,
                        options.threads(),
                        leafManifest);
                }

                long totalTiles = current.size();
                for (int level = leafLevel - 1; level >= 0; level--) {
                    Set<Long> parents =
                        ParentLevelGenerator.parentCoordinates(level, current);
                    try (LevelManifest parentManifest =
                             manifests.openLevel(level, parents)) {
                        sizeTracker.recordTileFiles(parentManifest.presentCount());
                        sizeTracker.setStage("Index existing level " + level);
                        ExistingTileIndexer.index(
                            options.outputRoot(),
                            parentManifest,
                            sizeTracker::recordTileFile);
                        sizeTracker.setStage("Parent level " + level);
                        current = ParentLevelGenerator.generate(
                            options.outputRoot(),
                            level,
                            options.threads(),
                            parentManifest,
                            sizeTracker::recordTileFile);
                        sizeTracker.checkpoint(
                            "Parent level " + level + " cores complete");
                        sizeTracker.setStage("Halo level " + level);
                        current = TileHaloGenerator.generate(
                            options.outputRoot(),
                            level,
                            options.threads(),
                            parentManifest);
                    }
                    totalTiles += current.size();
                }
                if (current.size() != 1) {
                    throw new IOException("Pyramid construction did not converge to one root tile");
                }
                if (sizeTracker.tileFiles() != totalTiles) {
                    throw new IOException(
                        "Pyramid tile inventory mismatch: expected " + totalTiles
                            + ", counted " + sizeTracker.tileFiles());
                }
                sizeTracker.markComplete();
                System.out.printf(
                    "DEM pyramid complete: %,d tiles through levels 0..%d at %s%n",
                    totalTiles,
                    leafLevel,
                    options.outputRoot());
            } finally {
                deleteWorkDirectory(workDirectory);
            }
        } finally {
            reportPendingTiffs(
                options.outputRoot(),
                sources.size(),
                headerScan.pendingTiffs());
        }
    }

    private static void reportPendingTiffs(
        Path outputRoot,
        int readableCount,
        List<PendingTiff> pending
    ) {
        Path report = outputRoot.resolve(PENDING_TIFF_REPORT);
        List<String> lines = new ArrayList<>(pending.size() + 2);
        lines.add("Readable TIFF files: " + readableCount);
        lines.add("Pending TIFF files: " + pending.size());
        for (PendingTiff item : pending) {
            lines.add(item.path() + "\t" + item.reason());
        }
        try {
            Files.write(report, lines);
        } catch (IOException exception) {
            System.err.println(
                "WARNING: could not write pending TIFF report " + report + ": "
                    + exception.getMessage());
        }

        System.out.printf(
            "TIFF header report: %,d readable, %,d pending. %s%n",
            readableCount,
            pending.size(),
            report);
        for (PendingTiff item : pending) {
            System.out.println("  PENDING " + item.path() + " — " + item.reason());
        }
    }

    private static void validateFabdemMetadata(Path source, RasterMetadata metadata) throws IOException {
        if (metadata.width() != 3600 || metadata.height() != 3600) {
            throw new IOException("Expected a 3600x3600 FABDEM tile, got "
                + metadata.width() + "x" + metadata.height() + ": " + source);
        }
        if (metadata.gdalDataType() != GDAL_FLOAT32) {
            throw new IOException("Expected Float32 FABDEM samples: " + source);
        }
        if (!metadata.hasNoData() || Math.abs(metadata.noData() - (-9999.0)) > 1e-6) {
            throw new IOException("Expected FABDEM NoData=-9999: " + source);
        }
        if (metadata.rotationX() != 0.0 || metadata.rotationY() != 0.0
            || metadata.pixelWidth() <= 0.0 || metadata.pixelHeight() >= 0.0
            || Math.abs(metadata.pixelWidth() + metadata.pixelHeight()) > 1e-12) {
            throw new IOException("Expected a north-up square FABDEM grid: " + source);
        }
    }

    private static void validateVrt(Path vrt) throws IOException {
        try (GdalDataset dataset = GdalDataset.open(vrt)) {
            if (!dataset.isWgs84Geographic()) {
                throw new IOException("Generated VRT is not WGS84 geographic");
            }
            RasterMetadata metadata = dataset.describe();
            if (metadata.rotationX() != 0.0 || metadata.rotationY() != 0.0) {
                throw new IOException("Generated VRT is rotated");
            }
        }
    }

    private static void validatePaths(CliOptions options) throws IOException {
        if (!Files.isDirectory(options.inputRoot()) || !Files.isReadable(options.inputRoot())) {
            throw new IOException("Input FABDEM folder is not a readable directory: "
                + options.inputRoot());
        }
        if (options.inputRoot().equals(options.outputRoot())
            || options.outputRoot().startsWith(options.inputRoot())) {
            throw new IOException("Output folder must be outside the FABDEM input tree");
        }
    }

    private static void deleteWorkDirectory(Path workDirectory) {
        try (Stream<Path> paths = Files.walk(workDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            System.err.println("WARNING: could not remove temporary work directory "
                + workDirectory + ": " + exception.getMessage());
        }
    }
}
