package site.kpeclub.launcher.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.model.ModSearchResult;
import site.kpeclub.launcher.util.LauncherConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to Modrinth's v2 API to search for individual mods and download specific mod files
 * directly into .minecraft/mods — independent of the modpack importer, for people who just
 * want to add one or two mods rather than a whole pack.
 *
 * https://docs.modrinth.com/api/
 * Modrinth requires a real, identifying User-Agent on every request or it may block the call.
 */
public class ModrinthClient {

    private static final String API_BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "KPEClubLauncher/1.0 (kpeclub.site)";

    private final HttpClient http = HttpClient.newHttpClient();

    public record ModFile(String versionId, String versionNumber, String fileName,
                           String downloadUrl, String sha1, long size, List<String> gameVersions, List<String> loaders) {}

    /** Searches Modrinth for mods matching the query, optionally filtered to a Minecraft
     *  version and loader (pass null for either to skip that filter). */
    public List<ModSearchResult> search(String query, String minecraftVersion, String loader) throws IOException, InterruptedException {
        StringBuilder facets = new StringBuilder("[[\"project_type:mod\"]");
        if (loader != null) facets.append(",[\"loaders:").append(loader.toLowerCase()).append("\"]");
        if (minecraftVersion != null) facets.append(",[\"versions:").append(minecraftVersion).append("\"]");
        facets.append("]");

        String url = API_BASE + "/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) +
                "&facets=" + URLEncoder.encode(facets.toString(), StandardCharsets.UTF_8) +
                "&limit=20";

        JsonObject response = fetchJson(url);
        JsonArray hits = response.getAsJsonArray("hits");

        List<ModSearchResult> results = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            JsonObject hit = hits.get(i).getAsJsonObject();
            results.add(new ModSearchResult(
                    hit.get("project_id").getAsString(),
                    hit.has("slug") ? hit.get("slug").getAsString() : hit.get("project_id").getAsString(),
                    hit.get("title").getAsString(),
                    hit.has("description") ? hit.get("description").getAsString() : "",
                    hit.has("author") ? hit.get("author").getAsString() : "",
                    hit.has("downloads") ? hit.get("downloads").getAsLong() : 0,
                    hit.has("icon_url") && !hit.get("icon_url").isJsonNull() ? hit.get("icon_url").getAsString() : null
            ));
        }
        return results;
    }

    /** Lists downloadable files for a project, optionally filtered to a Minecraft version + loader.
     *  Returns newest first (Modrinth's own ordering). */
    public List<ModFile> listVersions(String projectId, String minecraftVersion, String loader) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(API_BASE + "/project/" + projectId + "/version");
        List<String> queryParts = new ArrayList<>();
        if (minecraftVersion != null) {
            queryParts.add("game_versions=" + URLEncoder.encode("[\"" + minecraftVersion + "\"]", StandardCharsets.UTF_8));
        }
        if (loader != null) {
            queryParts.add("loaders=" + URLEncoder.encode("[\"" + loader.toLowerCase() + "\"]", StandardCharsets.UTF_8));
        }
        if (!queryParts.isEmpty()) {
            url.append("?").append(String.join("&", queryParts));
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("Modrinth API error (" + resp.statusCode() + ") fetching versions for " + projectId);
        }
        JsonArray versions = JsonParser.parseString(resp.body()).getAsJsonArray();

        List<ModFile> files = new ArrayList<>();
        for (int i = 0; i < versions.size(); i++) {
            JsonObject version = versions.get(i).getAsJsonObject();
            JsonArray fileArray = version.getAsJsonArray("files");
            if (fileArray.isEmpty()) continue;

            // Prefer the file marked primary, otherwise take the first one.
            JsonObject file = fileArray.get(0).getAsJsonObject();
            for (int f = 0; f < fileArray.size(); f++) {
                JsonObject candidate = fileArray.get(f).getAsJsonObject();
                if (candidate.has("primary") && candidate.get("primary").getAsBoolean()) {
                    file = candidate;
                    break;
                }
            }

            List<String> gameVersions = new ArrayList<>();
            if (version.has("game_versions")) {
                for (var gv : version.getAsJsonArray("game_versions")) gameVersions.add(gv.getAsString());
            }
            List<String> loaders = new ArrayList<>();
            if (version.has("loaders")) {
                for (var l : version.getAsJsonArray("loaders")) loaders.add(l.getAsString());
            }

            String sha1 = null;
            if (file.has("hashes") && file.getAsJsonObject("hashes").has("sha1")) {
                sha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
            }

            files.add(new ModFile(
                    version.get("id").getAsString(),
                    version.has("version_number") ? version.get("version_number").getAsString() : "",
                    file.get("filename").getAsString(),
                    file.get("url").getAsString(),
                    sha1,
                    file.has("size") ? file.get("size").getAsLong() : 0,
                    gameVersions,
                    loaders
            ));
        }
        return files;
    }

    /** Downloads the given file directly into .minecraft/mods. */
    public Path downloadModFile(ModFile file) throws IOException, InterruptedException {
        Path modsDir = LauncherConfig.GAME_DIR.resolve("mods");
        Files.createDirectories(modsDir);
        Path dest = modsDir.resolve(file.fileName());

        HttpRequest req = HttpRequest.newBuilder(URI.create(file.downloadUrl()))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() >= 400) {
            Files.deleteIfExists(dest);
            throw new IOException("Failed to download " + file.fileName() + " (" + resp.statusCode() + ")");
        }
        return dest;
    }

    private JsonObject fetchJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("Modrinth API error (" + resp.statusCode() + "): " + url);
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
