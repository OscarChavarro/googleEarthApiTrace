package planetdemviewer.io.elevation;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import planetdemviewer.geo.GeographicCoordinate;
import planetdemviewer.geo.GeoidUndulationProvider;

/**
 * Read-only EGM2008 undulation grid stored in globalDeltaHeightsMatrix.json.
 * Values are decoded into a compact float array; the JSON representation is
 * intentionally not retained after loading.
 */
public final class Egm2008GeoidGrid implements GeoidUndulationProvider {
    public static final int ROWS = 2_161;
    public static final int COLUMNS = 4_320;
    private static final double STEP_DEGREES = 5.0 / 60.0;
    private final float[] values;

    private Egm2008GeoidGrid(float[] values) {
        this.values = values;
    }

    public static Egm2008GeoidGrid load(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            StringBuilder header = new StringBuilder(4_096);
            String marker = "\"valuesMetres\":";
            int matched = 0;
            int value;
            while ((value = reader.read()) >= 0) {
                char character = (char) value;
                header.append(character);
                matched = character == marker.charAt(matched) ? matched + 1
                    : character == marker.charAt(0) ? 1 : 0;
                if (matched == marker.length()) {
                    break;
                }
                if (header.length() > 32_768) {
                    throw new IOException("EGM2008 metadata exceeds expected header size");
                }
            }
            if (matched != marker.length() || !header.toString().contains("\"model\":\"EGM2008\"")
                || !header.toString().contains("\"rows\":2161")
                || !header.toString().contains("\"columns\":4320")) {
                throw new IOException("Invalid EGM2008 grid metadata: " + file);
            }
            while ((value = reader.read()) >= 0 && value != '[') {
                // Skip the JSON separator before the values array.
            }
            if (value != '[') {
                throw new IOException("Missing EGM2008 values array: " + file);
            }
            float[] values = new float[ROWS * COLUMNS];
            StringBuilder token = new StringBuilder(12);
            int count = 0;
            while ((value = reader.read()) >= 0 && count < values.length) {
                char character = (char) value;
                if ((character >= '0' && character <= '9') || character == '-' || character == '.'
                    || character == 'e' || character == 'E' || character == '+') {
                    token.append(character);
                    continue;
                }
                if (token.length() == 0) {
                    continue;
                }
                values[count++] = Float.parseFloat(token.toString());
                token.setLength(0);
            }
            if (token.length() > 0 && count < values.length) {
                values[count++] = Float.parseFloat(token.toString());
            }
            if (count != values.length) {
                throw new IOException("EGM2008 grid has " + count + " values; expected " + values.length);
            }
            return new Egm2008GeoidGrid(values);
        }
        catch (NumberFormatException exception) {
            throw new IOException("Invalid numeric EGM2008 grid value: " + file, exception);
        }
    }

    static Egm2008GeoidGrid fromSamplesForTest(float[] samples) {
        if (samples.length != ROWS * COLUMNS) {
            throw new IllegalArgumentException("Unexpected EGM2008 test grid size");
        }
        return new Egm2008GeoidGrid(samples.clone());
    }

    @Override
    public double undulationMetres(GeographicCoordinate coordinate) {
        double longitude = coordinate.longitudeDegrees() % 360.0;
        if (longitude < 0.0) {
            longitude += 360.0;
        }
        double latitude = Math.max(-90.0, Math.min(90.0, coordinate.latitudeDegrees()));
        double row = (90.0 - latitude) / STEP_DEGREES;
        int row0 = Math.min(ROWS - 1, (int) Math.floor(row));
        int row1 = Math.min(ROWS - 1, row0 + 1);
        double rowWeight = row - row0;
        double column = longitude / STEP_DEGREES;
        int column0 = ((int) Math.floor(column)) % COLUMNS;
        int column1 = (column0 + 1) % COLUMNS;
        double columnWeight = column - Math.floor(column);
        double north = interpolate(value(row0, column0), value(row0, column1), columnWeight);
        double south = interpolate(value(row1, column0), value(row1, column1), columnWeight);
        return interpolate(north, south, rowWeight);
    }

    private float value(int row, int column) {
        return values[row * COLUMNS + column];
    }

    private static double interpolate(double first, double second, double weight) {
        return first + (second - first) * weight;
    }
}

