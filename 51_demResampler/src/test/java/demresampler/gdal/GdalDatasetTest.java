package demresampler.gdal;

import demresampler.model.RasterMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdalDatasetTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void opensAndReadsAFloat32VrtThroughTheNativeGdalBridge() throws Exception {
        Path raw = temporaryDirectory.resolve("samples.raw");
        ByteBuffer values = ByteBuffer.allocate(4 * 4 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            values.putFloat(i);
        }
        Files.write(raw, values.array());

        Path vrt = temporaryDirectory.resolve("samples.vrt");
        Files.writeString(vrt, """
            <VRTDataset rasterXSize="4" rasterYSize="4">
              <SRS>EPSG:4326</SRS>
              <GeoTransform>-0.5, 1, 0, 3.5, 0, -1</GeoTransform>
              <VRTRasterBand dataType="Float32" band="1" subClass="VRTRawRasterBand">
                <NoDataValue>-9999</NoDataValue>
                <SourceFilename relativeToVRT="1">samples.raw</SourceFilename>
                <ImageOffset>0</ImageOffset>
                <PixelOffset>4</PixelOffset>
                <LineOffset>16</LineOffset>
                <ByteOrder>LSB</ByteOrder>
              </VRTRasterBand>
            </VRTDataset>
            """);

        try (GdalDataset dataset = GdalDataset.open(vrt)) {
            RasterMetadata metadata = dataset.describe();
            assertEquals(4, metadata.width());
            assertEquals(4, metadata.height());
            assertEquals(-9999.0, metadata.noData());
            assertTrue(dataset.isWgs84Geographic());
            assertArrayEquals(
                new float[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                dataset.read(-0.5, 3.5, 3.5, -0.5, 4, 4));
        }
    }

    @Test
    void readsWhenRequestedBoundsExtendHalfAPixelPastRasterEdge() throws Exception {
        Path raw = temporaryDirectory.resolve("edge.raw");
        ByteBuffer values = ByteBuffer.allocate(4 * 4 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            values.putFloat(i);
        }
        Files.write(raw, values.array());

        Path vrt = temporaryDirectory.resolve("edge.vrt");
        Files.writeString(vrt, """
            <VRTDataset rasterXSize="4" rasterYSize="4">
              <SRS>EPSG:4326</SRS>
              <GeoTransform>-0.5, 1, 0, 3.5, 0, -1</GeoTransform>
              <VRTRasterBand dataType="Float32" band="1" subClass="VRTRawRasterBand">
                <NoDataValue>-9999</NoDataValue>
                <SourceFilename relativeToVRT="1">edge.raw</SourceFilename>
                <ImageOffset>0</ImageOffset>
                <PixelOffset>4</PixelOffset>
                <LineOffset>16</LineOffset>
                <ByteOrder>LSB</ByteOrder>
              </VRTRasterBand>
            </VRTDataset>
            """);

        try (GdalDataset dataset = GdalDataset.open(vrt)) {
            float[] result = dataset.read(-0.5, 3.5, 4.0, -1.0, 4, 4);
            assertEquals(16, result.length);
        }
    }
}
