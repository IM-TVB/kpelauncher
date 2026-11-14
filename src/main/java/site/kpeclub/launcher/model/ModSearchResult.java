package site.kpeclub.launcher.model;

/** One mod found via Modrinth search, before a specific file/version is chosen. */
public class ModSearchResult {
    private final String projectId;
    private final String slug;
    private final String title;
    private final String description;
    private final String author;
    private final long downloads;
    private final String iconUrl; // may be null

    public ModSearchResult(String projectId, String slug, String title, String description,
                            String author, long downloads, String iconUrl) {
        this.projectId = projectId;
        this.slug = slug;
        this.title = title;
        this.description = description;
        this.author = author;
        this.downloads = downloads;
        this.iconUrl = iconUrl;
    }

    public String getProjectId() { return projectId; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getAuthor() { return author; }
    public long getDownloads() { return downloads; }
    public String getIconUrl() { return iconUrl; }
}
