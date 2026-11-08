package com.leonimust.applecraft.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.leonimust.applecraft.Constants;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * config/applecraft.json
 * - workerUrl : URL of the Cloudflare Worker that signs the developer token
 * - port      : local player port (7531 by default; must be listed in the Worker's ALLOWED_ORIGINS)
 * - token     : random secret, generated once. It is part of the page URL, so other websites can't control
 *               the player, and an already open tab reconnects by itself when the game restarts.
 * - openBrowserOnStart : open the page automatically if no tab reconnects
 */
public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String workerUrl = "http://localhost:8787"; // replace with the deployed Worker before publishing
    public int port = 7531;
    public String token;
    public boolean openBrowserOnStart = true;

    // --- In-game display (editable in AppleCraft → Settings)
    /** Small "now playing" card in the HUD. */
    public boolean showNowPlaying = true;
    /** TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT */
    public String nowPlayingPosition = "TOP_LEFT";
    /** Message above the hotbar when the track changes. */
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
