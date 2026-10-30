package fr.nilsm.vastcontinents.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/**
 * Vanilla only trusts its own overworld dimension type: without this, every world of this type
 * would show the "experimental settings" warning when it is created and each time it is opened.
 */
@Mixin(WorldDimensions.class)
abstract class WorldDimensionsMixin {
	@Unique
	private static final ResourceKey<DimensionType> TALL_OVERWORLD = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath("vastcontinents", "tall_overworld"));

	@Inject(method = "isStableOverworld", at = @At("HEAD"), cancellable = true)
	private static void vastcontinents$trustTallOverworld(LevelStem dimension, CallbackInfoReturnable<Boolean> info) {
		if (dimension.type().is(TALL_OVERWORLD)) {
			info.setReturnValue(true);
		}
	}
}
