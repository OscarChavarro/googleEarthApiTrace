import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class KmlTurtlePlacemarkCounter {
    private static final String KML_RELATIVE_PATH = ".googleearth/myplaces.kml";
    private static final String TARGET_FOLDER_NAME = "turtle";

    static final class CountResult {
        private final boolean available;
        private final int count;

        private CountResult(boolean available, int count) {
            this.available = available;
            this.count = count;
        }

        static CountResult available(int count) {
            return new CountResult(true, count);
        }

        static CountResult unavailable() {
            return new CountResult(false, 0);
        }

        boolean isAvailable() {
            return available;
        }

        int getCount() {
            return count;
        }
    }

    CountResult countFromUserHome() {
        Path kmlPath = Path.of(System.getProperty("user.home"), KML_RELATIVE_PATH);
        if (!Files.exists(kmlPath)) {
            System.err.println("KML file not found: " + kmlPath);
            return CountResult.unavailable();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(kmlPath.toFile());
            NodeList folders = document.getElementsByTagName("Folder");

            for (int i = 0; i < folders.getLength(); i++) {
                Node folderNode = folders.item(i);
                if (folderNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }

                Element folder = (Element) folderNode;
                if (!TARGET_FOLDER_NAME.equals(readDirectChildText(folder, "name"))) {
                    continue;
                }

                return CountResult.available(countDirectPlacemarkChildren(folder));
            }

            System.err.println("Folder '" + TARGET_FOLDER_NAME + "' not found in KML file: " + kmlPath);
        } catch (Exception ex) {
            System.err.println("Failed to parse KML file " + kmlPath + ": " + ex.getMessage());
        }

        return CountResult.unavailable();
    }

    private int countDirectPlacemarkChildren(Element folder) {
        int count = 0;
        NodeList children = folder.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "Placemark".equals(child.getNodeName())) {
                count++;
            }
        }
        return count;
    }

    private String readDirectChildText(Element parent, String childName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && childName.equals(child.getNodeName())) {
                return child.getTextContent() == null ? "" : child.getTextContent().trim();
            }
        }
        return "";
    }
}
