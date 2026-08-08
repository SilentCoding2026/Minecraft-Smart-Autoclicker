package com.ra.client.mixin;

import com.ra.client.SmartClicksTelemetry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class SmartClicksGameModeMixin {
    @Inject(
            method = "attack",
            at = @At("HEAD"),
            remap = false
    )
    private void smartclicks$onAttack(Player player, Entity target, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || player == null) return;
        if (player == mc.player) {
            SmartClicksTelemetry.INSTANCE.recordAttackPacket(mc, target);
        }
    }
}