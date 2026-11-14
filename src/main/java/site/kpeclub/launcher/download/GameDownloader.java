package site.kpeclub.launcher.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.util.FileDownloader;
import site.kpeclub.launcher.util.LauncherConfig;
import site.kpeclub.launcher.util.LibraryResolver;
import site.kpeclub.launcher.util.LibraryResolver.LibFile;
import site.kpeclub.launcher.util.PlatformInfo;
import site.kpeclub.launcher.util.Safe;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Downloads everything needed to run a Minecraft version: client jar, libraries (+ natives
 * for the right 32-bit/64-bit CPU), logging config, and the asset index + objects.
 *
 * Improvements over the first version:
 *  - 8 parallel downloads (much faster for the ~3000 asset files)
 *  - safe downloads (.part file, SHA-1 check, retries) via FileDownloader
 *  - the right natives for 32-bit or 64-bit Java
 *  - a ".kpe-ok" marker, so an interrupted download is never mistaken for a finished one
 */
public class GameDownloader {

    private static final int THREADS = 8;

    private record Item(String url, Path dest, String sha1, long size) {}

    /** Marker file proving a version was fully downloaded for the given CPU. */
    public static Path markerFile(String versionId) {
        return LauncherConfig.VERSIONS_DIR.resolve(versionId).resolve(".kpe-ok");
    }

    public static boolean isFullyDownloaded(String versionId, PlatformInfo.Arch arch) {
        try {
            Path m = markerFile(versionId);
            return Files.exists(m) && Files.readString(m).trim().equals(arch.ruleName);
        } catch (IOException e) {
            return false;
        }
    }

    public void downloadVersion(JsonObject versionJson, PlatformInfo.Arch arch,
                                Consumer<DownloadProgress> progress,
                                BooleanSupplier cancelled) throws Exception {
        LauncherConfig.ensureDirs();
        String versionId = Safe.requireSafeId(versionJson.get("id").getAsString(), "version id");
        Path versionDir = LauncherConfig.VERSIONS_DIR.resolve(versionId);
        Files.createDirectories(versionDir);
        Files.deleteIfExists(markerFile(versionId)); // not "complete" again until the very end

        // Do not overwrite an existing "inheritsFrom"-only JSON on disk with a runtime-merged JSON;
        // only write the JSON if it's a standalone/merged profile that doesn't rely on local parent resolution.
        Path versionJsonPath = versionDir.resolve(versionId + ".json");
        if (!versionJson.has("inheritsFromResolvedParentId") || !Files.exists(versionJsonPath)) {
            Files.writeString(versionJsonPath, versionJson.toString());
        }

        List<Item> items = new ArrayList<>();

        // 1. Client jar (download to parent folder if this is an inheritsFrom version reusing parent's jar)
        JsonObject client = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        Path clientJarDest = versionDir.resolve(versionId + ".jar");
        if (versionJson.has("inheritsFromResolvedParentId")) {
            String parentId = Safe.requireSafeId(
                    versionJson.get("inheritsFromResolvedParentId").getAsString(), "parent version id");
            clientJarDest = LauncherConfig.VERSIONS_DIR.resolve(parentId).resolve(parentId + ".jar");
        }
        items.add(new Item(client.get("url").getAsString(), clientJarDest,
                str(client, "sha1"), client.has("size") ? client.get("size").getAsLong() : 0));

        // 2. Libraries (+ natives) for this CPU
        for (LibFile lf : LibraryResolver.resolve(versionJson, arch)) {
            if (lf.url() == null || lf.url().isBlank()) continue; // installed locally by a loader installer
            items.add(new Item(lf.url(), lf.path(), lf.sha1(), lf.size()));
        }

        // 3. Log4j config (protects 1.7–1.18 against the Log4Shell bug, same as the official launcher)
        if (versionJson.has("logging")) {
            JsonObject logging = versionJson.getAsJsonObject("logging");
            if (logging.has("client") && logging.getAsJsonObject("client").has("file")) {
                JsonObject f = logging.getAsJsonObject("client").getAsJsonObject("file");
                String logConfigId = Safe.requireSafeId(f.get("id").getAsString(), "log config id");
                items.add(new Item(f.get("url").getAsString(),
                        Safe.resolveInside(LauncherConfig.ASSETS_DIR.resolve("log_configs"), logConfigId),
                        str(f, "sha1"), f.has("size") ? f.get("size").getAsLong() : 0));
            }
        }

        // 4. Asset index + objects
        collectAssetItems(versionJson.getAsJsonObject("assetIndex"), items);

        long total = items.stream().mapToLong(i -> Math.max(1L, i.size())).sum();
        AtomicLong done = new AtomicLong();
        AtomicLong lastEmit = new AtomicLong();
        progress.accept(new DownloadProgress(0, total, "Preparing..."));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "kpe-download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Item item : items) {
                futures.add(pool.submit(() -> {
                    if (cancelled.getAsBoolean()) throw new CancellationException("Download cancelled.");
                    FileDownloader.fetch(item.url(), item.dest(), item.sha1(), item.size());
                    long now = done.addAndGet(Math.max(1L, item.size()));
                    long t = System.currentTimeMillis();
                    long last = lastEmit.get();
                    if (t - last > 120 && lastEmit.compareAndSet(last, t)) {
                        progress.accept(new DownloadProgress(now, total, item.dest().getFileName().toString()));
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    pool.shutdownNow();
                    Throwable c = e.getCause();
                    if (c instanceof Exception ex) throw ex;
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            throw new CancellationException("Download cancelled.");
        } finally {
            pool.shutdownNow();
        }

        if (cancelled.getAsBoolean()) throw new CancellationException("Download cancelled.");

        progress.accept(new DownloadProgress(total, total, "Done"));
        Files.writeString(markerFile(versionId), arch.ruleName);
    }

    private void collectAssetItems(JsonObject assetIndexInfo, List<Item> items) throws Exception {
        String indexUrl = assetIndexInfo.get("url").getAsString();
        String indexId = Safe.requireSafeId(assetIndexInfo.get("id").getAsString(), "asset index id");
        Path indexFile = Safe.resolveInside(LauncherConfig.ASSETS_DIR.resolve("indexes"), indexId + ".json");
        FileDownloader.fetch(indexUrl, indexFile, str(assetIndexInfo, "sha1"),
                assetIndexInfo.has("size") ? assetIndexInfo.get("size").getAsLong() : 0);

        JsonObject objects = JsonParser.parseString(Files.readString(indexFile))
                .getAsJsonObject().getAsJsonObject("objects");
        Path objectsDir = LauncherConfig.ASSETS_DIR.resolve("objects");
        for (String key : objects.keySet()) {
            JsonObject obj = objects.getAsJsonObject(key);
            String hash = obj.get("hash").getAsString();
            if (hash.length() < 2 || !hash.matches("^[0-9a-fA-F]+$")) continue;
            String prefix = hash.substring(0, 2);
            items.add(new Item("https://resources.download.minecraft.net/" + prefix + "/" + hash,
                    objectsDir.resolve(prefix).resolve(hash), hash,
                    obj.has("size") ? obj.get("size").getAsLong() : 0));
        }
    }

    /**
     * Very old versions (1.5.2 and older = "pre-1.6" index, and 1.6–1.8.1 = "legacy" index)
     * can't read the hashed assets/objects folder. They need real file names, copied to
     * assets/virtual/<id>/ or to <gameDir>/resources/. Returns the folder to pass as --assetsDir
     * (or the game's resources folder for "map_to_resources" versions).
     */
    public static Path prepareAssets(JsonObject versionJson) throws IOException {
        String indexId = Safe.requireSafeId(
                versionJson.getAsJsonObject("assetIndex").get("id").getAsString(), "asset index id");
        Path indexFile = Safe.resolveInside(LauncherConfig.ASSETS_DIR.resolve("indexes"), indexId + ".json");
        if (!Files.exists(indexFile)) return LauncherConfig.ASSETS_DIR;

        JsonObject index = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
        boolean virtual = index.has("virtual") && index.get("virtual").getAsBoolean();
        boolean mapToResources = index.has("map_to_resources") && index.get("map_to_resources").getAsBoolean();
        if (!virtual && !mapToResources) return LauncherConfig.ASSETS_DIR;

        Path target = mapToResources
                ? LauncherConfig.GAME_DIR.resolve("resources")
                : LauncherConfig.ASSETS_DIR.resolve("virtual").resolve(indexId);

        JsonObject objects = index.getAsJsonObject("objects");
        for (String name : objects.keySet()) {
            String hash = objects.getAsJsonObject(name).get("hash").getAsString();
            if (hash.length() < 2 || !hash.matches("^[0-9a-fA-F]+$")) continue;
            Path source = LauncherConfig.ASSETS_DIR.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            Path dest = Safe.resolveInside(target, name); // asset names come from a downloaded file: keep them inside
            if (Files.exists(dest) || !Files.exists(source)) continue;
            Files.createDirectories(dest.getParent());
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
