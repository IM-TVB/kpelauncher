package site.kpeclub.launcher.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Detects the REAL Windows architecture (not just the launcher's own JVM) and physical RAM
 * without requiring the heavy java.management / jdk.management modules.
 */
public final class PlatformInfo {

    public enum Arch {
        X86("x86", "windows-x86", "32"),
        X64("x64", "windows-x64", "64"),
        ARM64("arm64", "windows-arm64", "64");

        public final String ruleName;
        public final String runtimeKey;
        public final String bits;

        Arch(String ruleName, String runtimeKey, String bits) {
            this.ruleName = ruleName;
            this.runtimeKey = runtimeKey;
            this.bits = bits;
        }
    }

    private static final Arch OS_ARCH = detectOsArch();
    private static final boolean LAUNCHER_JVM_64 = detectJvm64();
    private static volatile long cachedTotalRamMb = -2;

    private PlatformInfo() {}

    public static Arch osArch() { return OS_ARCH; }

    public static Arch minecraftArch() {
        return OS_ARCH == Arch.X86 ? Arch.X86 : Arch.X64;
    }

    public static boolean isOs64Bit() { return OS_ARCH != Arch.X86; }

    public static boolean isLauncherJvm64Bit() { return LAUNCHER_JVM_64; }

    public static String describe() {
        return "Windows " + (isOs64Bit() ? "64-bit" : "32-bit") + " (" + OS_ARCH.ruleName + ")";
    }

    public static int maxSafeHeapMb(boolean java64) {
        if (!java64) return 1024;
        long total = totalPhysicalMemoryMb();
        if (total <= 0) return 4096;
        return (int) Math.max(1024, Math.min(32768, total - 1024));
    }

    /** Total installed RAM in MB, or -1 if unknown (cached after first query). */
    public static long totalPhysicalMemoryMb() {
        long cached = cachedTotalRamMb;
        if (cached != -2) return cached;
        long detected = queryWindowsMemoryMb();
        cachedTotalRamMb = detected;
        return detected;
    }

    private static long queryWindowsMemoryMb() {
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory")
                    .redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.matches("^\\d+$")) {
                        p.waitFor(3, TimeUnit.SECONDS);
                        return Long.parseLong(line) / (1024L * 1024L);
                    }
                }
            }
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (Throwable ignored) {}
        return -1;
    }

    private static volatile Boolean cachedModernGpuSupport;

    /**
     * Returns true when this PC's CPU and GPU natively support modern Minecraft (1.17+ OpenGL 3.2+),
     * so the legacy GPU driver-bypass UI can be hidden.
     */
    public static boolean supportsAllMinecraftVersionsNatively() {
        Boolean cached = cachedModernGpuSupport;
        if (cached != null) return cached;
        boolean result = detectModernGpuSupport();
        cachedModernGpuSupport = result;
        return result;
    }

    private static boolean detectModernGpuSupport() {
        if (!isOs64Bit()) return false;
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name) -join '|'")
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isBlank()) sb.append(line).append('|');
                }
            }
            p.waitFor(4, TimeUnit.SECONDS);
            String allGpus = sb.toString().toLowerCase(java.util.Locale.ROOT);
            if (allGpus.isBlank()) return false;

            boolean hasDedicatedOrModern = false;
            for (String rawName : allGpus.split("\\|")) {
                String g = rawName.trim();
                if (g.isEmpty()) continue;
                if (g.contains("microsoft basic") || g.contains("standard vga") || g.contains("remote")) continue;
                boolean isLegacyIntel = g.contains("gma") || g.contains("q45") || g.contains("q43")
                        || g.contains("g41") || g.contains("g43") || g.contains("g45")
                        || g.contains("4 series") || g.contains("945") || g.contains("965")
                        || g.contains("3150") || g.contains("hd graphics 2000") || g.contains("hd graphics 3000")
                        || (g.contains("intel(r) hd graphics") && !g.matches(".*\\b(4\\d{3}|5\\d{3}|6\\d{3}|5[123]0|6[123]0|uhd|iris)\\b.*"));
                boolean isLegacyOther = g.contains("radeon hd 2") || g.contains("radeon hd 3") || g.contains("radeon hd 4")
                        || g.contains("geforce 6") || g.contains("geforce 7") || g.contains("geforce 8")
                        || g.contains("geforce 9") || g.contains("geforce 210");
                if (!isLegacyIntel && !isLegacyOther) {
                    hasDedicatedOrModern = true;
                }
            }
            return hasDedicatedOrModern;
        } catch (Throwable ignored) {}
        return false;
    }

    private static Arch detectOsArch() {
        String wow64 = System.getenv("PROCESSOR_ARCHITEW6432");
        String native_ = wow64 != null ? wow64 : System.getenv("PROCESSOR_ARCHITECTURE");
        if (native_ != null) {
            String a = native_.toUpperCase();
            if (a.contains("ARM64")) return Arch.ARM64;
            if (a.contains("64")) return Arch.X64;
            if (a.equals("X86")) return Arch.X86;
        }
        String prop = System.getProperty("os.arch", "").toLowerCase();
        if (prop.contains("aarch64") || prop.contains("arm64")) return Arch.ARM64;
        if (prop.contains("64")) return Arch.X64;
        return Arch.X86;
    }

    private static boolean detectJvm64() {
        String model = System.getProperty("sun.arch.data.model");
        if (model != null) return model.equals("64");
        return System.getProperty("os.arch", "").contains("64");
    }
}
