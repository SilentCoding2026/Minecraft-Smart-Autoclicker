package com.ra.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.entity.player.PlayerEntity;
import java.net.HttpURLConnection;
import java.net.URL;

public class ExampleModClient implements ClientModInitializer {
    private static boolean wasLooking = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // بررسی نشانه روی (Crosshair) روی موجودات
            HitResult hit = client.crosshairTarget;
            boolean isLookingAtPlayer = (hit != null 
                    && hit.getType() == HitResult.Type.ENTITY 
                    && ((EntityHitResult) hit).getEntity() instanceof PlayerEntity);

            // منطق تغییر وضعیت تارگت
            if (isLookingAtPlayer && !wasLooking) {
                sendRequest("http://127.0.0.1:4321/target_locked");
            } else if (!isLookingAtPlayer && wasLooking) {
                sendRequest("http://127.0.0.1:4321/target_unlocked");
            }

            wasLooking = isLookingAtPlayer;
        });
    }

    // متد ارسال درخواست به صورت پس‌زمینه (بدون لگ زدن بازی)
    private static void sendRequest(String urlString) {
        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1000); // ۱ ثانیه تایم‌اوت برای جلوگیری از معطلی
                conn.getResponseCode(); 
                conn.disconnect();
            } catch (Exception e) {
                // اگر سرور پایتون روشن نباشد خطایی در کنسول چاپ می‌شود که بازی را کرش نمی‌دهد
                System.out.println("Python server communication error: " + e.getMessage());
            }
        }).start();
    }
}