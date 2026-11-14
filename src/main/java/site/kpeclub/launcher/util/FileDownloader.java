package site.kpeclub.launcher.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Safe file downloads for the whole launcher.
 *
 * Fixes the old behaviour where a download that was cut off (internet drop, app closed)
 * left a half-written file that was later treated as "already downloaded" and crashed the game:
 *  - writes to "<file>.part" first, then moves it into place only when complete
 *  - checks the SHA-1 hash when Mojang gives one
 *  - follows redirects, uses system proxy settings, falls back to HTTP/1.1 on retry, and retries 3 times
 */
public final class FileDownloader {

    static {
        // Prefer IPv4 stack by default so broken/partial IPv6 ISP routes do not blackhole HTTPS requests
        if (System.getProperty("java.net.preferIPv4Stack") == null) {
            System.setProperty("java.net.preferIPv4Stack", "true");
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong PART_SEQ = new java.util.concurrent.atomic.AtomicLong();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .proxy(ProxySelector.getDefault())
            .build();

    private FileDownloader() {}

    public static HttpClient http() { return HTTP; }

    /** Downloads a text/JSON URL fully into a String. */
    public static String getString(String url) throws IOException, InterruptedException {
        Safe.requireHttps(url);
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "KPEClubLauncher/1.8.1")
                        .GET();
                if (attempt > 1) {
                    rb.version(HttpClient.Version.HTTP_1_1);
                }
                HttpResponse<String> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 400) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
                return resp.body();
            } catch (IOException e) {
                last = e;
                Thread.sleep(400L * attempt);
            }
        }
        throw last;
    }

    /** True if the file exists and (when known) has the expected size. Cheap check, no hashing. */
    public static boolean looksComplete(Path file, long expectedSize) {
        try {
            if (!Files.isRegularFile(file)) return false;
            long size = Files.size(file);
            if (size == 0 && expectedSize != 0) return false;
            return expectedSize <= 0 || size == expectedSize;
        } catch (IOException e) {
            return false;
        }
    }

    /** True if the file exists, matches expectedSize (when > 0), and matches expectedSha1 (when provided). */
    public static boolean isVerifiedComplete(Path file, String expectedSha1, long expectedSize) {
        if (!looksComplete(file, expectedSize)) return false;
        if (expectedSha1 == null || expectedSha1.isBlank()) return true;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(expectedSha1);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Downloads url to dest unless a complete copy is already there.
     * @param sha1 expected SHA-1 (hex) or null if unknown
     * @param size expected size in bytes or 0 if unknown
     */
    public static void fetch(String url, Path dest, String sha1, long size) throws IOException, InterruptedException {
        Safe.requireHttps(url);
        if (isVerifiedComplete(dest, sha1, size)) return;
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part." + PART_SEQ.incrementAndGet());

        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "KPEClubLauncher/1.8.1")
                        .GET();
                if (attempt > 1) {
                    rb.version(HttpClient.Version.HTTP_1_1);
                }
                HttpResponse<InputStream> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() >= 400) {
                    resp.body().close();
                    throw new IOException("HTTP " + resp.statusCode() + " for " + url);
                }
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                long written = 0;
                try (InputStream in = resp.body();
                     var out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        written += n;
                    }
                }
                if (size > 0 && written != size) {
                    throw new IOException("Incomplete download (expected " + size + " bytes, got " + written + ") for " + url);
                }
                if (sha1 != null && !sha1.isBlank()
                        && !HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(sha1)) {
                    throw new IOException("Corrupted download (hash mismatch) for " + url);
                }
                try {
                    Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(part);
                Thread.sleep(500L * attempt);
            } catch (java.security.NoSuchAlgorithmException e) {
                Files.deleteIfExists(part);
                throw new IOException(e);
            }
        }
        throw new IOException("Failed to download " + url + ": " + (last != null ? last.getMessage() : "unknown"), last);
    }
}
