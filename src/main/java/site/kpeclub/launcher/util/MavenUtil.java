package site.kpeclub.launcher.util;

/**
 * Small shared helper for Fabric-style library entries, which specify libraries as
 * plain Maven coordinates ("group:artifact:version[:classifier][@ext]") rather than Mojang's
 * usual pre-resolved downloads.artifact.path/url — both the downloader and the launcher
 * need to derive the same relative path from a coordinate.
 */
public class MavenUtil {

    /** Converts "net.fabricmc:fabric-loader:0.16.9" (or "group:artifact:version:classifier@ext") into
     *  "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar" (standard Maven repo layout). */
    public static String coordinateToPath(String coordinate) {
        if (coordinate == null || coordinate.isBlank()) return null;
        String ext = "jar";
        String coord = coordinate.trim();
        int atIdx = coord.indexOf('@');
        if (atIdx > 0) {
            ext = coord.substring(atIdx + 1);
            coord = coord.substring(0, atIdx);
        }
        String[] parts = coord.split(":");
        if (parts.length < 3) return null;
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 && !parts[3].isEmpty() ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + ext;
    }
}
