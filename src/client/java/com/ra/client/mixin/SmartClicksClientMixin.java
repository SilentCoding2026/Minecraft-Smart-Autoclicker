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

    // --- State tracking ---
    private static boolean wasLooking = false;
    private static int lastTargetId = -1;
    private static long lastStateSent = 0L;
    private static String lastItem = "";
    private static boolean lastGui = false;
    private static boolean lastSprint = false;
    private static boolean lastGround = false;
    private static boolean lastDead = false;

    // ============================================================
    //  MAIN TICK – Target lock detection + periodic state sending
    // ============================================================
    @Inject(at = @At("HEAD"), method = "tick", remap = false)
    private void smartclicks$tick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null || mc.level == null) return;

        // --- Target detection ---
        HitResult hit = mc.hitResult;
        Entity target = null;
        boolean isLookingAtValidEnemy = false;

        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hit).getEntity();
            if (entity instanceof Player && entity != mc.player) {
                LivingEntity living = (LivingEntity) entity;
                if (living.isAlive() && !living.isInvisibleTo(mc.player)) {
                    isLookingAtValidEnemy = true;
                    target = entity;
                }
            }
        }

        int targetId = target == null ? -1 : target.getId();

        // --- Target lock state changes ---
        if (isLookingAtValidEnemy != wasLooking) {
            if (isLookingAtValidEnemy) {
                send("http://127.0.0.1:4321/target_locked");
            } else {
                send("http://127.0.0.1:4321/target_unlocked");
            }
        } else if (isLookingAtValidEnemy && targetId != lastTargetId) {
            // Switched targets
            send("http://127.0.0.1:4321/target_unlocked");
            send("http://127.0.0.1:4321/target_locked");
        }

        wasLooking = isLookingAtValidEnemy;
        lastTargetId = targetId;

        // --- Periodic state update (every 250ms) ---
        long now = System.currentTimeMillis();
        if (now - lastStateSent >= 250L) {
            lastStateSent = now;
            sendState(mc, target);
        }
    }

    // ============================================================
    //  ATTACK DETECTION – Send /event?name=hit with distance
    // ============================================================
    @Inject(method = "startAttack", at = @At("RETURN"), remap = false)
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
    //  STATE SENDER – Sends full context to Python
    // ============================================================
    private void sendState(Minecraft mc, Entity target) {
        boolean gui = mc.screen != null;
        boolean dead = !mc.player.isAlive();
        boolean sprint = mc.player.isSprinting();
        boolean ground = mc.player.onGround();
        String item = getItemName(mc.player.getMainHandItem());
        float distance = target != null ? mc.player.distanceTo(target) : 0f;
        float cooldown = mc.player.getAttackStrengthScale(0.5F);

        // Only send if something changed or every 1 second
        boolean changed = gui != lastGui || dead != lastDead ||
                sprint != lastSprint || ground != lastGround ||
                !item.equals(lastItem);

        if (!changed && System.currentTimeMillis() - lastStateSent < 1000L) {
            return;
        }

        lastGui = gui;
        lastDead = dead;
        lastSprint = sprint;
        lastGround = ground;
        lastItem = item;

        send(String.format(Locale.ROOT,
                "http://127.0.0.1:4321/state?gui=%b&dead=%b&item=%s&sprinting=%b&ground=%b&distance=%.2f&cooldown=%.2f",
                gui, dead, item, sprint, ground, distance, cooldown));
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
        if (item instanceof FoodItem) return "food";
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