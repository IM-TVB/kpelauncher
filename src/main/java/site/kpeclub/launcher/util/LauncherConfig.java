package site.kpeclub.launcher.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import site.kpeclub.launcher.model.LauncherSettings;
import site.kpeclub.launcher.model.ModpackInfo;
import site.kpeclub.launcher.model.ServerEntry;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the launcher's on-disk folder, settings, and the
 * combined list of preset (KPE Club) + custom (player-added) servers.
 */
public class LauncherConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // %APPDATA%/.kpelauncher for launcher-specific settings (servers, resolution prefs)
    public static final Path ROOT_DIR = Paths.get(System.getenv("APPDATA"), ".kpelauncher");
    public static final Path WALLPAPER_DIR = ROOT_DIR.resolve("wallpaper");

    // %APPDATA%/.minecraft — the SAME folder the official Minecraft Launcher uses.
    // Sharing this means players don't re-download versions/assets/libraries they
    // already have from the official launcher, at the cost of both launchers reading
    // and writing the same folder.
    public static final Path GAME_DIR = Paths.get(System.getenv("APPDATA"), ".minecraft");
    public static final Path VERSIONS_DIR = GAME_DIR.resolve("versions");
    public static final Path LIBRARIES_DIR = GAME_DIR.resolve("libraries");
    public static final Path ASSETS_DIR = GAME_DIR.resolve("assets");
    public static final Path NATIVES_DIR = GAME_DIR.resolve("natives");

    private static final Path SETTINGS_FILE = ROOT_DIR.resolve("settings.json");
    private static final Path CUSTOM_SERVERS_FILE = ROOT_DIR.resolve("custom_servers.json");
    private static final Path MODPACKS_FILE = ROOT_DIR.resolve("modpacks.json");

    /** Hardcode your KPE Club network servers here. */
    /** No hardcoded preset servers — players add their own via the Server dropdown's + Add button. */
    public static List<ServerEntry> getPresetServers() {
        return new ArrayList<>();
    }

    public static List<ServerEntry> loadCustomServers() {
        try {
            ensureDirs();
            if (!Files.exists(CUSTOM_SERVERS_FILE)) return new ArrayList<>();
            String json = Files.readString(CUSTOM_SERVERS_FILE);
            Type listType = new TypeToken<ArrayList<ServerEntry>>() {}.getType();
            List<ServerEntry> result = GSON.fromJson(json, listType);
            return result != null ? result : new ArrayList<>();
        } catch (IOException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public static void saveCustomServers(List<ServerEntry> servers) {
        try {
            ensureDirs();
            Files.writeString(CUSTOM_SERVERS_FILE, GSON.toJson(servers));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<ModpackInfo> loadModpacks() {
        try {
            ensureDirs();
            if (!Files.exists(MODPACKS_FILE)) return new ArrayList<>();
            String json = Files.readString(MODPACKS_FILE);
            Type listType = new TypeToken<ArrayList<ModpackInfo>>() {}.getType();
            List<ModpackInfo> result = GSON.fromJson(json, listType);
            return result != null ? result : new ArrayList<>();
        } catch (IOException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public static void saveModpacks(List<ModpackInfo> modpacks) {
        try {
            ensureDirs();
            Files.writeString(MODPACKS_FILE, GSON.toJson(modpacks));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static LauncherSettings loadSettings() {
        try {
            ensureDirs();
            if (!Files.exists(SETTINGS_FILE)) return new LauncherSettings(); // defaults: 1280x720
            String json = Files.readString(SETTINGS_FILE);
            LauncherSettings settings = GSON.fromJson(json, LauncherSettings.class);
            return settings != null ? settings : new LauncherSettings();
        } catch (IOException e) {
            e.printStackTrace();
            return new LauncherSettings();
        }
    }

    public static void saveSettings(LauncherSettings settings) {
        try {
            ensureDirs();
            Files.writeString(SETTINGS_FILE, GSON.toJson(settings));
        } catch (IOException e) {
            e.printStackTrace();
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
