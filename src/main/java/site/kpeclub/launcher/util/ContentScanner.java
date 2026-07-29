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
            for (Path path : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".jar"))::iterator) {
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
                } else if (path.toString().endsWith(".zip")) {
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

            // Try modern Forge: META-INF/mods.toml (TOML — we do a minimal manual parse,
            // just pulling out modId/version/displayName lines rather than pulling in a
            // full TOML library for a handful of fields).
            JarEntry forgeEntry = jar.getJarEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                try (InputStream in = jar.getInputStream(forgeEntry)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String modId = extractTomlValue(content, "modId");
                    String name = extractTomlValue(content, "displayName");
                    String version = extractTomlValue(content, "version");
                    return new ModInfo(fileName, modId, name != null ? name : modId, version, "Forge", size);
                }
            }

            // Try legacy Forge: mcmod.info (JSON array)
            JarEntry legacyForgeEntry = jar.getJarEntry("mcmod.info");
            if (legacyForgeEntry != null) {
                try (InputStream in = jar.getInputStream(legacyForgeEntry)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    var arr = JsonParser.parseString(content).getAsJsonArray();
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

    /** Very small manual TOML value extractor — good enough for the flat key = "value" lines
     *  mods.toml uses for modId/displayName/version. Not a general TOML parser. */
    private static String extractTomlValue(String tomlContent, String key) {
        for (String line : tomlContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key) && trimmed.contains("=")) {
                String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                value = value.replace("\"", "");
                if (!value.isEmpty() && !value.startsWith("$")) { // skip unresolved ${file.jarVersion} etc
                    return value;
                }
            }
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

    private static String parsePackMcmeta(String content) {
        try {
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (json.has("pack") && json.getAsJsonObject("pack").has("description")) {
                return json.getAsJsonObject("pack").get("description").getAsString();
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
        } catch (IOException e) {
            return 0;
        }
    }

    private static long folderSize(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(ContentScanner::sizeOf)
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
