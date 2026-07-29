package site.kpeclub.launcher.model;

/**
 * Persisted launcher settings. Currently just game window resolution,
 * but a natural place to add RAM allocation, Java path override, etc. later.
 */
public class LauncherSettings {
    private int gameWidth = 1280;
    private int gameHeight = 720;
    private String lastTestModeUsername = null;
    private boolean keepLauncherOpenWhileGameRunning = true;
    private int acceptedTermsVersion = 0; // 0 = never accepted; bump TERMS_VERSION in TermsOfService.java to re-prompt after changes
    private String lastSelectedVersion = null;
    private String lastSelectedModLoader = null; // "Vanilla" or "Fabric"
    private String lastSelectedFabricLoaderVersion = null;
    private String customWallpaperPath = null; // absolute path to a copy stored in %APPDATA%\.kpelauncher\wallpaper\
    private String theme = "System"; // "Dark", "Light", or "System"
    private double bannerBrightness = 0.65; // 0.0 = fully darkened overlay, 1.0 = no overlay at all
    private String uninstallDataPreference = null; // null = ask on uninstall; "Keep" or "Delete" = pre-decided

    public int getGameWidth() { return gameWidth; }
    public void setGameWidth(int gameWidth) { this.gameWidth = gameWidth; }

    public int getGameHeight() { return gameHeight; }
    public void setGameHeight(int gameHeight) { this.gameHeight = gameHeight; }

    public String getLastTestModeUsername() { return lastTestModeUsername; }
    public void setLastTestModeUsername(String lastTestModeUsername) { this.lastTestModeUsername = lastTestModeUsername; }

    public boolean isKeepLauncherOpenWhileGameRunning() { return keepLauncherOpenWhileGameRunning; }
    public void setKeepLauncherOpenWhileGameRunning(boolean value) { this.keepLauncherOpenWhileGameRunning = value; }

    public int getAcceptedTermsVersion() { return acceptedTermsVersion; }
    public void setAcceptedTermsVersion(int acceptedTermsVersion) { this.acceptedTermsVersion = acceptedTermsVersion; }

    public String getLastSelectedVersion() { return lastSelectedVersion; }
    public void setLastSelectedVersion(String lastSelectedVersion) { this.lastSelectedVersion = lastSelectedVersion; }

    public String getLastSelectedModLoader() { return lastSelectedModLoader; }
    public void setLastSelectedModLoader(String lastSelectedModLoader) { this.lastSelectedModLoader = lastSelectedModLoader; }

    public String getLastSelectedFabricLoaderVersion() { return lastSelectedFabricLoaderVersion; }
    public void setLastSelectedFabricLoaderVersion(String v) { this.lastSelectedFabricLoaderVersion = v; }

    public String getCustomWallpaperPath() { return customWallpaperPath; }
    public void setCustomWallpaperPath(String customWallpaperPath) { this.customWallpaperPath = customWallpaperPath; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public double getBannerBrightness() { return bannerBrightness; }
    public void setBannerBrightness(double bannerBrightness) { this.bannerBrightness = bannerBrightness; }

    public String getUninstallDataPreference() { return uninstallDataPreference; }
    public void setUninstallDataPreference(String uninstallDataPreference) { this.uninstallDataPreference = uninstallDataPreference; }
}
