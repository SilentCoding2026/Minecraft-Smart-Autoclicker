package com.ra.client.mixin;

import com.ra.client.SmartClicksTelemetry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class SmartClicksLivingEntityMixin {
    @Inject(
            method = "swing",
            at = @At("HEAD")
    )
    private void smartclicks$onSwing(
            InteractionHand hand,
            CallbackInfo ci
    ) {
        SmartClicksTelemetry.INSTANCE.recordSwing(
                (LivingEntity) (Object) this
        );
    }

    @Inject(
            method = "die",
            at = @At("HEAD")
    )
    private void smartclicks$onDie(
            DamageSource source,
            CallbackInfo ci
    ) {
        SmartClicksTelemetry.INSTANCE.recordDeath(
                (LivingEntity) (Object) this,
                source
        );
    }
}