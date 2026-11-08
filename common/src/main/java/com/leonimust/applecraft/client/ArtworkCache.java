package com.leonimust.applecraft.client;

import com.leonimust.applecraft.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ArtworkCache {
    private ArtworkCache() {}

    private static final int MAX = 160;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "AppleCraft-Artwork");
        t.setDaemon(true);
        return t;
    });

    private enum State { LOADING, READY, FAILED }
    private record Art(State state, Identifier id) {}

    private static int counter;
    private static final Map<String, Art> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Art> eldest) {
            if (size() <= MAX) return false;
            Art e = eldest.getValue();
            if (e.state == State.READY) Minecraft.getInstance().getTextureManager().release(e.id);
            return true;
        }
    };

    /** Must be called on the render thread. */
    public static Identifier get(String url) {
        if (url == null || url.isEmpty()) return null;
        Art e = CACHE.get(url);
        if (e == null) {
            CACHE.put(url, new Art(State.LOADING, null));
            POOL.execute(() -> load(url));
            return null;
        }
        return e.state == State.READY ? e.id : null;
    }

    private static void load(String url) {
        Minecraft mc = Minecraft.getInstance();
        try {
            HttpResponse<byte[]> res = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            NativeImage img = NativeImage.read(res.body());
            mc.execute(() -> {
                if (!CACHE.containsKey(url)) { img.close(); return; } // evicted in the meantime
                Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "artwork/" + (counter++));
                mc.getTextureManager().register(id, new DynamicTexture(() -> "AppleCraft artwork", img));
                CACHE.put(url, new Art(State.READY, id));
            });
        } catch (Exception ex) {
            Constants.LOG.debug("Artwork not loaded {}: {}", url, ex.toString());
            mc.execute(() -> CACHE.put(url, new Art(State.FAILED, null)));
        }
    }
}
