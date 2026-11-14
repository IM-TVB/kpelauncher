package site.kpeclub.launcher.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.*;

/**
 * Works out which library files a version needs on THIS PC (32-bit or 64-bit), in one place,
 * so the downloader and the game launcher always agree.
 */
public final class LibraryResolver {

    /** One file a version needs.
     *  @param nativeJar true if this is a natives jar that must be extracted (old-style natives). */
    public record LibFile(String key, Path path, String url, String sha1, long size,
                          boolean nativeJar, List<String> extractExcludes) {}

    private LibraryResolver() {}

    public static List<LibFile> resolve(JsonObject versionJson, PlatformInfo.Arch arch) {
        // LinkedHashMap + "key without version": if a loader (Fabric) ships a newer copy of a
        // library the vanilla json also has, the later one replaces the earlier — otherwise two
        // versions of the same jar end up on the classpath and the game can crash.
        Map<String, LibFile> out = new LinkedHashMap<>();
        if (!versionJson.has("libraries")) return new ArrayList<>();

        for (JsonElement el : versionJson.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            if (!Rules.allowed(lib, arch)) continue;

            String name = lib.has("name") ? lib.get("name").getAsString() : null;
            if (!matchesModernNativeArch(name, arch)) continue;

            if (lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");

                if (downloads.has("artifact")) {
                    JsonObject a = downloads.getAsJsonObject("artifact");
                    String path = a.has("path") && !a.get("path").isJsonNull()
                            ? a.get("path").getAsString() : pathFromName(name);
                    if (path != null && !path.isBlank()) {
                        out.put(keyOf(name, false), new LibFile(keyOf(name, false),
                                Safe.resolveInside(LauncherConfig.LIBRARIES_DIR, path),
                                str(a, "url"), str(a, "sha1"), lng(a, "size"), false, List.of()));
                    }
                }

                if (downloads.has("classifiers") && lib.has("natives")) {
                    JsonObject natives = lib.getAsJsonObject("natives");
                    if (natives.has("windows")) {
                        // Old versions: "natives-windows-${arch}" -> "natives-windows-32" / "-64".
                        String classifier = natives.get("windows").getAsString().replace("${arch}", arch.bits);
                        JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                        if (classifiers.has(classifier)) {
                            JsonObject n = classifiers.getAsJsonObject(classifier);
                            String path = n.has("path") && !n.get("path").isJsonNull()
                                    ? n.get("path").getAsString()
                                    : (name != null ? pathFromName(name + ":" + classifier) : null);
                            if (path != null && !path.isBlank()) {
                                List<String> excludes = new ArrayList<>();
                                if (lib.has("extract") && lib.getAsJsonObject("extract").has("exclude")) {
                                    for (JsonElement ex : lib.getAsJsonObject("extract").getAsJsonArray("exclude")) {
                                        excludes.add(ex.getAsString());
                                    }
                                }
                                out.put(keyOf(name, true), new LibFile(keyOf(name, true),
                                        Safe.resolveInside(LauncherConfig.LIBRARIES_DIR, path),
                                        str(n, "url"), str(n, "sha1"), lng(n, "size"), true, excludes));
                            }
                        }
                    }
                }
            } else if (name != null) {
                // Maven-coordinate-only library (Fabric style, with a repo "url"; or OptiFine style
                // with no url, which already exists on disk).
                String rel = pathFromName(name);
                if (rel != null) {
                    String base = lib.has("url") ? lib.get("url").getAsString() : null;
                    String url = null;
                    if (base != null && !base.isBlank()) {
                        url = base.endsWith("/") ? base + rel : base + "/" + rel;
                    }
                    out.put(keyOf(name, false), new LibFile(keyOf(name, false),
                            Safe.resolveInside(LauncherConfig.LIBRARIES_DIR, rel), url, str(lib, "sha1"), 0, false, List.of()));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Modern Minecraft (1.19+) lists native libraries as regular artifacts with classifiers like
     * ":natives-windows", ":natives-windows-x86", and ":natives-windows-arm64", all with a rule
     * that only checks os.name == "windows" (no os.arch in the rule). Filter out the wrong CPU's
     * native jars so 64-bit doesn't download/load x86/arm64 natives and 32-bit doesn't load x64/arm64.
     */
    private static boolean matchesModernNativeArch(String name, PlatformInfo.Arch arch) {
        if (name == null) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(":natives-windows");
        if (idx < 0) return true;
        String suffix = lower.substring(idx + ":natives-windows".length());
        if (suffix.startsWith("@")) suffix = "";
        return switch (arch) {
            case X86 -> suffix.equals("-x86") || suffix.startsWith("-x86:");
            case X64 -> suffix.isEmpty();
            case ARM64 -> suffix.equals("-arm64") || suffix.startsWith("-arm64:");
        };
    }

    /** group:artifact[:classifier] — the version number is left out on purpose. */
    private static String keyOf(String name, boolean nativeFlag) {
        if (name == null) return UUID.randomUUID().toString();
        String coord = name;
        int atIdx = coord.indexOf('@');
        if (atIdx > 0) coord = coord.substring(0, atIdx);
        String[] p = coord.split(":");
        String k = p.length >= 2 ? p[0] + ":" + p[1] : coord;
        if (p.length > 3 && !p[3].isEmpty()) k += ":" + p[3];
        return nativeFlag ? k + ":natives" : k;
    }

    private static String pathFromName(String name) {
        return name == null ? null : MavenUtil.coordinateToPath(name);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static long lng(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0;
    }

    /** Unused helper kept tiny on purpose: JSON array to string list. */
    static List<String> strings(JsonArray arr) {
        List<String> l = new ArrayList<>();
        for (JsonElement e : arr) l.add(e.getAsString());
        return l;
    }
}
