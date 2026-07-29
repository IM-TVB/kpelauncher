package site.kpeclub.launcher.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.util.LauncherConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Downloads everything needed to run a given Minecraft version:
 * client jar, libraries (+ natives), and the asset index + objects.
 *
 * progressCallback receives a DownloadProgress snapshot (bytes done, bytes total,
 * name of file currently downloading) so the UI can show "42% - 310 MB / 740 MB".
 */
public class GameDownloader {

    private final HttpClient http = HttpClient.newHttpClient();

    /** One thing that needs downloading: a URL, where it goes, and its known size in bytes. */
    private record DownloadItem(String url, Path dest, long size) {}

    public void downloadVersion(JsonObject versionJson, Consumer<DownloadProgress> progressCallback,
                                 BooleanSupplier cancelledCheck) throws Exception {
        LauncherConfig.ensureDirs();
        String versionId = versionJson.get("id").getAsString();
        Path versionDir = LauncherConfig.VERSIONS_DIR.resolve(versionId);
        Files.createDirectories(versionDir);

        // Save version json itself (needed later to build the launch command)
        Files.writeString(versionDir.resolve(versionId + ".json"), versionJson.toString());

        // ---- Build the full list of things to download BEFORE downloading anything,
        // so we know the true total size up front. ----
        List<DownloadItem> items = new ArrayList<>();

        // 1. Client jar
        JsonObject clientDownload = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        long clientSize = clientDownload.has("size") ? clientDownload.get("size").getAsLong() : 0;
        items.add(new DownloadItem(
                clientDownload.get("url").getAsString(),
                versionDir.resolve(versionId + ".jar"),
                clientSize
        ));

        // 2. Libraries + natives
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        for (int i = 0; i < libraries.size(); i++) {
            collectLibraryItems(libraries.get(i).getAsJsonObject(), items);
        }

        // 3. Asset index + objects
        JsonObject assetIndexInfo = versionJson.getAsJsonObject("assetIndex");
        collectAssetItems(assetIndexInfo, items);

        long totalBytes = items.stream().mapToLong(DownloadItem::size).sum();
        AtomicLong downloadedBytes = new AtomicLong(0);

        // Emit an initial snapshot immediately so the UI shows real numbers right away
        progressCallback.accept(new DownloadProgress(0, totalBytes, "Preparing..."));

        for (DownloadItem item : items) {
            if (cancelledCheck.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Download cancelled by user.");
            }
            downloadIfMissing(item.url(), item.dest());
            long newTotal = downloadedBytes.addAndGet(item.size());
            String fileName = item.dest().getFileName().toString();
            progressCallback.accept(new DownloadProgress(newTotal, totalBytes, fileName));
        }

        if (cancelledCheck.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("Download cancelled by user.");
        }

        progressCallback.accept(new DownloadProgress(totalBytes, totalBytes, "Done"));
    }

    private void collectLibraryItems(JsonObject lib, List<DownloadItem> items) {
        if (!isAllowedByRules(lib)) return; // skip OS-specific libs that don't apply

        if (lib.has("downloads")) {
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                String path = artifact.get("path").getAsString();
                String url = artifact.get("url").getAsString();
                long size = artifact.has("size") ? artifact.get("size").getAsLong() : 0;
                Path dest = LauncherConfig.LIBRARIES_DIR.resolve(path);
                items.add(new DownloadItem(url, dest, size));
            }

            // Natives (per-OS, only relevant on Windows for this launcher)
            if (downloads.has("classifiers") && lib.has("natives")) {
                JsonObject natives = lib.getAsJsonObject("natives");
                if (natives.has("windows")) {
                    String classifierKey = natives.get("windows").getAsString();
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    if (classifiers.has(classifierKey)) {
                        JsonObject nativeArtifact = classifiers.getAsJsonObject(classifierKey);
                        String url = nativeArtifact.get("url").getAsString();
                        long size = nativeArtifact.has("size") ? nativeArtifact.get("size").getAsLong() : 0;
                        String fileName = Paths.get(nativeArtifact.get("path").getAsString()).getFileName().toString();
                        Path dest = LauncherConfig.NATIVES_DIR.resolve(fileName);
                        items.add(new DownloadItem(url, dest, size));
                    }
                }
            }
        } else if (lib.has("name")) {
            // Maven-coordinate-only library, no downloads block. Two sub-cases:
            // 1. Has a "url" (Fabric-style) — fetch it from that Maven repo.
            // 2. No "url" at all (OptiFine-style) — this library is expected to already
            //    exist locally, placed there by OptiFine's own installer. We can't download
            //    something with no source, so just skip it; GameLauncher will still put it
            //    on the classpath from whatever's already in .minecraft/libraries.
            if (lib.has("url")) {
                String coordinate = lib.get("name").getAsString();
                String relativePath = site.kpeclub.launcher.util.MavenUtil.coordinateToPath(coordinate);
                if (relativePath != null) {
                    String baseUrl = lib.get("url").getAsString();
                    if (!baseUrl.endsWith("/")) baseUrl += "/";
                    String url = baseUrl + relativePath;
                    Path dest = LauncherConfig.LIBRARIES_DIR.resolve(relativePath);
                    items.add(new DownloadItem(url, dest, 0)); // meta APIs don't give sizes up front
                }
            }
            // else: no url — assume already present locally (OptiFine pattern), nothing to queue
        }
    }

    private boolean isAllowedByRules(JsonObject lib) {
        if (!lib.has("rules")) return true;
        JsonArray rules = lib.getAsJsonArray("rules");
        boolean allowed = false;
        for (int i = 0; i < rules.size(); i++) {
            JsonObject rule = rules.get(i).getAsJsonObject();
            String action = rule.get("action").getAsString();
            boolean matches = true;
            if (rule.has("os")) {
                String osName = rule.getAsJsonObject("os").has("name")
                        ? rule.getAsJsonObject("os").get("name").getAsString() : null;
                matches = "windows".equals(osName);
            }
            if (matches) {
                allowed = action.equals("allow");
            }
        }
        return allowed;
    }

    /** Downloads the asset index itself immediately (needed to know the object list + sizes),
     *  then queues each asset object as a DownloadItem with its real size. */
    private void collectAssetItems(JsonObject assetIndexInfo, List<DownloadItem> items) throws Exception {
        String indexUrl = assetIndexInfo.get("url").getAsString();
        String indexId = assetIndexInfo.get("id").getAsString();

        Path indexesDir = LauncherConfig.ASSETS_DIR.resolve("indexes");
        Files.createDirectories(indexesDir);
        Path indexFile = indexesDir.resolve(indexId + ".json");
        downloadIfMissing(indexUrl, indexFile); // small file, download eagerly so we can read sizes

        JsonObject indexJson = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
        JsonObject objects = indexJson.getAsJsonObject("objects");

        Path objectsDir = LauncherConfig.ASSETS_DIR.resolve("objects");
        Files.createDirectories(objectsDir);

        for (String key : objects.keySet()) {
            JsonObject obj = objects.getAsJsonObject(key);
            String hash = obj.get("hash").getAsString();
            long size = obj.has("size") ? obj.get("size").getAsLong() : 0;
            String prefix = hash.substring(0, 2);
            Path dest = objectsDir.resolve(prefix).resolve(hash);
            String url = "https://resources.download.minecraft.net/" + prefix + "/" + hash;
            items.add(new DownloadItem(url, dest, size));
        }
    }

    private void downloadIfMissing(String url, Path dest) throws IOException, InterruptedException {
        if (Files.exists(dest)) return;
        Files.createDirectories(dest.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() >= 400) {
            Files.deleteIfExists(dest);
            throw new IOException("Failed to download " + url + " (" + resp.statusCode() + ")");
        }
    }
}
