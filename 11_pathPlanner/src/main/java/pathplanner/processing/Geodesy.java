package pathplanner.processing;

import pathplanner.model.Point;

public final class Geodesy {
    private static final double WGS84_A = 6378137.0;
    private static final double WGS84_F = 1.0 / 298.257223563;
    private static final double WGS84_B = WGS84_A * (1.0 - WGS84_F);
    private static final double WGS84_E2 = WGS84_F * (2.0 - WGS84_F);

    private Geodesy() {}

    public static Point moveByMeters(Point start, int dir, double meters) {
        double azimuth;
        if (dir == 0) {
            azimuth = 0.0;
        } else if (dir == 1) {
            azimuth = 90.0;
        } else if (dir == 2) {
            azimuth = 180.0;
        } else {
            azimuth = 270.0;
        }
        return destinationWgs84(start.latDeg(), start.lonDeg(), azimuth, meters);
    }

    public static Point destinationWgs84(double latDeg, double lonDeg, double azimuthDeg, double distanceMeters) {
        double lat1 = Math.toRadians(latDeg);
        double lon1 = Math.toRadians(lonDeg);
        double alpha1 = Math.toRadians(azimuthDeg);

        double sinAlpha1 = Math.sin(alpha1);
        double cosAlpha1 = Math.cos(alpha1);

        double tanU1 = (1.0 - WGS84_F) * Math.tan(lat1);
        double cosU1 = 1.0 / Math.sqrt(1.0 + tanU1 * tanU1);
        double sinU1 = tanU1 * cosU1;
        double sigma1 = Math.atan2(tanU1, cosAlpha1);
        double sinAlpha = cosU1 * sinAlpha1;
        double cosSqAlpha = 1.0 - sinAlpha * sinAlpha;
        double uSq = cosSqAlpha * (WGS84_A * WGS84_A - WGS84_B * WGS84_B) / (WGS84_B * WGS84_B);
        double A = 1.0 + uSq / 16384.0 * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)));
        double B = uSq / 1024.0 * (256.0 + uSq * (-128.0 + uSq * (74.0 - 47.0 * uSq)));

        double sigma = distanceMeters / (WGS84_B * A);
        double sigmaP;
        double cos2SigmaM;
        double sinSigma;
        double cosSigma;

        do {
            cos2SigmaM = Math.cos(2.0 * sigma1 + sigma);
            sinSigma = Math.sin(sigma);
            cosSigma = Math.cos(sigma);
            double deltaSigma = B * sinSigma * (cos2SigmaM + B / 4.0 * (cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)
                    - B / 6.0 * cos2SigmaM * (-3.0 + 4.0 * sinSigma * sinSigma) * (-3.0 + 4.0 * cos2SigmaM * cos2SigmaM)));
            sigmaP = sigma;
            sigma = distanceMeters / (WGS84_B * A) + deltaSigma;
        } while (Math.abs(sigma - sigmaP) > 1e-12);

        double tmp = sinU1 * sinSigma - cosU1 * cosSigma * cosAlpha1;
        double lat2 = Math.atan2(
                sinU1 * cosSigma + cosU1 * sinSigma * cosAlpha1,
                (1.0 - WGS84_F) * Math.sqrt(sinAlpha * sinAlpha + tmp * tmp)
        );
        double lambda = Math.atan2(
                sinSigma * sinAlpha1,
                cosU1 * cosSigma - sinU1 * sinSigma * cosAlpha1
        );
        double C = WGS84_F / 16.0 * cosSqAlpha * (4.0 + WGS84_F * (4.0 - 3.0 * cosSqAlpha));
        double L = lambda - (1.0 - C) * WGS84_F * sinAlpha *
                (sigma + C * sinSigma * (cos2SigmaM + C * cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)));

        double lon2 = lon1 + L;
        return new Point(Math.toDegrees(lat2), Math.toDegrees(normalizeLonRad(lon2)));
    }

    public static InverseResult inverseWgs84(Point p1, Point p2) {
        double phi1 = Math.toRadians(p1.latDeg());
        double phi2 = Math.toRadians(p2.latDeg());
        double L = Math.toRadians(p2.lonDeg() - p1.lonDeg());

        double U1 = Math.atan((1.0 - WGS84_F) * Math.tan(phi1));
        double U2 = Math.atan((1.0 - WGS84_F) * Math.tan(phi2));
        double sinU1 = Math.sin(U1), cosU1 = Math.cos(U1);
        double sinU2 = Math.sin(U2), cosU2 = Math.cos(U2);

        double lambda = L;
        double lambdaPrev;
        double sinSigma;
        double cosSigma;
        double sigma;
        double sinAlpha;
        double cosSqAlpha;
        double cos2SigmaM;

        for (int i = 0; i < 100; i++) {
            double sinLambda = Math.sin(lambda);
            double cosLambda = Math.cos(lambda);
            sinSigma = Math.sqrt(
                    (cosU2 * sinLambda) * (cosU2 * sinLambda) +
                            (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda) * (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
            );
            if (sinSigma == 0.0) return new InverseResult(0.0, 0.0);

            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha;
            if (cosSqAlpha == 0.0) {
                cos2SigmaM = 0.0;
            } else {
                cos2SigmaM = cosSigma - 2.0 * sinU1 * sinU2 / cosSqAlpha;
            }

            double C = WGS84_F / 16.0 * cosSqAlpha * (4.0 + WGS84_F * (4.0 - 3.0 * cosSqAlpha));
            lambdaPrev = lambda;
            lambda = L + (1.0 - C) * WGS84_F * sinAlpha * (
                    sigma + C * sinSigma * (cos2SigmaM + C * cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM))
            );

            if (Math.abs(lambda - lambdaPrev) < 1e-12) {
                double uSq = cosSqAlpha * (WGS84_A * WGS84_A - WGS84_B * WGS84_B) / (WGS84_B * WGS84_B);
                double A = 1.0 + uSq / 16384.0 * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)));
                double B = uSq / 1024.0 * (256.0 + uSq * (-128.0 + uSq * (74.0 - 47.0 * uSq)));
                double deltaSigma = B * sinSigma * (
                        cos2SigmaM + B / 4.0 * (
                                cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM) -
                                        B / 6.0 * cos2SigmaM * (-3.0 + 4.0 * sinSigma * sinSigma) *
                                                (-3.0 + 4.0 * cos2SigmaM * cos2SigmaM)
                        )
                );
                double distance = WGS84_B * A * (sigma - deltaSigma);

                double y = Math.sin(lambda) * cosU2;
                double x = cosU1 * sinU2 - sinU1 * cosU2 * Math.cos(lambda);
                double initialBearing = Math.toDegrees(Math.atan2(y, x));
                if (initialBearing < 0.0) initialBearing += 360.0;

                return new InverseResult(distance, initialBearing);
            }
        }
        return inverseSphericalFallback(p1, p2);
    }

    public static double distanceWgs84Meters(Point p1, Point p2) {
        return inverseWgs84(p1, p2).distanceMeters();
    }

    private static double normalizeLonRad(double lonRad) {
        while (lonRad > Math.PI) lonRad -= 2.0 * Math.PI;
        while (lonRad < -Math.PI) lonRad += 2.0 * Math.PI;
        return lonRad;
    }

    private static InverseResult inverseSphericalFallback(Point p1, Point p2) {
        double lat1 = Math.toRadians(p1.latDeg());
        double lon1 = Math.toRadians(p1.lonDeg());
        double lat2 = Math.toRadians(p2.latDeg());
        double lon2 = Math.toRadians(p2.lonDeg());

        double dLat = lat2 - lat1;
        double dLon = normalizeLonRad(lon2 - lon1);
        double sinDLat2 = Math.sin(dLat * 0.5);
        double sinDLon2 = Math.sin(dLon * 0.5);
        double a = sinDLat2 * sinDLat2 + Math.cos(lat1) * Math.cos(lat2) * sinDLon2 * sinDLon2;
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
        double meanLat = 0.5 * (lat1 + lat2);
        double sinMeanLat = Math.sin(meanLat);
        double radius = WGS84_A * (1.0 - WGS84_E2) / Math.pow(1.0 - WGS84_E2 * sinMeanLat * sinMeanLat, 1.5);
        double distance = radius * c;

        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        if (bearing < 0.0) bearing += 360.0;
        if (!Double.isFinite(bearing)) bearing = 0.0;

        return new InverseResult(distance, bearing);
    }

    public record InverseResult(double distanceMeters, double initialBearingDeg) {}
}
