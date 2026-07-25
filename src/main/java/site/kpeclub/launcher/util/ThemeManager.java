package site.kpeclub.launcher.util;

import javafx.scene.Scene;
import site.kpeclub.launcher.model.LauncherSettings;

/**
 * Applies the Dark or Light stylesheet to a Scene based on the person's theme preference.
 * "System" resolves to whatever Windows' own light/dark app theme setting currently is,
 * read from the registry — if that can't be determined for any reason, defaults to Dark
 * to match this launcher's original look.
 */
public class ThemeManager {

    private static final String DARK_CSS = "/css/style-dark.css";
    private static final String LIGHT_CSS = "/css/style-light.css";

    /** Applies the correct stylesheet to the given scene based on the current setting. */
    public static void apply(Scene scene) {
        LauncherSettings settings = LauncherConfig.loadSettings();
        applyResolved(scene, resolveEffectiveTheme(settings.getTheme()));
    }

    /** Re-applies after a theme change, replacing whichever stylesheet was there before. */
    public static void reapply(Scene scene, String newPreference) {
        LauncherSettings settings = LauncherConfig.loadSettings();
        settings.setTheme(newPreference);
        LauncherConfig.saveSettings(settings);
        applyResolved(scene, resolveEffectiveTheme(newPreference));
    }

    private static void applyResolved(Scene scene, boolean dark) {
        scene.getStylesheets().removeIf(s -> s.contains("style-dark.css") || s.contains("style-light.css"));
        String path = dark ? DARK_CSS : LIGHT_CSS;
        var url = ThemeManager.class.getResource(path);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }

    /** @return true if dark theme should be used, false for light. */
    private static boolean resolveEffectiveTheme(String preference) {
        if ("Dark".equalsIgnoreCase(preference)) return true;
        if ("Light".equalsIgnoreCase(preference)) return false;
        return isWindowsSystemThemeDark(); // "System" or anything unrecognized
    }

    /**
     * Reads Windows' "AppsUseLightTheme" registry value to detect the system-wide light/dark
     * preference. Falls back to dark (this launcher's original default look) if the registry
     * can't be read for any reason — e.g. running on a non-Windows OS, or a locked-down
     * environment where reg.exe isn't available.
     */
    private static boolean isWindowsSystemThemeDark() {
        try {
            Process process = new ProcessBuilder(
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme"
            ).start();

            String output;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.lines().reduce("", (a, b) -> a + "\n" + b);
            }
            process.waitFor();

            // Output looks like: "    AppsUseLightTheme    REG_DWORD    0x0" (dark) or "0x1" (light)
            if (output.contains("0x0")) return true;  // AppsUseLightTheme = 0 -> dark mode
            if (output.contains("0x1")) return false; // AppsUseLightTheme = 1 -> light mode
        } catch (Exception ignored) {
            // Not on Windows, reg.exe unavailable, or registry key missing — fall through to default.
        }
        return true; // default to dark if we can't determine the system setting
    }
}
