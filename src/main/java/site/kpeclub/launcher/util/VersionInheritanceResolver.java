package site.kpeclub.launcher.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves "inheritsFrom" version JSON files — the format used by Fabric Installer,
 * Forge, and other loaders when installed via the OFFICIAL Minecraft Launcher (as
 * opposed to Fabric's meta API, which we merge ourselves in FabricInstaller).
 *
 * A child file like fabric-loader-0.19.3-1.21.1.json only has mainClass + its own
 * libraries + "inheritsFrom":"1.21.1" — it relies on the official launcher to fetch
 * downloads/assetIndex/etc from the parent version at runtime. Since we don't have
 * that merging logic built in, we do it once here and treat the result the same way
 * as any other version json from then on.
 */
public class VersionInheritanceResolver {

    /**
     * If the given version json has "inheritsFrom" and is missing "downloads"/"assetIndex",
     * merges it with its parent version (found in the same versions/ folder). Returns the
     * original json unchanged if there's nothing to inherit, or null if the parent can't be found.
     */
    public static JsonObject resolve(JsonObject versionJson, Path versionsDir) throws IOException {
        return resolveInternal(versionJson, versionsDir, new java.util.HashSet<>());
    }

    /** "visited" stops endless loops when version files point at each other (A -> B -> A, or A -> A). */
    private static JsonObject resolveInternal(JsonObject versionJson, Path versionsDir,
                                              java.util.Set<String> visited) throws IOException {
        if (!versionJson.has("inheritsFrom")) return versionJson;
        if (versionJson.has("id")) visited.add(versionJson.get("id").getAsString());
        boolean alreadyComplete = versionJson.has("downloads")
                && versionJson.getAsJsonObject("downloads").has("client")
                && versionJson.has("assetIndex");
        if (alreadyComplete) return versionJson;

        String parentId = Safe.requireSafeId(versionJson.get("inheritsFrom").getAsString(), "parent version id");
        if (!visited.add(parentId)) return null; // circular inheritsFrom
        Path parentJsonFile = versionsDir.resolve(parentId).resolve(parentId + ".json");
        if (!Files.exists(parentJsonFile)) {
            return null; // parent not installed — can't resolve locally
        }

        JsonObject parentJson = JsonParser.parseString(Files.readString(parentJsonFile)).getAsJsonObject();
        // Parent might itself inherit from something (rare, but handle it) before we use it.
        JsonObject resolvedParent = resolveInternal(parentJson, versionsDir, visited);
        if (resolvedParent == null) return null;

        JsonObject merged = resolvedParent.deepCopy();

        // Child's own identity/launch info wins.
        merged.addProperty("id", versionJson.get("id").getAsString());
        if (versionJson.has("mainClass")) {
            merged.addProperty("mainClass", versionJson.get("mainClass").getAsString());
        }

        // Old-format loaders (legacy Forge/OptiFine): the child's "minecraftArguments" replaces the parent's.
        if (versionJson.has("minecraftArguments")) {
            merged.addProperty("minecraftArguments", versionJson.get("minecraftArguments").getAsString());
            if (merged.has("arguments")) merged.getAsJsonObject("arguments").remove("game");
        }
        if (versionJson.has("javaVersion")) merged.add("javaVersion", versionJson.get("javaVersion"));
        if (versionJson.has("assetIndex")) merged.add("assetIndex", versionJson.get("assetIndex"));
        if (versionJson.has("logging")) merged.add("logging", versionJson.get("logging"));

        // Remember which folder actually holds the client jar, since an inheritsFrom install
        // (Fabric/Forge via the official launcher) has no jar of its own — it reuses the
        // root parent vanilla version's jar file directly.
        String rootParentId = resolvedParent.has("inheritsFromResolvedParentId")
                ? resolvedParent.get("inheritsFromResolvedParentId").getAsString()
                : parentId;
        merged.addProperty("inheritsFromResolvedParentId", rootParentId);

        // Combine libraries: parent's first, then child's own (e.g. Fabric loader + ASM + intermediary).
        JsonArray combinedLibraries = new JsonArray();
        if (resolvedParent.has("libraries")) combinedLibraries.addAll(resolvedParent.getAsJsonArray("libraries"));
        if (versionJson.has("libraries")) combinedLibraries.addAll(versionJson.getAsJsonArray("libraries"));
        merged.add("libraries", combinedLibraries);

        // Combine arguments.game: parent's first, then child's own (this is where loader-specific
        // flags like OptiFine's "--tweakClass optifine.OptiFineTweaker" or Baritone's tweaker live).
        JsonArray combinedGameArgs = new JsonArray();
        if (resolvedParent.has("arguments") && resolvedParent.getAsJsonObject("arguments").has("game")) {
            combinedGameArgs.addAll(resolvedParent.getAsJsonObject("arguments").getAsJsonArray("game"));
        }
        if (versionJson.has("arguments") && versionJson.getAsJsonObject("arguments").has("game")) {
            combinedGameArgs.addAll(versionJson.getAsJsonObject("arguments").getAsJsonArray("game"));
        }
        if (combinedGameArgs.size() > 0) {
            JsonObject argumentsObj = merged.has("arguments")
                    ? merged.getAsJsonObject("arguments")
                    : new JsonObject();
            argumentsObj.add("game", combinedGameArgs);
            merged.add("arguments", argumentsObj);
        }

        // Combine arguments.jvm the same way: parent's first, then child's own. This is where
        // modern Forge's module-path setup (-p, --add-modules ALL-MODULE-PATH, etc) lives.
        JsonArray combinedJvmArgs = new JsonArray();
        if (resolvedParent.has("arguments") && resolvedParent.getAsJsonObject("arguments").has("jvm")) {
            combinedJvmArgs.addAll(resolvedParent.getAsJsonObject("arguments").getAsJsonArray("jvm"));
        }
        if (versionJson.has("arguments") && versionJson.getAsJsonObject("arguments").has("jvm")) {
            combinedJvmArgs.addAll(versionJson.getAsJsonObject("arguments").getAsJsonArray("jvm"));
        }
        if (combinedJvmArgs.size() > 0) {
            JsonObject argumentsObj = merged.has("arguments")
                    ? merged.getAsJsonObject("arguments")
                    : new JsonObject();
            argumentsObj.add("jvm", combinedJvmArgs);
            merged.add("arguments", argumentsObj);
        }

        return merged;
    }
}
