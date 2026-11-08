package com.leonimust.applecraft.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.leonimust.applecraft.Constants;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Local player server embedded in the mod.
 *
 *   GET  /                 MusicKit page (inside the jar, applecraft-web/ folder)
 *   GET  /config.json      { workerUrl }
 *   GET  /events?t=TOKEN   Server-Sent Events: mod → page commands
 *   POST /reply?t=TOKEN    page → mod: { type: "result" | "state", ... }
 *
 * Everything uses the JDK (com.sun.net.httpserver): no dependency to bundle.
 * Message format is described in PROTOCOL.md.
 */
public final class PlayerBridge {

    public static final String ERR_SERVER_NOT_RUNNING = "server_not_running";
    public static final String ERR_PLAYER_NOT_CONNECTED = "player_not_connected";

    private static final Gson GSON = new Gson();
    private static final long REQUEST_TIMEOUT_MS = 20_000;
    private static final long KEEPALIVE_MS = 3_000; // detect a closed tab quickly
    private static final String WEB_ROOT = "/applecraft-web";

    private final BridgeConfig config;
    private final Consumer<URI> browserOpener;

    private final ExecutorService httpPool = Executors.newCachedThreadPool(daemon("AppleCraft-HTTP"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemon("AppleCraft-Bridge"));

    private final AtomicLong seq = new AtomicLong();
    private final Map<String, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<Models.State>> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Models.Status>> statusListeners = new CopyOnWriteArrayList<>();

    // volatile: written by the mod-loading thread, read by the render thread
    private volatile HttpServer server;
    private volatile int boundPort = -1;
    private volatile PageConnection page;
    private volatile Models.State state;
    private volatile Models.Status status = new Models.Status(false, false);
    private final java.util.concurrent.ConcurrentHashMap<String, Long> sentAt = new java.util.concurrent.ConcurrentHashMap<>();

    public PlayerBridge(BridgeConfig config, Consumer<URI> browserOpener) {
        this.config = config;
        this.browserOpener = browserOpener;
    }

    // ================================================================ lifecycle

    /** Starts the local server. If no tab reconnects within 4 s, opens the browser. */
    public synchronized void start() {
        if (server != null) return;
        // Fallback ports if 7531 is already taken (another Minecraft instance, …)
        for (int port = config.port; port < config.port + 3 && server == null; port++) {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
                boundPort = port;
            } catch (BindException e) {
                Constants.LOG.warn("Port {} already in use, trying the next one", port);
            } catch (IOException e) {
                Constants.LOG.error("Could not start the local player server", e);
                return;
            }
        }
        if (server == null) {
            Constants.LOG.error("No free port between {} and {}", config.port, config.port + 2);
            return;
        }
        server.createContext("/", this::handleStatic);
        server.createContext("/config.json", ex -> respond(ex, 200, "application/json", configJson()));
        server.createContext("/events", this::handleEvents);
        server.createContext("/reply", this::handleReply);
        server.setExecutor(httpPool);
        server.start();
        setStatus(new Models.Status(true, false));
        Constants.LOG.info("AppleCraft player on http://localhost:{}", boundPort);

        if (config.openBrowserOnStart) {
            scheduler.schedule(() -> { if (page == null) openPlayerPage(); }, 4, TimeUnit.SECONDS);
        }
    }

    public synchronized void stop() {
        if (server != null) server.stop(0);
        server = null;
        httpPool.shutdownNow();
        scheduler.shutdownNow();
    }

    /** Player page URL, including the token. */
    public URI playerUri() {
        return URI.create("http://localhost:" + boundPort + "/?t=" + config.token);
    }

    public void openPlayerPage() {
        if (boundPort < 0) return;
        Constants.LOG.info("Opening the player in the browser");
        try {
            browserOpener.accept(playerUri());
        } catch (Exception e) {
            Constants.LOG.warn("Could not open the browser, open {} manually", playerUri(), e);
        }
    }

    // ================================================================ HTTP

    private String configJson() {
        JsonObject o = new JsonObject();
        o.addProperty("workerUrl", config.workerUrl);
        return o.toString();
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (!path.matches("/[a-z0-9_-]+\\.(html|js|css|png|svg)")) { respond(ex, 404, "text/plain", "Not found"); return; }
        try (InputStream in = PlayerBridge.class.getResourceAsStream(WEB_ROOT + path)) {
            if (in == null) { respond(ex, 404, "text/plain", "Not found"); return; }
            String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : path.endsWith(".css") ? "text/css; charset=utf-8"
                    : path.endsWith(".svg") ? "image/svg+xml" : "image/png";
            respond(ex, 200, type, in.readAllBytes());
        }
    }

    private boolean authorized(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        String t = null;
        if (q != null) for (String kv : q.split("&")) if (kv.startsWith("t=")) t = kv.substring(2);
        // constant-time comparison
        return t != null && MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), config.token.getBytes(StandardCharsets.UTF_8));
    }

    /** SSE: the connection stays open and commands are pushed through it. The HTTP thread stays here until the tab closes. */
    private void handleEvents(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { respond(ex, 403, "text/plain", "Bad token"); return; }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);

        PageConnection conn = new PageConnection(ex.getResponseBody());
        PageConnection previous = page;
        page = conn;
        if (previous != null) previous.trySend("{\"type\":\"replaced\"}"); // only one active tab
        Constants.LOG.info("Player page connected");
        setStatus(new Models.Status(true, true));

        try {
            conn.send("{\"type\":\"hello\"}");
            while (!conn.closed) {
                Thread.sleep(KEEPALIVE_MS);
                conn.ping();
            }
        } catch (IOException | InterruptedException ignored) {
            // tab closed
        } finally {
            conn.closed = true;
            ex.close();
            if (page == conn) {
                page = null;
                Constants.LOG.info("Player page disconnected");
                pending.forEach((id, f) -> f.completeExceptionally(new BridgeException(ERR_PLAYER_NOT_CONNECTED)));
                pending.clear();
                setStatus(new Models.Status(true, false));
            }
        }
    }

    private void handleReply(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod()) || !authorized(ex)) { respond(ex, 403, "text/plain", "Forbidden"); return; }
        String body;
        try (InputStream in = ex.getRequestBody()) { body = new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8); }
        respond(ex, 204, null, new byte[0]);
        try {
            handle(JsonParser.parseString(body).getAsJsonObject());
        } catch (Exception e) {
            Constants.LOG.warn("Invalid message from the page: {}", e.toString());
        }
    }

    private static void respond(HttpExchange ex, int code, String type, String body) throws IOException {
        respond(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        if (type != null) ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        ex.close();
    }

    private static final class PageConnection {
        private final OutputStream out;
        volatile boolean closed;

        PageConnection(OutputStream out) { this.out = out; }

        synchronized void send(String json) throws IOException {
            out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        synchronized void ping() throws IOException {
            out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void trySend(String json) {
            try { send(json); } catch (IOException e) { closed = true; }
        }
    }

    // ================================================================ messages from the page

    private void handle(JsonObject msg) {
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        switch (type) {
            case "result" -> {
                CompletableFuture<JsonElement> f = pending.remove(msg.get("id").getAsString());
                if (f == null) return;
                if (msg.get("ok").getAsBoolean()) f.complete(msg.get("data"));
                else f.completeExceptionally(new BridgeException(msg.get("error").getAsString()));
            }
            case "state" -> {
                Models.State s = GSON.fromJson(msg.get("data"), Models.State.class);
                state = s;
                for (var l : stateListeners) l.accept(s);
            }
            default -> { }
        }
    }

    private void setStatus(Models.Status s) {
        if (s.equals(status)) return;
        status = s;
        for (var l : statusListeners) l.accept(s);
    }

    // ================================================================ sending

    /** Sends a raw command. The future fails with {@link BridgeException} (error code in getMessage()). */
    public CompletableFuture<JsonElement> send(String action, JsonObject params) {
        if (server == null) {
            Constants.LOG.debug("{} rejected: server not running", action);
            return CompletableFuture.failedFuture(new BridgeException(ERR_SERVER_NOT_RUNNING));
        }
        PageConnection conn = page;
        if (conn == null || conn.closed) {
            Constants.LOG.debug("{} rejected: no page (conn={}, closed={})", action, conn != null, conn != null && conn.closed);
            return CompletableFuture.failedFuture(new BridgeException(ERR_PLAYER_NOT_CONNECTED));
        }

        String id = "mc-" + seq.incrementAndGet();
        JsonObject cmd = new JsonObject();
        cmd.addProperty("type", "cmd");
        cmd.addProperty("id", id);
        cmd.addProperty("action", action);
        cmd.add("params", params != null ? params : new JsonObject());

        CompletableFuture<JsonElement> f = new CompletableFuture<JsonElement>().orTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        f.whenComplete((r, e) -> pending.remove(id));
        pending.put(id, f);

        String text = cmd.toString();
        sentAt.put(id, System.currentTimeMillis());
        Constants.LOG.debug("→ {} {} {}", id, action, params != null ? params : "");
        f.whenComplete((r, e) -> {
            Long t0 = sentAt.remove(id);
            long ms = t0 != null ? System.currentTimeMillis() - t0 : -1;
            if (e != null) Constants.LOG.debug("← {} {} ERROR {} ({} ms)", id, action, e.getMessage() != null ? e.getMessage() : e, ms);
            else Constants.LOG.debug("← {} {} ok ({} ms)", id, action, ms);
        });
        scheduler.execute(() -> {
            try {
                conn.send(text);
            } catch (IOException e) {
                conn.closed = true;
                f.completeExceptionally(new BridgeException(ERR_PLAYER_NOT_CONNECTED));
            }
        });
        return f;
    }

    private <T> CompletableFuture<T> send(String action, JsonObject params, java.lang.reflect.Type type) {
        return send(action, params).thenApply(json -> GSON.fromJson(json, type));
    }

    private static JsonObject params(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) o.add((String) kv[i], GSON.toJsonTree(kv[i + 1]));
        return o;
    }

    // ================================================================ typed API

    public CompletableFuture<JsonElement> play() { return send("play", null); }
    public CompletableFuture<JsonElement> pause() { return send("pause", null); }
    public CompletableFuture<JsonElement> toggle() { return send("toggle", null); }
    public CompletableFuture<JsonElement> next() { return send("next", null); }
    public CompletableFuture<JsonElement> prev() { return send("prev", null); }
    public CompletableFuture<JsonElement> volume(double v) { return send("volume", params("value", v)); }
    public CompletableFuture<JsonElement> seek(long positionMs) { return send("seek", params("positionMs", positionMs)); }
    public CompletableFuture<JsonElement> shuffle(boolean on) { return send("shuffle", params("on", on)); }
    public CompletableFuture<JsonElement> repeat(String mode) { return send("repeat", params("mode", mode)); }
    public CompletableFuture<JsonElement> playItem(Models.Item item) { return send("playItem", params("kind", item.kind(), "id", item.id())); }
    public CompletableFuture<JsonElement> playInList(Models.Item container, int index) {
        return send("playItem", params("kind", container.kind(), "id", container.id(), "startIndex", index));
    }

    public CompletableFuture<Models.State> getState() { return send("getState", null, Models.State.class); }
    public CompletableFuture<Models.State> authorize() { return send("authorize", null, Models.State.class); }

    public CompletableFuture<Models.SearchResult> search(String term, int limit) {
        return send("search", params("term", term, "limit", limit), Models.SearchResult.class);
    }

    private static final java.lang.reflect.Type ITEMS = new TypeToken<List<Models.Item>>() {}.getType();

    public CompletableFuture<List<Models.Item>> artistTopSongs(String artistId) { return send("artistTopSongs", params("id", artistId), ITEMS); }
    public CompletableFuture<List<Models.Item>> artistAlbums(String artistId) { return send("artistAlbums", params("id", artistId), ITEMS); }
    public CompletableFuture<List<Models.Item>> tracks(Models.Item albumOrPlaylist) {
        return send("tracks", params("kind", albumOrPlaylist.kind(), "id", albumOrPlaylist.id()), ITEMS);
    }
    public CompletableFuture<List<Models.Item>> libraryPlaylists() { return send("libraryPlaylists", null, ITEMS); }
    public CompletableFuture<List<Models.Item>> recentlyPlayed() { return send("recentlyPlayed", null, ITEMS); }

    // ================================================================ state

    public BridgeConfig config() { return config; }
    public Models.State state() { return state; }
    public Models.Status status() { return status; }
    public void onState(Consumer<Models.State> l) { stateListeners.add(l); }
    public void onStatus(Consumer<Models.Status> l) { statusListeners.add(l); }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public static final class BridgeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public BridgeException(String code) { super(code, null, false, false); }
    }
}
