package site.kpeclub.launcher.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Evaluates Mojang's "rules" arrays (used by libraries and by conditional arguments).
 * Windows only, but aware of CPU architecture so 32-bit and 64-bit PCs pick the right files.
 */
public final class Rules {

    private Rules() {}

    /** @return true if an item with this "rules" array applies to this PC. No rules = allowed. */
    public static boolean allowed(JsonObject owner, PlatformInfo.Arch arch) {
        if (owner == null || !owner.has("rules")) return true;
        return allowed(owner.getAsJsonArray("rules"), arch);
    }

    public static boolean allowed(JsonArray rules, PlatformInfo.Arch arch) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allowed = false;
        for (JsonElement el : rules) {
            JsonObject rule = el.getAsJsonObject();
            if (!matches(rule, arch)) continue;
            allowed = "allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }

    private static boolean matches(JsonObject rule, PlatformInfo.Arch arch) {
        // Features (demo mode, custom resolution, quick play...) are all off here, except the
        // ones we handle ourselves. A rule that requires a feature we don't enable never matches.
        if (rule.has("features")) {
            JsonObject features = rule.getAsJsonObject("features");
            for (String key : features.keySet()) {
                boolean wanted = features.get(key).getAsBoolean();
                boolean have = featureEnabled(key);
                if (wanted != have) return false;
            }
        }
        if (rule.has("os")) {
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !"windows".equals(os.get("name").getAsString())) return false;
            if (os.has("arch")) {
                // Mojang uses "x86" for 32-bit only; 64-bit PCs must NOT match it.
                if (!os.get("arch").getAsString().equals(arch.ruleName)) return false;
            }
            if (os.has("version")) {
                String regex = os.get("version").getAsString();
                String actual = System.getProperty("os.version", "");
                try {
                    if (!java.util.regex.Pattern.compile(regex).matcher(actual).find()) return false;
                } catch (Exception ignored) {
                    // bad regex in a version json — ignore this condition
                }
            }
        }
        return true;
    }

    /** Features this launcher turns on. */
    private static boolean featureEnabled(String key) {
        return switch (key) {
            case "has_custom_resolution" -> true;
            case "has_quick_plays_support", "is_quick_play_multiplayer" -> false; // we pass these flags ourselves
            default -> false;
        };
    }
}
