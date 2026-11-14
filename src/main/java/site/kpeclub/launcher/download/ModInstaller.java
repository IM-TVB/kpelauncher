package site.kpeclub.launcher.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.util.LauncherConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-click mod install: downloads the newest file for the chosen Minecraft version + Fabric,
 * AND the mods it needs (like Fabric API) — a missing dependency is the #1 reason a modded
 * game crashes for normal players. Remembers what it installed so the UI can show
 * "Installed" and offer "Remove".
 */
public class ModInstaller {

    private static Path registryFile() {
        return LauncherConfig.GAME_DIR.resolve(".kpe-mods.json");
    }

    private static Path modsDir() {
        return LauncherConfig.GAME_DIR.resolve("mods");
    }

    // ----- registry: projectId -> fileName (also supports slug lookup) -----

    private static synchronized JsonObject loadRegistry() {
        try {
            if (Files.exists(registryFile())) {
                return JsonParser.parseString(Files.readString(registryFile())).getAsJsonObject();
            }
        } catch (Exception ignored) {
            // broken file: start fresh
        }
        return new JsonObject();
    }

    private static synchronized void saveRegistry(JsonObject o) {
        try {
            Files.createDirectories(registryFile().getParent());
            Files.writeString(registryFile(), o.toString());
        } catch (IOException ignored) {
            // not critical
        }
    }

    /** Records a downloaded mod file in the registry under its projectId (and optional slug). */
    public static synchronized void recordInstalled(String projectId, String slug, String fileName) {
        JsonObject r = loadRegistry();
        if (projectId != null && !projectId.isBlank()) {
            r.addProperty(projectId, fileName);
        }
        if (slug != null && !slug.isBlank() && !slug.equals(projectId)) {
            r.addProperty(slug, fileName);
        }
        saveRegistry(r);
    }

    /** True if we installed this project (or slug) and its file is still in the mods folder. */
    public static boolean isInstalled(String projectIdOrSlug) {
        if (projectIdOrSlug == null || projectIdOrSlug.isBlank()) return false;
        JsonObject r = loadRegistry();
        try {
            if (r.has(projectIdOrSlug)) {
                String fn = r.get(projectIdOrSlug).getAsString();
                if (Files.exists(site.kpeclub.launcher.util.Safe.resolveInside(modsDir(), fn))) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            return false;
        }
        return false;
    }

    /** Deletes the mod's file and forgets it (along with any alias keys pointing to the same file). */
    public static synchronized void remove(String projectId) throws IOException {
        JsonObject r = loadRegistry();
        if (!r.has(projectId)) return;
        String fileName = r.get(projectId).getAsString();
        Files.deleteIfExists(site.kpeclub.launcher.util.Safe.resolveInside(modsDir(), fileName));
        List<String> keysToRemove = new ArrayList<>();
        for (String key : r.keySet()) {
            if (fileName.equals(r.get(key).getAsString())) {
                keysToRemove.add(key);
            }
        }
        for (String key : keysToRemove) {
            r.remove(key);
        }
        saveRegistry(r);
    }

    // ----- install -----

    /** @return file names installed (the mod first, then any dependencies) */
    public List<String> install(String projectId, String minecraftVersion) throws IOException, InterruptedException {
        List<String> installed = new ArrayList<>();
        installRecursive(projectId, minecraftVersion, new HashSet<>(), installed, 0);
        if (installed.isEmpty() && !isInstalled(projectId)) {
            throw new IOException("This mod has no Fabric version for Minecraft " + minecraftVersion + ".");
        }
        return installed;
    }

    private void installRecursive(String projectId, String mc, Set<String> seen, List<String> out, int depth)
            throws IOException, InterruptedException {
        if (depth > 3 || !seen.add(projectId)) return;
        ModrinthClient client = new ModrinthClient();
        var files = client.listVersions(projectId, mc, "fabric");
        if (files.isEmpty()) {
            if (depth == 0) return; // main mod unavailable -> caller reports it
            return;                 // a dependency we cannot find: the game will tell the player
        }
        var file = files.get(0);
        String resolvedProjectId = file.projectId() != null ? file.projectId() : projectId;
        seen.add(resolvedProjectId);
        // Remove older version jar if this project was installed for a different MC version
        synchronized (ModInstaller.class) {
            JsonObject r = loadRegistry();
            for (String key : List.of(projectId, resolvedProjectId)) {
                if (r.has(key)) {
                    String oldFile = r.get(key).getAsString();
                    if (!oldFile.equals(file.fileName())) {
                        try {
                            Files.deleteIfExists(site.kpeclub.launcher.util.Safe.resolveInside(modsDir(), oldFile));
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        if (!isInstalled(resolvedProjectId) || !Files.exists(site.kpeclub.launcher.util.Safe.resolveInside(modsDir(), file.fileName()))) {
            client.downloadModFile(file);
            recordInstalled(resolvedProjectId, projectId, file.fileName());
            out.add(file.fileName());
        }
        for (String dep : file.requiredDeps()) {
            // Fabric API may already be there from another install
            installRecursive(dep, mc, seen, out, depth + 1);
        }
    }
}
