package site.kpeclub.launcher.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.model.ModpackInfo;
import site.kpeclub.launcher.util.LauncherConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a Modrinth .mrpack modpack: reads modrinth.index.json, downloads every referenced
 * mod file (verifying against the manifest's sha1/sha512 hash), and copies the overrides/
 * folder (configs, resource packs, etc) into .minecraft.
 *
 * Format reference: https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack
 *
 * SECURITY: every file path from the manifest and every zip entry name is validated to stay
 * within the target instance directory before writing anything. A malicious or corrupted
 * .mrpack could otherwise use ".." path segments to write files outside .minecraft — we
 * reject any entry that would do that rather than trying to "fix" the path.
 */
public class ModpackImporter {

    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;        // one file
    private static final long MAX_OVERRIDE_BYTES = 2L * 1024 * 1024 * 1024; // all overrides together
    private static final int MAX_OVERRIDE_FILES = 20_000;

    public record ImportResult(ModpackInfo info, String minecraftVersion, String loader, String loaderVersion) {}

    /**
     * Imports the given .mrpack file: extracts overrides into .minecraft, downloads all
     * referenced client-relevant mod files into .minecraft/mods, and returns a summary of what was imported.
     */
    public ImportResult importPack(Path mrpackFile, Consumer<DownloadProgress> progressCallback) throws Exception {
        try (ZipFile zip = new ZipFile(mrpackFile.toFile())) {
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null) {
                throw new IllegalArgumentException(
                        "This doesn't look like a valid .mrpack file — missing modrinth.index.json.");
            }

            JsonObject index;
            try (InputStream in = zip.getInputStream(indexEntry)) {
                index = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }

            String name = index.has("name") ? index.get("name").getAsString() : mrpackFile.getFileName().toString();
            JsonObject dependencies = index.has("dependencies") ? index.getAsJsonObject("dependencies") : new JsonObject();
            String minecraftVersion = dependencies.has("minecraft") ? dependencies.get("minecraft").getAsString() : null;

            String loader = "unknown";
            String loaderVersion = null;
            for (String key : new String[]{"fabric-loader", "forge", "quilt-loader", "neoforge"}) {
                if (dependencies.has(key)) {
                    loader = key;
                    loaderVersion = dependencies.get(key).getAsString();
                    break;
                }
            }

            if (minecraftVersion == null) {
                throw new IllegalArgumentException("This modpack doesn't specify a Minecraft version — can't import.");
            }
            if (!"fabric-loader".equals(loader) && !"unknown".equals(loader)) {
                throw new IllegalStateException(
                        "This modpack requires " + loaderDisplayName(loader) +
                        ", which this launcher doesn't support yet (only Fabric is currently supported).");
            }

            // 1. Download every client-relevant mod file (skip server-only files where env.client == "unsupported")
            JsonArray files = index.has("files") ? index.getAsJsonArray("files") : new JsonArray();
            List<JsonObject> clientFiles = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                JsonObject f = files.get(i).getAsJsonObject();
                if (isClientSupported(f)) {
                    clientFiles.add(f);
                }
            }
            downloadIndexFiles(clientFiles, progressCallback);

            // 2. Apply overrides/ and client-overrides/ (configs, resource packs, etc) safely
            applyOverridesFromZip(zip, "overrides/");
            applyOverridesFromZip(zip, "client-overrides/"); // client-only extras layer on top, if present

            ModpackInfo info = new ModpackInfo(name, minecraftVersion, loader, loaderVersion, clientFiles.size());
            return new ImportResult(info, minecraftVersion, loader, loaderVersion);
        }
    }

    private static boolean isClientSupported(JsonObject fileEntry) {
        if (!fileEntry.has("env") || !fileEntry.get("env").isJsonObject()) return true;
        JsonObject env = fileEntry.getAsJsonObject("env");
        if (!env.has("client") || env.get("client").isJsonNull()) return true;
        return !"unsupported".equalsIgnoreCase(env.get("client").getAsString());
    }

    private String loaderDisplayName(String loaderKey) {
        return switch (loaderKey) {
            case "forge" -> "Forge";
            case "quilt-loader" -> "Quilt";
            case "neoforge" -> "NeoForge";
            default -> loaderKey;
        };
    }

    /** Downloads each file entry from modrinth.index.json's "files" array into .minecraft,
     *  verifying against its declared hash and rejecting any path that would escape .minecraft. */
    private void downloadIndexFiles(List<JsonObject> files, Consumer<DownloadProgress> progressCallback) throws Exception {
        long totalBytes = 0;
        for (JsonObject file : files) {
            totalBytes += file.has("fileSize") ? file.get("fileSize").getAsLong() : 0;
        }

        long downloaded = 0;
        for (JsonObject file : files) {
            String relativePath = file.get("path").getAsString();
            Path dest = resolveSafely(LauncherConfig.GAME_DIR, relativePath);

            long size = file.has("fileSize") ? file.get("fileSize").getAsLong() : 0;
            String expectedSha1 = null;
            if (file.has("hashes") && file.getAsJsonObject("hashes").has("sha1")) {
                expectedSha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
            }

            if (Files.exists(dest) && expectedSha1 != null && sha1Matches(dest, expectedSha1)) {
                downloaded += size;
                progressCallback.accept(new DownloadProgress(downloaded, totalBytes, relativePath + " (already present)"));
                continue;
            }

            JsonArray downloadUrls = file.getAsJsonArray("downloads");
            if (downloadUrls == null || downloadUrls.isEmpty()) {
                throw new IOException("No download URL provided for " + relativePath);
            }
            if (expectedSha1 == null) {
                throw new SecurityException("This modpack has a file without a checksum (" + relativePath
                        + "). Refusing to import it.");
            }
            if (size > MAX_FILE_BYTES) {
                throw new SecurityException("A file in this modpack is too large: " + relativePath);
            }

            Files.createDirectories(dest.getParent());

            IOException lastDownloadError = null;
            boolean fetched = false;
            for (int u = 0; u < downloadUrls.size(); u++) {
                String url = downloadUrls.get(u).getAsString();
                // A modpack is untrusted input: only well-known hosts, only https, and a checksum is REQUIRED.
                if (!site.kpeclub.launcher.util.Safe.isTrustedFileHost(url)) {
                    lastDownloadError = new IOException("Untrusted download host: " + url);
                    continue;
                }
                try {
                    site.kpeclub.launcher.util.FileDownloader.fetch(url, dest, expectedSha1, size);
                    fetched = true;
                    break;
                } catch (IOException ex) {
                    lastDownloadError = ex;
                }
            }
            if (!fetched) {
                if (lastDownloadError != null) throw lastDownloadError;
                throw new SecurityException("No trusted download URL succeeded for " + relativePath);
            }

            String expectedSha512 = file.has("hashes") && file.getAsJsonObject("hashes").has("sha512")
                    ? file.getAsJsonObject("hashes").get("sha512").getAsString() : null;
            if (expectedSha512 != null && !digestMatches(dest, "SHA-512", expectedSha512)) {
                Files.deleteIfExists(dest);
                throw new IOException("Downloaded file failed hash check: " + relativePath +
                        " — the file may be corrupted or tampered with.");
            }

            downloaded += size;
            progressCallback.accept(new DownloadProgress(downloaded, totalBytes, relativePath));
        }
    }

    /** Copies every entry under the given prefix (e.g. "overrides/") from the zip into
     *  .minecraft, rejecting any entry whose resolved path would land outside .minecraft. */
    private void applyOverridesFromZip(ZipFile zip, String prefix) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        long written = 0;
        int count = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
            if (++count > MAX_OVERRIDE_FILES) {
                throw new SecurityException("This modpack contains too many files. Refusing to import it.");
            }

            String relativePath = entry.getName().substring(prefix.length());
            if (relativePath.isBlank()) continue;

            Path dest = resolveSafely(LauncherConfig.GAME_DIR, relativePath);
            Files.createDirectories(dest.getParent());
            if (Files.isSymbolicLink(dest)) {
                throw new SecurityException("Refusing to write through a link: " + relativePath);
            }
            // Count REAL bytes while copying (the size written in the zip can lie — "zip bomb").
            try (InputStream in = zip.getInputStream(entry);
                 var out = Files.newOutputStream(dest)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    written += n;
                    if (written > MAX_OVERRIDE_BYTES) {
                        throw new SecurityException("This modpack unpacks to too much data. Refusing to import it.");
                    }
                    out.write(buf, 0, n);
                }
            }
        }
    }

    /**
     * Resolves a manifest/zip-entry-provided relative path against the instance directory,
     * rejecting anything that would escape it (via "..", a leading "/", or a drive letter).
     * This is the key security check for handling untrusted modpack files.
     */
    private Path resolveSafely(Path instanceDir, String relativePath) {
        // Rejects "..", absolute paths, drive letters and ':' (Windows hidden data streams).
        return site.kpeclub.launcher.util.Safe.resolveInside(instanceDir, relativePath.replace('\\', '/'));
    }

    private boolean digestMatches(Path file, String algorithm, String expectedHex) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            }
            return java.util.HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expectedHex);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean sha1Matches(Path file, String expectedHex) {
        return digestMatches(file, "SHA-1", expectedHex);
    }
}
