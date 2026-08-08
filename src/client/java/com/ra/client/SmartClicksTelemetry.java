package com.ra.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class SmartClicksTelemetry {

    public static final SmartClicksTelemetry INSTANCE = new SmartClicksTelemetry();

    private static final class PreHurt {
        final float health;
        final Vec3 motion;
        final long time;

        PreHurt(float health, Vec3 motion, long time) {
            this.health = health;
            this.motion = motion;
            this.time = time;
        }
    }

    private final Path dir;
    private final Gson gson = new GsonBuilder().create();
    private final BlockingQueue<JsonObject> queue = new LinkedBlockingQueue<>();

    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SmartClicks-Telemetry");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong seq = new AtomicLong();

    private final Map<Integer, Long> lastAttackAt = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastDamageAt = new ConcurrentHashMap<>();
    private final Map<Integer, PreHurt> preHurt = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastSwingAt = new ConcurrentHashMap<>();

    private volatile String sessionId;
    private volatile long startNano;
    private volatile String profile = "unknown";
    private volatile int currentTargetId = -1;
    private volatile boolean enabled = true;

    private SmartClicksTelemetry() {
        this.dir = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("smartclicks")
                .resolve("telemetry");

        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }

        newSession("init");

        io.scheduleWithFixedDelay(this::drainToFile, 2, 2, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::drainToFile));
    }

    public synchronized void newSession(String reason) {
        sessionId = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
                + "_"
                + UUID.randomUUID().toString().substring(0, 8);

        startNano = System.nanoTime();
        seq.set(0);

        lastAttackAt.clear();
        lastDamageAt.clear();
        preHurt.clear();
        lastSwingAt.clear();

        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        log("session_start", data);
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public void setProfile(String profileName) {
        this.profile = profileName == null ? "unknown" : profileName;
    }

    public void setCurrentTarget(int entityId) {
        this.currentTargetId = entityId;
    }

    private double tMs() {
        return (System.nanoTime() - startNano) / 1_000_000.0D;
    }

    public void log(String type, JsonObject data) {
        if (!enabled) return;

        JsonObject obj = new JsonObject();
        obj.addProperty("session", sessionId);
        obj.addProperty("seq", seq.incrementAndGet());
        obj.addProperty("wall", System.currentTimeMillis());
        obj.addProperty("t", tMs());
        obj.addProperty("profile", profile);
        obj.addProperty("type", type);

        if (data != null) {
            for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                obj.add(entry.getKey(), entry.getValue());
            }
        }

        queue.offer(obj);

        // Safety valve: never allow unlimited memory growth
        if (queue.size() > 50000) {
            queue.poll();
        }
    }

    public void requestFlush(String reason) {
        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        log("flush_request", data);

        io.submit(this::drainToFile);
    }

    private synchronized void drainToFile() {
        try {
            List<JsonObject> batch = new ArrayList<>();
            queue.drainTo(batch);

            if (batch.isEmpty()) return;

            Map<String, StringBuilder> bySession = new HashMap<>();

            for (JsonObject obj : batch) {
                String sid = obj.has("session") ? obj.get("session").getAsString() : sessionId;
                bySession.computeIfAbsent(sid, k -> new StringBuilder())
                        .append(gson.toJson(obj))
                        .append('\n');
            }

            for (Map.Entry<String, StringBuilder> entry : bySession.entrySet()) {
                Path file = dir.resolve(entry.getKey() + ".jsonl");
                Files.write(
                        file,
                        entry.getValue().toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (Exception e) {
            System.out.println("[SmartClicks] Telemetry write failed: " + e);
        }
    }

    private JsonObject baseCombatData(Minecraft mc, Entity target) {
        JsonObject data = new JsonObject();

        if (mc.player != null) {
            data.addProperty("health", mc.player.getHealth());

            try {
                data.addProperty("cooldown", mc.player.getAttackStrengthScale(0.5F));
            } catch (Throwable ignored) {
            }

            data.addProperty("sprinting", mc.player.isSprinting());

            try {
                data.addProperty("onGround", mc.player.onGround);
            } catch (Throwable ignored) {
            }
        }

        data.addProperty("currentTargetId", currentTargetId);

        if (target != null) {
            data.addProperty("targetId", target.getId());
            data.addProperty("targetPlayer", target instanceof Player);

            if (target instanceof LivingEntity living) {
                data.addProperty("targetHealth", living.getHealth());
            }

            if (mc.player != null) {
                data.addProperty("distance", mc.player.distanceTo(target));
            }
        } else {
            data.addProperty("targetId", -1);
        }

        return data;
    }

    public void recordTargetChange(Minecraft mc, Entity target, boolean locked) {
        setCurrentTarget(target == null ? -1 : target.getId());

        JsonObject data = baseCombatData(mc, target);
        data.addProperty("locked", locked);

        log(locked ? "target_locked" : "target_unlocked", data);
    }

    public void recordPlayerState(Minecraft mc, Entity target) {
        JsonObject data = baseCombatData(mc, target);
        log("state", data);
    }

    public void recordAttackStart(Minecraft mc, Entity target) {
        JsonObject data = baseCombatData(mc, target);
        data.addProperty("stage", "start");
        log("attack_attempt", data);
    }

    public void recordAttackReturn(Minecraft mc, Entity target, boolean success) {
        JsonObject data = baseCombatData(mc, target);
        data.addProperty("stage", "return");
        data.addProperty("success", success);

        if (success && target != null) {
            lastAttackAt.put(target.getId(), System.currentTimeMillis());
        }

        log("attack_attempt", data);
    }

    public void recordAttackPacket(Minecraft mc, Entity target) {
        if (target != null) {
            lastAttackAt.put(target.getId(), System.currentTimeMillis());
        }

        JsonObject data = baseCombatData(mc, target);
        log("attack_sent", data);
    }

    public void recordSwing(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == null) return;

        long now = System.currentTimeMillis();
        Long last = lastSwingAt.get(entity.getId());

        if (last != null && now - last < 20) {
            return;
        }

        lastSwingAt.put(entity.getId(), now);

        JsonObject data = baseCombatData(mc, entity);
        data.addProperty("entityId", entity.getId());
        data.addProperty("isSelf", entity == mc.player);

        if (entity == mc.player) {
            log("local_swing", data);
        } else if (entity.getId() == currentTargetId || mc.player.distanceTo(entity) < 8.0F) {
            log("enemy_swing", data);
        }
    }

    public void recordHurtStart(LivingEntity entity, DamageSource source, float amount) {
        if (entity == null) return;

        preHurt.put(
                entity.getId(),
                new PreHurt(
                        entity.getHealth(),
                        entity.getDeltaMovement(),
                        System.currentTimeMillis()
                )
        );
    }

    public void recordHurtReturn(LivingEntity entity, DamageSource source, float amount, boolean result) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == null) return;

        PreHurt pre = preHurt.remove(entity.getId());
        long now = System.currentTimeMillis();

        Entity attacker = source == null ? null : source.getEntity();

        JsonObject data = baseCombatData(mc, entity);

        data.addProperty("victimId", entity.getId());
        data.addProperty("victimPlayer", entity == mc.player);
        data.addProperty("damageAmount", amount);
        data.addProperty("hurtResult", result);

        if (attacker != null) {
            data.addProperty("attackerId", attacker.getId());
            data.addProperty("attackerPlayer", attacker instanceof Player);
            data.addProperty("attackerMe", attacker == mc.player);
        }

        double healthDelta = pre == null ? 0.0D : (double) pre.health - (double) entity.getHealth();

        double motionDelta = 0.0D;
        if (pre != null) {
            Vec3 nowMotion = entity.getDeltaMovement();
            motionDelta = nowMotion.subtract(pre.motion).length();
        }

        data.addProperty("healthDelta", healthDelta);
        data.addProperty("motionDelta", motionDelta);

        if (result) {
            lastDamageAt.put(entity.getId(), now);
        }

        if (entity == mc.player && result) {
            data.addProperty("kind", "damage_taken");

            if (attacker instanceof Player && attacker != mc.player) {
                data.addProperty("enemyRegisteredHit", true);
            }

            log("damage_taken", data);
        } else if (attacker == mc.player && result) {
            Long last = lastAttackAt.get(entity.getId());
            if (last != null) {
                data.addProperty("msSinceAttack", now - last);
            }

            data.addProperty("registeredHit", true);
            data.addProperty("damageConfirmed", healthDelta > 0.001D);
            data.addProperty("knockbackCandidate", motionDelta > 0.03D);

            log("damage_dealt", data);
        } else if (result) {
            log("entity_hurt", data);
        }
    }

    public void recordDeath(LivingEntity entity, DamageSource source) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == null) return;

        long now = System.currentTimeMillis();
        Entity attacker = source == null ? null : source.getEntity();

        JsonObject data = baseCombatData(mc, entity);

        data.addProperty("deadId", entity.getId());
        data.addProperty("deadPlayer", entity instanceof Player);
        data.addProperty("isMe", entity == mc.player);

        if (attacker != null) {
            data.addProperty("killerId", attacker.getId());
            data.addProperty("killerPlayer", attacker instanceof Player);
            data.addProperty("killerMe", attacker == mc.player);
        }

        if (entity == mc.player) {
            log("death", data);
            requestFlush("death");
            newSession("after_death");
            return;
        }

        boolean likelyKill = attacker == mc.player;

        Long lastDamage = lastDamageAt.get(entity.getId());
        Long lastAttack = lastAttackAt.get(entity.getId());

        if (!likelyKill && lastDamage != null && now - lastDamage < 3000) {
            likelyKill = true;
        }

        if (!likelyKill && lastAttack != null && now - lastAttack < 7000) {
            likelyKill = true;
        }

        if (likelyKill) {
            data.addProperty("killCredit", true);
            log("kill", data);
            requestFlush("kill");
        } else {
            log("entity_death", data);
        }
    }
}