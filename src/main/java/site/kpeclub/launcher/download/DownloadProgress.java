package site.kpeclub.launcher.download;

/**
 * Snapshot of download progress passed to the UI on each update.
 */
public record DownloadProgress(long bytesDownloaded, long totalBytes, String currentFile) {

    public double fraction() {
        if (totalBytes <= 0) return 0;
        return Math.min(1.0, (double) bytesDownloaded / totalBytes);
    }

    public double percent() {
        return fraction() * 100.0;
    }

    public double downloadedMB() {
        return bytesDownloaded / 1024.0 / 1024.0;
    }

    public double totalMB() {
        return totalBytes / 1024.0 / 1024.0;
    }
}
