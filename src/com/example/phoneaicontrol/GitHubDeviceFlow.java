package com.example.phoneaicontrol;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class GitHubDeviceFlow {
    private static final String DEFAULT_CLIENT_ID = "";
    private static final String DEFAULT_SCOPE = "repo read:user";
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String DEFAULT_VERIFICATION_URI = "https://github.com/login/device";
    private static final int TIMEOUT_MS = 10000;

    private GitHubDeviceFlow() {
    }

    static boolean isConfigured(Context context) {
        return !loadClientId(context).isEmpty();
    }

    static boolean hasUserConfiguredClientId(Context context) {
        return !loadUserConfiguredClientId(context).isEmpty();
    }

    static String loadUserConfiguredClientId(Context context) {
        JSONObject relayConfig = GitHubRelaySync.loadRelayConfigSnapshot(context);
        String clientId = normalizedString(relayConfig, "oauth_client_id");
        if (!clientId.isEmpty()) {
            return clientId;
        }
        JSONObject oauthConfig = loadExternalOauthConfig(context);
        clientId = normalizedString(oauthConfig, "client_id");
        if (!clientId.isEmpty()) {
            return clientId;
        }
        return "";
    }

    static String loadClientId(Context context) {
        String clientId = loadUserConfiguredClientId(context);
        if (!clientId.isEmpty()) {
            return clientId;
        }
        return DEFAULT_CLIENT_ID.trim();
    }

    static String loadScope(Context context) {
        JSONObject relayConfig = GitHubRelaySync.loadRelayConfigSnapshot(context);
        String scope = normalizedString(relayConfig, "oauth_scope");
        if (!scope.isEmpty()) {
            return scope;
        }
        JSONObject oauthConfig = loadExternalOauthConfig(context);
        scope = normalizedString(oauthConfig, "scope");
        if (!scope.isEmpty()) {
            return scope;
        }
        return DEFAULT_SCOPE;
    }

    static JSONObject start(Context context) throws Exception {
        String clientId = loadClientId(context);
        if (clientId.isEmpty()) {
            throw new IllegalStateException("No GitHub OAuth client_id is configured for Device Flow.");
        }
        String body = "client_id=" + URLEncoder.encode(clientId, "UTF-8")
                + "&scope=" + URLEncoder.encode(loadScope(context), "UTF-8");
        JSONObject response = requestForm(DEVICE_CODE_URL, body);
        response.put("client_id", clientId);
        if (!response.has("verification_uri")) {
            response.put("verification_uri", DEFAULT_VERIFICATION_URI);
        }
        return response;
    }

    static JSONObject pollForAccessToken(JSONObject session, int timeoutSeconds) throws Exception {
        if (session == null) {
            throw new IllegalArgumentException("Device flow session is missing.");
        }
        String clientId = normalizedString(session, "client_id");
        String deviceCode = normalizedString(session, "device_code");
        if (clientId.isEmpty() || deviceCode.isEmpty()) {
            throw new IllegalStateException("Device flow session is incomplete.");
        }
        int interval = Math.max(5, session.optInt("interval", 5));
        long expiresAt = System.currentTimeMillis() + Math.max(60, session.optInt("expires_in", 900)) * 1000L;
        if (timeoutSeconds > 0) {
            long timeoutAt = System.currentTimeMillis() + timeoutSeconds * 1000L;
            expiresAt = Math.min(expiresAt, timeoutAt);
        }
        while (System.currentTimeMillis() < expiresAt) {
            String body = "client_id=" + URLEncoder.encode(clientId, "UTF-8")
                    + "&device_code=" + URLEncoder.encode(deviceCode, "UTF-8")
                    + "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8");
            JSONObject response = requestForm(ACCESS_TOKEN_URL, body);
            String accessToken = normalizedString(response, "access_token");
            if (!accessToken.isEmpty()) {
                return response;
            }
            String error = normalizedString(response, "error");
            if ("authorization_pending".equals(error)) {
                sleepSeconds(interval);
                continue;
            }
            if ("slow_down".equals(error)) {
                interval = Math.max(interval + 5, response.optInt("interval", interval + 5));
                sleepSeconds(interval);
                continue;
            }
            throw new IllegalStateException(error.isEmpty()
                    ? "GitHub Device Flow returned no access_token."
                    : "GitHub Device Flow failed: " + error);
        }
        throw new IllegalStateException("GitHub Device Flow timed out before the device code was authorized.");
    }

    static String buildVerificationUri(JSONObject session) {
        String uri = normalizedString(session, "verification_uri");
        return uri.isEmpty() ? DEFAULT_VERIFICATION_URI : uri;
    }

    static String buildUserCode(JSONObject session) {
        return normalizedString(session, "user_code");
    }

    private static JSONObject loadExternalOauthConfig(Context context) {
        try {
            File file = GitHubRelaySync.resolveAppMediaFile(context, "github-oauth.json");
            if (!file.exists() || !file.isFile()) {
                return null;
            }
            InputStream stream = new java.io.FileInputStream(file);
            try {
                byte[] raw = new byte[(int) Math.min(file.length(), 32 * 1024)];
                int count = stream.read(raw);
                if (count <= 0) {
                    return null;
                }
                return new JSONObject(new String(raw, 0, count, StandardCharsets.UTF_8));
            } finally {
                stream.close();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject requestForm(String rawUrl, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(raw.length));
        OutputStream output = conn.getOutputStream();
        try {
            output.write(raw);
            output.flush();
        } finally {
            output.close();
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String payload = "";
        if (stream != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            reader.close();
            payload = builder.toString();
        }
        conn.disconnect();
        if (payload == null || payload.trim().isEmpty()) {
            throw new IllegalStateException("GitHub returned an empty response for Device Flow.");
        }
        return new JSONObject(payload);
    }

    private static String normalizedString(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) {
            return "";
        }
        String value = object.optString(key, "").trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    private static void sleepSeconds(int seconds) throws InterruptedException {
        Thread.sleep(Math.max(1, seconds) * 1000L);
    }
}
