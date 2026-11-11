package com.leonimust.applecraft.bridge;

import com.google.gson.JsonParser;
import com.leonimust.applecraft.Constants;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class BuildInfo {
    private BuildInfo() {}

    public static final String DEFAULT_WORKER_URL = "http://localhost:8787";
    public static final String WORKER_URL = read();

    private static String read() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/applecraft-build.json")) {
            if (in == null) return DEFAULT_WORKER_URL;
            String url = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().get("workerUrl").getAsString();
            // Not expanded (e.g. running from an IDE without Gradle) → fall back to the local Worker
            return url.isBlank() || url.contains("${") ? DEFAULT_WORKER_URL : url;
        } catch (Exception e) {
            Constants.LOG.warn("Could not read applecraft-build.json", e);
            return DEFAULT_WORKER_URL;
        }
    }
}
