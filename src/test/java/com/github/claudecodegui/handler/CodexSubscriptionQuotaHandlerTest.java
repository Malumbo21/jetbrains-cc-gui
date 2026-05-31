package com.github.claudecodegui.handler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CodexSubscriptionQuotaHandlerTest {

    @Test
    public void mapsWhamUsageResetAtToResetsAtMillis() {
        JsonObject usage = JsonParser.parseString("""
                {
                  "plan_type": "pro",
                  "rate_limit": {
                    "primary_window": {
                      "used_percent": 31,
                      "reset_at": 1735401600,
                      "limit_window_seconds": 18000
                    },
                    "secondary_window": {
                      "used_percent": 58,
                      "reset_at": 1735920000,
                      "limit_window_seconds": 604800
                    }
                  }
                }
                """).getAsJsonObject();

        JsonObject payload = CodexSubscriptionQuotaHandler.buildPayloadFromUsage(usage, 1710000000000L);
        JsonObject windows = payload.getAsJsonObject("windows");
        JsonObject fiveHour = windows.getAsJsonObject("fiveHour");
        JsonObject weekly = windows.getAsJsonObject("weekly");

        assertEquals(31.0, fiveHour.get("usedPercent").getAsDouble(), 0.001);
        assertEquals(69.0, fiveHour.get("remainingPercent").getAsDouble(), 0.001);
        assertEquals(1735401600000L, fiveHour.get("resetsAt").getAsLong());
        assertEquals(5, fiveHour.get("windowHours").getAsInt());

        assertEquals(42.0, weekly.get("remainingPercent").getAsDouble(), 0.001);
        assertEquals(1735920000000L, weekly.get("resetsAt").getAsLong());
        assertEquals(168, weekly.get("windowHours").getAsInt());
    }

    @Test
    public void mapsAppServerRateLimitsResetAtToResetsAtMillis() {
        JsonObject usage = JsonParser.parseString("""
                {
                  "rateLimits": {
                    "primary": {
                      "usedPercent": 25,
                      "windowDurationMins": 300,
                      "resetsAt": 1730947200
                    },
                    "secondary": {
                      "usedPercent": 80,
                      "windowDurationMins": 10080,
                      "resetsAt": 1731552000
                    }
                  }
                }
                """).getAsJsonObject();

        JsonObject payload = CodexSubscriptionQuotaHandler.buildPayloadFromUsage(usage, 1710000000000L);
        JsonObject windows = payload.getAsJsonObject("windows");

        assertEquals(75.0, windows.getAsJsonObject("fiveHour").get("remainingPercent").getAsDouble(), 0.001);
        assertEquals(1730947200000L, windows.getAsJsonObject("fiveHour").get("resetsAt").getAsLong());
        assertEquals(20.0, windows.getAsJsonObject("weekly").get("remainingPercent").getAsDouble(), 0.001);
        assertEquals(1731552000000L, windows.getAsJsonObject("weekly").get("resetsAt").getAsLong());
        assertTrue(payload.getAsJsonObject("windows").has("weekly"));
    }
}
