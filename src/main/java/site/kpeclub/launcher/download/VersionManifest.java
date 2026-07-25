package site.kpeclub.launcher.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches https://launchermeta.mojang.com/mc/game/version_manifest_v2.json
 * and per-version JSON files that describe libraries, assets, and the main class.
 */
public class VersionManifest {

    private static final String MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpClient http = HttpClient.newHttpClient();

    public record VersionEntry(String id, String type, String url) {}

    public List<VersionEntry> fetchVersionList() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MANIFEST_URL)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray versions = root.getAsJsonArray("versions");

        List<VersionEntry> list = new ArrayList<>();
        for (int i = 0; i < versions.size(); i++) {
            JsonObject v = versions.get(i).getAsJsonObject();
            list.add(new VersionEntry(
                    v.get("id").getAsString(),
                    v.get("type").getAsString(),
                    v.get("url").getAsString()
            ));
        }
        return list;
    }

    /** Fetches the full version JSON (libraries, asset index, main class, arguments). */
    public JsonObject fetchVersionJson(String versionUrl) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(versionUrl)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
