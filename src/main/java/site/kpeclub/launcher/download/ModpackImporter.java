package site.kpeclub.launcher.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.model.ModpackInfo;
import site.kpeclub.launcher.util.LauncherConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
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

    private final HttpClient http = HttpClient.newHttpClient();

    public record ImportResult(ModpackInfo info, String minecraftVersion, String loader, String loaderVersion) {}

    /**
     * Imports the given .mrpack file: extracts overrides into .minecraft, downloads all
     * referenced mod files into .minecraft/mods, and returns a summary of what was imported.
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

            // 1. Download every referenced mod file
            JsonArray files = index.has("files") ? index.getAsJsonArray("files") : new JsonArray();
            downloadIndexFiles(files, progressCallback);

            // 2. Apply overrides/ and client-overrides/ (configs, resource packs, etc) safely
            applyOverridesFromZip(zip, "overrides/");
            applyOverridesFromZip(zip, "client-overrides/"); // client-only extras layer on top, if present

            ModpackInfo info = new ModpackInfo(name, minecraftVersion, loader, loaderVersion, files.size());
            return new ImportResult(info, minecraftVersion, loader, loaderVersion);
        }
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
    private void downloadIndexFiles(JsonArray files, Consumer<DownloadProgress> progressCallback) throws Exception {
        long totalBytes = 0;
        for (int i = 0; i < files.size(); i++) {
            JsonObject file = files.get(i).getAsJsonObject();
            totalBytes += file.has("fileSize") ? file.get("fileSize").getAsLong() : 0;
        }

        long downloaded = 0;
        for (int i = 0; i < files.size(); i++) {
            JsonObject file = files.get(i).getAsJsonObject();
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
            if (downloadUrls.isEmpty()) {
                throw new IOException("No download URL provided for " + relativePath);
            }
            String url = downloadUrls.get(0).getAsString();

            Files.createDirectories(dest.getParent());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
            if (resp.statusCode() >= 400) {
                Files.deleteIfExists(dest);
                throw new IOException("Failed to download " + relativePath + " (" + resp.statusCode() + ")");
            }

            if (expectedSha1 != null && !sha1Matches(dest, expectedSha1)) {
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
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;

            String relativePath = entry.getName().substring(prefix.length());
            if (relativePath.isBlank()) continue;

            Path dest = resolveSafely(LauncherConfig.GAME_DIR, relativePath);
            Files.createDirectories(dest.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Resolves a manifest/zip-entry-provided relative path against the instance directory,
     * rejecting anything that would escape it (via "..", a leading "/", or a drive letter).
     * This is the key security check for handling untrusted modpack files.
     */
    private Path resolveSafely(Path instanceDir, String relativePath) {
        if (relativePath.contains("..")) {
            throw new SecurityException("Modpack file path contains '..' — refusing for safety: " + relativePath);
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new SecurityException("Modpack file path is absolute — refusing for safety: " + relativePath);
        }
        if (relativePath.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("Modpack file path specifies a drive — refusing for safety: " + relativePath);
        }

        Path resolved = instanceDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(instanceDir.normalize())) {
            throw new SecurityException("Modpack file path escapes the instance directory: " + relativePath);
        }
        return resolved;
    }

    private boolean sha1Matches(Path file, String expectedHex) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(expectedHex);
        } catch (Exception e) {
            return false; // if we can't verify, treat as not matching — safer to re-download
        }
    }
}
