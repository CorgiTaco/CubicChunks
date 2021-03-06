package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.feature;

import io.github.opencubicchunks.cubicchunks.server.CubicLevelHeightAccessor;
import io.github.opencubicchunks.cubicchunks.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.CubeWorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.BlockBlobFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

//TODO: Configure this properly
@Mixin(BlockBlobFeature.class)
public class MixinBlockBlobFeature {

    @Redirect(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinBuildHeight()I"))
    private int useCubeMinY(WorldGenLevel worldGenLevel) {
        if (!((CubicLevelHeightAccessor) worldGenLevel).isCubic()) {
            return worldGenLevel.getMinBuildHeight();
        }
        return Coords.cubeToMinBlock(((CubeWorldGenRegion) worldGenLevel).getMainCubeY());

    }
}
