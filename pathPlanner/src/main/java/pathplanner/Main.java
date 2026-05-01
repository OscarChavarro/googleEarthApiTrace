package pathplanner;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Main {
    private static final String KML_PATH = "/home/jedilink/.googleearth/myplaces.kml";
    private static final String TURTLE_FOLDER_NAME = "turtle";
    private static final String TURTLE_STYLE_ID = "turtleLineStyle";
    private static final String GX_NS = "http://www.google.com/kml/ext/2.2";

    private static final double WGS84_A = 6378137.0;
    private static final double WGS84_F = 1.0 / 298.257223563;
    private static final double WGS84_B = WGS84_A * (1.0 - WGS84_F);

    private record Point(double latDeg, double lonDeg) {}
    private record InverseResult(double distanceMeters, double initialBearingDeg) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Uso: gradle run --args=\"<lat> <lon> <distancia_paso_m> <distancia_maxima_m>\"");
            System.exit(1);
        }

        double lat = Double.parseDouble(args[0]);
        double lon = Double.parseDouble(args[1]);
        double stepMeters = Double.parseDouble(args[2]);
        double maxDistanceMeters = Double.parseDouble(args[3]);
        if (stepMeters <= 0.0) {
            throw new IllegalArgumentException("La distancia debe ser positiva.");
        }
        if (maxDistanceMeters <= 0.0) {
            throw new IllegalArgumentException("La distancia máxima debe ser positiva.");
        }

        List<Point> curve = buildTurtleCurve(lat, lon, stepMeters, maxDistanceMeters);
        List<Point> markerPoints = samplePointsOnCurve(curve, 2.0 * stepMeters);
        updateKml(curve, markerPoints);

        System.out.println("Curva turtle generada con " + curve.size() + " vértices y " + markerPoints.size() + " puntos z en " + KML_PATH);
    }

    private static List<Point> buildTurtleCurve(double startLat, double startLon, double stepMeters, double maxDistanceFromStartMeters) {
        List<Point> points = new ArrayList<>();
        Point start = new Point(startLat, startLon);
        Point current = start;
        points.add(current);

        int lengthUnits = 1;
        int dir = 0;

        while (true) {
            for (int repeat = 0; repeat < 2; repeat++) {
                double legMeters = lengthUnits * stepMeters;
                Point legEnd = moveByMeters(current, dir, legMeters);
                double endDistance = distanceWgs84Meters(start, legEnd);
                if (endDistance <= maxDistanceFromStartMeters) {
                    current = legEnd;
                    points.add(current);
                } else {
                    double allowed = maxDistanceAlongDirection(current, start, dir, legMeters, maxDistanceFromStartMeters);
                    if (allowed > 1e-6) {
                        current = moveByMeters(current, dir, allowed);
                        points.add(current);
                    }
                    return points;
                }
                dir = (dir + 1) % 4;
            }
            lengthUnits++;
        }
    }

    private static double maxDistanceAlongDirection(
            Point from,
            Point start,
            int dir,
            double maxLegMeters,
            double maxDistanceFromStartMeters
    ) {
        double low = 0.0;
        double high = maxLegMeters;
        for (int i = 0; i < 60; i++) {
            double mid = (low + high) * 0.5;
            Point probe = moveByMeters(from, dir, mid);
            double probeDistance = distanceWgs84Meters(start, probe);
            if (probeDistance <= maxDistanceFromStartMeters) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static List<Point> samplePointsOnCurve(List<Point> curvePoints, double maxSpacingMeters) {
        List<Point> out = new ArrayList<>();
        if (curvePoints.isEmpty()) return out;

        out.add(curvePoints.get(0));
        for (int i = 1; i < curvePoints.size(); i++) {
            Point a = curvePoints.get(i - 1);
            Point b = curvePoints.get(i);
            InverseResult inv = inverseWgs84(a, b);
            if (inv.distanceMeters <= 1e-9) {
                continue;
            }

            int parts = Math.max(1, (int) Math.ceil(inv.distanceMeters / maxSpacingMeters));
            double partLen = inv.distanceMeters / parts;
            for (int j = 1; j <= parts; j++) {
                if (j == parts) {
                    out.add(b);
                } else {
                    out.add(destinationWgs84(a.latDeg, a.lonDeg, inv.initialBearingDeg, partLen * j));
                }
            }
        }
        return out;
    }

    private static Point moveByMeters(Point start, int dir, double meters) {
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
        return destinationWgs84(start.latDeg, start.lonDeg, azimuth, meters);
    }

    private static Point destinationWgs84(double latDeg, double lonDeg, double azimuthDeg, double distanceMeters) {
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

    private static double normalizeLonRad(double lonRad) {
        while (lonRad > Math.PI) lonRad -= 2.0 * Math.PI;
        while (lonRad < -Math.PI) lonRad += 2.0 * Math.PI;
        return lonRad;
    }

    private static InverseResult inverseWgs84(Point p1, Point p2) {
        double phi1 = Math.toRadians(p1.latDeg);
        double phi2 = Math.toRadians(p2.latDeg);
        double L = Math.toRadians(p2.lonDeg - p1.lonDeg);

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
        throw new IllegalStateException("No convergió la inversa geodésica en WGS84.");
    }

    private static double distanceWgs84Meters(Point p1, Point p2) {
        return inverseWgs84(p1, p2).distanceMeters;
    }

    private static void updateKml(List<Point> points, List<Point> markerPoints) throws Exception {
        File file = new File(KML_PATH);
        if (!file.exists()) {
            throw new IllegalStateException("No existe el archivo KML: " + KML_PATH);
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(file);

        Element documentElement = findFirstElementByLocalName(doc.getDocumentElement(), "Document");
        if (documentElement == null) {
            throw new IllegalStateException("KML inválido: no se encontró nodo Document");
        }

        removeFolderByName(documentElement, TURTLE_FOLDER_NAME);

        Element folder = createElementSameNs(doc, documentElement, "Folder");
        Element folderName = createElementSameNs(doc, documentElement, "name");
        folderName.setTextContent(TURTLE_FOLDER_NAME);
        folder.appendChild(folderName);

        Element pathPlacemark = createElementSameNs(doc, documentElement, "Placemark");
        Element placemarkName = createElementSameNs(doc, documentElement, "name");
        placemarkName.setTextContent("turtle_path");
        pathPlacemark.appendChild(placemarkName);

        Element styleUrl = createElementSameNs(doc, documentElement, "styleUrl");
        styleUrl.setTextContent("#" + TURTLE_STYLE_ID);
        pathPlacemark.appendChild(styleUrl);

        Element lineString = createElementSameNs(doc, documentElement, "LineString");
        Element tessellate = createElementSameNs(doc, documentElement, "tessellate");
        tessellate.setTextContent("1");
        lineString.appendChild(tessellate);

        Element coordinates = createElementSameNs(doc, documentElement, "coordinates");
        coordinates.setTextContent(buildCoordinatesText(points));
        lineString.appendChild(coordinates);
        pathPlacemark.appendChild(lineString);
        folder.appendChild(pathPlacemark);

        appendMarkerPlacemarks(doc, documentElement, folder, markerPoints);

        documentElement.appendChild(folder);
        upsertTurtleStyle(doc, documentElement);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.transform(new DOMSource(doc), new StreamResult(file));
    }

    private static void appendMarkerPlacemarks(Document doc, Element documentElement, Element folder, List<Point> markerPoints) {
        for (int i = 0; i < markerPoints.size(); i++) {
            Point p = markerPoints.get(i);
            String name = String.format("z%04d", i + 1);

            Element placemark = createElementSameNs(doc, documentElement, "Placemark");

            Element placemarkName = createElementSameNs(doc, documentElement, "name");
            placemarkName.setTextContent(name);
            placemark.appendChild(placemarkName);

            Element lookAt = createElementSameNs(doc, documentElement, "LookAt");
            Element lon = createElementSameNs(doc, documentElement, "longitude");
            lon.setTextContent(Double.toString(p.lonDeg));
            Element lat = createElementSameNs(doc, documentElement, "latitude");
            lat.setTextContent(Double.toString(p.latDeg));
            Element alt = createElementSameNs(doc, documentElement, "altitude");
            alt.setTextContent("0");
            Element heading = createElementSameNs(doc, documentElement, "heading");
            heading.setTextContent("-0.01019120726538249");
            Element tilt = createElementSameNs(doc, documentElement, "tilt");
            tilt.setTextContent("0");
            Element range = createElementSameNs(doc, documentElement, "range");
            range.setTextContent("184.6844034672007");
            Element gxAltitudeMode = doc.createElementNS(GX_NS, "gx:altitudeMode");
            gxAltitudeMode.setTextContent("relativeToSeaFloor");
            lookAt.appendChild(lon);
            lookAt.appendChild(lat);
            lookAt.appendChild(alt);
            lookAt.appendChild(heading);
            lookAt.appendChild(tilt);
            lookAt.appendChild(range);
            lookAt.appendChild(gxAltitudeMode);
            placemark.appendChild(lookAt);

            Element styleUrl = createElementSameNs(doc, documentElement, "styleUrl");
            styleUrl.setTextContent("#m_ylw-pushpin");
            placemark.appendChild(styleUrl);

            Element point = createElementSameNs(doc, documentElement, "Point");
            Element gxDrawOrder = doc.createElementNS(GX_NS, "gx:drawOrder");
            gxDrawOrder.setTextContent("1");
            Element coordinates = createElementSameNs(doc, documentElement, "coordinates");
            coordinates.setTextContent(p.lonDeg + "," + p.latDeg + ",0");
            point.appendChild(gxDrawOrder);
            point.appendChild(coordinates);
            placemark.appendChild(point);

            folder.appendChild(placemark);
        }
    }

    private static void upsertTurtleStyle(Document doc, Element documentElement) {
        NodeList children = documentElement.getChildNodes();
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Style".equals(e.getLocalName()) && !"Style".equals(e.getNodeName())) continue;
            String id = e.getAttribute("id");
            if (TURTLE_STYLE_ID.equals(id)) {
                toRemove.add(e);
            }
        }
        for (Node n : toRemove) {
            documentElement.removeChild(n);
        }

        Element style = createElementSameNs(doc, documentElement, "Style");
        style.setAttribute("id", TURTLE_STYLE_ID);
        Element lineStyle = createElementSameNs(doc, documentElement, "LineStyle");
        Element color = createElementSameNs(doc, documentElement, "color");
        color.setTextContent("ff0000ff");
        Element width = createElementSameNs(doc, documentElement, "width");
        width.setTextContent("4");
        lineStyle.appendChild(color);
        lineStyle.appendChild(width);
        style.appendChild(lineStyle);
        documentElement.appendChild(style);
    }

    private static String buildCoordinatesText(List<Point> points) {
        StringBuilder sb = new StringBuilder();
        for (Point p : points) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p.lonDeg).append(',').append(p.latDeg).append(',').append(0);
        }
        return sb.toString();
    }

    private static void removeFolderByName(Element documentElement, String folderName) {
        NodeList folders = documentElement.getChildNodes();
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < folders.getLength(); i++) {
            Node n = folders.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Folder".equals(e.getLocalName()) && !"Folder".equals(e.getNodeName())) continue;

            Element name = findFirstElementByLocalName(e, "name");
            if (name != null && folderName.equals(name.getTextContent())) {
                toRemove.add(e);
            }
        }
        for (Node n : toRemove) {
            documentElement.removeChild(n);
        }
    }

    private static Element findFirstElementByLocalName(Element root, String localName) {
        if (root == null) return null;
        if (localName.equals(root.getLocalName()) || localName.equals(root.getNodeName())) {
            return root;
        }
        NodeList all = root.getElementsByTagNameNS("*", localName);
        if (all.getLength() > 0) {
            return (Element) all.item(0);
        }
        NodeList plain = root.getElementsByTagName(localName);
        if (plain.getLength() > 0) {
            return (Element) plain.item(0);
        }
        return null;
    }

    private static Element createElementSameNs(Document doc, Element ref, String localName) {
        String ns = ref.getNamespaceURI();
        if (ns != null && !ns.isEmpty()) {
            return doc.createElementNS(ns, localName);
        }
        return doc.createElement(localName);
    }
}
