package com.leonimust.applecraft;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class AppleCraft {

    public AppleCraft(IEventBus eventBus) {
        CommonClass.init();
    }
}
