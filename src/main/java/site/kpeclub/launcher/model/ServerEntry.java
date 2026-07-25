package site.kpeclub.launcher.model;

/**
 * Represents a server the launcher can connect to.
 * "preset" servers come bundled with the launcher (KPE Club network).
 * Non-preset servers are added manually by the player.
 */
public class ServerEntry {
    private String name;
    private String address; // host:port
    private boolean preset;
    private String iconPath; // optional, resources/images/

    public ServerEntry() {}

    public ServerEntry(String name, String address, boolean preset, String iconPath) {
        this.name = name;
        this.address = address;
        this.preset = preset;
        this.iconPath = iconPath;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public boolean isPreset() { return preset; }
    public void setPreset(boolean preset) { this.preset = preset; }

    public String getIconPath() { return iconPath; }
    public void setIconPath(String iconPath) { this.iconPath = iconPath; }

    @Override
    public String toString() {
        return name + "  (" + address + ")";
    }
}
