package pathplanner.io;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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
import pathplanner.model.Point;

public final class KmlPersistence {
    private static final String GX_NS = "http://www.google.com/kml/ext/2.2";
    private static final String MARKER_LOOKAT_HEADING = "0";
    private static final String MARKER_LOOKAT_TILT = "0";
    private static final String MARKER_LOOKAT_RANGE = "184.6844034672007";

    public void updateKml(String kmlPath, String turtleFolderName, String turtleStyleId, List<Point> points, List<Point> markerPoints) throws Exception {
        File file = new File(kmlPath);
        if (!file.exists()) {
            throw new IllegalStateException("KML file does not exist: " + kmlPath);
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(file);
        removeWhitespaceTextNodes(doc.getDocumentElement());

        Element documentElement = findFirstElementByLocalName(doc.getDocumentElement(), "Document");
        if (documentElement == null) {
            throw new IllegalStateException("Invalid KML: Document node was not found");
        }

        removeFoldersByName(documentElement, turtleFolderName);

        Element folder = createElementSameNs(doc, documentElement, "Folder");
        Element folderName = createElementSameNs(doc, documentElement, "name");
        folderName.setTextContent(turtleFolderName);
        folder.appendChild(folderName);

        Element pathPlacemark = createElementSameNs(doc, documentElement, "Placemark");
        Element placemarkName = createElementSameNs(doc, documentElement, "name");
        placemarkName.setTextContent("turtle_path");
        pathPlacemark.appendChild(placemarkName);

        Element styleUrl = createElementSameNs(doc, documentElement, "styleUrl");
        styleUrl.setTextContent("#" + turtleStyleId);
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
        upsertTurtleStyle(doc, documentElement, turtleStyleId);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.transform(new DOMSource(doc), new StreamResult(file));
    }

    private void appendMarkerPlacemarks(Document doc, Element documentElement, Element folder, List<Point> markerPoints) {
        for (int i = 0; i < markerPoints.size(); i++) {
            Point p = markerPoints.get(i);
            String name = String.format("z%04d", i + 1);

            Element placemark = createElementSameNs(doc, documentElement, "Placemark");

            Element placemarkName = createElementSameNs(doc, documentElement, "name");
            placemarkName.setTextContent(name);
            placemark.appendChild(placemarkName);

            Element lookAt = createElementSameNs(doc, documentElement, "LookAt");
            String lonText = Double.toString(p.lonDeg());
            String latText = Double.toString(p.latDeg());
            String altitudeText = Double.toString(p.altitudeMeters() * 3.0);
            Element lon = createElementSameNs(doc, documentElement, "longitude");
            lon.setTextContent(lonText);
            Element lat = createElementSameNs(doc, documentElement, "latitude");
            lat.setTextContent(latText);
            Element alt = createElementSameNs(doc, documentElement, "altitude");
            alt.setTextContent(altitudeText);
            Element heading = createElementSameNs(doc, documentElement, "heading");
            heading.setTextContent(MARKER_LOOKAT_HEADING);
            Element tilt = createElementSameNs(doc, documentElement, "tilt");
            tilt.setTextContent(MARKER_LOOKAT_TILT);
            Element range = createElementSameNs(doc, documentElement, "range");
            range.setTextContent(MARKER_LOOKAT_RANGE);
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
            coordinates.setTextContent(lonText + "," + latText + "," + altitudeText);
            point.appendChild(gxDrawOrder);
            point.appendChild(coordinates);
            placemark.appendChild(point);

            folder.appendChild(placemark);
        }
    }

    private void upsertTurtleStyle(Document doc, Element documentElement, String turtleStyleId) {
        NodeList children = documentElement.getChildNodes();
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Style".equals(e.getLocalName()) && !"Style".equals(e.getNodeName())) continue;
            String id = e.getAttribute("id");
            if (turtleStyleId.equals(id)) {
                toRemove.add(e);
            }
        }
        for (Node n : toRemove) {
            documentElement.removeChild(n);
        }

        Element style = createElementSameNs(doc, documentElement, "Style");
        style.setAttribute("id", turtleStyleId);
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

    private String buildCoordinatesText(List<Point> points) {
        StringBuilder sb = new StringBuilder();
        for (Point p : points) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p.lonDeg()).append(',').append(p.latDeg()).append(',').append(p.altitudeMeters());
        }
        return sb.toString();
    }

    private void removeFoldersByName(Element documentElement, String folderName) {
        NodeList folders = documentElement.getElementsByTagNameNS("*", "Folder");
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < folders.getLength(); i++) {
            Node n = folders.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Folder".equals(e.getLocalName()) && !"Folder".equals(e.getNodeName())) continue;

            Element name = findDirectChildElementByLocalName(e, "name");
            if (name != null && folderName.equals(name.getTextContent())) {
                toRemove.add(e);
            }
        }
        for (Node n : toRemove) {
            Node parent = n.getParentNode();
            if (parent != null) {
                parent.removeChild(n);
            }
        }
    }

    private Element findDirectChildElementByLocalName(Element root, String localName) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (localName.equals(e.getLocalName()) || localName.equals(e.getNodeName())) {
                return e;
            }
        }
        return null;
    }

    private void removeWhitespaceTextNodes(Node node) {
        NodeList children = node.getChildNodes();
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().trim().isEmpty()) {
                toRemove.add(child);
                continue;
            }
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                removeWhitespaceTextNodes(child);
            }
        }
        for (Node whitespace : toRemove) {
            node.removeChild(whitespace);
        }
    }

    private Element findFirstElementByLocalName(Element root, String localName) {
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

    private Element createElementSameNs(Document doc, Element ref, String localName) {
        String ns = ref.getNamespaceURI();
        if (ns != null && !ns.isEmpty()) {
            return doc.createElementNS(ns, localName);
        }
        return doc.createElement(localName);
    }
}
