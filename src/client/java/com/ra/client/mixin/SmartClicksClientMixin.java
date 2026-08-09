package com.ra.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
    private static String lastItem = "";
    private static boolean lastGui = false;
    private static boolean lastSprint = false;
    private static boolean lastGround = false;
    private static boolean lastDead = false;

    // ============================================================
    //  MAIN TICK – Target lock detection + periodic state sending
    // ============================================================
    @Inject(at = @At("HEAD"), method = "tick")
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
                if (living.isAlive()) {
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
            send("http://127.0.0.1:4321/target_unlocked");
            send("http://127.0.0.1:4321/target_locked");
        }

        wasLooking = isLookingAtValidEnemy;
        lastTargetId = targetId;

        // --- Periodic state update (every 300ms) ---
        long now = System.currentTimeMillis();
        if (now - lastStateSent >= 300L) {
            lastStateSent = now;
            sendState(mc, target);
        }
    }

    // ============================================================
    //  ATTACK DETECTION – Send /event?name=hit with distance
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
    //  STATE SENDER – Sends full context to Python
    // ============================================================
    private void sendState(Minecraft mc, Entity target) {
        boolean gui = mc.screen != null;
        boolean dead = !mc.player.isAlive();
        boolean sprint = mc.player.isSprinting();
        boolean ground = mc.player.onGround();
        String item = getItemCategory(mc.player.getMainHandItem());
        float distance = target != null ? mc.player.distanceTo(target) : 0f;
        float cooldown = mc.player.getAttackStrengthScale(0.5F);

        // Only send if something changed or every 1 second
        boolean changed = gui != lastGui || dead != lastDead ||
                sprint != lastSprint || ground != lastGround ||
                !item.equals(lastItem);

        if (!changed && System.currentTimeMillis() - lastStateSent < 700L) {
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

    // ============================================================
    //  SAFE ITEM DETECTION – Uses Registry Name (never crashes)
    // ============================================================
    private String getItemCategory(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) return "other";
            
            String name = id.getPath().toLowerCase();
            
            // Sword detection
            if (name.contains("sword") || name.contains("katana")) return "sword";
            
            // Axe detection
            if (name.contains("_axe") && !name.contains("pickaxe")) return "axe";
            
            // Pickaxe detection
            if (name.contains("pickaxe")) return "pickaxe";
            
            // Bow/Crossbow
            if (name.equals("bow")) return "bow";
            if (name.equals("crossbow")) return "crossbow";
            
            // Fishing rod
            if (name.equals("fishing_rod")) return "rod";
            
            // Trident
            if (name.equals("trident")) return "trident";
            
            // Potions
            if (name.contains("potion")) return "potion";
            
            // Blocks
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) return "blocks";
            
            // Throwable items
            if (name.equals("snowball")) return "snowball";
            if (name.equals("ender_pearl")) return "ender_pearl";
            if (name.equals("egg")) return "egg";
            
            // Shield
            if (name.equals("shield")) return "shield";
            
            // Food
            if (stack.getItem().getFoodProperties(stack, null) != null) return "food";
            
            return "other";
        } catch (Exception e) {
            return "other";
        }
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