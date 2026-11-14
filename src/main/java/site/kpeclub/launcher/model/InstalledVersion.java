package site.kpeclub.launcher.model;

/**
 * One entry in the Installations list — a version folder found on disk under
 * .minecraft/versions/, whether it's vanilla or modded, installed by this
 * launcher or the official one (Fabric Installer, Forge Installer, OptiFine).
 */
public class InstalledVersion {

    public enum LoaderType {
        VANILLA("Vanilla"),
        FABRIC("Fabric"),
        FORGE("Forge"),
        OPTIFINE("OptiFine");

        private final String label;
        LoaderType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private final String versionId;
    private final LoaderType loaderType;
    private final long sizeBytes;

    public InstalledVersion(String versionId, LoaderType loaderType, long sizeBytes) {
        this.versionId = versionId;
        this.loaderType = loaderType;
        this.sizeBytes = sizeBytes;
    }

    public String getVersionId() { return versionId; }
    public LoaderType getLoaderType() { return loaderType; }
    public boolean isFabric() { return loaderType == LoaderType.FABRIC; }
    public boolean isModded() { return loaderType != LoaderType.VANILLA; }
    public long getSizeBytes() { return sizeBytes; }

    public double getSizeMB() {
        return sizeBytes / 1024.0 / 1024.0;
    }

    @Override
    public String toString() {
        return String.format("%s  [%s]  -  %.1f MB", versionId, loaderType.getLabel(), getSizeMB());
    }
}
