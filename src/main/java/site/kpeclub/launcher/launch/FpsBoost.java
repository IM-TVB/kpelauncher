package site.kpeclub.launcher.launch;

import site.kpeclub.launcher.download.DownloadProgress;
import site.kpeclub.launcher.download.ModInstaller;
import site.kpeclub.launcher.util.JavaProbe;
import site.kpeclub.launcher.util.LauncherConfig;
import site.kpeclub.launcher.util.PlatformInfo.Arch;
import site.kpeclub.launcher.util.Safe;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Performance helpers and optional Old PC Mesa3D (opengl32.dll) GPU driver bypass management.
 */
public final class FpsBoost {

    /** Performance mods on Modrinth (Fabric). Slugs work in place of project ids. */
    public static final List<String> FPS_MODS = List.of("sodium", "lithium", "ferrite-core", "immediatelyfast");

    /**
     * Pinned Mesa3D Windows release from pal1000/mesa-dist-win (the official Windows binary
     * distribution linked by docs.mesa3d.org) with pinned SHA-256 verification.
     */
    private static final String MESA_ARCHIVE_URL =
            "https://github.com/pal1000/mesa-dist-win/releases/download/26.2.6/mesa3d-26.2.6-release-mingw.7z";
    private static final String MESA_ARCHIVE_SHA256 =
            "da0f71d9181b672c1918f3c342f11118c687f6059a46b16502845160733cc388";

    /**
     * Pinned standalone 7-Zip 9.20 CLI archive (7za920.zip) and 7zr.exe with pinned SHA-256 hashes.
     */
    private static final String SEVEN_ZA_ZIP_URL = "https://www.7-zip.org/a/7za920.zip";
    private static final String SEVEN_ZA_ZIP_MIRROR_URL =
            "https://sourceforge.net/projects/sevenzip/files/7-Zip/9.20/7za920.zip/download";
    private static final String SEVEN_ZA_ZIP_SHA256 =
            "2a3afe19c180f8373fa02ff00254d5394fec0349f5804e0ad2f6067854ff28ac";

    private static final String SEVEN_ZR_EXE_URL = "https://www.7-zip.org/a/7zr.exe";
    private static final String SEVEN_ZR_EXE_SHA256 =
            "a1c56fc63d58c613115c298baca7da311f021e451c4394b08323b95572c2ecb6";

    private static final String[] COMPANION_DLL_ORDER = {
            "libwinpthread-1.dll",
            "libgcc_s_seh-1.dll",
            "libgcc_s_dw2-1.dll",
            "libstdc++-6.dll",
            "libglapi.dll",
            "libgallium_wgl.dll"
    };

    private FpsBoost() {}

    /** Directory where optional Mesa3D OpenGL DLLs (opengl32.dll, libgallium_wgl.dll, libglapi.dll) live. */
    public static Path mesaDir(Arch arch) {
        return LauncherConfig.ROOT_DIR.resolve("mesa").resolve(arch == Arch.X86 ? "x86" : "x64");
    }

    /** True if Mesa3D's opengl32.dll is installed for the given CPU architecture. */
    public static boolean isMesaInstalled(Arch arch) {
        Path targetDir = mesaDir(arch);
        Path p1 = targetDir.resolve("opengl32.dll");
        if (isValidMesaDll(p1, arch)) return true;

        Path p2 = LauncherConfig.ROOT_DIR.resolve("mesa").resolve("opengl32.dll");
        if (isValidMesaDll(p2, arch)) {
            try {
                Files.createDirectories(targetDir);
                Files.copy(p2, p1, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception ignored) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidMesaDll(Path dll, Arch arch) {
        try {
            return dll != null && Files.isRegularFile(dll) && Files.size(dll) > 80_000L && matchesPeArch(dll, arch);
        } catch (Exception e) {
            return false;
        }
    }

    public static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Could not compute SHA-256 hash for " + file, e);
        }
    }

    /**
     * Walks a directory tree up to maxDepth without ever throwing AccessDeniedException or
     * UncheckedIOException when encountering protected Windows junctions.
     */
    private static List<Path> walkFilesSafely(Path root, int maxDepth) {
        List<Path> results = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) return results;
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(root) && (attrs.isSymbolicLink() || attrs.isOther())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile()) {
                                results.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (Throwable ignored) {
        }
        return results;
    }

    /**
     * Checks the Windows PE header of a .dll file to verify its architecture matches Arch (X64 = 0x8664, X86 = 0x014c).
     * Prevents loading a 32-bit opengl32.dll into 64-bit Java (or vice versa), which causes GLFW error 65542.
     */
    public static boolean matchesPeArch(Path dllFile, Arch arch) {
        try (RandomAccessFile raf = new RandomAccessFile(dllFile.toFile(), "r")) {
            if (raf.length() < 64) return false;
            if (raf.readUnsignedByte() != 'M' || raf.readUnsignedByte() != 'Z') return false;
            raf.seek(0x3C);
            int peOffset = Integer.reverseBytes(raf.readInt());
            if (peOffset < 0 || peOffset + 6 > raf.length()) return false;
            raf.seek(peOffset);
            if (raf.readUnsignedByte() != 'P' || raf.readUnsignedByte() != 'E'
                    || raf.readUnsignedByte() != 0 || raf.readUnsignedByte() != 0) {
                return false;
            }
            int machine = Short.toUnsignedInt(Short.reverseBytes(raf.readShort()));
            if (arch == Arch.X86) return machine == 0x014c;
            return machine == 0x8664;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Imports a user-selected opengl32.dll, .zip, or .7z archive containing Mesa3D DLLs
     * into %APPDATA%\.kpelauncher\mesa\<arch>\.
     */
    public static int importMesaFile(Path sourceFile, Arch arch) throws IOException {
        Path targetDir = mesaDir(arch);
        Files.createDirectories(targetDir);
        String name = sourceFile.getFileName().toString().toLowerCase(Locale.ROOT);
        int count = 0;
        if (name.endsWith(".dll")) {
            if (!matchesPeArch(sourceFile, arch)) {
                throw new IOException("That DLL does not match your " + arch.bits + "-bit Java architecture.");
            }
            Path dest = targetDir.resolve("opengl32.dll");
            Files.copy(sourceFile, dest, StandardCopyOption.REPLACE_EXISTING);
            count = 1;
            Path parent = sourceFile.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                for (Path p : walkFilesSafely(parent, 1)) {
                    String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (matchesPeArch(p, arch) && isMesaRelatedDllName(fn)) {
                        try {
                            Files.copy(p, targetDir.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                            if (!fn.equals("opengl32.dll")) count++;
                        } catch (Exception ignored) {}
                    }
                }
            }
            return count;
        } else if (name.endsWith(".zip")) {
            try (InputStream fis = Files.newInputStream(sourceFile);
                 ZipInputStream zis = new ZipInputStream(fis)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String entryName = entry.getName().replace('\\', '/');
                    String lower = entryName.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".dll")) continue;
                    if ((lower.contains("/x86/") || lower.contains("/win32/")) && arch != Arch.X86) continue;
                    if ((lower.contains("/x64/") || lower.contains("/win64/")) && arch == Arch.X86) continue;
                    String baseName = Path.of(entryName).getFileName().toString();
                    if (!isMesaRelatedDllName(baseName.toLowerCase(Locale.ROOT))) continue;
                    Path out = targetDir.resolve(baseName);
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                    if (!matchesPeArch(out, arch)) {
                        Files.deleteIfExists(out);
                    } else {
                        count++;
                    }
                }
            }
            if (!isValidMesaDll(targetDir.resolve("opengl32.dll"), arch)) {
                throw new IOException("No " + arch.bits + "-bit opengl32.dll found inside the selected .zip archive.");
            }
            return count;
        } else if (name.endsWith(".7z")) {
            count = extractSevenZipArchive(sourceFile, targetDir, arch);
            if (!isValidMesaDll(targetDir.resolve("opengl32.dll"), arch)) {
                throw new IOException("No " + arch.bits + "-bit opengl32.dll found inside the selected .7z archive.");
            }
            return count;
        }
        throw new IOException("Please choose an opengl32.dll file or a Mesa3D .zip / .7z archive.");
    }

    private static boolean isMesaRelatedDllName(String fn) {
        return fn.equals("opengl32.dll")
                || fn.startsWith("libgl")
                || fn.startsWith("libgallium")
                || fn.startsWith("libstdc++")
                || fn.startsWith("libgcc")
                || fn.startsWith("libwinpthread")
                || fn.equals("dxil.dll")
                || fn.equals("zink_dri.dll");
    }

    /**
     * Extracts a .7z archive into targetDir using:
     *  1. Installed 7-Zip / WinRAR or SHA-256 verified cached 7za.exe / 7zr.exe
     *  2. Windows 11+ System32\tar.exe
     *  3. Auto-downloaded SHA-256 verified standalone 7za920.zip / 7zr.exe
     */
    private static int extractSevenZipArchive(Path archiveFile, Path targetDir, Arch arch) throws IOException {
        Files.createDirectories(targetDir);
        Path tempExtract = targetDir.resolve("_extract_" + System.currentTimeMillis());
        Files.createDirectories(tempExtract);
        try {
            boolean extracted = false;

            for (String sevenZip : candidateSevenZipPaths()) {
                if (Files.isRegularFile(Path.of(sevenZip))) {
                    extracted = runArchiveExtractor(
                            List.of(sevenZip, "x", "-y", "-o" + tempExtract.toAbsolutePath(),
                                    archiveFile.toAbsolutePath().toString()),
                            tempExtract);
                    if (extracted) break;
                }
            }

            if (!extracted) {
                for (String winRar : candidateWinRarPaths()) {
                    if (Files.isRegularFile(Path.of(winRar))) {
                        extracted = runArchiveExtractor(
                                List.of(winRar, "x", "-ibck", "-y",
                                        archiveFile.toAbsolutePath().toString(),
                                        tempExtract.toAbsolutePath() + File.separator),
                                tempExtract);
                        if (extracted) break;
                    }
                }
            }

            if (!extracted) {
                extracted = runArchiveExtractor(
                        List.of(Safe.systemExe("tar.exe"), "-xf", archiveFile.toAbsolutePath().toString(),
                                "-C", tempExtract.toAbsolutePath().toString()),
                        tempExtract);
            }

            if (!extracted) {
                Path auto7z = ensureSevenZipExtractor();
                if (auto7z != null && Files.isRegularFile(auto7z)) {
                    extracted = runArchiveExtractor(
                            List.of(auto7z.toAbsolutePath().toString(), "x", "-y",
                                    "-o" + tempExtract.toAbsolutePath(),
                                    archiveFile.toAbsolutePath().toString()),
                            tempExtract);
                }
            }

            int copied = 0;
            for (Path p : walkFilesSafely(tempExtract, 5)) {
                String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!fn.endsWith(".dll") || !isMesaRelatedDllName(fn)) continue;
                String rel = tempExtract.relativize(p).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if ((rel.contains("x86/") || rel.contains("win32/") || rel.contains("32bit")) && arch != Arch.X86) {
                    if (!matchesPeArch(p, arch)) continue;
                }
                if ((rel.contains("x64/") || rel.contains("win64/") || rel.contains("64bit")) && arch == Arch.X86) {
                    if (!matchesPeArch(p, arch)) continue;
                }
                if (matchesPeArch(p, arch)) {
                    Files.copy(p, targetDir.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
            return copied;
        } finally {
            deleteDirectoryQuietly(tempExtract);
        }
    }

    /**
     * Downloads the official standalone 7-Zip console extractor (7za920.zip or 7zr.exe) into
     * %APPDATA%\.kpelauncher\mesa\ and strictly verifies its pinned SHA-256 hash before extraction/use.
     */
    private static Path ensureSevenZipExtractor() {
        Path toolsDir = LauncherConfig.ROOT_DIR.resolve("mesa");
        try {
            Files.createDirectories(toolsDir);
            Path sevenZa = toolsDir.resolve("7za.exe");
            if (Files.isRegularFile(sevenZa) && Files.size(sevenZa) > 100_000L) {
                return sevenZa;
            }
            Path sevenZr = toolsDir.resolve("7zr.exe");
            if (Files.isRegularFile(sevenZr) && SEVEN_ZR_EXE_SHA256.equalsIgnoreCase(sha256Hex(sevenZr))) {
                return sevenZr;
            }

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            // 1. Download versioned 7za920.zip, verify pinned SHA-256, then extract 7za.exe
            for (String zipUrl : List.of(SEVEN_ZA_ZIP_URL, SEVEN_ZA_ZIP_MIRROR_URL)) {
                Path zipPart = toolsDir.resolve("7za920.zip.part");
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(zipUrl))
                            .header("User-Agent", "KPEClubLauncher/1.8.1")
                            .timeout(Duration.ofSeconds(45))
                            .GET()
                            .build();
                    HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        try (InputStream in = resp.body()) {
                            Files.copy(in, zipPart, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (!SEVEN_ZA_ZIP_SHA256.equalsIgnoreCase(sha256Hex(zipPart))) {
                            Files.deleteIfExists(zipPart);
                            continue;
                        }
                        try (InputStream fis = Files.newInputStream(zipPart);
                             ZipInputStream zis = new ZipInputStream(fis)) {
                            ZipEntry entry;
                            while ((entry = zis.getNextEntry()) != null) {
                                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith("7za.exe")) {
                                    Files.copy(zis, sevenZa, StandardCopyOption.REPLACE_EXISTING);
                                    break;
                                }
                            }
                        }
                        Files.deleteIfExists(zipPart);
                        if (Files.isRegularFile(sevenZa) && Files.size(sevenZa) > 100_000L) {
                            return sevenZa;
                        }
                    }
                } catch (Throwable ignored) {
                    try { Files.deleteIfExists(zipPart); } catch (IOException ignored2) {}
                }
            }

            // 2. Fallback: download 7zr.exe and verify pinned SHA-256
            Path zrPart = toolsDir.resolve("7zr.exe.part");
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(SEVEN_ZR_EXE_URL))
                        .header("User-Agent", "KPEClubLauncher/1.8.1")
                        .timeout(Duration.ofSeconds(45))
                        .GET()
                        .build();
                HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    try (InputStream in = resp.body()) {
                        Files.copy(in, zrPart, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (SEVEN_ZR_EXE_SHA256.equalsIgnoreCase(sha256Hex(zrPart))) {
                        Files.move(zrPart, sevenZr, StandardCopyOption.REPLACE_EXISTING);
                        return sevenZr;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { Files.deleteIfExists(zrPart); } catch (IOException ignored2) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean runArchiveExtractor(List<String> command, Path tempExtract) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor(60, TimeUnit.SECONDS);
            for (Path f : walkFilesSafely(tempExtract, 4)) {
                if (f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dll")) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private static List<String> candidateSevenZipPaths() {
        List<String> list = new ArrayList<>();
        Path mesaTools = LauncherConfig.ROOT_DIR.resolve("mesa");
        list.add(mesaTools.resolve("7za.exe").toString());
        list.add(mesaTools.resolve("7zr.exe").toString());
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String progData = System.getenv("ProgramData");
        if (pf != null) list.add(Path.of(pf, "7-Zip", "7z.exe").toString());
        if (pf86 != null) list.add(Path.of(pf86, "7-Zip", "7z.exe").toString());
        if (progData != null) {
            list.add(Path.of(progData, "chocolatey", "bin", "7z.exe").toString());
            list.add(Path.of(progData, "chocolatey", "tools", "7z.exe").toString());
        }
        return list;
    }

    private static List<String> candidateWinRarPaths() {
        List<String> list = new ArrayList<>();
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        if (pf != null) {
            list.add(Path.of(pf, "WinRAR", "WinRAR.exe").toString());
            list.add(Path.of(pf, "WinRAR", "UnRAR.exe").toString());
        }
        if (pf86 != null) {
            list.add(Path.of(pf86, "WinRAR", "WinRAR.exe").toString());
            list.add(Path.of(pf86, "WinRAR", "UnRAR.exe").toString());
        }
        return list;
    }

    /**
     * Ensures Mesa3D opengl32.dll is installed in %APPDATA%\.kpelauncher\mesa\<arch>\.
     * Downloads the pinned pal1000/mesa-dist-win release, verifies its SHA-256 hash,
     * and extracts opengl32.dll + libgallium_wgl.dll.
     */
    public static void ensureMesaInstalled(Arch arch,
                                           Consumer<DownloadProgress> progress,
                                           BooleanSupplier cancelled) throws Exception {
        if (isMesaInstalled(arch)) {
            if (progress != null) progress.accept(new DownloadProgress(1, 1, "Mesa3D ready"));
            return;
        }

        Path targetDir = mesaDir(arch);
        Files.createDirectories(targetDir);
        Path archivePath = targetDir.resolve("mesa3d-26.2.6-release-mingw.7z");
        Path partPath = archivePath.resolveSibling(archivePath.getFileName().toString() + ".part");

        boolean needDownload = true;
        if (Files.isRegularFile(archivePath)) {
            if (MESA_ARCHIVE_SHA256.equalsIgnoreCase(sha256Hex(archivePath))) {
                needDownload = false;
            } else {
                Files.deleteIfExists(archivePath);
            }
        }

        if (needDownload) {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();

            HttpRequest req = HttpRequest.newBuilder(URI.create(MESA_ARCHIVE_URL))
                    .header("User-Agent", "KPEClubLauncher/1.8.1")
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();

            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("HTTP " + resp.statusCode() + " while downloading Mesa3D from " + MESA_ARCHIVE_URL);
            }

            long totalBytes = resp.headers().firstValueAsLong("Content-Length").orElse(55_800_000L);
            long downloaded = 0;
            long lastEmit = 0;

            try (InputStream in = resp.body();
                 OutputStream out = Files.newOutputStream(partPath)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new CancellationException("Download cancelled.");
                    }
                    out.write(buf, 0, n);
                    downloaded += n;
                    long now = System.currentTimeMillis();
                    if (progress != null && (now - lastEmit > 100 || downloaded == totalBytes)) {
                        lastEmit = now;
                        progress.accept(new DownloadProgress(downloaded, Math.max(downloaded, totalBytes), "opengl32.dll"));
                    }
                }
            }

            String gotHash = sha256Hex(partPath);
            if (!MESA_ARCHIVE_SHA256.equalsIgnoreCase(gotHash)) {
                Files.deleteIfExists(partPath);
                throw new SecurityException("Mesa3D archive SHA-256 verification failed (expected "
                        + MESA_ARCHIVE_SHA256 + ", got " + gotHash + ").");
            }
            Files.move(partPath, archivePath, StandardCopyOption.REPLACE_EXISTING);
        }

        extractSevenZipArchive(archivePath, targetDir, arch);

        if (!isMesaInstalled(arch)) {
            throw new IOException("Downloaded Mesa3D archive, but could not extract opengl32.dll.");
        }
        Files.deleteIfExists(archivePath);
    }

    /**
     * Prepares an isolated JRE shim in %APPDATA%\.kpelauncher\mesa\jre-shim-<arch>\ and stages
     * Mesa3D DLLs into nativesDir without modifying the user's own system JDK bin folder.
     */
    public static Path prepareJavaForDriverBypass(Path javaBinaryPath, Path nativesDir, Arch arch) {
        if (javaBinaryPath == null || !isMesaInstalled(arch)) return javaBinaryPath;

        Path activeMesaDir = mesaDir(arch);
        copyMesaDllsToDir(activeMesaDir, nativesDir, arch);

        Path shimmed = createWritableJreShim(javaBinaryPath, activeMesaDir, arch);
        return shimmed != null ? shimmed : javaBinaryPath;
    }

    private static boolean copyMesaDllsToDir(Path activeMesaDir, Path destDir, Arch arch) {
        if (destDir == null) return false;
        try {
            Files.createDirectories(destDir);
            for (Path p : walkFilesSafely(activeMesaDir, 1)) {
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dll")
                        && matchesPeArch(p, arch)) {
                    Path out = destDir.resolve(p.getFileName().toString());
                    if (!Files.exists(out) || Files.size(out) != Files.size(p)) {
                        Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return isValidMesaDll(destDir.resolve("opengl32.dll"), arch);
        } catch (Throwable e) {
            return isValidMesaDll(destDir.resolve("opengl32.dll"), arch);
        }
    }

    private static Path createWritableJreShim(Path originalJavaExe, Path activeMesaDir, Arch arch) {
        try {
            Path origBin = originalJavaExe.toAbsolutePath().normalize().getParent();
            if (origBin == null) return null;
            Path origHome = origBin.getParent();
            if (origHome == null || !Files.isDirectory(origHome)) return null;

            String tag = Integer.toHexString(origHome.toString().toLowerCase(Locale.ROOT).hashCode());
            Path shimHome = LauncherConfig.ROOT_DIR.resolve("mesa")
                    .resolve("jre-shim-" + (arch == Arch.X86 ? "x86-" : "x64-") + tag);
            Path shimBin = shimHome.resolve("bin");
            Files.createDirectories(shimBin);

            try (var stream = Files.newDirectoryStream(origBin)) {
                for (Path p : stream) {
                    String fn = p.getFileName().toString();
                    Path dest = shimBin.resolve(fn);
                    if (Files.isRegularFile(p)) {
                        if (!Files.exists(dest) || Files.size(dest) != Files.size(p)) {
                            Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else if (Files.isDirectory(p) && !Files.exists(dest)) {
                        createJunction(dest, p);
                    }
                }
            }

            try (var stream = Files.newDirectoryStream(origHome)) {
                for (Path p : stream) {
                    String fn = p.getFileName().toString();
                    if (fn.equalsIgnoreCase("bin")) continue;
                    Path linkOrCopy = shimHome.resolve(fn);
                    if (Files.isRegularFile(p)) {
                        if (!Files.exists(linkOrCopy)) {
                            Files.copy(p, linkOrCopy, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else if (Files.isDirectory(p) && !Files.exists(linkOrCopy)) {
                        createJunction(linkOrCopy, p);
                    }
                }
            }

            copyMesaDllsToDir(activeMesaDir, shimBin, arch);
            Path shimExe = shimBin.resolve(originalJavaExe.getFileName().toString());
            if (Files.isRegularFile(shimExe)
                    && isValidMesaDll(shimBin.resolve("opengl32.dll"), arch)
                    && JavaProbe.probe(shimExe) != null) {
                return shimExe;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void createJunction(Path link, Path target) {
        try {
            Process mk = new ProcessBuilder(
                    Safe.systemExe("cmd.exe"), "/c", "mklink", "/J",
                    link.toAbsolutePath().toString(),
                    target.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();
            mk.getInputStream().transferTo(OutputStream.nullOutputStream());
            mk.waitFor(5, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Generates a tiny Java 5 (classfile version 49.0) javaagent JAR at
     * %APPDATA%\.kpelauncher\mesa\kpe-mesa-agent.jar that calls System.load(agentArgs)
     * inside premain() before Minecraft/LWJGL/GLFW loads any native libraries.
     */
    private static Path ensureMesaAgentJar() {
        Path jarPath = LauncherConfig.ROOT_DIR.resolve("mesa").resolve("kpe-mesa-agent.jar");
        try {
            if (Files.isRegularFile(jarPath) && Files.size(jarPath) > 200) {
                return jarPath;
            }
            Files.createDirectories(jarPath.getParent());
            byte[] classBytes = buildMesaAgentClassBytes();
            String manifest = "Manifest-Version: 1.0\r\n"
                    + "Premain-Class: site.kpeclub.launcher.launch.KpeMesaAgent\r\n\r\n";
            try (OutputStream fos = Files.newOutputStream(jarPath);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                ZipEntry mf = new ZipEntry("META-INF/MANIFEST.MF");
                zos.putNextEntry(mf);
                zos.write(manifest.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                ZipEntry cls = new ZipEntry("site/kpeclub/launcher/launch/KpeMesaAgent.class");
                zos.putNextEntry(cls);
                zos.write(classBytes);
                zos.closeEntry();
            }
            return jarPath;
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] buildMesaAgentClassBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);  // minor
            out.writeShort(49); // major 49 (Java 5 — runs on Java 8, 16, 17, 21+ without StackMapTable)
            out.writeShort(15); // constant_pool_count = 14 entries + 1
            out.writeByte(1); out.writeUTF("site/kpeclub/launcher/launch/KpeMesaAgent"); // #1
            out.writeByte(7); out.writeShort(1);                                          // #2 Class #1
            out.writeByte(1); out.writeUTF("java/lang/Object");                           // #3
            out.writeByte(7); out.writeShort(3);                                          // #4 Class #3
            out.writeByte(1); out.writeUTF("premain");                                    // #5
            out.writeByte(1); out.writeUTF("(Ljava/lang/String;)V");                      // #6
            out.writeByte(1); out.writeUTF("Code");                                       // #7
            out.writeByte(1); out.writeUTF("java/lang/System");                           // #8
            out.writeByte(7); out.writeShort(8);                                          // #9 Class #8
            out.writeByte(1); out.writeUTF("load");                                       // #10
            out.writeByte(12); out.writeShort(10); out.writeShort(6);                     // #11 NameAndType #10:#6
            out.writeByte(10); out.writeShort(9); out.writeShort(11);                     // #12 Methodref #9.#11
            out.writeByte(1); out.writeUTF("java/lang/Throwable");                        // #13
            out.writeByte(7); out.writeShort(13);                                         // #14 Class #13

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2);      // this_class
            out.writeShort(4);      // super_class
            out.writeShort(0);      // interfaces_count
            out.writeShort(0);      // fields_count
            out.writeShort(1);      // methods_count = 1

            out.writeShort(0x0009); // ACC_PUBLIC | ACC_STATIC
            out.writeShort(5);      // "premain"
            out.writeShort(6);      // "(Ljava/lang/String;)V"
            out.writeShort(1);      // attributes_count = 1 (Code)

            out.writeShort(7);      // "Code"
            out.writeInt(27);       // attribute_length
            out.writeShort(1);      // max_stack = 1
            out.writeShort(2);      // max_locals = 2
            out.writeInt(7);        // code_length = 7
            out.write(new byte[]{0x2a, (byte) 0xb8, 0x00, 0x0c, (byte) 0xb1, 0x4c, (byte) 0xb1});
            out.writeShort(1);      // exception_table_length = 1
            out.writeShort(0);      // start_pc = 0
            out.writeShort(4);      // end_pc = 4
            out.writeShort(5);      // handler_pc = 5
            out.writeShort(14);     // catch_type = #14 (Throwable)
            out.writeShort(0);      // code attributes_count = 0

            out.writeShort(0);      // class attributes_count = 0
        }
        return baos.toByteArray();
    }

    /**
     * Returns JVM flags for Old PC GPU driver bypass, including the premain Java agent and
     * LWJGL explicit OpenGL library path when Mesa3D is installed.
     */
    public static List<String> driverBypassJvmFlags(Path javaBinaryPath, Path nativesDir, Arch arch, int requiredJavaMajor) {
        List<String> flags = new ArrayList<>();
        flags.add("-Dsodium.checks.issue2561=false");
        flags.add("-Dsodium.checks.issue1486=false");
        flags.add("-Dsodium.checks.win32.intel=false");
        flags.add("-Diris.allowUnsupportedGpu=true");

        if (isMesaInstalled(arch)) {
            Path activeMesaDir = mesaDir(arch).toAbsolutePath().normalize();
            Path mesaGl = activeMesaDir.resolve("opengl32.dll");
            flags.add("-Dorg.lwjgl.opengl.libname=" + mesaGl);

            Path agentJar = ensureMesaAgentJar();
            if (agentJar != null && Files.isRegularFile(agentJar)) {
                String agentPath = agentJar.toAbsolutePath().normalize().toString();
                for (String companion : COMPANION_DLL_ORDER) {
                    Path compPath = activeMesaDir.resolve(companion);
                    if (Files.isRegularFile(compPath) && matchesPeArch(compPath, arch)) {
                        flags.add("-javaagent:" + agentPath + "=" + compPath.toAbsolutePath().normalize());
                    }
                }
                flags.add("-javaagent:" + agentPath + "=" + mesaGl);
            }
        }
        return flags;
    }

    /**
     * Populates environment variables for Mesa3D LLVMPipe software rendering across all Minecraft versions.
     * Sets MESA_GL_VERSION_OVERRIDE=3.3 ONLY on Minecraft 1.17+ (requiredJavaMajor >= 16) while preserving
     * Compatibility Profile on Minecraft 1.16.5 and older.
     */
    public static void applyDriverBypassEnv(Map<String, String> env, Path nativesDir, Arch arch, int requiredJavaMajor) {
        if (env == null) return;
        env.put("GALLIUM_DRIVER", "llvmpipe");
        env.put("LIBGL_ALWAYS_SOFTWARE", "1");
        env.put("MESA_NO_ERROR", "1");

        if (requiredJavaMajor >= 16) {
            env.put("MESA_GL_VERSION_OVERRIDE", "3.3");
            env.put("MESA_GLSL_VERSION_OVERRIDE", "330");
        } else {
            env.remove("MESA_GL_VERSION_OVERRIDE");
            env.remove("MESA_GLSL_VERSION_OVERRIDE");
        }

        // Prepend activeMesaDir and nativesDir to PATH (handling Windows case-insensitive Path key)
        String pathKey = "PATH";
        for (String k : env.keySet()) {
            if (k.equalsIgnoreCase("PATH")) {
                pathKey = k;
                break;
            }
        }
        String existingPath = env.getOrDefault(pathKey, "");
        StringBuilder newPath = new StringBuilder();
        if (isMesaInstalled(arch)) {
            newPath.append(mesaDir(arch).toAbsolutePath().normalize()).append(File.pathSeparator);
        }
        if (nativesDir != null) {
            newPath.append(nativesDir.toAbsolutePath().normalize()).append(File.pathSeparator);
        }
        newPath.append(existingPath);
        env.put(pathKey, newPath.toString());
    }

    /**
     * Removes staged Mesa3D DLL copies from nativesDir, GAME_DIR, runtimes, and JRE shim folders
     * when the user turns OFF "Bypass unsupported GPU driver" (while keeping .kpelauncher/mesa/<arch>/).
     */
    /**
     * Removes any staged Mesa3D DLLs from a specific natives directory so 1.16.5 and older
     * run purely on the original javaw.exe and native OpenGL 2.1 driver.
     */
    public static void removeMesaFromDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try {
            for (Path f : walkFilesSafely(dir, 1)) {
                String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (isMesaRelatedDllName(fn)) {
                    try { Files.deleteIfExists(f); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void cleanupStagedMesaDlls() {
        try {
            for (String dll : new String[]{"opengl32.dll", "libgallium_wgl.dll", "libglapi.dll"}) {
                try {
                    Files.deleteIfExists(LauncherConfig.GAME_DIR.resolve(dll));
                } catch (Throwable ignored) {}
            }
            for (Path base : List.of(LauncherConfig.ROOT_DIR.resolve("runtimes"), LauncherConfig.VERSIONS_DIR)) {
                for (Path f : walkFilesSafely(base, 4)) {
                    String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (isMesaRelatedDllName(fn)) {
                        try {
                            Files.deleteIfExists(f);
                        } catch (Throwable ignored) {}
                    }
                }
            }
            Path mesaRoot = LauncherConfig.ROOT_DIR.resolve("mesa");
            if (Files.isDirectory(mesaRoot)) {
                try (var stream = Files.newDirectoryStream(mesaRoot, "jre-shim-*")) {
                    for (Path shimDir : stream) {
                        deleteDirectoryQuietly(shimDir);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), 6,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                            if (!d.equals(dir) && (attrs.isSymbolicLink() || attrs.isOther())) {
                                try { Files.deleteIfExists(d); } catch (Throwable ignored) {}
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            try { Files.deleteIfExists(file); } catch (Throwable ignored) {}
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                            try { Files.deleteIfExists(d); } catch (Throwable ignored) {}
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /**
     * JVM flags for smooth gameplay.
     * Deliberately omits -XX:+DisableExplicitGC so mods/Netty can reclaim direct ByteBuffers.
     */
    public static List<String> jvmFlags(boolean java64) {
        if (!java64) return List.of();
        return List.of(
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseG1GC",
                "-XX:+ParallelRefProcEnabled",
                "-XX:MaxGCPauseMillis=50",
                "-XX:G1NewSizePercent=30",
                "-XX:G1MaxNewSizePercent=40",
                "-XX:G1HeapRegionSize=8M",
                "-XX:G1ReservePercent=20",
                "-XX:G1HeapWastePercent=5",
                "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=15",
                "-XX:G1MixedGCLiveThresholdPercent=90",
                "-XX:SurvivorRatio=32",
                "-XX:MaxTenuringThreshold=1",
                "-XX:+PerfDisableSharedMem");
    }

    /**
     * Makes Windows give the game CPU time first using lightweight wmic on Windows 7/10,
     * falling back to PowerShell Set-ProcessPriority when wmic.exe is absent on newer Windows 11.
     */
    public static void setHighPriority(long pid) {
        Thread t = new Thread(() -> {
            try {
                String wmicPath = Safe.systemExe("wbem\\wmic.exe");
                if (Files.isRegularFile(Path.of(wmicPath))) {
                    Process p = new ProcessBuilder(
                            wmicPath, "process", "where", "ProcessId=" + pid, "call", "setpriority", "128"
                    ).redirectErrorStream(true).start();
                    p.getInputStream().transferTo(OutputStream.nullOutputStream());
                    p.waitFor(3, TimeUnit.SECONDS);
                } else {
                    Process p = new ProcessBuilder(
                            Safe.systemExe("WindowsPowerShell\\v1.0\\powershell.exe"),
                            "-NoProfile", "-NonInteractive", "-Command",
                            "try { (Get-Process -Id " + pid + " -ErrorAction Stop).PriorityClass = 'High' } catch {}"
                    ).redirectErrorStream(true).start();
                    p.getInputStream().transferTo(OutputStream.nullOutputStream());
                    p.waitFor(4, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
            }
        }, "kpe-priority");
        t.setDaemon(true);
        t.start();
    }

    /** Downloads the FPS mods (and their required dependencies like Fabric API) for this Minecraft
     *  version into the mods folder and registers them in ModInstaller. */
    public static List<String> installFpsMods(String minecraftVersion) throws IOException, InterruptedException {
        ModInstaller installer = new ModInstaller();
        List<String> installed = new ArrayList<>();
        IOException lastError = null;
        for (String slug : FPS_MODS) {
            try {
                List<String> files = installer.install(slug, minecraftVersion);
                if (!files.isEmpty() || ModInstaller.isInstalled(slug)) {
                    installed.add(slug);
                }
            } catch (IOException e) {
                if (e.getMessage() == null || !e.getMessage().startsWith("This mod has no")) {
                    lastError = e;
                }
            }
        }
        if (installed.isEmpty() && lastError != null) {
            throw lastError;
        }
        return installed;
    }

    /** Sets faster graphics in options.txt (keeps every other option the player chose). */
    public static void applyLowGraphics() throws IOException {
        Path file = LauncherConfig.GAME_DIR.resolve("options.txt");
        Map<String, String> wanted = new LinkedHashMap<>();
        wanted.put("renderDistance", "8");
        wanted.put("graphicsMode", "0");      // 1.16+: 0 = Fast
        wanted.put("fancyGraphics", "false"); // older versions
        wanted.put("particles", "2");         // minimal
        wanted.put("entityShadows", "false");
        wanted.put("enableVsync", "false");
        wanted.put("maxFps", "144");
        wanted.put("biomeBlendRadius", "1");
        wanted.put("mipmapLevels", "2");

        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            int c = lines.get(i).indexOf(':');
            if (c <= 0) continue;
            String key = lines.get(i).substring(0, c);
            if (wanted.containsKey(key)) {
                lines.set(i, key + ":" + wanted.get(key));
                done.add(key);
            }
        }
        for (var e : wanted.entrySet()) {
            if (!done.contains(e.getKey())) lines.add(e.getKey() + ":" + e.getValue());
        }
        Files.createDirectories(file.getParent());
        Files.write(file, lines);
    }
}
