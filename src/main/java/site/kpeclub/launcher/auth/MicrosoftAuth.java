package site.kpeclub.launcher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles the full premium Microsoft -> Xbox Live -> XSTS -> Minecraft auth chain.
 *
 * Client ID protection:
 *  - No user-facing Client ID setting or plaintext UUID exists in source code or .class constant pools.
 *  - Reconstructs the internal Client ID at runtime via SHA-256 keystream XOR unmasking.
 */
public class MicrosoftAuth {

    private static final int CALLBACK_PORT = 43110;
    private static final String REDIRECT_URI = "http://127.0.0.1:" + CALLBACK_PORT + "/callback";

    /**
     * Obfuscated 16-byte UUID shards (XOR-masked with SHA-256 keystream so no plaintext
     * UUID or recognizable hex constant exists in Git history or .class constant pools).
     */
    private static final byte[] OBFUSCATED_CID = {
            (byte) 0xb3, (byte) 0x0c, (byte) 0x2a, (byte) 0x8f,
            (byte) 0xac, (byte) 0x1f, (byte) 0x76, (byte) 0x74,
            (byte) 0xc3, (byte) 0x23, (byte) 0x10, (byte) 0xe8,
            (byte) 0x0f, (byte) 0x15, (byte) 0x1d, (byte) 0x98
    };

    /**
     * For local IDE/dev runs (mvn javafx:run), checks KPE_CLIENT_ID in environment or local .env.
     * In packaged builds, installer/inject-client-id.ps1 bakes KPE_CLIENT_ID directly into OBFUSCATED_CID.
     */
    private static String readLocalDotEnvClientId() {
        try {
            String env = System.getenv("KPE_CLIENT_ID");
            if (env != null && env.trim().matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                    && !env.trim().equals("00000000-0000-0000-0000-000000000000")) {
                return env.trim();
            }
            java.nio.file.Path dotEnv = java.nio.file.Path.of(".env");
            if (java.nio.file.Files.isRegularFile(dotEnv)) {
                for (String line : java.nio.file.Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("#") || !t.contains("=")) continue;
                    int eq = t.indexOf('=');
                    String k = t.substring(0, eq).trim();
                    String v = t.substring(eq + 1).trim().replace("\"", "").replace("'", "");
                    if ("KPE_CLIENT_ID".equals(k)
                            && v.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                            && !v.equals("00000000-0000-0000-0000-000000000000")) {
                        return v;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String clientId() {
        String envCid = readLocalDotEnvClientId();
        if (envCid != null) return envCid;
        try {
            String seed = "kpe.oauth2.v2::XboxLive.signin::" + CALLBACK_PORT;
            byte[] mask = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            byte[] raw = new byte[16];
            for (int i = 0; i < 16; i++) {
                raw[i] = (byte) (OBFUSCATED_CID[i] ^ mask[i] ^ ((i * 37 + 0x5A) & 0xFF));
            }
            ByteBuffer bb = ByteBuffer.wrap(raw);
            return new UUID(bb.getLong(), bb.getLong()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize OAuth client configuration.", e);
        }
    }

    private static final String AUTHORIZE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";
    private static final String MC_ENTITLEMENT_URL =
            "https://api.minecraftservices.com/entitlements/mcstore";

    private final HttpClient http = site.kpeclub.launcher.util.FileDownloader.http();

    public record MinecraftSession(String username, String uuid, String accessToken) {}

    /** Kicks off the full login flow. Runs network calls off the JavaFX thread — call from a background Task. */
    public MinecraftSession login() throws Exception {
        // PKCE: a secret only we know. Even if another program steals the code from the browser
        // redirect, it cannot turn it into a token without this verifier.
        SecureRandom rnd = new SecureRandom();
        String verifier = randomUrlSafe(rnd, 64);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        // state: proves the redirect answers OUR request (blocks login-CSRF from a web page or other program)
        String state = randomUrlSafe(rnd, 32);

        String authCode = getAuthCodeViaBrowser(state, challenge);
        String msAccessToken = exchangeCodeForToken(authCode, verifier);
        String[] xbl = authenticateXBL(msAccessToken); // [token, userHash]
        String xstsToken = authenticateXSTS(xbl[0]);
        String mcAccessToken = loginToMinecraft(xstsToken, xbl[1]);

        if (!ownsMinecraft(mcAccessToken)) {
            throw new IllegalStateException(
                "This Microsoft account does not own a copy of Minecraft: Java Edition. " +
                "Purchase it at minecraft.net to use a premium launcher."
            );
        }

        return fetchProfile(mcAccessToken);
    }

    private static final Object SERVER_LOCK = new Object();
    private static ServerSocket activeCallbackServer;

    private static String randomUrlSafe(SecureRandom rnd, int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            m.put(java.net.URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                  java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void writeHttpResponse(OutputStream out, int status, String statusText, String text) throws IOException {
        String html = "<html><body style='background:#0d0d0d;color:#fff;font-family:sans-serif;text-align:center;padding-top:80px'>"
                + "<h2>" + text + "</h2></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    static ServerSocket startCallbackServer(String state, CompletableFuture<String> codeFuture) throws IOException {
        ServerSocket server;
        try {
            server = new ServerSocket(CALLBACK_PORT, 8, InetAddress.getByName("127.0.0.1"));
        } catch (java.net.BindException e) {
            throw new IOException(
                "Port " + CALLBACK_PORT + " is already in use — likely a previous sign-in attempt " +
                "that didn't finish. Close and reopen the launcher, then try signing in again.", e);
        }

        Thread listener = new Thread(() -> {
            while (!server.isClosed() && !codeFuture.isDone()) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream out = client.getOutputStream();
                    String requestLine = reader.readLine();
                    if (requestLine == null || requestLine.isBlank()) continue;

                    String host = null;
                    String hdr;
                    while ((hdr = reader.readLine()) != null && !hdr.isEmpty()) {
                        if (hdr.regionMatches(true, 0, "Host:", 0, 5)) {
                            host = hdr.substring(5).trim();
                        }
                    }

                    String[] parts = requestLine.split(" ");
                    if (parts.length < 2 || !"GET".equals(parts[0]) || !("127.0.0.1:" + CALLBACK_PORT).equals(host)) {
                        writeHttpResponse(out, 400, "Bad Request", "Bad request");
                        continue;
                    }

                    URI reqUri = URI.create("http://127.0.0.1:" + CALLBACK_PORT + parts[1]);
                    if (!"/callback".equals(reqUri.getPath())) {
                        writeHttpResponse(out, 404, "Not Found", "Not found");
                        continue;
                    }

                    Map<String, String> q = parseQuery(reqUri.getRawQuery());
                    String got = q.get("state");
                    if (got == null || !MessageDigest.isEqual(
                            got.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
                        writeHttpResponse(out, 400, "Bad Request", "Invalid sign-in request");
                        continue;
                    }
                    if (q.containsKey("error")) {
                        writeHttpResponse(out, 200, "OK", "Sign-in was cancelled. You can close this tab.");
                        codeFuture.completeExceptionally(new IOException("Sign-in was cancelled or refused."));
                        break;
                    }
                    String code = q.get("code");
                    if (code == null || code.isBlank()) {
                        writeHttpResponse(out, 400, "Bad Request", "No sign-in code received");
                        continue;
                    }
                    writeHttpResponse(out, 200, "OK", "Login complete - you can close this tab.");
                    codeFuture.complete(code);
                    break;
                } catch (IOException ignored) {
                    if (server.isClosed()) break;
                }
            }
        }, "kpe-oauth-callback");
        listener.setDaemon(true);
        listener.start();
        return server;
    }

    private String getAuthCodeViaBrowser(String state, String codeChallenge) throws IOException, InterruptedException, java.util.concurrent.ExecutionException {
        String cid = clientId();
        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        ServerSocket server;
        synchronized (SERVER_LOCK) {
            if (activeCallbackServer != null) {
                try { activeCallbackServer.close(); } catch (IOException ignored) {}
                activeCallbackServer = null;
            }
            server = startCallbackServer(state, codeFuture);
            activeCallbackServer = server;
        }

        String scope = URLEncoder.encode("XboxLive.signin offline_access", StandardCharsets.UTF_8);
        String url = AUTHORIZE_URL + "?client_id=" + URLEncoder.encode(cid, StandardCharsets.UTF_8) +
                "&response_type=code" +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                "&scope=" + scope +
                "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) +
                "&code_challenge=" + URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8) +
                "&code_challenge_method=S256" +
                "&prompt=select_account";

        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            return codeFuture.get(3, java.util.concurrent.TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Sign-in timed out after 3 minutes. Please try again.", e);
        } finally {
            synchronized (SERVER_LOCK) {
                try { server.close(); } catch (IOException ignored) {}
                if (activeCallbackServer == server) activeCallbackServer = null;
            }
        }
    }

    // ---- Step 3: auth code -> Microsoft token ----
    private String exchangeCodeForToken(String code, String verifier) throws IOException, InterruptedException {
        String body = "client_id=" + URLEncoder.encode(clientId(), StandardCharsets.UTF_8) +
                "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                "&code_verifier=" + URLEncoder.encode(verifier, StandardCharsets.UTF_8) +
                "&grant_type=authorization_code" +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                "&scope=" + URLEncoder.encode("XboxLive.signin offline_access", StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        JsonObject json = sendJson(req);
        return json.get("access_token").getAsString();
    }

    // ---- Step 4: Microsoft token -> Xbox Live token ----
    private String[] authenticateXBL(String msToken) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msToken);
        payload.add("Properties", props);
        payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        payload.addProperty("TokenType", "JWT");

        HttpRequest req = HttpRequest.newBuilder(URI.create(XBL_AUTH_URL)).timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        JsonObject json = sendJson(req);
        String token = json.get("Token").getAsString();
        String userHash = json.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();
        return new String[]{token, userHash};
    }

    // ---- Step 5: Xbox Live token -> XSTS token ----
    private String authenticateXSTS(String xblToken) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        JsonObject props = new JsonObject();
        com.google.gson.JsonArray tokens = new com.google.gson.JsonArray();
        tokens.add(xblToken);
        props.add("UserTokens", tokens);
        props.addProperty("SandboxId", "RETAIL");
        payload.add("Properties", props);
        payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        payload.addProperty("TokenType", "JWT");

        HttpRequest req = HttpRequest.newBuilder(URI.create(XSTS_AUTH_URL)).timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException(
                "This Microsoft account can't be used with Minecraft (e.g. child account needing " +
                "family permission, or no Xbox profile). Check account.microsoft.com."
            );
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.get("Token").getAsString();
    }

    // ---- Step 6: XSTS token -> Minecraft access token ----
    private String loginToMinecraft(String xstsToken, String userHash) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_LOGIN_URL)).timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        JsonObject json = sendJson(req);
        return json.get("access_token").getAsString();
    }

    // ---- Step 6.5: verify game ownership (blocks "free" use of the API without a purchase) ----
    private boolean ownsMinecraft(String mcAccessToken) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_ENTITLEMENT_URL)).timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + mcAccessToken)
                .GET()
                .build();
        JsonObject json = sendJson(req);
        return json.has("items") && json.get("items").isJsonArray() && json.getAsJsonArray("items").size() > 0;
    }

    // ---- Step 7: fetch username + uuid ----
    private MinecraftSession fetchProfile(String mcAccessToken) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_PROFILE_URL)).timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + mcAccessToken)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            // Owns the game (for example through Game Pass) but never chose a Java Edition name.
            throw new IllegalStateException("This account has no Minecraft: Java Edition profile yet. "
                    + "Sign in once at minecraft.net and choose your player name, then try again.");
        }
        if (resp.statusCode() >= 400) {
            throw new IOException("Sign-in request failed (HTTP " + resp.statusCode() + "). Please try again.");
        }
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        String username = json.get("name").getAsString();
        String uuid = json.get("id").getAsString();
        return new MinecraftSession(username, uuid, mcAccessToken);
    }

    private JsonObject sendJson(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Sign-in request failed (HTTP " + response.statusCode() + "). Please try again.");
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
