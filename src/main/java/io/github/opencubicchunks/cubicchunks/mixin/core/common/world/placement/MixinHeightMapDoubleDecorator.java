package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.placement;

import java.util.Random;
import java.util.stream.Stream;

import io.github.opencubicchunks.cubicchunks.chunk.IBigCube;
import io.github.opencubicchunks.cubicchunks.misc.BlockPosHeightMapDoubleMarker;
import io.github.opencubicchunks.cubicchunks.server.CubicLevelHeightAccessor;
import io.github.opencubicchunks.cubicchunks.world.CubeWorldGenRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.feature.configurations.HeightmapConfiguration;
import net.minecraft.world.level.levelgen.placement.DecorationContext;
import net.minecraft.world.level.levelgen.placement.HeightmapDoubleDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HeightmapDoubleDecorator.class)
public abstract class MixinHeightMapDoubleDecorator {

    @Inject(method = "getPositions", at = @At(value = "HEAD"), cancellable = true)
    private void allowNegativeCoords(DecorationContext decorationContext, Random random, HeightmapConfiguration heightmapConfiguration, BlockPos blockPos,
                                     CallbackInfoReturnable<Stream<BlockPos>> cir) {
        CubicLevelHeightAccessor context = (CubicLevelHeightAccessor) decorationContext;

        if (!context.isCubic()) {
            return;
        }

        int x = blockPos.getX();
        int z = blockPos.getZ();

        int yHeightMap = decorationContext.getHeight(heightmapConfiguration.heightmap, x, z);
        if (!((CubeWorldGenRegion) decorationContext.getLevel()).insideCubeHeight(yHeightMap)) {
            cir.setReturnValue(Stream.of());
        } else {
            int y = blockPos.getY() + random.nextInt(IBigCube.DIAMETER_IN_BLOCKS);
            if (random.nextFloat() >= (0.1F * IBigCube.DIAMETER_IN_SECTIONS)) {
                cir.setReturnValue(Stream.of(new BlockPosHeightMapDoubleMarker(x, y, z, true)));
                return;
            }
            cir.setReturnValue(Stream.of(new BlockPosHeightMapDoubleMarker(x, y, z, false)));
        }
    }
}
