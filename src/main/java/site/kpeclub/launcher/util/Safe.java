package site.kpeclub.launcher.util;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/** Small input-safety helpers used wherever data comes from outside the launcher
 *  (text fields, internet, modpack files, files on disk). */
public final class Safe {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+\\- ]{0,99}$");
    private static final Pattern GUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Hosts a mod or modpack file may be downloaded from (same list Modrinth allows for modpacks). */
    private static final Set<String> TRUSTED_FILE_HOSTS = Set.of(
            "cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com");

    private Safe() {}

    /** Minecraft's own rule: 3-16 letters, digits or underscore. Stops argument injection through the name. */
    public static boolean isValidUsername(String name) {
        return name != null && USERNAME.matcher(name).matches();
    }

    /** Version ids / loader versions end up in folder names and URLs, so only plain characters are allowed. */
    public static boolean isSafeId(String id) {
        return id != null && ID.matcher(id).matches() && !id.contains("..") && !id.endsWith(" ") && !id.endsWith(".");
    }

    public static String requireSafeId(String id, String what) {
        if (!isSafeId(id)) throw new SecurityException("Refusing unsafe " + what + ": " + id);
        return id;
    }

    public static boolean isGuid(String s) {
        return s != null && GUID.matcher(s.trim()).matches();
    }

    /** Only https downloads — never plain http (anyone on the network could swap the file). */
    public static void requireHttps(String url) {
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) {
            throw new SecurityException("Refusing non-HTTPS download: " + url);
        }
    }

    public static boolean isTrustedFileHost(String url) {
        try {
            URI u = URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                    && TRUSTED_FILE_HOSTS.contains(u.getHost().toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    /** Resolves rel inside base, or throws if it would escape (.., absolute path, drive, ':' streams). */
    public static Path resolveInside(Path base, String rel) {
        if (rel == null || rel.isBlank() || rel.indexOf(':') >= 0 || rel.indexOf('\0') >= 0) {
            throw new SecurityException("Unsafe path: " + rel);
        }
        String normalizedRel = rel.replace('\\', '/');
        if (normalizedRel.startsWith("/")) {
            throw new SecurityException("Unsafe absolute path: " + rel);
        }
        for (String seg : normalizedRel.split("/")) {
            if ("..".equals(seg)) {
                throw new SecurityException("Path traversal segment refused: " + rel);
            }
        }
        Path b = base.toAbsolutePath().normalize();
        Path r = b.resolve(normalizedRel).normalize();
        if (!r.startsWith(b) || r.equals(b)) throw new SecurityException("Path escapes its folder: " + rel);
        return r;
    }

    /** Full path of a Windows system program, so a fake copy next to the launcher is never run. */
    public static String systemExe(String relativeToSystem32) {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) root = "C:\\Windows";
        return root + "\\System32\\" + relativeToSystem32;
    }
}
