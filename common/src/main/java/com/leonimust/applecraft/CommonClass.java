package com.leonimust.applecraft;

import com.leonimust.applecraft.platform.Services;

/** Common init (client + server). AppleCraft is a client-side mod: all the real work happens in client/. */
public class CommonClass {

    public static void init() {
        Constants.LOG.info("AppleCraft loaded on {} ({})", Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());
    }
}
