package site.kpeclub.launcher.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.launch.FpsBoost;
import site.kpeclub.launcher.util.FileDownloader;
import site.kpeclub.launcher.util.LauncherConfig;
import site.kpeclub.launcher.util.PlatformInfo;
import site.kpeclub.launcher.util.Safe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Checks for a newer version of KPE Club Launcher and downloads + runs the Inno Setup
 * installer in upgrade mode (preserving all player settings, modpacks, mods, and worlds).
 *
 * Expected JSON format at UPDATE_MANIFEST_URL (https://kpeclub.site/launcher/version.json):
 * {
 *   "version": "1.8.1",
 *   "url64": "https://kpeclub.site/launcher/KPE-Club-Launcher-Setup-1.8.1-x64.exe",
 *   "sha256_64": "<64-char-hex-sha256>",
 *   "size64": 45000000,
 *   "url32": "https://kpeclub.site/launcher/KPE-Club-Launcher-Setup-1.8.1-x86.exe",
 *   "sha256_32": "<64-char-hex-sha256>",
 *   "size32": 42000000,
 *   "notes": "Bug fixes and performance improvements."
 * }
 */
public final class LauncherUpdater {

    public static final String CURRENT_VERSION = "1.8.1";
    public static final String UPDATE_MANIFEST_URL = "https://kpeclub.site/launcher/version.json";

    public record UpdateInfo(String version, String downloadUrl, String sha256, long size, String notes) {}

    private LauncherUpdater() {}

    /**
     * Checks if a newer launcher version is published at UPDATE_MANIFEST_URL.
     * Requires a valid 64-character hex SHA-256 hash in the manifest.
     */
    public static UpdateInfo checkForUpdate() throws IOException, InterruptedException {
        String raw = FileDownloader.getString(UPDATE_MANIFEST_URL);
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
        if (!json.has("version") || json.get("version").isJsonNull()) return null;

        String latestVersion = json.get("version").getAsString().trim();
        if (latestVersion.startsWith("v") || latestVersion.startsWith("V")) {
            latestVersion = latestVersion.substring(1).trim();
        }
        if (compareVersions(latestVersion, CURRENT_VERSION) <= 0) {
            return null; // Already running the latest (or newer) version
        }

        boolean is64 = PlatformInfo.isOs64Bit();
        String urlKey = is64 ? "url64" : "url32";
        String sha256Key = is64 ? "sha256_64" : "sha256_32";
        String sizeKey = is64 ? "size64" : "size32";

        String url = json.has(urlKey) && !json.get(urlKey).isJsonNull()
                ? json.get(urlKey).getAsString()
                : (json.has("url") && !json.get("url").isJsonNull() ? json.get("url").getAsString() : null);
        if (url == null || url.isBlank()) return null;
        Safe.requireHttps(url);

        String sha256 = json.has(sha256Key) && !json.get(sha256Key).isJsonNull()
                ? json.get(sha256Key).getAsString().trim()
                : (json.has("sha256") && !json.get("sha256").isJsonNull() ? json.get("sha256").getAsString().trim() : null);
        if (sha256 == null || !sha256.matches("(?i)^[0-9a-f]{64}$")) {
            throw new SecurityException("Update manifest is missing a required 64-character SHA-256 checksum.");
        }

        long size = json.has(sizeKey) && !json.get(sizeKey).isJsonNull()
                ? json.get(sizeKey).getAsLong() : 0L;
        String notes = json.has("notes") && !json.get("notes").isJsonNull()
                ? json.get("notes").getAsString() : "";

        return new UpdateInfo(latestVersion, url, sha256.toLowerCase(Locale.ROOT), size, notes);
    }

    /**
     * Always fresh-downloads the update installer into %APPDATA%/.kpelauncher/updates/
     * (never shared %TEMP%) and verifies its required SHA-256 checksum.
     */
    public static Path downloadUpdateInstaller(UpdateInfo info,
                                               Consumer<DownloadProgress> progress,
                                               BooleanSupplier cancelled) throws Exception {
        Safe.requireHttps(info.downloadUrl());
        if (info.sha256() == null || !info.sha256().matches("(?i)^[0-9a-f]{64}$")) {
            throw new SecurityException("Refusing to download update without a required SHA-256 hash.");
        }
        String safeVer = Safe.requireSafeId(info.version(), "update version");
        String archSuffix = PlatformInfo.isOs64Bit() ? "x64" : "x86";
        Path updatesDir = LauncherConfig.ROOT_DIR.resolve("updates");
        Files.createDirectories(updatesDir);
        Path dest = updatesDir.resolve("KPE-Club-Launcher-Setup-" + safeVer + "-" + archSuffix + ".exe");

        // Always delete any pre-existing file so a stale or planted binary is never reused
        Files.deleteIfExists(dest);

        progress.accept(new DownloadProgress(0, Math.max(1L, info.size()), "Downloading update v" + safeVer + "..."));
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Update cancelled.");

        FileDownloader.fetch(info.downloadUrl(), dest, null, info.size());
        String actualSha256 = FpsBoost.sha256Hex(dest);
        if (!info.sha256().equalsIgnoreCase(actualSha256)) {
            Files.deleteIfExists(dest);
            throw new SecurityException("Downloaded update failed SHA-256 verification (expected "
                    + info.sha256() + ", got " + actualSha256 + ").");
        }
        progress.accept(new DownloadProgress(1, 1, "Update verified"));
        return dest;
    }

    /**
     * Launches an Inno Setup installer (.exe) to upgrade the existing installation in place.
     * For automatic online updates (silent == true), verifies the Authenticode digital signature first.
     */
    public static void runInstallerAndExit(Path installerExe, boolean silent) throws IOException {
        if (installerExe == null || !Files.isRegularFile(installerExe)
                || !installerExe.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            throw new IOException("Invalid installer file: " + installerExe);
        }
        if (silent && !hasValidAuthenticodeSignature(installerExe)) {
            throw new SecurityException("Refusing to auto-run unsigned or untrusted update installer: "
                    + installerExe.getFileName());
        }
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(installerExe.toAbsolutePath().toString());
        cmd.add("/CLOSEAPPLICATIONS");
        if (silent) {
            cmd.add("/SILENT");
        }
        new ProcessBuilder(cmd)
                .directory(installerExe.getParent().toFile())
                .start();
        javafx.application.Platform.exit();
    }

    private static boolean hasValidAuthenticodeSignature(Path exePath) {
        try {
            String escaped = exePath.toAbsolutePath().toString().replace("'", "''");
            Process p = new ProcessBuilder(
                    Safe.systemExe("WindowsPowerShell\\v1.0\\powershell.exe"),
                    "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-AuthenticodeSignature -LiteralPath '" + escaped + "').Status"
            ).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line.trim());
                }
            }
            p.waitFor(10, TimeUnit.SECONDS);
            return "Valid".equalsIgnoreCase(out.toString().trim());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Semantic version comparison where a final release ("1.8.1") is newer than its
     * pre-release ("1.8.1-beta" or "1.8.1-rc1"), while "1.8.1.1" is newer than "1.8.1".
     */
    public static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]"), pb = b.split("[.\\-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            boolean missX = i >= pa.length, missY = i >= pb.length;
            String x = missX ? "" : pa[i], y = missY ? "" : pb[i];
            boolean nx = x.matches("\\d+"), ny = y.matches("\\d+");
            int c;
            if (missX || missY) {
                boolean extraIsNumber = missX ? ny : nx;
                if (extraIsNumber) {
                    long num = Long.parseLong(missX ? y : x);
                    if (num == 0L) continue;
                    c = missX ? -1 : 1;
                } else {
                    // Shorter release id ("1.8.1") is newer than pre-release suffix ("1.8.1-beta")
                    c = missX ? 1 : -1;
                }
            } else if (nx && ny) {
                c = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } else if (nx != ny) {
                c = nx ? 1 : -1;
            } else {
                c = x.compareToIgnoreCase(y);
            }
            if (c != 0) return c;
        }
        return 0;
    }
}
