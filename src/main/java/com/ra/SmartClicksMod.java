package com.ra;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartClicksMod implements ModInitializer {
    public static final String MOD_ID = "smartclicks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("SmartClicks loaded - waiting for Python server on port 4321");
    }
}