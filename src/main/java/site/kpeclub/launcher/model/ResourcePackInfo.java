package site.kpeclub.launcher.model;

/** One resource pack found in .minecraft/resourcepacks — a folder or a .zip file. */
public class ResourcePackInfo {
    private final String name;
    private final String description; // from pack.mcmeta, null if unavailable
    private final boolean isZip;
    private final long sizeBytes;

    public ResourcePackInfo(String name, String description, boolean isZip, long sizeBytes) {
        this.name = name;
        this.description = description;
        this.isZip = isZip;
        this.sizeBytes = sizeBytes;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isZip() { return isZip; }
    public long getSizeBytes() { return sizeBytes; }
    public double getSizeMB() { return sizeBytes / 1024.0 / 1024.0; }
}
