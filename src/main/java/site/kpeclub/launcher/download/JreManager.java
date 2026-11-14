package site.kpeclub.launcher.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.kpeclub.launcher.util.FileDownloader;
import site.kpeclub.launcher.util.JavaProbe;
import site.kpeclub.launcher.util.LauncherConfig;
import site.kpeclub.launcher.util.PlatformInfo.Arch;
import site.kpeclub.launcher.util.Safe;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Downloads and manages Mojang's OWN per-version Java runtimes (same ones the official
 * launcher uses), for the right CPU: 32-bit Windows gets "windows-x86", 64-bit gets
 * "windows-x64". Also auto-detects existing Java installations across PATH, Windows
 * Registry, Program Files, user JDK folders, and other Minecraft launchers.
 *
 * Folder layout (64-bit keeps the old path, so existing installs don't re-download):
 *   64-bit: %APPDATA%/.kpelauncher/runtimes/<component>/<component>/bin/javaw.exe
 *   32-bit: %APPDATA%/.kpelauncher/runtimes/x86/<component>/<component>/bin/javaw.exe
 */
public class JreManager {

    private static final String RUNTIME_INDEX_URL =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    public static Path runtimesDir() {
        return LauncherConfig.ROOT_DIR.resolve("runtimes");
    }

    private static Path componentRoot(String component, Arch arch) {
        Safe.requireSafeId(component, "Java runtime component");
        Path base = arch == Arch.X86 ? runtimesDir().resolve("x86") : runtimesDir();
        return base.resolve(component);
    }

    /** Which runtime component a version needs. Old versions without it use Java 8 ("jre-legacy"). */
    public String requiredComponent(JsonObject versionJson) {
        if (versionJson != null && versionJson.has("javaVersion")) {
            JsonObject jv = versionJson.getAsJsonObject("javaVersion");
            if (jv.has("component")) {
                return Safe.requireSafeId(jv.get("component").getAsString(), "Java runtime component");
            }
        }
        return "jre-legacy";
    }

    /** Java version number a Minecraft version needs (8 for very old versions). */
    public int requiredMajor(JsonObject versionJson) {
        if (versionJson != null && versionJson.has("javaVersion")
                && versionJson.getAsJsonObject("javaVersion").has("majorVersion")) {
            return versionJson.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
        }
        return 8;
    }

    /**
     * Finds a Java that is ALREADY on this PC, so nothing needs to be downloaded:
     *  1. our own runtimes folder
     *  2. the official Minecraft Launcher's runtimes (normal, Microsoft Store, CurseForge)
     *  3. a Java installed on the system (JAVA_HOME, PATH, Windows Registry, Program Files, .jdks, Modrinth, Prism...)
     * @return path to javaw.exe / java.exe, or null if none fits (then the launcher downloads one)
     */
    public Path findExistingJava(JsonObject versionJson, Arch arch) {
        String component = requiredComponent(versionJson);

        Path own = javaExecutableFor(component, arch);
        if (own != null && isComponentInstalled(component, arch)) return own;

        // Official launcher & CurseForge runtime folders: <root>/<component>/windows-x64/<component>/bin/javaw.exe
        List<Path> roots = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        String userHome = System.getProperty("user.home");
        if (appData != null) roots.add(Path.of(appData, ".minecraft", "runtime"));
        if (local != null) roots.add(Path.of(local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe",
                "LocalCache", "Local", "runtime"));
        if (userHome != null) roots.add(Path.of(userHome, "curseforge", "minecraft", "Install", "runtime"));
        for (Path root : roots) {
            Path bin = root.resolve(component).resolve(arch.runtimeKey).resolve(component).resolve("bin");
            Path exe = exeIn(bin);
            // the official launcher writes a ".version" file only when the runtime is complete
            if (exe != null && Files.exists(root.resolve(component).resolve(arch.runtimeKey).resolve(".version"))) {
                return exe;
            }
        }

        // 3. System-wide Java installs (check exact v1.4.4 locations first, then extended locations)
        int major = requiredMajor(versionJson);
        for (Path home : primarySystemJavaHomes()) {
            Path exe = exeIn(home.resolve("bin"));
            if (exe == null) continue;
            JavaProbe.Info info = JavaProbe.probe(exe);
            if (info == null) continue;
            boolean bitsOk = info.is64Bit() == (arch != Arch.X86);
            boolean versionOk = major <= 8 ? info.majorVersion() == 8 : info.majorVersion() >= major;
            if (bitsOk && versionOk) return exe;
        }
        for (Path home : systemJavaHomes()) {
            Path exe = exeIn(home.resolve("bin"));
            if (exe == null) continue;
            JavaProbe.Info info = JavaProbe.probe(exe);
            if (info == null) continue;
            boolean bitsOk = info.is64Bit() == (arch != Arch.X86);
            boolean versionOk = major <= 8 ? info.majorVersion() == 8 : info.majorVersion() >= major;
            if (bitsOk && versionOk) return exe;
        }
        return null;
    }

    /**
     * Auto-detects the best installed Java executable on this PC for the given architecture
     * (preferring the newest LTS/major version found in KPE runtimes, Official Launcher, or system JDK/JREs).
     */
    public static Path detectBestJavaOnSystem(Arch arch) {
        List<Path> candidates = new ArrayList<>();
        // 1. Check KPE's own downloaded runtimes
        for (String comp : new String[]{"java-runtime-delta", "java-runtime-gamma", "java-runtime-beta", "java-runtime-alpha", "jre-legacy"}) {
            Path bin = componentRoot(comp, arch).resolve(comp).resolve("bin");
            Path exe = exeIn(bin);
            if (exe != null && Files.exists(componentRoot(comp, arch).resolve(".kpe-ok"))) {
                candidates.add(exe);
            }
        }
        // 2. Check Official Launcher runtimes
        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        List<Path> roots = new ArrayList<>();
        if (appData != null) roots.add(Path.of(appData, ".minecraft", "runtime"));
        if (local != null) roots.add(Path.of(local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe",
                "LocalCache", "Local", "runtime"));
        for (Path root : roots) {
            for (String comp : new String[]{"java-runtime-delta", "java-runtime-gamma", "java-runtime-beta", "java-runtime-alpha", "jre-legacy"}) {
                Path bin = root.resolve(comp).resolve(arch.runtimeKey).resolve(comp).resolve("bin");
                Path exe = exeIn(bin);
                if (exe != null && Files.exists(root.resolve(comp).resolve(arch.runtimeKey).resolve(".version"))) {
                    candidates.add(exe);
                }
            }
        }
        // 3. Check all system Java homes
        for (Path home : systemJavaHomes()) {
            Path exe = exeIn(home.resolve("bin"));
            if (exe != null) candidates.add(exe);
        }

        Path bestExe = null;
        int bestMajor = -1;
        for (Path exe : candidates) {
            JavaProbe.Info info = JavaProbe.probe(exe);
            if (info == null) continue;
            if (info.is64Bit() != (arch != Arch.X86)) continue;
            Path resolved = exe;
            if (info.javaHome() != null && !info.javaHome().isBlank()) {
                Path realExe = exeIn(Path.of(info.javaHome(), "bin"));
                if (realExe != null) resolved = realExe;
            }
            if (info.majorVersion() > bestMajor) {
                bestMajor = info.majorVersion();
                bestExe = resolved;
            }
        }
        return bestExe;
    }

    private static Path exeIn(Path bin) {
        if (bin == null) return null;
        if (Files.exists(bin.resolve("javaw.exe"))) return bin.resolve("javaw.exe");
        if (Files.exists(bin.resolve("java.exe"))) return bin.resolve("java.exe");
        return null;
    }

    /** Exact v1.4.4 system Java lookup order (JAVA_HOME + 1-level ProgramFiles vendor directories). */
    private static List<Path> primarySystemJavaHomes() {
        List<Path> homes = new ArrayList<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) homes.add(Path.of(javaHome));
        List<String> bases = new ArrayList<>();
        for (String var : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
            String v = System.getenv(var);
            if (v != null && !bases.contains(v)) bases.add(v);
        }
        String[] vendors = {"Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft", "Semeru", "AdoptOpenJDK"};
        for (String base : bases) {
            for (String vendor : vendors) {
                Path dir = Path.of(base, vendor);
                if (!Files.isDirectory(dir)) continue;
                try (var list = Files.list(dir)) {
                    list.filter(Files::isDirectory).forEach(homes::add);
                } catch (IOException ignored) {}
            }
        }
        return homes;
    }

    private static volatile List<Path> cachedSystemHomes = null;

    public static List<Path> systemJavaHomes() {
        List<Path> existing = cachedSystemHomes;
        if (existing != null) return existing;
        Set<Path> homes = new LinkedHashSet<>();

        // 1. JAVA_HOME environment variable
        for (String envVar : new String[]{"JAVA_HOME", "JDK_HOME", "JRE_HOME"}) {
            String val = System.getenv(envVar);
            addHomeIfValid(homes, val);
        }

        // 2. Program Files vendor directories (exact v1.4.4 priority: "Java" first!)
        List<String> bases = new ArrayList<>();
        for (String var : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
            String v = System.getenv(var);
            if (v != null && !v.isBlank() && !bases.contains(v)) bases.add(v);
        }
        String[] vendors = {
                "Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto",
                "BellSoft", "Liberica", "Semeru", "AdoptOpenJDK", "RedHat", "SapMachine", "GraalVM"
        };
        for (String base : bases) {
            for (String vendor : vendors) {
                scanDirectoryForHomes(homes, Path.of(base, vendor));
            }
        }

        // 3. Windows Registry (JavaSoft, Eclipse Adoptium, Microsoft, Azul, BellSoft)
        queryRegistryJavaHomes(homes);

        // 4. PATH entries (resolves Oracle/Adoptium javapath shims via JavaProbe if needed)
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String entry : pathEnv.split(File.pathSeparator)) {
                if (entry == null || entry.isBlank()) continue;
                try {
                    Path binDir = Path.of(entry.trim());
                    Path exe = exeIn(binDir);
                    if (exe != null) {
                        Path parent = binDir.getParent();
                        if (parent != null && Files.exists(parent.resolve("release"))) {
                            homes.add(parent.toAbsolutePath().normalize());
                        } else {
                            JavaProbe.Info info = JavaProbe.probe(exe);
                            if (info != null && info.javaHome() != null) {
                                addHomeIfValid(homes, info.javaHome());
                            } else if (parent != null) {
                                homes.add(parent.toAbsolutePath().normalize());
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // 5. User profile JDK/JRE directories (.jdks, AppData\Local\Programs, Scoop, Modrinth, Prism)
        String userHome = System.getProperty("user.home");
        String localAppData = System.getenv("LOCALAPPDATA");
        String appData = System.getenv("APPDATA");
        if (userHome != null && !userHome.isBlank()) {
            scanDirectoryForHomes(homes, Path.of(userHome, ".jdks"));
            scanDirectoryForHomes(homes, Path.of(userHome, "scoop", "apps", "temurin-lts-jdk"));
            scanDirectoryForHomes(homes, Path.of(userHome, "scoop", "apps", "temurin21-jdk"));
            scanDirectoryForHomes(homes, Path.of(userHome, "scoop", "apps", "temurin17-jdk"));
            scanDirectoryForHomes(homes, Path.of(userHome, "scoop", "apps", "temurin8-jdk"));
            scanDirectoryForHomes(homes, Path.of(userHome, "scoop", "apps", "openjdk"));
        }
        if (localAppData != null && !localAppData.isBlank()) {
            for (String vendor : vendors) {
                scanDirectoryForHomes(homes, Path.of(localAppData, "Programs", vendor));
            }
        }
        if (appData != null && !appData.isBlank()) {
            scanDirectoryForHomes(homes, Path.of(appData, "ModrinthApp", "meta", "java_versions"));
            scanDirectoryForHomes(homes, Path.of(appData, "PrismLauncher", "java"));
        }

        List<Path> result = List.copyOf(homes);
        cachedSystemHomes = result;
        return result;
    }

    private static void addHomeIfValid(Set<Path> homes, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return;
        try {
            Path p = Path.of(rawPath.trim());
            if (exeIn(p.resolve("bin")) != null) {
                homes.add(p.toAbsolutePath().normalize());
            } else if (exeIn(p) != null && p.getParent() != null) {
                homes.add(p.getParent().toAbsolutePath().normalize());
            }
        } catch (Exception ignored) {}
    }

    private static void scanDirectoryForHomes(Set<Path> homes, Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        if (exeIn(dir.resolve("bin")) != null) {
            homes.add(dir.toAbsolutePath().normalize());
        }
        try (var list = Files.list(dir)) {
            list.filter(Files::isDirectory).forEach(sub -> {
                if (exeIn(sub.resolve("bin")) != null) {
                    homes.add(sub.toAbsolutePath().normalize());
                } else if (exeIn(sub.resolve("jre").resolve("bin")) != null) {
                    homes.add(sub.resolve("jre").toAbsolutePath().normalize());
                }
            });
        } catch (IOException ignored) {}
    }

    private static void queryRegistryJavaHomes(Set<Path> homes) {
        String[] keys = {
                "HKLM\\SOFTWARE\\Eclipse Adoptium\\JDK",
                "HKLM\\SOFTWARE\\Eclipse Adoptium\\JRE",
                "HKLM\\SOFTWARE\\JavaSoft\\JDK",
                "HKLM\\SOFTWARE\\JavaSoft\\Java Runtime Environment",
                "HKLM\\SOFTWARE\\Microsoft\\JDK",
                "HKLM\\SOFTWARE\\AdoptOpenJDK\\JDK",
                "HKLM\\SOFTWARE\\Azul Systems\\Zulu",
                "HKLM\\SOFTWARE\\BellSoft\\Liberica",
                "HKLM\\SOFTWARE\\WOW6432Node\\Eclipse Adoptium\\JDK",
                "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\JDK",
                "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\Java Runtime Environment"
        };
        for (String key : keys) {
            try {
                Process p = new ProcessBuilder(Safe.systemExe("reg.exe"), "query", key, "/s", "/v", "JavaHome")
                        .redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        int idx = line.indexOf("REG_SZ");
                        if (idx > 0) {
                            String val = line.substring(idx + "REG_SZ".length()).trim();
                            addHomeIfValid(homes, val);
                        }
                    }
                }
                p.waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
    }

    public boolean isComponentInstalled(String component, Arch arch) {
        return Files.exists(componentRoot(component, arch).resolve(".kpe-ok"))
                && javaExecutableFor(component, arch) != null;
    }

    public Path javaExecutableFor(String component, Arch arch) {
        Path bin = componentRoot(component, arch).resolve(component).resolve("bin");
        if (Files.exists(bin.resolve("javaw.exe"))) return bin.resolve("javaw.exe");
        if (Files.exists(bin.resolve("java.exe"))) return bin.resolve("java.exe");
        return null;
    }

    public void downloadComponentIfMissing(String component, Arch arch,
                                           Consumer<DownloadProgress> progress) throws Exception {
        downloadComponentIfMissing(component, arch, progress, () -> false);
    }

    public void downloadComponentIfMissing(String component, Arch arch,
                                           Consumer<DownloadProgress> progress,
                                           BooleanSupplier cancelled) throws Exception {
        if (isComponentInstalled(component, arch)) {
            progress.accept(new DownloadProgress(1, 1, "Already installed"));
            return;
        }

        JsonObject manifest = JsonParser.parseString(FileDownloader.getString(RUNTIME_INDEX_URL)).getAsJsonObject();
        String key = arch.runtimeKey;
        if (!manifest.has(key) || !manifest.getAsJsonObject(key).has(component)
                || manifest.getAsJsonObject(key).getAsJsonArray(component).isEmpty()) {
            if (arch == Arch.X86) {
                throw new IllegalStateException(
                        "This Minecraft version needs Java \"" + component + "\", which Mojang does not offer for " +
                        "32-bit Windows. Use an older Minecraft version, or install a 32-bit Java yourself and " +
                        "pick it in Settings > Java path.");
            }
            throw new IllegalStateException("No Java runtime available for " + component + " on " + key + ".");
        }

        JsonObject entry = manifest.getAsJsonObject(key).getAsJsonArray(component).get(0).getAsJsonObject();
        String manifestUrl = entry.getAsJsonObject("manifest").get("url").getAsString();
        JsonObject files = JsonParser.parseString(FileDownloader.getString(manifestUrl))
                .getAsJsonObject().getAsJsonObject("files");

        Path root = componentRoot(component, arch);
        Files.deleteIfExists(root.resolve(".kpe-ok"));
        Path dir = root.resolve(component);
        Files.createDirectories(dir);

        record Job(String url, Path dest, String sha1, long size) {}
        List<Job> jobs = new ArrayList<>();
        long total = 0;
        for (String name : files.keySet()) {
            JsonObject f = files.getAsJsonObject(name);
            Path dest = Safe.resolveInside(dir, name);
            String type = f.get("type").getAsString();
            if ("directory".equals(type)) {
                if (Files.isRegularFile(dest)) Files.deleteIfExists(dest);
                Files.createDirectories(dest);
            } else if ("file".equals(type)) {
                if (!f.has("downloads") || !f.getAsJsonObject("downloads").has("raw")) continue;
                JsonObject raw = f.getAsJsonObject("downloads").getAsJsonObject("raw");
                long size = raw.has("size") ? raw.get("size").getAsLong() : 0;
                String sha1 = raw.has("sha1") ? raw.get("sha1").getAsString() : null;
                jobs.add(new Job(raw.get("url").getAsString(), dest, sha1, size));
                total += size;
            }
        }

        final long totalBytes = total;
        AtomicLong done = new AtomicLong();
        AtomicLong lastEmit = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "kpe-jre");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Job j : jobs) {
                fs.add(pool.submit(() -> {
                    if (cancelled.getAsBoolean()) throw new CancellationException("Download cancelled.");
                    if (Files.isDirectory(j.dest())) {
                        // A previous broken install created a directory where a file belongs
                        try (var s = Files.walk(j.dest())) {
                            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                        }
                    }
                    FileDownloader.fetch(j.url(), j.dest(), j.sha1(), j.size());
                    long now = done.addAndGet(j.size());
                    long t = System.currentTimeMillis(), last = lastEmit.get();
                    if (t - last > 120 && lastEmit.compareAndSet(last, t)) {
                        progress.accept(new DownloadProgress(now, totalBytes, j.dest().getFileName().toString()));
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    pool.shutdownNow();
                    if (e.getCause() instanceof Exception ex) throw ex;
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            throw new CancellationException("Download cancelled.");
        } finally {
            pool.shutdownNow();
        }

        Files.writeString(root.resolve(".kpe-ok"), "ok");
    }
}
