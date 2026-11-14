package site.kpeclub.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.auth.MicrosoftAuth.MinecraftSession;
import site.kpeclub.launcher.download.GameDownloader;
import site.kpeclub.launcher.download.JreManager;
import site.kpeclub.launcher.model.LauncherSettings;
import site.kpeclub.launcher.util.*;
import site.kpeclub.launcher.util.PlatformInfo.Arch;
import site.kpeclub.launcher.util.LibraryResolver.LibFile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Builds the Java command line for a downloaded version and starts Minecraft.
 *
 * Works for every era of Minecraft:
 *  - old versions (1.5 - 1.12): "minecraftArguments" text + legacy assets
 *  - new versions (1.13+):      "arguments" with rules
 *  - loaders (Fabric, Forge, OptiFine) through "inheritsFrom"
 * and for both 32-bit and 64-bit Java.
 */
public class GameLauncher {

    private static final int MAX_LOG_LINES = 60;
    private static final Deque<String> LAST_OUTPUT = new ArrayDeque<>();
    private static volatile String lastJavaBinary = "";

    /** Last lines the game printed — used to explain a crash in the UI. */
    public static synchronized List<String> lastOutput() {
        return new ArrayList<>(LAST_OUTPUT);
    }

    public static String lastJavaUsed() {
        return lastJavaBinary;
    }

    public static Path logFile() {
        return LauncherConfig.ROOT_DIR.resolve("logs").resolve("latest.log");
    }

    public Process launch(String versionId, MinecraftSession session,
                          String serverHost, Integer serverPort,
                          int width, int height, JavaChoice choice) throws Exception {

        if (!Safe.isValidUsername(session.username())) {
            throw new IllegalStateException("Invalid player name. Use 3-16 letters, numbers or _.");
        }
        Safe.requireSafeId(versionId, "version id");
        LauncherSettings settings = LauncherConfig.loadSettings();
        Path versionDir = LauncherConfig.VERSIONS_DIR.resolve(versionId);
        JsonObject versionJson = JsonParser.parseString(
                Files.readString(versionDir.resolve(versionId + ".json"))).getAsJsonObject();

        if (versionJson.has("inheritsFrom")) {
            JsonObject resolved = VersionInheritanceResolver.resolve(versionJson, LauncherConfig.VERSIONS_DIR);
            if (resolved == null) {
                throw new IllegalStateException("Cannot launch " + versionId + ": it needs \""
                        + versionJson.get("inheritsFrom").getAsString()
                        + "\" which is not installed. Install that vanilla version first.");
            }
            versionJson = resolved;
        }
        if (!versionJson.has("mainClass") || !versionJson.has("assetIndex")) {
            throw new IllegalStateException("Version file for " + versionId + " is incomplete. "
                    + "Delete the folder \"" + versionDir + "\" and try again.");
        }

        Arch arch = choice.arch();
        List<LibFile> libs = LibraryResolver.resolve(versionJson, arch);

        Path nativesDir = versionDir.resolve("natives");
        extractNatives(libs, nativesDir);

        Path assetsRoot = GameDownloader.prepareAssets(versionJson);
        String classpath = buildClasspath(libs, versionJson, versionId);
        String assetIndexId = versionJson.getAsJsonObject("assetIndex").get("id").getAsString();

        boolean offlineSession = "TEST_MODE_NO_TOKEN".equals(session.accessToken());
        String versionType = versionJson.has("type") && !versionJson.get("type").isJsonNull()
                ? versionJson.get("type").getAsString() : "release";

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", session.username());
        vars.put("version_name", versionId);
        vars.put("game_directory", LauncherConfig.GAME_DIR.toAbsolutePath().toString());
        vars.put("assets_root", assetsRoot.toAbsolutePath().toString());
        vars.put("game_assets", assetsRoot.toAbsolutePath().toString());
        vars.put("assets_index_name", assetIndexId);
        vars.put("auth_uuid", session.uuid());
        vars.put("auth_access_token", session.accessToken());
        vars.put("auth_session", "token:" + session.accessToken() + ":" + session.uuid());
        vars.put("user_type", offlineSession ? "legacy" : "msa");
        vars.put("user_properties", "{}");
        vars.put("version_type", versionType);
        vars.put("resolution_width", String.valueOf(width));
        vars.put("resolution_height", String.valueOf(height));
        vars.put("natives_directory", nativesDir.toAbsolutePath().toString());
        vars.put("launcher_name", "KPEClubLauncher");
        vars.put("launcher_version", "1.8.1");
        vars.put("classpath", classpath);
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", LauncherConfig.LIBRARIES_DIR.toAbsolutePath().toString());
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");

        boolean legacy1165OrOlder = isLegacy1165OrOlder(versionId, versionJson);
        boolean useDriverBypass = settings.isBypassDriverCheck() && !legacy1165OrOlder;
        if (legacy1165OrOlder) {
            FpsBoost.removeMesaFromDir(nativesDir);
        }

        Path javaBinaryPath = findJavaBinary(versionJson, choice, legacy1165OrOlder);
        int requiredJavaMajor = new JreManager().requiredMajor(versionJson);
        if (useDriverBypass) {
            javaBinaryPath = FpsBoost.prepareJavaForDriverBypass(javaBinaryPath, nativesDir, arch);
        }
        lastJavaBinary = javaBinaryPath.toString();

        List<String> command = new ArrayList<>();
        command.add(javaBinaryPath.toString());

        // ---- memory ----
        command.add("-Xmx" + chooseRamMb(settings, choice.is64Bit()) + "M");

        // ---- JVM arguments ----
        List<String> jvm = new ArrayList<>();
        if (versionJson.has("arguments") && versionJson.getAsJsonObject("arguments").has("jvm")) {
            collectArgs(versionJson.getAsJsonObject("arguments").getAsJsonArray("jvm"), arch, vars, jvm);
        }
        // we add -cp ourselves — drop any "-cp <classpath>" pair the json carries
        for (int i = 0; i < jvm.size(); i++) {
            if (jvm.get(i).equals("-cp") || jvm.get(i).equals("-classpath")) {
                jvm.remove(i);
                if (i < jvm.size()) jvm.remove(i);
                i--;
            }
        }
        command.addAll(jvm);
        if (!choice.is64Bit() && !jvm.contains("-Xss1M")) command.add("-Xss1M"); // old versions have no rule for it
        if (jvm.stream().noneMatch(a -> a.startsWith("-Djava.library.path="))) {
            command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        }
        if (jvm.stream().noneMatch(a -> a.startsWith("-Dminecraft.launcher.brand="))) {
            command.add("-Dminecraft.launcher.brand=KPEClubLauncher");
        }
        // Always enforce Log4Shell mitigation on every version
        if (command.stream().noneMatch(a -> a.startsWith("-Dlog4j2.formatMsgNoLookups="))) {
            command.add("-Dlog4j2.formatMsgNoLookups=true");
        }
        addLoggingArg(versionJson, command);

        if (settings.isFpsBoost()) {
            for (String flag : FpsBoost.jvmFlags(choice.is64Bit())) {
                String name = flag.contains("=") ? flag.substring(0, flag.indexOf('=')) : flag;
                if (jvm.stream().noneMatch(a -> a.startsWith(name))) command.add(flag);
            }
        }

        if (useDriverBypass) {
            for (String flag : FpsBoost.driverBypassJvmFlags(javaBinaryPath, nativesDir, arch, requiredJavaMajor)) {
                if (flag.startsWith("-javaagent:")) {
                    if (!command.contains(flag)) command.add(flag);
                } else {
                    String name = flag.contains("=") ? flag.substring(0, flag.indexOf('=') + 1) : flag;
                    if (command.stream().noneMatch(a -> a.startsWith(name))) command.add(flag);
                }
            }
        }

        String extra = settings.getExtraJvmArgs().trim();
        if (!extra.isEmpty()) command.addAll(Arrays.asList(extra.split("\\s+")));

        command.add("-cp");
        command.add(classpath);
        command.add(versionJson.get("mainClass").getAsString());

        // ---- game arguments ----
        List<String> game = new ArrayList<>();
        boolean quickPlay = false;
        if (versionJson.has("arguments") && versionJson.getAsJsonObject("arguments").has("game")) {
            JsonArray ga = versionJson.getAsJsonObject("arguments").getAsJsonArray("game");
            quickPlay = ga.toString().contains("quickPlayMultiplayer");
            collectArgs(ga, arch, vars, game);
        } else if (versionJson.has("minecraftArguments")) { // old format: one long string (used only if modern arguments.game is absent)
            for (String tok : versionJson.get("minecraftArguments").getAsString().trim().split("\\s+")) {
                game.add(substitute(tok, vars));
            }
        }
        // Honor the window size for every kind of version file (custom loader jsons may lack the rule).
        if (!game.contains("--width")) {
            game.add("--width"); game.add(String.valueOf(width));
            game.add("--height"); game.add(String.valueOf(height));
        }
        command.addAll(game);

        if (serverHost != null) {
            if (quickPlay) {
                command.add("--quickPlayMultiplayer");
                command.add(serverHost + ":" + (serverPort != null ? serverPort : 25565));
            } else {
                command.add("--server"); command.add(serverHost);
                command.add("--port"); command.add(String.valueOf(serverPort != null ? serverPort : 25565));
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(LauncherConfig.GAME_DIR.toFile());
        if (useDriverBypass) {
            FpsBoost.applyDriverBypassEnv(pb.environment(), nativesDir, arch, requiredJavaMajor);
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        if (settings.isFpsBoost()) FpsBoost.setHighPriority(process.pid());

        synchronized (GameLauncher.class) { LAST_OUTPUT.clear(); }
        Thread reader = new Thread(() -> {
            BufferedWriter log = null;
            try {
                Files.createDirectories(logFile().getParent());
                log = Files.newBufferedWriter(logFile());
            } catch (IOException ignored) {
                // If latest.log cannot be opened, still drain stdout so the game pipe never blocks
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (log != null) {
                        try {
                            log.write(line);
                            log.newLine();
                            log.flush();
                        } catch (IOException writeErr) {
                            try { log.close(); } catch (IOException ignored) {}
                            log = null; // keep draining stdout even if disk fills up
                        }
                    }
                    synchronized (GameLauncher.class) {
                        LAST_OUTPUT.addLast(line);
                        while (LAST_OUTPUT.size() > MAX_LOG_LINES) LAST_OUTPUT.removeFirst();
                    }
                }
            } catch (IOException ignored) {
                // process ended — normal
            } finally {
                if (log != null) {
                    try { log.close(); } catch (IOException ignored) {}
                }
            }
        }, "kpe-game-output");
        reader.setDaemon(true);
        reader.start();
        return process;
    }

    // ------------------------------------------------------------------------------------

    /** Handles both plain strings and rule-based entries from an "arguments" array. */
    private void collectArgs(JsonArray array, Arch arch, Map<String, String> vars, List<String> out) {
        for (JsonElement el : array) {
            if (el.isJsonPrimitive()) {
                out.add(substitute(el.getAsString(), vars));
            } else if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (!Rules.allowed(o, arch) || !o.has("value")) continue;
                JsonElement v = o.get("value");
                if (v.isJsonArray()) {
                    for (JsonElement x : v.getAsJsonArray()) out.add(substitute(x.getAsString(), vars));
                } else {
                    out.add(substitute(v.getAsString(), vars));
                }
            }
        }
    }

    private String substitute(String raw, Map<String, String> vars) {
        String r = raw;
        for (var e : vars.entrySet()) r = r.replace("${" + e.getKey() + "}", e.getValue());
        return r;
    }

    private void addLoggingArg(JsonObject versionJson, List<String> command) {
        try {
            if (!versionJson.has("logging")) return;
            JsonObject c = versionJson.getAsJsonObject("logging").getAsJsonObject("client");
            JsonObject fileObj = c.getAsJsonObject("file");
            String logConfigId = Safe.requireSafeId(fileObj.get("id").getAsString(), "log config id");
            Path file = Safe.resolveInside(LauncherConfig.ASSETS_DIR.resolve("log_configs"), logConfigId);
            if (!Files.exists(file) && fileObj.has("url")) {
                String url = fileObj.get("url").getAsString();
                String sha1 = fileObj.has("sha1") ? fileObj.get("sha1").getAsString() : null;
                long size = fileObj.has("size") ? fileObj.get("size").getAsLong() : 0L;
                FileDownloader.fetch(url, file, sha1, size);
            }
            if (Files.exists(file)) {
                command.add(c.get("argument").getAsString().replace("${path}", file.toAbsolutePath().toString()));
            }
        } catch (Exception ignored) {
            // -Dlog4j2.formatMsgNoLookups=true is already set above as baseline protection
        }
    }

    private int chooseRamMb(LauncherSettings s, boolean java64) {
        int max = PlatformInfo.maxSafeHeapMb(java64);
        int ram = s.getRamMb();
        if (ram <= 0) {
            if (!java64) return 1024;
            long total = PlatformInfo.totalPhysicalMemoryMb();
            ram = total > 0 ? (int) Math.max(1024, Math.min(4096, total / 2)) : 2048;
        }
        return Math.max(512, Math.min(ram, max));
    }

    private String buildClasspath(List<LibFile> libs, JsonObject versionJson, String versionId) {
        List<String> paths = new ArrayList<>();
        for (LibFile lf : libs) {
            if (!lf.nativeJar()) paths.add(lf.path().toAbsolutePath().toString());
        }
        paths.add(resolveClientJar(versionJson, versionId).toAbsolutePath().toString());
        return String.join(File.pathSeparator, paths);
    }

    private Path resolveClientJar(JsonObject versionJson, String versionId) {
        Path own = LauncherConfig.VERSIONS_DIR.resolve(versionId).resolve(versionId + ".jar");
        if (Files.exists(own)) return own;
        if (versionJson.has("inheritsFromResolvedParentId")) {
            String parent = versionJson.get("inheritsFromResolvedParentId").getAsString();
            Path pj = LauncherConfig.VERSIONS_DIR.resolve(parent).resolve(parent + ".jar");
            if (Files.exists(pj)) return pj;
        }
        if (versionJson.has("inheritsFrom")) {
            String parent = versionJson.get("inheritsFrom").getAsString();
            Path pj = LauncherConfig.VERSIONS_DIR.resolve(parent).resolve(parent + ".jar");
            if (Files.exists(pj)) return pj;
        }
        return own;
    }

    /** Extracts the .dll files of old-style natives jars into this version's OWN natives folder
     *  (the old shared folder mixed 32-bit/64-bit and LWJGL 2/3 files between versions). */
    private void extractNatives(List<LibFile> libs, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        for (LibFile lf : libs) {
            if (!lf.nativeJar() || !Files.exists(lf.path())) continue;
            try (JarFile jar = new JarFile(lf.path().toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || lf.extractExcludes().stream().anyMatch(name::startsWith)) continue;
                    if (!name.toLowerCase().endsWith(".dll")) continue;
                    String fileName = Paths.get(name).getFileName().toString();
                    Path out = Safe.resolveInside(nativesDir, fileName);
                    if (Files.exists(out) && entry.getSize() >= 0 && Files.size(out) == entry.getSize()) continue;
                    try (var in = jar.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException locked) {
                        // a dll still in use by a running game — it is the same file, keep it
                    }
                }
            }
        }
    }

    /**
     * Returns true if the version is Minecraft 1.16.5 or older (uses OpenGL 2.1 and Java 8 original javaw.exe).
     */
    public static boolean isLegacy1165OrOlder(String versionId, JsonObject versionJson) {
        if (versionJson != null && new JreManager().requiredMajor(versionJson) <= 8) {
            return true;
        }
        for (String candidate : candidateVersionStrings(versionId, versionJson)) {
            if (isVersionString1165OrOlder(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> candidateVersionStrings(String versionId, JsonObject versionJson) {
        List<String> list = new ArrayList<>();
        if (versionId != null && !versionId.isBlank()) list.add(versionId.trim());
        if (versionJson != null) {
            for (String key : new String[]{"id", "inheritsFrom", "inheritsFromResolvedParentId", "jar"}) {
                if (versionJson.has(key) && !versionJson.get(key).isJsonNull()) {
                    list.add(versionJson.get(key).getAsString().trim());
                }
            }
        }
        return list;
    }

    private static boolean isVersionString1165OrOlder(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.startsWith("b1.") || s.startsWith("a1.") || s.startsWith("c0.") || s.startsWith("inf-") || s.startsWith("rd-")) {
            return true;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|[^0-9])1\\.(\\d+)(?:\\.(\\d+))?(?:[^0-9]|$)")
                .matcher(s);
        while (m.find()) {
            int minor = Integer.parseInt(m.group(1));
            int patch = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            if (minor < 16) return true;
            if (minor == 16 && patch <= 5) return true;
            if (minor >= 17) return false;
        }
        return false;
    }

    private Path findJavaBinary(JsonObject versionJson, JavaChoice choice, boolean legacy1165OrOlder) throws IOException {
        if (choice.customJava() != null) {
            String customPathStr = choice.customJava().toString().toLowerCase(Locale.ROOT);
            boolean isMesaShim = customPathStr.contains("jre-shim-");
            if (!isMesaShim) {
                if (!legacy1165OrOlder) {
                    return choice.customJava();
                }
                JavaProbe.Info info = JavaProbe.probe(choice.customJava());
                if (info != null && info.majorVersion() <= 8) {
                    return choice.customJava();
                }
            }
        }

        JreManager jre = new JreManager();
        String component = jre.requiredComponent(versionJson);
        Path existing = jre.findExistingJava(versionJson, choice.arch());
        if (existing != null) return existing;

        throw new IllegalStateException("Could not find a compatible Java runtime for \"" + component + "\" ("
                + choice.arch().ruleName + "). Connect to the internet once so the launcher can download it, "
                + "or choose a compatible Java in Settings.");
    }

}
