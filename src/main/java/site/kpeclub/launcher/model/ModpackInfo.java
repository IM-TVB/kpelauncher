package site.kpeclub.launcher.model;

/** One imported Modrinth (.mrpack) modpack, tracked so it can be relaunched later. */
public class ModpackInfo {
    private final String name;
    private final String minecraftVersion;
    private final String loader;       // "fabric-loader", "forge", "quilt-loader", "neoforge", or "unknown"
    private final String loaderVersion; // may be null if not specified
    private final int fileCount;

    public ModpackInfo(String name, String minecraftVersion, String loader, String loaderVersion, int fileCount) {
        this.name = name;
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
        this.loaderVersion = loaderVersion;
        this.fileCount = fileCount;
    }

    public String getName() { return name; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public String getLoader() { return loader; }
    public String getLoaderVersion() { return loaderVersion; }
    public int getFileCount() { return fileCount; }
}
