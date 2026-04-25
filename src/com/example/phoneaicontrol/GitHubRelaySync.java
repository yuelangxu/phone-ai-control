package com.example.phoneaicontrol;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class GitHubRelaySync {
    private static final String TAG = "PhoneAiGitHubRelay";
    private static final String LEGACY_MEDIA_DIR = "/storage/emulated/0/Android/media/com.example.phoneaicontrol";
    private static final String CONFIG_FILE_NAME = "github-relay.json";
    private static final String STATE_FILE_NAME = "github-relay-state.json";
    private static final String RUNTIME_FILE_NAME = "runtime.json";
    private static final String DEFAULT_API_BASE = "https://api.github.com";
    private static final String DEFAULT_LOCAL_API = "http://127.0.0.1:8787";
    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_CONTENTS_PATH = "relay/current_device.json";
    private static final long MIN_ATTEMPT_INTERVAL_MS = 20_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L;
    private static final int HTTP_TIMEOUT_MS = 8000;

    private GitHubRelaySync() {
    }

    static String getConfigPath() {
        return getConfigPath(null);
    }

    static String getConfigPath(Context context) {
        return resolveConfigFile(context).getAbsolutePath();
    }

    static synchronized String getConfiguredRepoUrl(Context context) {
        JSONObject config = loadConfig(context);
        if (config == null) {
            return "";
        }
        String owner = config.optString("owner", "").trim();
        String repo = config.optString("repo", "").trim();
        if (owner.isEmpty() || repo.isEmpty()) {
            return "";
        }
        return "https://github.com/" + owner + "/" + repo;
    }

    static synchronized String loadGitHubTokenForCopy(Context context) {
        JSONObject config = loadConfig(context);
        if (config == null) {
            return "";
        }
        return loadGitHubToken(context, config);
    }

    static synchronized JSONObject ensureRelayRepository(Context context, boolean allowCreate) {
        JSONObject config = loadConfig(context);
        if (config == null) {
            return writeState(context, buildUnconfiguredState(context, "GitHub relay is not configured."));
        }
        if (!config.optBoolean("enabled", true)) {
            JSONObject disabled = baseStateFromConfig(context, config);
            putSafe(disabled, "configured", true);
            putSafe(disabled, "enabled", false);
            putSafe(disabled, "last_ok", false);
            putSafe(disabled, "last_message", "GitHub relay is configured but disabled.");
            logInfo("GitHub relay config is present but disabled.");
            return writeState(context, disabled);
        }
        String owner = config.optString("owner", "").trim();
        String repo = config.optString("repo", "").trim();
        if (owner.isEmpty() || repo.isEmpty()) {
            return writeState(context, buildInvalidConfigState(context, config, "Missing owner/repo in GitHub relay config."));
        }
        String token = loadGitHubToken(context, config);
        if (token.isEmpty()) {
            return writeState(context, buildInvalidConfigState(context, config, "GitHub relay token is missing."));
        }
        JSONObject state = mergeBaseState(context, loadState(context), config);
        putSafe(state, "configured", true);
        putSafe(state, "enabled", true);
        putSafe(state, "last_attempt_epoch_ms", System.currentTimeMillis());
        try {
            RepoResult repoResult = ensureRepositoryExists(config, token, allowCreate);
            putSafe(state, "repo_exists", repoResult.exists);
            putSafe(state, "repo_created", repoResult.created);
            putSafe(state, "repo_url", emptyToNull(repoResult.repoHtmlUrl));
            putSafe(state, "last_ok", repoResult.exists);
            putSafe(state, "last_message", repoResult.message);
            if (repoResult.exists) {
                putSafe(state, "last_success_epoch_ms", System.currentTimeMillis());
            }
            logInfo("Repository ensure result: " + repoResult.message);
        } catch (Exception e) {
            putSafe(state, "last_ok", false);
            putSafe(state, "repo_exists", false);
            putSafe(state, "repo_created", false);
            putSafe(state, "last_message", "GitHub relay repo check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            logError("Repository ensure failed.", e);
        }
        return writeState(context, state);
    }

    static synchronized JSONObject triggerSyncFromPhoneContext(Context context, String syncSource, boolean force) {
        String localApiBase = resolveLocalApiBaseFromRuntime(context);
        boolean localApiOk = false;
        String localStatus = "Offline";
        JSONObject health = null;
        boolean publicReachable = false;
        String publicProbeDetail = "Local API unavailable.";
        try {
            health = getJsonNoAuth(localApiBase + "/healthz", HTTP_TIMEOUT_MS);
            localApiOk = health.optBoolean("ok", false);
            localStatus = localApiOk ? "Online" : "Unexpected response";
            String publicUrl = normalizePublicUrl(health.optString("public_url", ""));
            boolean publicEnabled = health.optBoolean("public_enabled", false);
            if (publicEnabled && publicUrl.startsWith("https://")) {
                PublicTunnelProbeResult probe = probePublicTunnel(publicUrl, HTTP_TIMEOUT_MS);
                publicReachable = probe.reachable;
                publicProbeDetail = probe.detail;
            } else {
                publicReachable = false;
                publicProbeDetail = publicEnabled ? "Public URL missing while tunnel reports enabled." : "Public exposure disabled.";
            }
        } catch (Exception e) {
            publicReachable = false;
            publicProbeDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
            localStatus = "Offline";
        }
        JSONObject snapshot = buildDeviceState(
                context,
                syncSource,
                localApiBase,
                localApiOk,
                localStatus,
                health,
                publicReachable,
                publicProbeDetail,
                collectPermissionState(context)
        );
        logInfo("Triggering GitHub relay sync from " + syncSource + " using local API base " + localApiBase);
        return maybeSyncCurrentDevice(context, snapshot, force);
    }

    static JSONObject buildDeviceState(
            Context context,
            String syncSource,
            String localApiBase,
            boolean localApiOk,
            String localStatus,
            JSONObject health,
            boolean publicReachable,
            String publicProbeDetail,
            JSONObject permissionState
    ) {
        JSONObject payload = new JSONObject();
        try {
            String publicUrl = normalizePublicUrl(health == null ? "" : health.optString("public_url", ""));
            boolean publicEnabled = health != null && health.optBoolean("public_enabled", false);
            boolean authRequired = health == null || health.optBoolean("auth_required", true);
            boolean localtunnelRunning = health != null && health.optBoolean("localtunnel_running", false);
            boolean cloudflaredRunning = health != null && health.optBoolean("cloudflared_running", false);

            payload.put("updated_at", iso8601Utc(System.currentTimeMillis()));
            payload.put("local_api_ok", localApiOk);
            payload.put("local_status", emptyToNull(localStatus));
            payload.put("local_api_base", emptyToNull(localApiBase));
            payload.put("public_enabled", publicEnabled);
            payload.put("public_reachable", publicReachable);
            payload.put("healthy", localApiOk && (!publicEnabled || publicReachable));
            payload.put("public_url", publicUrl.isEmpty() ? JSONObject.NULL : publicUrl);
            payload.put("schema_url", publicUrl.isEmpty() ? JSONObject.NULL : publicUrl + "/openapi.json");
            payload.put("auth_required", authRequired);
            payload.put("localtunnel_running", localtunnelRunning);
            payload.put("cloudflared_running", cloudflaredRunning);
            payload.put("public_probe_detail", emptyToNull(publicProbeDetail));
            payload.put("sync_source", emptyToNull(syncSource));
            payload.put("app_package", context.getPackageName());
            payload.put("app_version", getVersionName(context));
            payload.put("android_sdk_int", Build.VERSION.SDK_INT);
            payload.put("permissions", permissionState == null ? new JSONObject() : permissionState);
        } catch (Exception ignored) {
        }
        return payload;
    }

    static synchronized JSONObject maybeSyncCurrentDevice(Context context, JSONObject snapshot, boolean force) {
        JSONObject state = loadState(context);
        JSONObject config = loadConfig(context);
        if (config == null) {
            return writeState(context, buildUnconfiguredState(context, "GitHub relay is not configured."));
        }
        if (!config.optBoolean("enabled", true)) {
            JSONObject disabled = baseStateFromConfig(context, config);
            putSafe(disabled, "configured", true);
            putSafe(disabled, "enabled", false);
            putSafe(disabled, "last_ok", false);
            putSafe(disabled, "last_message", "GitHub relay is configured but disabled.");
            putSafe(disabled, "last_attempt_epoch_ms", System.currentTimeMillis());
            return writeState(context, disabled);
        }
        String owner = config.optString("owner", "").trim();
        String repo = config.optString("repo", "").trim();
        if (owner.isEmpty() || repo.isEmpty()) {
            return writeState(context, buildInvalidConfigState(context, config, "Missing owner/repo in GitHub relay config."));
        }
        String token = loadGitHubToken(context, config);
        if (token.isEmpty()) {
            return writeState(context, buildInvalidConfigState(context, config, "GitHub relay token is missing."));
        }

        long now = System.currentTimeMillis();
        if (snapshot == null) {
            snapshot = new JSONObject();
        }
        JSONObject payload = cloneObject(snapshot);
        if (emptyToNull(payload.optString("device_id", "")) == null) {
            putSafe(payload, "device_id", config.optString("device_id", "phone-main"));
        }
        if (emptyToNull(config.optString("device_name", "")) != null && !payload.has("device_name")) {
            putSafe(payload, "device_name", config.optString("device_name", ""));
        }

        String semanticHash = computeSemanticHash(payload);
        long lastAttempt = state == null ? 0L : state.optLong("last_attempt_epoch_ms", 0L);
        long lastSuccess = state == null ? 0L : state.optLong("last_success_epoch_ms", 0L);
        String lastHash = state == null ? "" : state.optString("last_payload_hash", "");
        if (!force) {
            if (now - lastAttempt < MIN_ATTEMPT_INTERVAL_MS) {
                JSONObject skipped = mergeBaseState(context, state, config);
                putSafe(skipped, "last_ok", skipped.optBoolean("last_ok", false));
                putSafe(skipped, "last_message", "Skipped GitHub relay sync due to cooldown.");
                putSafe(skipped, "skipped", true);
                putSafe(skipped, "last_attempt_epoch_ms", now);
                logInfo("Skipped GitHub relay sync because of cooldown.");
                return writeState(context, skipped);
            }
            if (semanticHash.equals(lastHash) && now - lastSuccess < HEARTBEAT_INTERVAL_MS) {
                JSONObject skipped = mergeBaseState(context, state, config);
                putSafe(skipped, "last_ok", true);
                putSafe(skipped, "last_message", "GitHub relay already has the latest device state.");
                putSafe(skipped, "skipped", true);
                putSafe(skipped, "last_attempt_epoch_ms", now);
                logInfo("Skipped GitHub relay sync because payload is unchanged.");
                return writeState(context, skipped);
            }
        }

        JSONObject inProgress = mergeBaseState(context, state, config);
        putSafe(inProgress, "configured", true);
        putSafe(inProgress, "enabled", true);
        putSafe(inProgress, "skipped", false);
        putSafe(inProgress, "last_attempt_epoch_ms", now);
        putSafe(inProgress, "last_payload_hash", semanticHash);
        writeState(context, inProgress);

        try {
            SyncTarget target = resolveTarget(config);
            RepoResult repoResult = ensureRepositoryExists(config, token, true);
            putSafe(inProgress, "repo_exists", repoResult.exists);
            putSafe(inProgress, "repo_created", repoResult.created);
            putSafe(inProgress, "repo_url", emptyToNull(repoResult.repoHtmlUrl));
            HttpResult existing = getExistingContents(target, token);
            String payloadJson = payload.toString(2);
            String payloadBase64 = Base64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String existingBase64 = "";
            String existingSha = "";
            if (existing.statusCode == 200 && existing.body != null && !existing.body.isEmpty()) {
                JSONObject existingJson = new JSONObject(existing.body);
                existingSha = existingJson.optString("sha", "").trim();
                existingBase64 = existingJson.optString("content", "").replace("\n", "").replace("\r", "").trim();
            }
            if (!existingBase64.isEmpty() && existingBase64.equals(payloadBase64)) {
                JSONObject upToDate = mergeBaseState(context, inProgress, config);
                putSafe(upToDate, "last_ok", true);
                putSafe(upToDate, "last_message", "GitHub relay file is already up to date.");
                putSafe(upToDate, "last_success_epoch_ms", now);
                putSafe(upToDate, "last_http_status", 200);
                putSafe(upToDate, "last_payload_hash", semanticHash);
                putSafe(upToDate, "skipped", true);
                logInfo("GitHub relay target already contained the latest payload.");
                return writeState(context, upToDate);
            }

            JSONObject requestBody = new JSONObject();
            requestBody.put("message", buildCommitMessage(config, payload));
            requestBody.put("content", payloadBase64);
            requestBody.put("branch", target.branch);
            if (!existingSha.isEmpty()) {
                requestBody.put("sha", existingSha);
            }
            HttpResult putResult = requestJson("PUT", target.contentsApiUrl, token, requestBody.toString());
            if (putResult.statusCode < 200 || putResult.statusCode >= 300) {
                throw new IllegalStateException("HTTP " + putResult.statusCode + ": " + truncate(putResult.body, 220));
            }
            JSONObject success = mergeBaseState(context, inProgress, config);
            putSafe(success, "last_ok", true);
            putSafe(success, "last_message", repoResult.created
                    ? "Created private GitHub relay repo and synced current device state."
                    : "Synced current device state to GitHub.");
            putSafe(success, "last_success_epoch_ms", now);
            putSafe(success, "last_http_status", putResult.statusCode);
            putSafe(success, "last_payload_hash", semanticHash);
            putSafe(success, "skipped", false);
            try {
                JSONObject body = new JSONObject(putResult.body);
                JSONObject content = body.optJSONObject("content");
                if (content != null) {
                    putSafe(success, "last_commit_sha", content.optString("sha", ""));
                }
            } catch (Exception ignored) {
            }
            logInfo("GitHub relay sync completed successfully with HTTP " + putResult.statusCode + ".");
            return writeState(context, success);
        } catch (Exception e) {
            JSONObject failed = mergeBaseState(context, inProgress, config);
            putSafe(failed, "last_ok", false);
            putSafe(failed, "last_message", "GitHub relay sync failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            putSafe(failed, "skipped", false);
            logError("GitHub relay sync failed.", e);
            return writeState(context, failed);
        }
    }

    static synchronized String describeForUi(Context context) {
        JSONObject config = loadConfig(context);
        JSONObject state = loadState(context);
        if (config == null) {
            return "github relay: not configured\nconfig path: " + getConfigPath(context);
        }
        String owner = config.optString("owner", "").trim();
        String repo = config.optString("repo", "").trim();
        String branch = config.optString("branch", DEFAULT_BRANCH).trim();
        String contentsPath = config.optString("contents_path", DEFAULT_CONTENTS_PATH).trim();
        StringBuilder sb = new StringBuilder();
        sb.append("github relay: ");
        if (owner.isEmpty() || repo.isEmpty()) {
            sb.append("invalid config");
        } else {
            sb.append(owner).append("/").append(repo).append("@").append(branch).append(" -> ").append(contentsPath.isEmpty() ? DEFAULT_CONTENTS_PATH : contentsPath);
        }
        if (state != null) {
            String message = state.optString("last_message", "").trim();
            long successAt = state.optLong("last_success_epoch_ms", 0L);
            if (state.optBoolean("last_ok", false) && successAt > 0L) {
                sb.append("\ngithub sync: ok at ").append(formatLocalTime(successAt));
            } else if (!message.isEmpty()) {
                sb.append("\ngithub sync: ").append(message);
            }
            if (state.optBoolean("repo_created", false)) {
                sb.append("\nrepo bootstrap: created automatically");
            }
        } else {
            sb.append("\ngithub sync: waiting for first attempt");
        }
        return sb.toString();
    }

    private static JSONObject loadConfig(Context context) {
        try {
            File file = resolveConfigFile(context);
            if (!file.exists() || !file.isFile()) {
                logInfo("GitHub relay config file not found at " + file.getAbsolutePath());
                return null;
            }
            return new JSONObject(new String(readFileUpTo(file, 64 * 1024), StandardCharsets.UTF_8));
        } catch (Exception e) {
            logError("Failed to load GitHub relay config.", e);
            return null;
        }
    }

    private static JSONObject loadState(Context context) {
        try {
            File file = resolveStateFile(context);
            if (!file.exists() || !file.isFile()) {
                return null;
            }
            return new JSONObject(new String(readFileUpTo(file, 64 * 1024), StandardCharsets.UTF_8));
        } catch (Exception e) {
            logError("Failed to load GitHub relay state.", e);
            return null;
        }
    }

    private static JSONObject writeState(Context context, JSONObject state) {
        try {
            File target = resolveStateFile(context);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream stream = new FileOutputStream(target, false);
            try {
                stream.write(state.toString(2).getBytes(StandardCharsets.UTF_8));
                stream.flush();
            } finally {
                stream.close();
            }
            logInfo("Wrote GitHub relay state to " + target.getAbsolutePath());
        } catch (Exception e) {
            logError("Failed to write GitHub relay state.", e);
        }
        return state;
    }

    private static JSONObject buildUnconfiguredState(Context context, String message) {
        JSONObject state = new JSONObject();
        putSafe(state, "configured", false);
        putSafe(state, "enabled", false);
        putSafe(state, "last_ok", false);
        putSafe(state, "last_message", message);
        putSafe(state, "config_path", getConfigPath(context));
        putSafe(state, "last_attempt_epoch_ms", System.currentTimeMillis());
        return state;
    }

    private static JSONObject buildInvalidConfigState(Context context, JSONObject config, String message) {
        JSONObject state = baseStateFromConfig(context, config);
        putSafe(state, "configured", true);
        putSafe(state, "enabled", config.optBoolean("enabled", true));
        putSafe(state, "last_ok", false);
        putSafe(state, "last_message", message);
        putSafe(state, "last_attempt_epoch_ms", System.currentTimeMillis());
        return state;
    }

    private static JSONObject baseStateFromConfig(Context context, JSONObject config) {
        JSONObject state = new JSONObject();
        putSafe(state, "config_path", getConfigPath(context));
        if (config != null) {
            putSafe(state, "owner", config.optString("owner", ""));
            putSafe(state, "repo", config.optString("repo", ""));
            putSafe(state, "branch", emptyToDefault(config.optString("branch", ""), DEFAULT_BRANCH));
            putSafe(state, "contents_path", emptyToDefault(config.optString("contents_path", ""), DEFAULT_CONTENTS_PATH));
        }
        return state;
    }

    private static JSONObject mergeBaseState(Context context, JSONObject existing, JSONObject config) {
        JSONObject merged = existing == null ? new JSONObject() : cloneObject(existing);
        putSafe(merged, "config_path", getConfigPath(context));
        if (config != null) {
            putSafe(merged, "owner", config.optString("owner", ""));
            putSafe(merged, "repo", config.optString("repo", ""));
            putSafe(merged, "branch", emptyToDefault(config.optString("branch", ""), DEFAULT_BRANCH));
            putSafe(merged, "contents_path", emptyToDefault(config.optString("contents_path", ""), DEFAULT_CONTENTS_PATH));
        }
        return merged;
    }

    private static String loadGitHubToken(Context context, JSONObject config) {
        String inline = config.optString("github_token", "").trim();
        if (!inline.isEmpty()) {
            return inline;
        }
        String tokenFile = config.optString("github_token_file", "").trim();
        if (tokenFile.isEmpty()) {
            File defaultTokenFile = resolveDefaultTokenFile(context);
            if (!defaultTokenFile.exists() || !defaultTokenFile.isFile()) {
                return "";
            }
            tokenFile = defaultTokenFile.getAbsolutePath();
        }
        if ("github-relay-token.txt".equals(tokenFile)) {
            tokenFile = resolveDefaultTokenFile(context).getAbsolutePath();
        }
        try {
            File file = resolveArbitraryFile(context, tokenFile);
            if (!file.exists() || !file.isFile()) {
                logInfo("GitHub relay token file not found at " + file.getAbsolutePath());
                return "";
            }
            return new String(readFileUpTo(file, 32 * 1024), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            logError("Failed to read GitHub relay token.", e);
            return "";
        }
    }

    private static RepoResult ensureRepositoryExists(JSONObject config, String token, boolean allowCreate) throws Exception {
        String apiBase = emptyToDefault(config.optString("api_base", ""), DEFAULT_API_BASE);
        String owner = config.optString("owner", "").trim();
        String repo = config.optString("repo", "").trim();
        String repoApiUrl = trimTrailingSlash(apiBase) + "/repos/" + owner + "/" + repo;
        HttpResult getResult = requestJson("GET", repoApiUrl, token, null);
        if (getResult.statusCode == 200) {
            String htmlUrl = extractHtmlUrl(getResult.body, owner, repo);
            return new RepoResult(true, false, "GitHub relay repo is ready.", repoApiUrl, htmlUrl);
        }
        if (getResult.statusCode != 404) {
            throw new IllegalStateException("HTTP " + getResult.statusCode + ": " + truncate(getResult.body, 220));
        }
        if (!allowCreate) {
            return new RepoResult(false, false, "GitHub relay repo does not exist yet.", repoApiUrl, "https://github.com/" + owner + "/" + repo);
        }

        HttpResult createResult = createRepository(config, token);
        if (createResult.statusCode < 200 || createResult.statusCode >= 300) {
            throw new IllegalStateException("HTTP " + createResult.statusCode + ": " + truncate(createResult.body, 220));
        }
        String htmlUrl = extractHtmlUrl(createResult.body, owner, repo);
        return new RepoResult(true, true, "Created private GitHub relay repo.", repoApiUrl, htmlUrl);
    }

    private static HttpResult createRepository(JSONObject config, String token) throws Exception {
        String apiBase = emptyToDefault(config.optString("api_base", ""), DEFAULT_API_BASE);
        String owner = config.optString("owner", "").trim();
        String repo = config.optString("repo", "").trim();
        String ownerType = config.optString("owner_type", "").trim().toLowerCase(Locale.US);
        String createUrl;
        if ("org".equals(ownerType)) {
            createUrl = trimTrailingSlash(apiBase) + "/orgs/" + owner + "/repos";
        } else if ("user".equals(ownerType)) {
            createUrl = trimTrailingSlash(apiBase) + "/user/repos";
        } else {
            HttpResult userResult = requestJson("GET", trimTrailingSlash(apiBase) + "/user", token, null);
            String login = owner;
            if (userResult.statusCode == 200 && userResult.body != null && !userResult.body.isEmpty()) {
                try {
                    login = new JSONObject(userResult.body).optString("login", owner);
                } catch (Exception ignored) {
                }
            }
            if (owner.equalsIgnoreCase(login)) {
                createUrl = trimTrailingSlash(apiBase) + "/user/repos";
            } else {
                createUrl = trimTrailingSlash(apiBase) + "/orgs/" + owner + "/repos";
            }
        }

        JSONObject body = new JSONObject();
        body.put("name", repo);
        body.put("private", true);
        body.put("auto_init", true);
        body.put("has_issues", true);
        body.put("description", "Phone AI Control GitHub relay repository.");
        return requestJson("POST", createUrl, token, body.toString());
    }

    private static JSONObject collectPermissionState(Context context) {
        JSONObject permissionState = new JSONObject();
        try {
            permissionState.put("all_files_access", hasAllFilesAccess(context));
            permissionState.put("usage_access", hasUsageAccess(context));
            permissionState.put("notification_access", NotificationAccessStore.hasNotificationAccess(context));
            permissionState.put("contacts_access", hasContactsAccess(context));
            permissionState.put("call_log_access", hasCallLogAccess(context));
            permissionState.put("battery_optimization_exemption", isIgnoringBatteryOptimizations(context));
            permissionState.put("can_request_package_installs", canRequestPackageInstallsCompat(context));
        } catch (Exception ignored) {
        }
        return permissionState;
    }

    private static boolean hasAllFilesAccess(Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasUsageAccess(Context context) {
        if (Build.VERSION.SDK_INT < 21) {
            return false;
        }
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasContactsAccess(Context context) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasCallLogAccess(Context context) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean canRequestPackageInstallsCompat(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 26) {
                return true;
            }
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String resolveLocalApiBaseFromRuntime(Context context) {
        try {
            File runtimeFile = resolveRuntimeFile(context);
            if (runtimeFile.exists() && runtimeFile.isFile()) {
                JSONObject runtime = new JSONObject(new String(readFileUpTo(runtimeFile, 16 * 1024), StandardCharsets.UTF_8));
                String runtimeUrl = runtime.optString("local_api_url", "").trim();
                if (!runtimeUrl.isEmpty()) {
                    return runtimeUrl;
                }
                int runtimePort = runtime.optInt("local_port", 0);
                if (runtimePort >= 1000 && runtimePort <= 9999) {
                    return "http://127.0.0.1:" + runtimePort;
                }
            }
        } catch (Exception e) {
            logError("Failed to resolve runtime local API base.", e);
        }
        return DEFAULT_LOCAL_API;
    }

    private static File resolveConfigFile(Context context) {
        return new File(resolveMediaDir(context), CONFIG_FILE_NAME);
    }

    private static File resolveStateFile(Context context) {
        return new File(resolveMediaDir(context), STATE_FILE_NAME);
    }

    private static File resolveRuntimeFile(Context context) {
        return new File(resolveMediaDir(context), RUNTIME_FILE_NAME);
    }

    private static File resolveDefaultTokenFile(Context context) {
        return new File(resolveMediaDir(context), "github-relay-token.txt");
    }

    private static File resolveArbitraryFile(Context context, String path) {
        if (path == null || path.trim().isEmpty()) {
            return resolveDefaultTokenFile(context);
        }
        File raw = new File(path);
        if (raw.isAbsolute()) {
            return raw;
        }
        return new File(resolveMediaDir(context), path);
    }

    private static File resolveMediaDir(Context context) {
        if (context != null) {
            try {
                File[] dirs = context.getExternalMediaDirs();
                if (dirs != null) {
                    for (File dir : dirs) {
                        if (dir != null) {
                            return dir;
                        }
                    }
                }
            } catch (Exception e) {
                logError("Failed to inspect external media dirs.", e);
            }
        }
        return new File(LEGACY_MEDIA_DIR);
    }

    private static JSONObject getJsonNoAuth(String rawUrl, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("bypass-tunnel-reminder", "1");
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String body = "";
        if (stream != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            body = sb.toString();
        }
        conn.disconnect();
        if (code < 200 || code >= 400) {
            throw new IllegalStateException("HTTP " + code + ": " + body);
        }
        return new JSONObject(body);
    }

    private static PublicTunnelProbeResult probePublicTunnel(String publicUrl, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(publicUrl + "/healthz").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
            conn.setRequestProperty("bypass-tunnel-reminder", "1");
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = "";
            if (stream != null) {
                body = new String(readStreamFully(stream), StandardCharsets.UTF_8).trim();
            }
            if (code == 200) {
                try {
                    JSONObject payload = new JSONObject(body);
                    if (payload.optBoolean("ok", true)) {
                        return new PublicTunnelProbeResult(true, code, "HTTP 200");
                    }
                } catch (Exception ignored) {
                    return new PublicTunnelProbeResult(true, code, "HTTP 200");
                }
            }
            return new PublicTunnelProbeResult(false, code, summarizeProbeFailure(code, body));
        } catch (Exception e) {
            return new PublicTunnelProbeResult(false, -1, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static SyncTarget resolveTarget(JSONObject config) {
        String apiBase = emptyToDefault(config.optString("api_base", ""), DEFAULT_API_BASE);
        String owner = config.optString("owner", "").trim();
        String repo = config.optString("repo", "").trim();
        String branch = emptyToDefault(config.optString("branch", ""), DEFAULT_BRANCH);
        String contentsPath = emptyToDefault(config.optString("contents_path", ""), DEFAULT_CONTENTS_PATH);
        String encodedBranch;
        try {
            encodedBranch = URLEncoder.encode(branch, "UTF-8");
        } catch (Exception e) {
            encodedBranch = branch;
        }
        String contentsApiUrl = trimTrailingSlash(apiBase) + "/repos/" + owner + "/" + repo + "/contents/" + contentsPath;
        String getUrl = contentsApiUrl + "?ref=" + encodedBranch;
        return new SyncTarget(contentsApiUrl, getUrl, branch);
    }

    private static String buildCommitMessage(JSONObject config, JSONObject payload) {
        String deviceId = payload.optString("device_id", "").trim();
        if (deviceId.isEmpty()) {
            deviceId = config.optString("device_id", "phone-main");
        }
        return "phone relay sync: " + deviceId + " " + iso8601Utc(System.currentTimeMillis());
    }

    private static HttpResult getExistingContents(SyncTarget target, String token) throws Exception {
        HttpResult result = requestJson("GET", target.getUrl, token, null);
        if (result.statusCode == 404) {
            return result;
        }
        if (result.statusCode < 200 || result.statusCode >= 300) {
            throw new IllegalStateException("HTTP " + result.statusCode + ": " + truncate(result.body, 220));
        }
        return result;
    }

    private static HttpResult requestJson(String method, String rawUrl, String token, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(raw.length));
            OutputStream stream = conn.getOutputStream();
            try {
                stream.write(raw);
                stream.flush();
            } finally {
                stream.close();
            }
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String response = "";
        if (stream != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            response = sb.toString();
        }
        conn.disconnect();
        return new HttpResult(code, response);
    }

    private static String computeSemanticHash(JSONObject payload) {
        try {
            JSONObject copy = cloneObject(payload);
            copy.remove("updated_at");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(copy.toString().getBytes(StandardCharsets.UTF_8));
            return hexEncode(hash);
        } catch (Exception e) {
            return String.valueOf(payload.toString().hashCode());
        }
    }

    private static String getVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizePublicUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("http://")) {
            normalized = "https://" + normalized.substring("http://".length());
        }
        return trimTrailingSlash(normalized);
    }

    private static String iso8601Utc(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMs));
    }

    private static String formatLocalTime(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return format.format(new Date(epochMs));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String emptyToDefault(String value, String defaultValue) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }

    private static JSONObject cloneObject(JSONObject object) {
        if (object == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(object.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void putSafe(JSONObject object, String key, Object value) {
        try {
            object.put(key, value == null ? JSONObject.NULL : value);
        } catch (Exception ignored) {
        }
    }

    private static String hexEncode(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            int b = value & 0xff;
            if (b < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }

    private static byte[] readFileUpTo(File target, int maxBytes) throws Exception {
        FileInputStream stream = new FileInputStream(target);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        try {
            byte[] chunk = new byte[8192];
            int remaining = maxBytes;
            while (remaining > 0) {
                int read = stream.read(chunk, 0, Math.min(chunk.length, remaining));
                if (read < 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
                remaining -= read;
            }
            return buffer.toByteArray();
        } finally {
            stream.close();
            buffer.close();
        }
    }

    private static byte[] readStreamFully(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                if (read == 0) {
                    continue;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
            buffer.close();
        }
    }

    private static String summarizeProbeFailure(int statusCode, String body) {
        String normalized = body == null ? "" : body.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() > 160) {
            normalized = normalized.substring(0, 160) + "...";
        }
        if (normalized.isEmpty()) {
            return "HTTP " + statusCode;
        }
        return "HTTP " + statusCode + ": " + normalized;
    }

    private static void logInfo(String message) {
        Log.i(TAG, message);
    }

    private static void logError(String message, Throwable error) {
        if (error == null) {
            Log.e(TAG, message);
        } else {
            Log.e(TAG, message, error);
        }
    }

    private static final class SyncTarget {
        final String contentsApiUrl;
        final String getUrl;
        final String branch;

        SyncTarget(String contentsApiUrl, String getUrl, String branch) {
            this.contentsApiUrl = contentsApiUrl;
            this.getUrl = getUrl;
            this.branch = branch;
        }
    }

    private static final class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }
    }

    private static final class PublicTunnelProbeResult {
        final boolean reachable;
        final int statusCode;
        final String detail;

        PublicTunnelProbeResult(boolean reachable, int statusCode, String detail) {
            this.reachable = reachable;
            this.statusCode = statusCode;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static String extractHtmlUrl(String body, String owner, String repo) {
        if (body != null && !body.isEmpty()) {
            try {
                String htmlUrl = new JSONObject(body).optString("html_url", "").trim();
                if (!htmlUrl.isEmpty()) {
                    return htmlUrl;
                }
            } catch (Exception ignored) {
            }
        }
        return "https://github.com/" + owner + "/" + repo;
    }

    private static final class RepoResult {
        final boolean exists;
        final boolean created;
        final String message;
        final String repoApiUrl;
        final String repoHtmlUrl;

        RepoResult(boolean exists, boolean created, String message, String repoApiUrl, String repoHtmlUrl) {
            this.exists = exists;
            this.created = created;
            this.message = message == null ? "" : message;
            this.repoApiUrl = repoApiUrl == null ? "" : repoApiUrl;
            this.repoHtmlUrl = repoHtmlUrl == null ? "" : repoHtmlUrl;
        }
    }
}
