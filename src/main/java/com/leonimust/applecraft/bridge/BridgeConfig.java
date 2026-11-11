package com.leonimust.applecraft.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.leonimust.applecraft.Constants;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String workerUrl; // null = use BuildInfo.WORKER_URL
    public int port = 7531;
    public String token;
    public boolean openBrowserOnStart = true;

    public boolean showNowPlaying = true;
    public String nowPlayingPosition = "TOP_LEFT";
    public boolean showTrackChangeMessage = true;

    private transient Path file;

    public static BridgeConfig load(Path file) {
        BridgeConfig cfg = null;
        try {
            if (Files.exists(file)) cfg = GSON.fromJson(Files.readString(file), BridgeConfig.class);
        } catch (Exception e) {
            Constants.LOG.warn("Unreadable config/applecraft.json, using defaults", e);
        }
        if (cfg == null) cfg = new BridgeConfig();
        if (cfg.token == null || cfg.token.length() < 32) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            cfg.token = HexFormat.of().formatHex(b);
        }
        cfg.file = file;
        if (!java.util.Set.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT").contains(cfg.nowPlayingPosition)) cfg.nowPlayingPosition = "TOP_LEFT";
        cfg.save();
        return cfg;
    }

    /** Worker URL actually used: the user's override if set, otherwise the one baked into the jar. */
    public String effectiveWorkerUrl() {
        return workerUrl != null && !workerUrl.isBlank() ? workerUrl : BuildInfo.WORKER_URL;
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (Exception e) {
            Constants.LOG.warn("Could not write {}", file, e);
        }
    }
}
