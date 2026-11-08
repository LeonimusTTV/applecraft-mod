package com.leonimust.applecraft.bridge;

import java.util.List;

/** JSON types of the protocol (see PROTOCOL.md). Gson can fill records directly. */
public final class Models {
    private Models() {}

    /** A displayable item: song, album, artist or playlist. */
    public record Item(String kind, String id, String name, String subtitle,
                       String artworkUrl, Long durationMs, boolean playable) {}

    public record NowPlaying(String id, String name, String artist, String album,
                             String artworkUrl, Long durationMs) {}

    /** audioUnlocked = false: the browser blocks sound until the user clicks the play button in the player tab. */
    public record State(boolean authorized, String storefront, boolean isPlaying, double volume,
                        boolean shuffle, String repeat, long positionMs, Boolean audioUnlocked, NowPlaying item) {
        public boolean soundBlocked() { return Boolean.FALSE.equals(audioUnlocked); }
    }

    public record SearchResult(List<Item> songs, List<Item> albums, List<Item> artists, List<Item> playlists) {}

    /** serverUp = the local server is running; playerUp = the MusicKit page is connected to it. */
    public record Status(boolean serverUp, boolean playerUp) {}
}
