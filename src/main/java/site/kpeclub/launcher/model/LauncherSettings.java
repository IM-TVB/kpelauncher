package site.kpeclub.launcher.model;

/**
 * Persisted launcher settings.
 */
public class LauncherSettings {
    private int gameWidth = 1280;
    private int gameHeight = 720;
    private String lastTestModeUsername = null;
    private boolean keepLauncherOpenWhileGameRunning = true;
    private String lastSelectedVersion = null;
    private String lastSelectedModLoader = null; // "Vanilla" or "Fabric"
    private String lastSelectedFabricLoaderVersion = null;
    private String customWallpaperPath = null; // absolute path to a copy stored in %APPDATA%\.kpelauncher\wallpaper\
    private String theme = "System"; // "Dark", "Light", or "System"
    private double bannerBrightness = 0.65; // 0.0 = fully darkened overlay, 1.0 = no overlay at all
    private String uninstallDataPreference = null; // null = ask on uninstall; "Keep" or "Delete" = pre-decided

    private boolean fpsBoost = true; // faster Java settings + high priority
    private boolean oldGpuBypass = false; // off by default so good GPUs use hardware OpenGL; auto-enabled on GL crash
    private int ramMb = 0; // 0 = automatic (picked from this PC's RAM and 32/64-bit Java)
    private String javaPath = null; // optional custom java.exe / javaw.exe; null = Mojang's own runtime
    private String extraJvmArgs = ""; // optional extra JVM flags
    private boolean showSnapshots = false;
    private boolean showOldVersions = false; // old_beta / old_alpha (legacy) versions

    public boolean isFpsBoost() { return fpsBoost; }
    public void setFpsBoost(boolean v) { this.fpsBoost = v; }

    public boolean isBypassDriverCheck() { return oldGpuBypass; }
    public void setBypassDriverCheck(boolean oldGpuBypass) { this.oldGpuBypass = oldGpuBypass; }

    public int getRamMb() { return ramMb; }
    public void setRamMb(int ramMb) { this.ramMb = ramMb; }

    public String getJavaPath() { return javaPath; }
    public void setJavaPath(String javaPath) { this.javaPath = (javaPath == null || javaPath.isBlank()) ? null : javaPath.trim(); }

    public String getExtraJvmArgs() { return extraJvmArgs == null ? "" : extraJvmArgs; }
    public void setExtraJvmArgs(String extraJvmArgs) { this.extraJvmArgs = extraJvmArgs; }

    public boolean isShowSnapshots() { return showSnapshots; }
    public void setShowSnapshots(boolean showSnapshots) { this.showSnapshots = showSnapshots; }

    public boolean isShowOldVersions() { return showOldVersions; }
    public void setShowOldVersions(boolean showOldVersions) { this.showOldVersions = showOldVersions; }

    public int getGameWidth() { return gameWidth; }
    public void setGameWidth(int gameWidth) { this.gameWidth = gameWidth; }

    public int getGameHeight() { return gameHeight; }
    public void setGameHeight(int gameHeight) { this.gameHeight = gameHeight; }

    public String getLastTestModeUsername() { return lastTestModeUsername; }
    public void setLastTestModeUsername(String lastTestModeUsername) { this.lastTestModeUsername = lastTestModeUsername; }

    public boolean isKeepLauncherOpenWhileGameRunning() { return keepLauncherOpenWhileGameRunning; }
    public void setKeepLauncherOpenWhileGameRunning(boolean value) { this.keepLauncherOpenWhileGameRunning = value; }

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
