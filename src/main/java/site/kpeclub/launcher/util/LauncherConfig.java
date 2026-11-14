package site.kpeclub.launcher.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import site.kpeclub.launcher.model.LauncherSettings;
import site.kpeclub.launcher.model.ModpackInfo;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the launcher's on-disk folder, settings, and imported modpacks list.
 * Uses atomic temp-file writes (.tmp -> target) so interrupted writes never corrupt settings.json.
 */
public class LauncherConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path resolveAppDataRoot() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData);
        }
        return Paths.get(System.getProperty("user.home", "."));
    }

    // %APPDATA%/.kpelauncher for launcher-specific settings (resolution, wallpaper, etc.)
    public static final Path ROOT_DIR = resolveAppDataRoot().resolve(".kpelauncher");
    public static final Path WALLPAPER_DIR = ROOT_DIR.resolve("wallpaper");

    // %APPDATA%/.minecraft — the SAME folder the official Minecraft Launcher uses.
    public static final Path GAME_DIR = resolveAppDataRoot().resolve(".minecraft");
    public static final Path VERSIONS_DIR = GAME_DIR.resolve("versions");
    public static final Path LIBRARIES_DIR = GAME_DIR.resolve("libraries");
    public static final Path ASSETS_DIR = GAME_DIR.resolve("assets");
    public static final Path NATIVES_DIR = GAME_DIR.resolve("natives");

    private static final Path SETTINGS_FILE = ROOT_DIR.resolve("settings.json");
    private static final Path MODPACKS_FILE = ROOT_DIR.resolve("modpacks.json");

    public static synchronized List<ModpackInfo> loadModpacks() {
        try {
            ensureDirs();
            if (!Files.exists(MODPACKS_FILE)) return new ArrayList<>();
            String json = Files.readString(MODPACKS_FILE, StandardCharsets.UTF_8);
            Type listType = new TypeToken<ArrayList<ModpackInfo>>() {}.getType();
            List<ModpackInfo> result = GSON.fromJson(json, listType);
            return result != null ? result : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static synchronized void saveModpacks(List<ModpackInfo> modpacks) {
        try {
            ensureDirs();
            writeAtomically(MODPACKS_FILE, GSON.toJson(modpacks));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized LauncherSettings loadSettings() {
        try {
            ensureDirs();
            if (Files.exists(SETTINGS_FILE)) {
                String json = Files.readString(SETTINGS_FILE, StandardCharsets.UTF_8);
                LauncherSettings settings = GSON.fromJson(json, LauncherSettings.class);
                if (settings != null) return settings;
            }
            Path tmp = SETTINGS_FILE.resolveSibling(SETTINGS_FILE.getFileName().toString() + ".tmp");
            if (Files.exists(tmp)) {
                String json = Files.readString(tmp, StandardCharsets.UTF_8);
                LauncherSettings settings = GSON.fromJson(json, LauncherSettings.class);
                if (settings != null) return settings;
            }
            return new LauncherSettings();
        } catch (Exception e) {
            return new LauncherSettings();
        }
    }

    public static synchronized void saveSettings(LauncherSettings settings) {
        try {
            ensureDirs();
            writeAtomically(SETTINGS_FILE, GSON.toJson(settings));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void ensureDirs() throws IOException {
        Files.createDirectories(ROOT_DIR);
        Files.createDirectories(GAME_DIR);
        Files.createDirectories(VERSIONS_DIR);
        Files.createDirectories(LIBRARIES_DIR);
        Files.createDirectories(ASSETS_DIR);
        Files.createDirectories(NATIVES_DIR);
    }
}
