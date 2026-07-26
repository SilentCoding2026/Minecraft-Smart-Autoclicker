package com.ra.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.HttpURLConnection;
import java.net.URL;

@Mixin(Minecraft.class)
public class SmartClicksClientMixin {
    private static boolean wasLooking = false;

    // Target detection – runs every tick
    @Inject(at = @At("HEAD"), method = "tick")
    private void onTick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
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
    }

    // Attack detection – intercept startAttack
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            Entity target = ((EntityHitResult) mc.hitResult).getEntity();
            if (target instanceof Player) {
                sendRequest("http://127.0.0.1:4321/hit");
            }
        }
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