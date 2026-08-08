package com.ra.client.mixin;

import com.ra.client.SmartClicksTelemetry;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class SmartClicksEntityMixin {
    @Inject(
            method = "hurtClient",
            at = @At("HEAD")
    )
    private void smartclicks$onHurtHead(
            DamageSource source,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof LivingEntity living) {
            SmartClicksTelemetry.INSTANCE.recordHurtStart(
                    living,
                    source,
                    0.0f
            );
        }
    }

    @Inject(
            method = "hurtClient",
            at = @At("RETURN")
    )
    private void smartclicks$onHurtReturn(
            DamageSource source,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof LivingEntity living) {
            SmartClicksTelemetry.INSTANCE.recordHurtReturn(
                    living,
                    source,
                    0.0f,
                    cir.getReturnValue()
            );
        }
    }
}