package site.kpeclub.launcher.model;

/** One mod jar found in .minecraft/mods, with metadata read from inside the jar if possible. */
public class ModInfo {
    private final String fileName;
    private final String modId;      // null if we couldn't read metadata
    private final String modName;    // null if we couldn't read metadata
    private final String version;    // null if we couldn't read metadata
    private final String loader;     // "Fabric", "Forge", or "Unknown"
    private final long sizeBytes;

    public ModInfo(String fileName, String modId, String modName, String version, String loader, long sizeBytes) {
        this.fileName = fileName;
        this.modId = modId;
        this.modName = modName;
        this.version = version;
        this.loader = loader;
        this.sizeBytes = sizeBytes;
    }

    public String getFileName() { return fileName; }
    public String getModId() { return modId; }
    public String getModName() { return modName; }
    public String getVersion() { return version; }
    public String getLoader() { return loader; }
    public long getSizeBytes() { return sizeBytes; }
    public double getSizeMB() { return sizeBytes / 1024.0 / 1024.0; }

    /** Best display name available — falls back to the file name if metadata couldn't be read. */
    public String getDisplayName() {
        return (modName != null && !modName.isBlank()) ? modName : fileName;
    }
}
