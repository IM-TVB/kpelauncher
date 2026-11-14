package site.kpeclub.launcher.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.model.ModInfo;
import site.kpeclub.launcher.model.ResourcePackInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scans .minecraft for installed content beyond just versions — mods and resource packs —
 * reading real metadata from inside each file where possible (Fabric's fabric.mod.json,
 * Forge's META-INF/mods.toml or the older mcmod.info, and resource packs' pack.mcmeta).
 */
public class ContentScanner {

    public static Path modsDir() {
        return LauncherConfig.GAME_DIR.resolve("mods");
    }

    public static Path resourcePacksDir() {
        return LauncherConfig.GAME_DIR.resolve("resourcepacks");
    }

    /** Scans .minecraft/mods for .jar files and reads their metadata where possible. */
    public static List<ModInfo> scanMods() {
        List<ModInfo> mods = new ArrayList<>();
        Path dir = modsDir();
        if (!Files.exists(dir)) return mods;

        try (var stream = Files.list(dir)) {
            for (Path path : (Iterable<Path>) stream.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))::iterator) {
                mods.add(readModJar(path));
            }
        } catch (IOException e) {
            // Return whatever we found before the error rather than nothing at all
        }
        return mods;
    }

    /** Scans .minecraft/resourcepacks for both folders and .zip packs. */
    public static List<ResourcePackInfo> scanResourcePacks() {
        List<ResourcePackInfo> packs = new ArrayList<>();
        Path dir = resourcePacksDir();
        if (!Files.exists(dir)) return packs;

        try (var stream = Files.list(dir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) {
                    packs.add(readResourcePackFolder(path));
                } else if (path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    packs.add(readResourcePackZip(path));
                }
            }
        } catch (IOException e) {
            // Return whatever we found before the error
        }
        return packs;
    }

    // ---------------- Mod jar reading ----------------

    private static ModInfo readModJar(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        long size = sizeOf(jarPath);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Try Fabric first: fabric.mod.json at jar root
            JarEntry fabricEntry = jar.getJarEntry("fabric.mod.json");
            if (fabricEntry != null) {
                try (InputStream in = jar.getInputStream(fabricEntry)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                    String modId = json.has("id") ? json.get("id").getAsString() : null;
                    String name = json.has("name") ? json.get("name").getAsString() : modId;
                    String version = json.has("version") ? json.get("version").getAsString() : null;
                    return new ModInfo(fileName, modId, name, version, "Fabric", size);
                }
            }

            // Modern Forge (META-INF/mods.toml) and NeoForge 1.20.5+ (META-INF/neoforge.mods.toml).
            // Minimal manual TOML read, limited to the [[mods]] section so a dependency's
            // modId/version is never mistaken for the mod's own.
            for (String[] t : new String[][]{{"META-INF/neoforge.mods.toml", "NeoForge"}, {"META-INF/mods.toml", "Forge"}}) {
                JarEntry tomlEntry = jar.getJarEntry(t[0]);
                if (tomlEntry == null) continue;
                try (InputStream in = jar.getInputStream(tomlEntry)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String modId = extractTomlModValue(content, "modId");
                    String name = extractTomlModValue(content, "displayName");
                    String version = extractTomlModValue(content, "version");
                    if (version == null && jar.getManifest() != null) {
                        // version = "${file.jarVersion}" means: take it from the jar manifest
                        version = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
                    }
                    return new ModInfo(fileName, modId, name != null ? name : modId, version, t[1], size);
                }
            }

            // Try legacy Forge: mcmod.info (JSON array)
            JarEntry legacyForgeEntry = jar.getJarEntry("mcmod.info");
            if (legacyForgeEntry != null) {
                try (InputStream in = jar.getInputStream(legacyForgeEntry)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    var root = JsonParser.parseString(content);
                    // Two shapes exist: a plain array, or {"modListVersion":2,"modList":[...]}
                    var arr = root.isJsonObject() && root.getAsJsonObject().has("modList")
                            ? root.getAsJsonObject().getAsJsonArray("modList") : root.getAsJsonArray();
                    if (arr.size() > 0) {
                        JsonObject json = arr.get(0).getAsJsonObject();
                        String modId = json.has("modid") ? json.get("modid").getAsString() : null;
                        String name = json.has("name") ? json.get("name").getAsString() : modId;
                        String version = json.has("version") ? json.get("version").getAsString() : null;
                        return new ModInfo(fileName, modId, name, version, "Forge", size);
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to the "unknown metadata" case below — a mod jar existing but
            // being unreadable shouldn't crash the whole scan.
        }

        return new ModInfo(fileName, null, null, null, "Unknown", size);
    }

    /** Reads key = "value" from the FIRST [[mods]] section only (stops at the next [[...]] header). */
    private static String extractTomlModValue(String toml, String key) {
        boolean inMods = false;
        for (String line : toml.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("[")) {
                if (inMods) return null;              // the [[mods]] section ended without the key
                inMods = t.replace(" ", "").equals("[[mods]]");
                continue;
            }
            if (!inMods) continue;
            int eq = t.indexOf('=');
            if (eq <= 0 || !t.substring(0, eq).trim().equals(key)) continue;
            String value = t.substring(eq + 1).trim();
            if (value.startsWith("\"")) {                 // "text"  # optional comment
                int close = value.indexOf('"', 1);
                value = close > 0 ? value.substring(1, close) : value.substring(1);
            } else {
                int hash = value.indexOf('#');            // unquoted value + trailing comment
                if (hash >= 0) value = value.substring(0, hash).trim();
            }
            if (!value.isEmpty() && !value.startsWith("$")) return value; // skip ${file.jarVersion}
            return null;
        }
        return null;
    }

    // ---------------- Resource pack reading ----------------

    private static ResourcePackInfo readResourcePackFolder(Path dir) {
        long size = folderSize(dir);
        Path mcmeta = dir.resolve("pack.mcmeta");
        String description = readPackDescription(mcmeta);
        return new ResourcePackInfo(dir.getFileName().toString(), description, false, size);
    }

    private static ResourcePackInfo readResourcePackZip(Path zipPath) {
        long size = sizeOf(zipPath);
        String description = null;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zip.getEntry("pack.mcmeta");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    description = parsePackMcmeta(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            // Unreadable zip — still list it, just without a description
        }
        return new ResourcePackInfo(zipPath.getFileName().toString(), description, true, size);
    }

    private static String readPackDescription(Path mcmetaFile) {
        if (!Files.exists(mcmetaFile)) return null;
        try {
            return parsePackMcmeta(Files.readString(mcmetaFile));
        } catch (IOException e) {
            return null;
        }
    }

    /** Pack descriptions can be a string, a JSON array or a text-component object: get plain text from any. */
    private static String flattenTextComponent(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString().replaceAll("\u00a7.", "");
        StringBuilder sb = new StringBuilder();
        if (el.isJsonArray()) {
            for (var x : el.getAsJsonArray()) sb.append(flattenTextComponent(x));
        } else if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("text")) sb.append(flattenTextComponent(o.get("text")));
            else if (o.has("fallback")) sb.append(flattenTextComponent(o.get("fallback")));
            else if (o.has("translate")) sb.append(flattenTextComponent(o.get("translate")));
            if (o.has("extra")) sb.append(flattenTextComponent(o.get("extra")));
        }
        return sb.toString();
    }

    private static String parsePackMcmeta(String content) {
        try {
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (json.has("pack") && json.getAsJsonObject("pack").has("description")) {
                String text = flattenTextComponent(json.getAsJsonObject("pack").get("description"));
                return text.isBlank() ? null : text;
            }
        } catch (Exception e) {
            // Malformed pack.mcmeta — not fatal, just no description
        }
        return null;
    }

    // ---------------- Size helpers ----------------

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long folderSize(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(ContentScanner::sizeOf)
                    .sum();
        } catch (Exception e) {
            return 0;
        }
    }
}
