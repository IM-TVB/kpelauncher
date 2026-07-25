package site.kpeclub.launcher.util;

/**
 * Small shared helper for Fabric-style library entries, which specify libraries as
 * plain Maven coordinates ("group:artifact:version[:classifier]") rather than Mojang's
 * usual pre-resolved downloads.artifact.path/url — both the downloader and the launcher
 * need to derive the same relative path from a coordinate.
 */
public class MavenUtil {

    /** Converts "net.fabricmc:fabric-loader:0.16.9" into
     *  "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar" (standard Maven repo layout). */
    public static String coordinateToPath(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3) return null;
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }
}
