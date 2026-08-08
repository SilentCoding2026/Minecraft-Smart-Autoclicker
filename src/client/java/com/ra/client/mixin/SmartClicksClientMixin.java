package com.ra.client.mixin;

import com.ra.client.SmartClicksTelemetry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mixin(Minecraft.class)
public class SmartClicksClientMixin {
    private static final ExecutorService HTTP = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SmartClicks-HTTP");
        t.setDaemon(true);
        return t;
    });

    private static boolean wasLooking = false;
    private static int lastTargetId = -1;
    private static long lastStateLog = 0L;

    @Inject(at = @At("HEAD"), method = "tick", remap = false)
    private void onTick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null) return;

        HitResult hit = mc.hitResult;
        Entity target = null;
        boolean isLookingAtPlayer = false;

        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hit).getEntity();
            if (entity instanceof Player) {
                isLookingAtPlayer = true;
                target = entity;
            }
        }

        int targetId = target == null ? -1 : target.getId();

        if (isLookingAtPlayer != wasLooking) {
            if (isLookingAtPlayer) {
                sendRequest("http://127.0.0.1:4321/target_locked");
                SmartClicksTelemetry.INSTANCE.recordTargetChange(mc, target, true);
            } else {
                sendRequest("http://127.0.0.1:4321/target_unlocked");
                SmartClicksTelemetry.INSTANCE.recordTargetChange(mc, null, false);
            }
        } else if (isLookingAtPlayer && targetId != lastTargetId) {
            sendRequest("http://127.0.0.1:4321/target_unlocked");
            sendRequest("http://127.0.0.1:4321/target_locked");
            SmartClicksTelemetry.INSTANCE.recordTargetChange(mc, target, false);
            SmartClicksTelemetry.INSTANCE.recordTargetChange(mc, target, true);
        }

        wasLooking = isLookingAtPlayer;
        lastTargetId = targetId;
        SmartClicksTelemetry.INSTANCE.setCurrentTarget(targetId);

        long now = System.currentTimeMillis();
        if (now - lastStateLog > 1000) {
            SmartClicksTelemetry.INSTANCE.recordPlayerState(mc, target);
            lastStateLog = now;
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), remap = false)
    private void onStartAttackHead(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        Entity target = getTargetEntity(mc);
        SmartClicksTelemetry.INSTANCE.recordAttackStart(mc, target);
    }

    @Inject(method = "startAttack", at = @At("RETURN"), remap = false)
    private void onStartAttackReturn(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        Entity target = getTargetEntity(mc);
        boolean success = cir.getReturnValue();
        SmartClicksTelemetry.INSTANCE.recordAttackReturn(mc, target, success);

        if (success && target instanceof Player && mc.player != null) {
            float distance = mc.player.distanceTo(target);
            sendRequest("http://127.0.0.1:4321/hit");
            sendRequest(String.format(
                    Locale.ROOT,
                    "http://127.0.0.1:4321/event?name=hit&distance=%.2f",
                    distance
            ));
        }
    }

    private static Entity getTargetEntity(Minecraft mc) {
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) {
            return null;
        }
        return ((EntityHitResult) mc.hitResult).getEntity();
    }

    private static void sendRequest(String urlString) {
        HTTP.submit(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(700);
                conn.setReadTimeout(700);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {
            }
        });
    }
}