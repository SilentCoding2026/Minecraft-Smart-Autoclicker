package com.ra.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;

import java.net.HttpURLConnection;
import java.net.URL;

import com.ra.command.SmartClicksCommand;

public class SmartClicksModClient implements ClientModInitializer {

    private static boolean wasLooking = false;

    @Override
    public void onInitializeClient() {
        // Register command
        SmartClicksCommand.register();

        // Target lock detection (existing)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            HitResult hit = mc.hitResult;
            boolean isLookingAtPlayer = hit != null &&
                    hit.getType() == HitResult.Type.ENTITY &&
                    ((EntityHitResult) hit).getEntity() instanceof Player;
            if (isLookingAtPlayer && !wasLooking) {
                sendRequest("http://127.0.0.1:4321/target_locked");
            } else if (!isLookingAtPlayer && wasLooking) {
                sendRequest("http://127.0.0.1:4321/target_unlocked");
            }
            wasLooking = isLookingAtPlayer;
        });

        // Hit detection – send /hit when we attack a player
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (entity instanceof Player) {
                sendRequest("http://127.0.0.1:4321/hit");
            }
            return InteractionResult.PASS;
        });
    }

    private static void sendRequest(String urlString) {
        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                System.out.println("SmartClicks HTTP error: " + e.getMessage());
            }
        }).start();
    }
}