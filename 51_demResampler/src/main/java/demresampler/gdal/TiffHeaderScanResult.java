package demresampler.gdal;

import demresampler.io.FabdemSourceTile;

import java.util.List;

public record TiffHeaderScanResult(
    List<FabdemSourceTile> readableSources,
    List<PendingTiff> pendingTiffs,
    TiffHeaderMetadata representativeHeader
) {
    public TiffHeaderScanResult {
        readableSources = List.copyOf(readableSources);
        pendingTiffs = List.copyOf(pendingTiffs);
        if (!readableSources.isEmpty() && representativeHeader == null) {
            throw new IllegalArgumentException(
                "Readable TIFF sources require representative header metadata");
        }
    }
}
