package com.ra.client.mixin;

import com.ra.client.SmartClicksTelemetry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class SmartClicksLivingEntityMixin {

    @Inject(
            method = "swing(Lnet/minecraft/world/InteractionHand;)V",
            at = @At("HEAD")
    )
    private void smartclicks$onSwing(InteractionHand hand, CallbackInfo ci) {
        SmartClicksTelemetry.INSTANCE.recordSwing((LivingEntity) (Object) this);
    }

    @Inject(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD")
    )
    private void smartclicks$onHurtHead(
            DamageSource source,
            float amount,
            CallbackInfoReturnable<Boolean> cir
    ) {
        SmartClicksTelemetry.INSTANCE.recordHurtStart(
                (LivingEntity) (Object) this,
                source,
                amount
        );
    }

    @Inject(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("RETURN")
    )
    private void smartclicks$onHurtReturn(
            DamageSource source,
            float amount,
            CallbackInfoReturnable<Boolean> cir
    ) {
        SmartClicksTelemetry.INSTANCE.recordHurtReturn(
                (LivingEntity) (Object) this,
                source,
                amount,
                cir.getReturnValue()
        );
    }

    @Inject(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("HEAD")
    )
    private void smartclicks$onDie(DamageSource source, CallbackInfo ci) {
        SmartClicksTelemetry.INSTANCE.recordDeath(
                (LivingEntity) (Object) this,
                source
        );
    }
}