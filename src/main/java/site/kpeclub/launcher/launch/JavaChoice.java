package site.kpeclub.launcher.launch;

import site.kpeclub.launcher.model.LauncherSettings;
import site.kpeclub.launcher.util.JavaProbe;
import site.kpeclub.launcher.util.PlatformInfo;
import site.kpeclub.launcher.util.PlatformInfo.Arch;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which Java will run Minecraft, and whether it is 32-bit or 64-bit.
 * The files we download (natives!) must match this Java, not the launcher's own JVM.
 *
 * @param customJava the java the player picked in Settings, or null to use auto-detected / Mojang's runtime
 * @param arch       X86 (32-bit) or X64 (64-bit)
 */
public record JavaChoice(Path customJava, Arch arch) {

    public static JavaChoice from(LauncherSettings settings) {
        String custom = settings.getJavaPath();
        if (custom != null && !custom.isBlank()) {
            String cleaned = custom.trim();
            if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            }
            try {
                Path p = Path.of(cleaned);
                // If user pasted a JDK/JRE home directory or bin directory, resolve javaw.exe / java.exe inside it
                if (Files.isDirectory(p)) {
                    if (Files.isRegularFile(p.resolve("bin").resolve("javaw.exe"))) {
                        p = p.resolve("bin").resolve("javaw.exe");
                    } else if (Files.isRegularFile(p.resolve("bin").resolve("java.exe"))) {
                        p = p.resolve("bin").resolve("java.exe");
                    } else if (Files.isRegularFile(p.resolve("javaw.exe"))) {
                        p = p.resolve("javaw.exe");
                    } else if (Files.isRegularFile(p.resolve("java.exe"))) {
                        p = p.resolve("java.exe");
                    }
                }
                String fn = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
                // only a real java.exe / javaw.exe may be run from a setting
                if (Files.isRegularFile(p) && (fn.equals("java.exe") || fn.equals("javaw.exe"))) {
                    JavaProbe.Info info = JavaProbe.probe(p);
                    if (info != null) return new JavaChoice(p, info.is64Bit() ? Arch.X64 : Arch.X86);
                    return new JavaChoice(p, PlatformInfo.minecraftArch());
                }
            } catch (Exception ignored) {
                // Invalid path syntax — fall back to automatic Java detection
            }
        }
        return new JavaChoice(null, PlatformInfo.minecraftArch());
    }

    public boolean is64Bit() { return arch != Arch.X86; }
}
