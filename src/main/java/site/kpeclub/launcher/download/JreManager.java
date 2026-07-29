package site.kpeclub.launcher.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.util.LauncherConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Downloads and manages Mojang's OWN official per-version Java runtimes — the exact same
 * ones the real Minecraft Launcher bundles and uses. This is the correct way to guarantee
 * Minecraft always has a full, compatible JRE to run on, regardless of what Java (if any)
 * is installed system-wide, and regardless of what modules our OWN launcher's jlink-trimmed
 * runtime happens to include (which is a different, smaller runtime meant only for our UI).
 *
 * Manifest: https://launchermeta.mojang.com/v1/products/java-runtime/<hash>/all.json
 * Each Minecraft version json specifies which runtime "component" it needs via
 * javaVersion.component (e.g. "java-runtime-gamma", "jre-legacy" for old versions).
 */
public class JreManager {

    private static final String RUNTIME_INDEX_URL =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private final HttpClient http = HttpClient.newHttpClient();

    /** Where downloaded runtimes live: %APPDATA%/.kpelauncher/runtimes/<component>/ */
    public static Path runtimesDir() {
        return LauncherConfig.ROOT_DIR.resolve("runtimes");
    }

    /** Reads which runtime component a version needs from its own version json.
     *  Falls back to "jre-legacy" (Java 8) for very old versions that don't specify one. */
    public String requiredComponent(JsonObject versionJson) {
        if (versionJson.has("javaVersion")) {
            JsonObject javaVersion = versionJson.getAsJsonObject("javaVersion");
            if (javaVersion.has("component")) {
                return javaVersion.get("component").getAsString();
            }
        }
        return "jre-legacy";
    }

    /** True if this component's runtime is already downloaded and looks usable. */
    public boolean isComponentInstalled(String component) {
        Path javaExe = javaExecutableFor(component);
        return javaExe != null && Files.exists(javaExe);
    }

    /** The java.exe path for an installed component, or null if not installed. */
    public Path javaExecutableFor(String component) {
        Path dir = runtimesDir().resolve(component);
        // Mojang's runtime archives use this nested layout on Windows.
        Path candidate = dir.resolve(component).resolve("bin").resolve("javaw.exe");
        if (Files.exists(candidate)) return candidate;
        candidate = dir.resolve(component).resolve("bin").resolve("java.exe");
        if (Files.exists(candidate)) return candidate;
        return null;
    }

    /** Downloads the given runtime component if not already present. */
    public void downloadComponentIfMissing(String component, Consumer<DownloadProgress> progressCallback) throws Exception {
        if (isComponentInstalled(component)) {
            progressCallback.accept(new DownloadProgress(1, 1, "Already installed"));
            return;
        }

        JsonObject manifest = fetchRuntimeManifest();
        if (!manifest.has("windows-x64")) {
            throw new IllegalStateException("Mojang's runtime manifest has no windows-x64 entry — this shouldn't happen.");
        }
        JsonObject windowsRuntimes = manifest.getAsJsonObject("windows-x64");
        if (!windowsRuntimes.has(component) || windowsRuntimes.getAsJsonArray(component).isEmpty()) {
            throw new IllegalStateException("No Windows runtime available for component: " + component);
        }

        JsonObject runtimeEntry = windowsRuntimes.getAsJsonArray(component).get(0).getAsJsonObject();
        String manifestUrl = runtimeEntry.getAsJsonObject("manifest").get("url").getAsString();

        JsonObject filesManifest = fetchJson(manifestUrl);
        JsonObject files = filesManifest.getAsJsonObject("files");

        Path componentDir = runtimesDir().resolve(component).resolve(component);
        Files.createDirectories(componentDir);

        long totalBytes = 0;
        for (String key : files.keySet()) {
            JsonObject entry = files.getAsJsonObject(key);
            if (!"file".equals(entry.get("type").getAsString())) continue;
            totalBytes += entry.getAsJsonObject("downloads").getAsJsonObject("raw").get("size").getAsLong();
        }

        long downloaded = 0;
        for (String key : files.keySet()) {
            JsonObject entry = files.getAsJsonObject(key);
            String type = entry.get("type").getAsString();
            Path dest = componentDir.resolve(key.replace("/", java.io.File.separator));

            if ("directory".equals(type)) {
                Files.createDirectories(dest);
                continue;
            }
            if (!"file".equals(type)) continue; // skip symlinks — not meaningful on Windows

            JsonObject raw = entry.getAsJsonObject("downloads").getAsJsonObject("raw");
            String url = raw.get("url").getAsString();
            long size = raw.get("size").getAsLong();

            Files.createDirectories(dest.getParent());
            downloadFile(url, dest);
            downloaded += size;

            progressCallback.accept(new DownloadProgress(downloaded, totalBytes, key));
        }
    }

    private JsonObject fetchRuntimeManifest() throws IOException, InterruptedException {
        return fetchJson(RUNTIME_INDEX_URL);
    }

    private JsonObject fetchJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("Failed to fetch " + url + " (" + resp.statusCode() + ")");
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private void downloadFile(String url, Path dest) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() >= 400) {
            Files.deleteIfExists(dest);
            throw new IOException("Failed to download " + url + " (" + resp.statusCode() + ")");
        }
    }
}
