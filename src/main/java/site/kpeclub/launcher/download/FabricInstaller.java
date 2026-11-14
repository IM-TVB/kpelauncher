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
 * Talks to Fabric's meta API (https://meta.fabricmc.net) to find loader versions
 * for a given Minecraft version, and builds a "profile" JSON in the same shape as
 * Mojang's version JSON so GameDownloader/GameLauncher can treat it uniformly —
 * just with mainClass swapped to Fabric's knot client, and Fabric's own libraries
 * appended to the library list.
 */
public class FabricInstaller {

    private static final String META_BASE = "https://meta.fabricmc.net/v2";
    private final HttpClient http = site.kpeclub.launcher.util.FileDownloader.http();

    public record FabricLoaderVersion(String version, boolean stable) {}

    /** Lists available Fabric loader versions compatible with the given Minecraft version. */
    public List<FabricLoaderVersion> fetchLoaderVersions(String minecraftVersion) throws IOException, InterruptedException {
        site.kpeclub.launcher.util.Safe.requireSafeId(minecraftVersion, "Minecraft version");
        String encodedMc = java.net.URLEncoder.encode(minecraftVersion, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = META_BASE + "/versions/loader/" + encodedMc;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("User-Agent", "KPEClubLauncher/1.8.1")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("Fabric has no loader builds for Minecraft " + minecraftVersion);
        }
        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        List<FabricLoaderVersion> result = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject entry = arr.get(i).getAsJsonObject();
            JsonObject loader = entry.getAsJsonObject("loader");
            result.add(new FabricLoaderVersion(
                    loader.get("version").getAsString(),
                    loader.get("stable").getAsBoolean()
            ));
        }
        return result;
    }

    /**
     * Fetches the full Fabric "launcher profile" JSON for a given MC version + loader version.
     * This has the same overall shape as a vanilla version JSON (libraries, mainClass,
     * inheritsFrom), so the rest of the download/launch pipeline doesn't need to know
     * it's Fabric — except mainClass is Fabric's knot client and there are extra libraries.
     */
    public JsonObject fetchFabricProfile(String minecraftVersion, String loaderVersion) throws IOException, InterruptedException {
        site.kpeclub.launcher.util.Safe.requireSafeId(minecraftVersion, "Minecraft version");
        site.kpeclub.launcher.util.Safe.requireSafeId(loaderVersion, "Fabric loader version");
        String encodedMc = java.net.URLEncoder.encode(minecraftVersion, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String encodedLoader = java.net.URLEncoder.encode(loaderVersion, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = META_BASE + "/versions/loader/" + encodedMc + "/" + encodedLoader + "/profile/json";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("User-Agent", "KPEClubLauncher/1.8.1")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("Failed to fetch Fabric profile for " + minecraftVersion + " + loader " + loaderVersion);
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /**
     * Merges a Fabric profile with the vanilla version JSON it depends on ("inheritsFrom"):
     * vanilla libraries + Fabric libraries combined, vanilla's downloads/assetIndex kept,
     * mainClass, id, and Fabric's extra JVM/game arguments taken from the Fabric profile.
     */
    public JsonObject buildMergedVersionJson(JsonObject vanillaJson, JsonObject fabricProfile) {
        JsonObject merged = vanillaJson.deepCopy();

        // Fabric's own id (e.g. "fabric-loader-0.16.9-1.21.1") — keeps Fabric installs
        // separate from vanilla ones in the versions/ folder.
        merged.addProperty("id", fabricProfile.get("id").getAsString());
        merged.addProperty("mainClass", fabricProfile.get("mainClass").getAsString());
        if (fabricProfile.has("inheritsFrom")) {
            merged.addProperty("inheritsFrom", fabricProfile.get("inheritsFrom").getAsString());
        } else if (vanillaJson.has("id")) {
            merged.addProperty("inheritsFrom", vanillaJson.get("id").getAsString());
        }

        JsonArray combinedLibraries = new JsonArray();
        if (vanillaJson.has("libraries")) combinedLibraries.addAll(vanillaJson.getAsJsonArray("libraries"));
        if (fabricProfile.has("libraries")) combinedLibraries.addAll(fabricProfile.getAsJsonArray("libraries"));
        merged.add("libraries", combinedLibraries);

        // Merge arguments.jvm and arguments.game from fabricProfile (e.g. -DFabricMcEmu= net.minecraft.client.main.Main)
        if (fabricProfile.has("arguments")) {
            JsonObject fabricArgs = fabricProfile.getAsJsonObject("arguments");
            JsonObject mergedArgs = merged.has("arguments") ? merged.getAsJsonObject("arguments") : new JsonObject();

            if (fabricArgs.has("game")) {
                JsonArray combinedGame = mergedArgs.has("game") ? mergedArgs.getAsJsonArray("game") : new JsonArray();
                combinedGame.addAll(fabricArgs.getAsJsonArray("game"));
                mergedArgs.add("game", combinedGame);
            }
            if (fabricArgs.has("jvm")) {
                JsonArray combinedJvm = mergedArgs.has("jvm") ? mergedArgs.getAsJsonArray("jvm") : new JsonArray();
                combinedJvm.addAll(fabricArgs.getAsJsonArray("jvm"));
                mergedArgs.add("jvm", combinedJvm);
            }
            merged.add("arguments", mergedArgs);
        }

        return merged;
    }
}
