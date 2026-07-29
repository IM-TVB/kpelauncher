package site.kpeclub.launcher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import com.sun.net.httpserver.HttpServer;

/**
 * Handles the full premium Microsoft -> Xbox Live -> XSTS -> Minecraft auth chain.
 *
 * Flow:
 *  1. Open the user's browser to Microsoft's OAuth consent page (localhost redirect).
 *  2. Catch the "code" param on a tiny local HTTP server on 127.0.0.1.
 *  3. Exchange code -> Microsoft access token.
 *  4. Microsoft token -> Xbox Live token (XBL).
 *  5. XBL token -> XSTS token.
 *  6. XSTS token + user hash -> Minecraft access token.
 *  7. Use Minecraft token to fetch the player's profile (uuid + username).
 *
 * You must register your own app at https://portal.azure.com/ (Azure AD App Registration)
 * with:
 *   - Redirect URI: http://127.0.0.1:{PORT}/callback  (type: "Web" or "Public client")
 *   - API permissions: XboxLive.signin, offline_access
 * Then paste your CLIENT_ID below.
 */
public class MicrosoftAuth {

    // TODO: replace with your Azure app's client ID
    private static final String CLIENT_ID = "c8b47bec-d409-4e8c-8118-e30f09471094";
    private static final int CALLBACK_PORT = 43110;
    private static final String REDIRECT_URI = "http://127.0.0.1:" + CALLBACK_PORT + "/callback";

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

    private final HttpClient http = HttpClient.newHttpClient();

    public record MinecraftSession(String username, String uuid, String accessToken) {}

    /** Kicks off the full login flow. Runs network calls off the JavaFX thread — call from a background Task. */
    public MinecraftSession login() throws Exception {
        String authCode = getAuthCodeViaBrowser();
        String msAccessToken = exchangeCodeForToken(authCode);
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

    // ---- Step 1 & 2: open browser, catch redirect locally ----
    private String getAuthCodeViaBrowser() throws IOException, InterruptedException, java.util.concurrent.ExecutionException {
        CompletableFuture<String> codeFuture = new CompletableFuture<>();

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", CALLBACK_PORT), 0);
        } catch (java.net.BindException e) {
            throw new IOException(
                "Port " + CALLBACK_PORT + " is already in use — likely a previous sign-in attempt " +
                "that didn't finish. Close and reopen the launcher, then try signing in again.", e);
        }

        server.createContext("/callback", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String code = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("code=")) {
                        code = param.substring(5);
                    }
                }
            }
            String responseHtml = "<html><body style='background:#0d0d0d;color:#fff;font-family:sans-serif;text-align:center;padding-top:80px'>" +
                    "<h2>Login complete - you can close this tab.</h2></body></html>";
            byte[] responseBytes = responseHtml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
            if (code != null) codeFuture.complete(code);
            else codeFuture.completeExceptionally(new IOException("No auth code returned"));
        });
        server.start();

        String scope = URLEncoder.encode("XboxLive.signin offline_access", StandardCharsets.UTF_8);
        String url = AUTHORIZE_URL + "?client_id=" + CLIENT_ID +
                "&response_type=code" +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                "&scope=" + scope +
                "&prompt=select_account";

        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI.create(url));
        } else {
            throw new IOException("Cannot open browser automatically. Open this URL manually: " + url);
        }

        try {
            // Timeout so an abandoned attempt (closed tab, cancelled login, etc) always
            // releases the port eventually instead of holding it until the app restarts.
            return codeFuture.get(3, java.util.concurrent.TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Sign-in timed out after 3 minutes. Please try again.", e);
        } finally {
            server.stop(0);
        }
    }

    // ---- Step 3: auth code -> Microsoft token ----
    private String exchangeCodeForToken(String code) throws IOException, InterruptedException {
        String body = "client_id=" + CLIENT_ID +
                "&code=" + code +
                "&grant_type=authorization_code" +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                "&scope=" + URLEncoder.encode("XboxLive.signin offline_access", StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
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

        HttpRequest req = HttpRequest.newBuilder(URI.create(XBL_AUTH_URL))
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

        HttpRequest req = HttpRequest.newBuilder(URI.create(XSTS_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
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

        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_LOGIN_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        JsonObject json = sendJson(req);
        return json.get("access_token").getAsString();
    }

    // ---- Step 6.5: verify game ownership (blocks "free" use of the API without a purchase) ----
    private boolean ownsMinecraft(String mcAccessToken) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_ENTITLEMENT_URL))
                .header("Authorization", "Bearer " + mcAccessToken)
                .GET()
                .build();
        JsonObject json = sendJson(req);
        return json.getAsJsonArray("items").size() > 0;
    }

    // ---- Step 7: fetch username + uuid ----
    private MinecraftSession fetchProfile(String mcAccessToken) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_PROFILE_URL))
                .header("Authorization", "Bearer " + mcAccessToken)
                .GET()
                .build();
        JsonObject json = sendJson(req);
        String username = json.get("name").getAsString();
        String uuid = json.get("id").getAsString();
        return new MinecraftSession(username, uuid, mcAccessToken);
    }

    private JsonObject sendJson(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Auth request failed (" + response.statusCode() + "): " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
