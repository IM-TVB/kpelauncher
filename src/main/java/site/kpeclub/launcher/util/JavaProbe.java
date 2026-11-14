package site.kpeclub.launcher.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Asks a java.exe what it is (32/64-bit, major version, and real java.home) so natives and RAM match it. */
public final class JavaProbe {

    public record Info(boolean is64Bit, int majorVersion, String javaHome) {
        public Info(boolean is64Bit, int majorVersion) {
            this(is64Bit, majorVersion, null);
        }
    }

    private static final java.util.Map<Path, Info> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private JavaProbe() {}

    public static Info probe(Path javaExe) {
        if (javaExe == null) return null;
        Path key = javaExe.toAbsolutePath().normalize();
        Info cached = CACHE.get(key);
        if (cached != null) return cached; // only good results are cached
        Info info = probeUncached(javaExe);
        if (info != null) CACHE.put(key, info);
        return info;
    }

    private static Info probeUncached(Path javaExe) {
        try {
            Path fnPath = javaExe.getFileName();
            Path exe = fnPath != null && fnPath.toString().equalsIgnoreCase("javaw.exe")
                    && java.nio.file.Files.exists(javaExe.resolveSibling("java.exe"))
                    ? javaExe.resolveSibling("java.exe") : javaExe;
            Process p = new ProcessBuilder(exe.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true).start();
            boolean is64 = true;
            boolean sawArch = false;
            int major = 8;
            String javaHome = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("sun.arch.data.model =")) {
                        is64 = line.endsWith("64");
                        sawArch = true;
                    } else if (line.startsWith("java.specification.version =")) {
                        String v = line.substring(line.indexOf('=') + 1).trim();
                        if (v.startsWith("1.")) v = v.substring(2);
                        try { major = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                    } else if (line.startsWith("java.home =")) {
                        javaHome = line.substring(line.indexOf('=') + 1).trim();
                    }
                }
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null; // hung
            }
            if (p.exitValue() != 0 || !sawArch) return null; // broken Java: do not pretend it is 64-bit Java 8
            return new Info(is64, major, javaHome);
        } catch (Exception e) {
            return null;
        }
    }
}
