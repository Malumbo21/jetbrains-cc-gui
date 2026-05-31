package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.CodexSettingsManager;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Loads Codex subscription quota snapshots from the same upstream used by Codex OAuth sessions.
 * Source priority:
 * 1) ChatGPT backend usage API (`/backend-api/wham/usage`) via ~/.codex/auth.json access token.
 */
public class CodexSubscriptionQuotaHandler {

    private static final Logger LOG = Logger.getInstance(CodexSubscriptionQuotaHandler.class);
    private static final Gson GSON = new Gson();
    private static final String WHAM_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(12);

    private final HandlerContext context;
    private final CodemossSettingsService settingsService = new CodemossSettingsService();

    public CodexSubscriptionQuotaHandler(HandlerContext context) {
        this.context = context;
    }

    public void handleGetCodexSubscriptionQuota() {
        CompletableFuture.runAsync(() -> {
            try {
                if (CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE.equals(settingsService.getCodexRuntimeAccessMode())) {
                    sendUnavailable("Codex runtime access is inactive");
                    return;
                }

                JsonObject auth = new CodexSettingsManager(GSON).readAuthJson();
                String token = readAccessToken(auth);
                if (token == null || token.isBlank()) {
                    sendUnavailable("No access_token in ~/.codex/auth.json");
                    return;
                }

                JsonObject usage = fetchWhamUsage(token);
                JsonObject payload = buildPayloadFromUsage(usage, System.currentTimeMillis());
                sendPayload(payload);
            } catch (Exception e) {
                LOG.warn("[CodexSubscriptionQuotaHandler] Failed to load quota from wham/usage: " + e.getMessage());
                sendUnavailable(e.getMessage());
            }
        });
    }

    private JsonObject fetchWhamUsage(String token) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WHAM_USAGE_URL))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("User-Agent", "jetbrains-cc-gui-codex-quota")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("wham/usage HTTP " + response.statusCode());
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException("wham/usage returned non-object JSON");
        }
        return parsed.getAsJsonObject();
    }

    static JsonObject buildPayloadFromUsage(JsonObject usage, long now) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "ok");
        payload.addProperty("fetchedAt", now);
        payload.addProperty("source", "wham_usage");

        JsonObject rateLimit = resolveRateLimitRoot(usage);
        JsonObject windows = new JsonObject();
        windows.add("fiveHour", toWindow("5h", 5, pickFirstObject(rateLimit, "primary_window", "primary")));
        windows.add("weekly", toWindow("weekly", 7 * 24, pickFirstObject(rateLimit, "secondary_window", "secondary")));
        payload.add("windows", windows);
        return payload;
    }

    private static JsonObject toWindow(String label, int defaultHours, JsonObject source) {
        JsonObject window = new JsonObject();
        window.addProperty("windowLabel", label);
        int durationMinutes = readInt(source, "window_duration_mins", "windowDurationMins");
        if (durationMinutes <= 0) {
            int durationSeconds = readInt(source, "limit_window_seconds", "limitWindowSeconds");
            durationMinutes = durationSeconds > 0 ? Math.max(1, durationSeconds / 60) : 0;
        }
        window.addProperty("windowHours", durationMinutes > 0 ? Math.max(1, durationMinutes / 60) : defaultHours);

        Double usedPercent = readDoubleNullable(source, "used_percent", "usedPercent");
        Double remainingPercent = readDoubleNullable(source, "remaining_percent", "remainingPercent");
        if (usedPercent == null && remainingPercent != null) {
            usedPercent = Math.max(0.0, Math.min(100.0, 100.0 - remainingPercent));
        }
        if (remainingPercent == null && usedPercent != null) {
            remainingPercent = Math.max(0.0, Math.min(100.0, 100.0 - usedPercent));
        }

        if (usedPercent != null) {
            window.addProperty("usedPercent", usedPercent);
        } else {
            window.add("usedPercent", JsonNull.INSTANCE);
        }
        if (remainingPercent != null) {
            window.addProperty("remainingPercent", remainingPercent);
        } else {
            window.add("remainingPercent", JsonNull.INSTANCE);
        }

        Long resetsAtMs = readEpochMillisNullable(source, "reset_at", "resets_at", "resetAt", "resetsAt");
        if (resetsAtMs != null) {
            window.addProperty("resetsAt", resetsAtMs);
        } else {
            window.add("resetsAt", JsonNull.INSTANCE);
        }

        // Keep legacy fields for frontend compatibility.
        window.addProperty("usedTokens", 0);
        window.add("limitTokens", JsonNull.INSTANCE);
        window.add("remainingTokens", JsonNull.INSTANCE);
        window.add("usedCost", JsonNull.INSTANCE);
        window.addProperty("sessionCount", 0);
        window.addProperty("lastUpdated", System.currentTimeMillis());
        window.addProperty("source", "wham_usage");
        return window;
    }

    private static JsonObject pickFirstObject(JsonObject root, String... keys) {
        if (root == null) {
            return null;
        }
        for (String key : keys) {
            if (root.has(key) && root.get(key).isJsonObject()) {
                return root.getAsJsonObject(key);
            }
        }
        return null;
    }

    private static JsonObject resolveRateLimitRoot(JsonObject usage) {
        JsonObject fromByLimitId = pickNestedObject(usage, "rateLimitsByLimitId", "codex");
        if (fromByLimitId != null) {
            return fromByLimitId;
        }
        JsonObject directRateLimits = pickFirstObject(usage, "rateLimits");
        if (directRateLimits != null) {
            JsonObject nestedCodex = pickFirstObject(directRateLimits, "codex");
            return nestedCodex != null ? nestedCodex : directRateLimits;
        }
        return pickFirstObject(usage, "rate_limit");
    }

    private static JsonObject pickNestedObject(JsonObject root, String firstKey, String secondKey) {
        JsonObject first = pickFirstObject(root, firstKey);
        if (first == null) {
            return null;
        }
        return pickFirstObject(first, secondKey);
    }

    private String readAccessToken(JsonObject auth) {
        JsonObject tokens = pickFirstObject(auth, "tokens");
        if (tokens == null) {
            return null;
        }
        return readStringNullable(tokens, "access_token", "accessToken");
    }

    private static String readStringNullable(JsonObject obj, String... keys) {
        if (obj == null) {
            return null;
        }
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsString();
            }
        }
        return null;
    }

    private static int readInt(JsonObject obj, String... keys) {
        if (obj == null) {
            return 0;
        }
        for (String key : keys) {
            try {
                if (obj.has(key) && !obj.get(key).isJsonNull()) {
                    return obj.get(key).getAsInt();
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static Double readDoubleNullable(JsonObject obj, String... keys) {
        if (obj == null) {
            return null;
        }
        for (String key : keys) {
            try {
                if (obj.has(key) && !obj.get(key).isJsonNull()) {
                    return obj.get(key).getAsDouble();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Long readEpochMillisNullable(JsonObject obj, String... keys) {
        if (obj == null) {
            return null;
        }
        for (String key : keys) {
            try {
                if (obj.has(key) && !obj.get(key).isJsonNull()) {
                    JsonElement element = obj.get(key);
                    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                        long value = element.getAsLong();
                        if (value > 1_000_000_000_000L) {
                            return value; // already milliseconds
                        }
                        if (value > 1_000_000_000L) {
                            return value * 1000L; // seconds
                        }
                    } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        String raw = element.getAsString().trim();
                        if (!raw.isEmpty()) {
                            return Instant.parse(raw).toEpochMilli();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void sendUnavailable(String reason) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "unavailable");
        payload.addProperty("fetchedAt", System.currentTimeMillis());
        payload.addProperty("source", "wham_usage");
        payload.addProperty("error", reason != null ? reason : "unavailable");

        JsonObject windows = new JsonObject();
        windows.add("fiveHour", toWindow("5h", 5, null));
        windows.add("weekly", toWindow("weekly", 7 * 24, null));
        payload.add("windows", windows);
        sendPayload(payload);
    }

    private void sendPayload(JsonObject payload) {
        ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.updateCodexSubscriptionQuota", context.escapeJs(GSON.toJson(payload))));
    }
}
