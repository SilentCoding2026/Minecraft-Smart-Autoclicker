package com.ra.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
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
    private static long lastStateSent = 0L;

    // ============================================================
    //  TARGET DETECTION - every tick
    // ============================================================
    @Inject(at = @At("HEAD"), method = "tick")
    private void smartclicks$tick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null || mc.level == null) return;

        HitResult hit = mc.hitResult;
        Entity target = null;
        boolean isLookingAtEnemy = false;

        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hit).getEntity();
            if (entity instanceof Player && entity != mc.player) {
                LivingEntity living = (LivingEntity) entity;
                if (living.isAlive()) {
                    isLookingAtEnemy = true;
                    target = entity;
                }
            }
        }

        int targetId = target == null ? -1 : target.getId();

        if (isLookingAtEnemy != wasLooking) {
            if (isLookingAtEnemy) {
                send("http://127.0.0.1:4321/target_locked");
            } else {
                send("http://127.0.0.1:4321/target_unlocked");
            }
        } else if (isLookingAtEnemy && targetId != lastTargetId) {
            send("http://127.0.0.1:4321/target_unlocked");
            send("http://127.0.0.1:4321/target_locked");
        }

        wasLooking = isLookingAtEnemy;
        lastTargetId = targetId;

        // Send state every 300ms
        long now = System.currentTimeMillis();
        if (now - lastStateSent >= 300L) {
            lastStateSent = now;
            sendState(mc, target);
        }
    }

    // ============================================================
    //  HIT DETECTION
    // ============================================================
    @Inject(method = "startAttack", at = @At("RETURN"))
    private void smartclicks$onAttack(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;

        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null || mc.hitResult == null) return;
        if (mc.hitResult.getType() != HitResult.Type.ENTITY) return;

        Entity target = ((EntityHitResult) mc.hitResult).getEntity();
        if (!(target instanceof Player) || target == mc.player) return;

        float distance = mc.player.distanceTo(target);
        send(String.format(Locale.ROOT,
                "http://127.0.0.1:4321/event?name=hit&distance=%.2f", distance));
    }

    // ============================================================
    //  STATE SENDER
    // ============================================================
    private void sendState(Minecraft mc, Entity target) {
        boolean gui = mc.screen != null;
        boolean dead = !mc.player.isAlive();
        boolean sprint = mc.player.isSprinting();
        boolean ground = mc.player.onGround();
        String item = getItemName(mc.player.getMainHandItem());
        float distance = target != null ? mc.player.distanceTo(target) : 0f;

        send(String.format(Locale.ROOT,
                "http://127.0.0.1:4321/state?gui=%b&dead=%b&item=%s&sprinting=%b&ground=%b&distance=%.2f",
                gui, dead, item, sprint, ground, distance));
    }

    private String getItemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        Item item = stack.getItem();
        if (item instanceof SwordItem) return "sword";
        if (item instanceof AxeItem) return "axe";
        if (item instanceof PickaxeItem) return "pickaxe";
        if (item instanceof BowItem) return "bow";
        if (item instanceof CrossbowItem) return "crossbow";
        if (item instanceof FishingRodItem) return "rod";
        if (item instanceof TridentItem) return "trident";
        if (item instanceof PotionItem) return "potion";
        if (item instanceof BlockItem) return "blocks";
        if (item instanceof SnowballItem) return "snowball";
        if (item instanceof EnderpearlItem) return "ender_pearl";
        if (item instanceof EggItem) return "egg";
        if (item instanceof ShieldItem) return "shield";
        return "other";
    }

    // ============================================================
    //  HTTP HELPER
    // ============================================================
    private static void send(String urlString) {
        HTTP.submit(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {
            }
        });
    }
}