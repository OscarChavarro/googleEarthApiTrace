package demresampler.gdal;

import demresampler.io.FabdemSourceTile;
import demresampler.model.RasterMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TiffHeaderScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsReadableSourcesAndReportsTimedOutAndFailedHeaders() throws Exception {
        List<FabdemSourceTile> sources = List.of(
            source("first.tif", 0),
            source("slow.tif", 1),
            source("broken.tif", 2),
            source("last.tif", 3));
        AtomicInteger nextResult = new AtomicInteger();

        TiffHeaderScanResult result = TiffHeaderScanner.scan(
            sources,
            Duration.ofSeconds(2),
            () -> new TiffHeaderScanner.HeaderProbe() {
                @Override
                public TiffHeaderScanner.ProbeResult probe(Path path, Duration timeout)
                    throws IOException {
                    return switch (nextResult.getAndIncrement()) {
                        case 0, 3 -> TiffHeaderScanner.ProbeResult.readableHeader(
                            validMetadata());
                        case 1 -> TiffHeaderScanner.ProbeResult.pending(
                            "header read timed out after 2000 ms");
                        default -> throw new IOException("unreadable header");
                    };
                }

                @Override
                public void close() {
                }
            });

        assertEquals(List.of(sources.get(0), sources.get(3)), result.readableSources());
        assertEquals(2, result.pendingTiffs().size());
        assertEquals(sources.get(1).path().toAbsolutePath(), result.pendingTiffs().get(0).path());
        assertEquals("header read timed out after 2000 ms", result.pendingTiffs().get(0).reason());
        assertEquals(sources.get(2).path().toAbsolutePath(), result.pendingTiffs().get(1).path());
        assertEquals(
            "TIFF header probe I/O error: unreadable header",
            result.pendingTiffs().get(1).reason());
    }

    @Test
    void rejectsANonPositiveTimeout() {
        assertThrows(
            IllegalArgumentException.class,
            () -> TiffHeaderScanner.scan(List.of(), Duration.ZERO, () -> null));
    }

    @Test
    void readsAHeaderThroughTheIsolatedProductionProcess() throws Exception {
        Path raster = temporaryDirectory.resolve("valid.tif");
        Files.writeString(raster, """
            <VRTDataset rasterXSize="3600" rasterYSize="3600">
              <SRS>EPSG:4326</SRS>
              <GeoTransform>0, 0.0002777777777777778, 0, 1, 0, -0.0002777777777777778</GeoTransform>
              <VRTRasterBand dataType="Float32" band="1">
                <NoDataValue>-9999</NoDataValue>
              </VRTRasterBand>
            </VRTDataset>
            """);
        FabdemSourceTile source = new FabdemSourceTile(raster, 0, 0);

        TiffHeaderScanResult result = TiffHeaderScanner.scan(List.of(source));

        assertEquals(List.of(source), result.readableSources());
        assertEquals(List.of(), result.pendingTiffs());
        assertEquals(3600, result.representativeHeader().raster().width());
    }

    private static FabdemSourceTile source(String filename, int coordinate) {
        return new FabdemSourceTile(Path.of(filename), coordinate, coordinate);
    }

    private static TiffHeaderMetadata validMetadata() {
        return new TiffHeaderMetadata(
            new RasterMetadata(
                3600, 3600, 0.0, 1.0 / 3600.0, 0.0, 1.0, 0.0,
                -1.0 / 3600.0, -9999.0, true, 6),
            true);
    }
}
