package site.kpeclub.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.auth.MicrosoftAuth.MinecraftSession;
import site.kpeclub.launcher.util.LauncherConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Builds the JVM command line for a downloaded version and launches Minecraft as a subprocess.
 * Optionally auto-connects to a target server via --server / --port (works for most vanilla
 * versions' handshake, though modern versions may need the user to click Multiplayer once
 * if quickPlay args aren't used).
 */
public class GameLauncher {

    /**
     * @param versionId    e.g. "1.21.1"
     * @param session      logged-in Microsoft/Minecraft session
     * @param serverHost   nullable — if set, launcher passes it through so MC can auto-join
     * @param serverPort   ignored if serverHost is null
     * @param width        game window width in pixels (default 1280)
     * @param height       game window height in pixels (default 720)
     */
    public Process launch(String versionId, MinecraftSession session,
                           String serverHost, Integer serverPort,
                           int width, int height) throws Exception {

        Path versionDir = LauncherConfig.VERSIONS_DIR.resolve(versionId);
        Path versionJsonFile = versionDir.resolve(versionId + ".json");
        JsonObject versionJson = JsonParser.parseString(Files.readString(versionJsonFile)).getAsJsonObject();

        if (versionJson.has("inheritsFrom")) {
            JsonObject resolved = site.kpeclub.launcher.util.VersionInheritanceResolver
                    .resolve(versionJson, LauncherConfig.VERSIONS_DIR);
            if (resolved == null) {
                throw new IllegalStateException(
                        "Cannot launch " + versionId + ": it inherits from \"" +
                        versionJson.get("inheritsFrom").getAsString() +
                        "\", but that parent version isn't installed. Install that vanilla version first.");
            }
            versionJson = resolved;
        }

        if (!versionJson.has("downloads") || !versionJson.has("mainClass") || !versionJson.has("assetIndex")) {
            throw new IllegalStateException(
                    "Cached version file for " + versionId + " is incomplete or corrupted. " +
                    "Delete the folder \"" + versionDir + "\" and try downloading again.");
        }

        extractNatives(versionJson);

        String classpath = buildClasspath(versionJson, versionId);
        String mainClass = versionJson.get("mainClass").getAsString();
        String assetIndexId = versionJson.getAsJsonObject("assetIndex").get("id").getAsString();

        List<String> command = new ArrayList<>();
        command.add(findJavaBinary(versionJson));
        command.add("-Xmx2G");
        command.add("-Djava.library.path=" + LauncherConfig.NATIVES_DIR.toAbsolutePath());

        // Loader-specific JVM args (e.g. modern Forge's module-path setup: "-p", "--add-modules
        // ALL-MODULE-PATH", library-directory defines, etc). These come from the version json's
        // arguments.jvm array and MUST be added before -cp/mainClass since they're real JVM flags,
        // not program args. Values contain placeholder tokens like ${classpath} that we substitute.
        List<String> jvmArgs = buildJvmArgs(versionJson, classpath);
        command.addAll(jvmArgs);

        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);

        // Standard MC client args
        command.add("--username"); command.add(session.username());
        command.add("--version"); command.add(versionId);
        command.add("--gameDir"); command.add(LauncherConfig.GAME_DIR.toAbsolutePath().toString());
        command.add("--assetsDir"); command.add(LauncherConfig.ASSETS_DIR.toAbsolutePath().toString());
        command.add("--assetIndex"); command.add(assetIndexId);
        command.add("--uuid"); command.add(session.uuid());
        command.add("--accessToken"); command.add(session.accessToken());
        command.add("--userType"); command.add("msa");
        command.add("--versionType"); command.add("release");
        command.add("--width"); command.add(String.valueOf(width));
        command.add("--height"); command.add(String.valueOf(height));

        // Auto-connect to a server (modern MC: quickPlayMultiplayer)
        if (serverHost != null) {
            command.add("--quickPlayMultiplayer");
            command.add(serverHost + (serverPort != null ? ":" + serverPort : ""));
        }

        // Extra loader-specific args (e.g. OptiFine's "--tweakClass optifine.OptiFineTweaker",
        // Forge's "--launchTarget fmlclient --fml.forgeVersion ...", or Baritone/other mods'
        // tweakers). Modern vanilla's own arguments.game ALSO repeats standard flags like
        // "--username", "--version", "--gameDir" etc with ${...} placeholders — we must skip
        // those since we already set them explicitly above, or Minecraft's arg parser throws
        // "multiple arguments for option X". We only pass through flags we don't already handle.
        if (versionJson.has("arguments")) {
            JsonObject arguments = versionJson.getAsJsonObject("arguments");
            if (arguments.has("game")) {
                var gameArgsArray = arguments.getAsJsonArray("game");
                for (int i = 0; i < gameArgsArray.size(); i++) {
                    var element = gameArgsArray.get(i);
                    if (!element.isJsonPrimitive()) continue; // skip rule-based/conditional entries

                    String value = element.getAsString();

                    // Flag names we already add explicitly above — skip the flag AND its value.
                    if (isKnownStandardFlag(value)) {
                        i++; // also skip the following value token (e.g. skip "${version_name}" after "--version")
                        continue;
                    }

                    // Anything else (tweaker classes, fml.* flags, their values, etc) gets kept,
                    // with placeholders substituted just in case (e.g. ${version_name}).
                    command.add(substitutePlaceholders(value, classpath));
                }
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(LauncherConfig.GAME_DIR.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // IMPORTANT: if nothing reads the process's stdout, the OS pipe buffer fills up
        // and Minecraft can hang waiting to write to it — this looks exactly like a freeze.
        // Stream it out to our own console so it never blocks, and so we can see real errors.
        Thread outputReader = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[MC] " + line);
                }
            } catch (IOException ignored) {
                // process ended, pipe closed — normal
            }
        });
        outputReader.setDaemon(true);
        outputReader.start();

        return process;
    }

    /** Flags this launcher already adds explicitly — if the version json's own arguments.game
     *  template repeats one of these (which modern vanilla always does), we must skip both the
     *  flag and its following value, or Minecraft's arg parser throws "multiple arguments". */
    private boolean isKnownStandardFlag(String value) {
        return switch (value) {
            case "--username", "--version", "--gameDir", "--assetsDir", "--assetIndex",
                 "--uuid", "--accessToken", "--userType", "--versionType",
                 "--width", "--height", "--quickPlayMultiplayer",
                 "--clientId", "--xuid" -> true; // last two appear in some modern templates, unused by us but still skip cleanly
            default -> false;
        };
    }

    /**
     * Reads the version json's arguments.jvm array (used by modern Forge for module-path
     * setup, library-directory defines, etc) and substitutes Mojang/Forge's placeholder
     * tokens with real values. Skips conditional/rule-based entries (JsonObject elements) —
     * those are OS-specific flags we don't need since this launcher is Windows-only.
     */
    private List<String> buildJvmArgs(JsonObject versionJson, String classpath) {
        List<String> result = new ArrayList<>();
        if (!versionJson.has("arguments")) return result;
        JsonObject arguments = versionJson.getAsJsonObject("arguments");
        if (!arguments.has("jvm")) return result;

        for (var element : arguments.getAsJsonArray("jvm")) {
            if (!element.isJsonPrimitive()) continue; // skip rule-based/conditional entries
            String raw = element.getAsString();
            result.add(substitutePlaceholders(raw, classpath));
        }
        return result;
    }

    /** Replaces the placeholder tokens Mojang/Forge use in jvm/game argument templates. */
    private String substitutePlaceholders(String raw, String classpath) {
        return raw
                .replace("${classpath}", classpath)
                .replace("${classpath_separator}", File.pathSeparator)
                .replace("${natives_directory}", LauncherConfig.NATIVES_DIR.toAbsolutePath().toString())
                .replace("${library_directory}", LauncherConfig.LIBRARIES_DIR.toAbsolutePath().toString())
                .replace("${game_directory}", LauncherConfig.GAME_DIR.toAbsolutePath().toString())
                .replace("${launcher_name}", "KPEClubLauncher")
                .replace("${launcher_version}", "1.0.0")
                .replace("${version_name}", "");
        // Note: ${version_name} is left blank rather than guessed — Forge sometimes uses it in
        // -DignoreList= style flags where an exact match matters more than a real value; an
        // empty string is safer than a wrong one. If a specific Forge build needs it, this is
        // the spot to add version-aware substitution.
    }

    private String buildClasspath(JsonObject versionJson, String versionId) {
        List<String> paths = new ArrayList<>();
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        for (int i = 0; i < libraries.size(); i++) {
            JsonObject lib = libraries.get(i).getAsJsonObject();

            if (lib.has("downloads")) {
                // Vanilla-style library: has a proper downloads.artifact.path
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("artifact")) {
                    String path = downloads.getAsJsonObject("artifact").get("path").getAsString();
                    paths.add(LauncherConfig.LIBRARIES_DIR.resolve(path).toAbsolutePath().toString());
                }
            } else if (lib.has("name")) {
                // Fabric-style library: just a Maven coordinate ("group:artifact:version"),
                // no downloads block — derive the relative jar path ourselves.
                String relativePath = site.kpeclub.launcher.util.MavenUtil.coordinateToPath(lib.get("name").getAsString());
                if (relativePath != null) {
                    paths.add(LauncherConfig.LIBRARIES_DIR.resolve(relativePath).toAbsolutePath().toString());
                }
            }
        }
        paths.add(resolveClientJarPath(versionJson, versionId).toAbsolutePath().toString());
        return String.join(File.pathSeparator, paths);
    }

    /** Finds the actual client jar for this version. If the version's own folder has no jar
     *  (common for "inheritsFrom" installs like Fabric-via-official-launcher, which reuse the
     *  parent vanilla version's jar), falls back to searching sibling version folders whose
     *  release time / id matches, defaulting to the parent id embedded during inheritance
     *  resolution (stored as "inheritsFromResolvedParentId" by GameLauncher before calling this). */
    private Path resolveClientJarPath(JsonObject versionJson, String versionId) {
        Path ownJar = LauncherConfig.VERSIONS_DIR.resolve(versionId).resolve(versionId + ".jar");
        if (Files.exists(ownJar)) return ownJar;

        if (versionJson.has("inheritsFromResolvedParentId")) {
            String parentId = versionJson.get("inheritsFromResolvedParentId").getAsString();
            Path parentJar = LauncherConfig.VERSIONS_DIR.resolve(parentId).resolve(parentId + ".jar");
            if (Files.exists(parentJar)) return parentJar;
        }

        return ownJar; // let it fail loudly downstream if truly missing — better than silently wrong path
    }

    /** Extracts native .dll files from downloaded native jars into the natives directory. */
    private void extractNatives(JsonObject versionJson) throws Exception {
        Files.createDirectories(LauncherConfig.NATIVES_DIR);
        File nativesDir = LauncherConfig.NATIVES_DIR.toFile();
        for (File f : nativesDir.listFiles((dir, name) -> name.endsWith(".jar"))) {
            try (JarFile jar = new JarFile(f)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".dll")) {
                        Path out = LauncherConfig.NATIVES_DIR.resolve(
                                Paths.get(entry.getName()).getFileName().toString());
                        if (!Files.exists(out)) {
                            Files.copy(jar.getInputStream(entry), out);
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds a Java binary suitable for running MINECRAFT for this specific version.
     *
     * Order of preference:
     *  1. Mojang's own official runtime for this version's required component (downloaded
     *     via JreManager — the exact same JRE the real Minecraft Launcher uses). This is
     *     the only option guaranteed to have everything Minecraft/mods need, since it's
     *     literally the runtime Mojang tests against.
     *  2. System JDK (JAVA_HOME or PATH), if the Mojang runtime somehow isn't available.
     *  3. This process's own runtime, as an absolute last resort (only correct when running
     *     via "mvn javafx:run" with a full JDK — NOT correct when running from the packaged
     *     .exe, whose jlink-trimmed runtime is missing modules like java.logging).
     */
    private String findJavaBinary(JsonObject versionJson) throws IOException {
        site.kpeclub.launcher.download.JreManager jreManager = new site.kpeclub.launcher.download.JreManager();
        String component = jreManager.requiredComponent(versionJson);
        Path mojangJava = jreManager.javaExecutableFor(component);
        if (mojangJava != null && Files.exists(mojangJava)) {
            return mojangJava.toString();
        }

        // Fell through — Mojang's runtime for this component wasn't downloaded (shouldn't
        // normally happen if GameDownloader ran first, but handle it gracefully).
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null) {
            Path candidate = resolveJavaExe(Paths.get(javaHomeEnv));
            if (candidate != null && hasLoggingModule(candidate)) return candidate.toString();
        }

        Path onPath = findJavaOnPath();
        if (onPath != null && hasLoggingModule(onPath)) return onPath.toString();

        String ownJavaHome = System.getProperty("java.home");
        Path ownExe = resolveJavaExe(Paths.get(ownJavaHome));
        if (ownExe != null) return ownExe.toString();

        throw new IllegalStateException(
                "Could not find a Java Runtime to launch Minecraft with (needed component: " +
                component + "). Try launching once with internet connected so it can download " +
                "automatically, or install a JDK/JRE (17+) and set JAVA_HOME.");
    }

    private Path resolveJavaExe(Path javaHome) {
        Path javaw = javaHome.resolve("bin").resolve("javaw.exe");
        if (Files.exists(javaw)) return javaw;
        Path java = javaHome.resolve("bin").resolve("java.exe");
        if (Files.exists(java)) return java;
        return null;
    }

    /** Runs the candidate java binary with --list-modules and checks java.logging is present —
     *  a cheap, reliable way to detect a jlink-trimmed runtime missing modules Minecraft needs. */
    private boolean hasLoggingModule(Path javaExe) {
        try {
            // Use java.exe (not javaw.exe) for this check since we need to read stdout.
            Path checkableExe = javaExe.getFileName().toString().equals("javaw.exe")
                    ? javaExe.resolveSibling("java.exe")
                    : javaExe;
            if (!Files.exists(checkableExe)) return true; // can't verify, assume OK rather than block launch

            Process proc = new ProcessBuilder(checkableExe.toString(), "--list-modules")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                output = reader.lines().reduce("", (a, b) -> a + "\n" + b);
            }
            proc.waitFor();
            return output.contains("java.logging");
        } catch (Exception e) {
            return true; // if the check itself fails, don't block launch over it
        }
    }

    /** Searches PATH for a java executable, e.g. from a JDK installed separately from this app. */
    private Path findJavaOnPath() {
        try {
            Process proc = new ProcessBuilder("where", "java").start();
            String firstLine;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                firstLine = reader.readLine(); // first match, e.g. "C:\Program Files\Java\jdk-21\bin\java.exe"
            }
            proc.waitFor();
            if (firstLine == null || firstLine.isBlank()) return null;

            Path javaExe = Paths.get(firstLine.trim());
            if (!Files.exists(javaExe)) return null;

            // javaExe is .../bin/java.exe — its JAVA_HOME is two levels up.
            Path binDir = javaExe.getParent();
            Path javaHome = (binDir != null) ? binDir.getParent() : null;
            return (javaHome != null) ? resolveJavaExe(javaHome) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
