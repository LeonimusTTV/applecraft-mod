package com.leonimust.applecraft.bridge;

import com.leonimust.applecraft.Constants;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Opens a URL in the default browser with the OS command, without any Minecraft API.
 * Minecraft's own helper moved between versions (Util.OS#openUri → Blaze3D#openUri), this works everywhere.
 */
public final class Browser {
    private Browser() {}

    public static void open(URI uri) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String url = uri.toString();
        String[] cmd = os.contains("mac") ? new String[]{"open", url}
                : os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
                : new String[]{"xdg-open", url};
        try {
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (IOException e) {
            Constants.LOG.warn("Could not open the browser, open {} yourself", url, e);
        }
    }
}
