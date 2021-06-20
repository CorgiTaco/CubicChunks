package io.github.opencubicchunks.cubicchunks.mixin.core.common.chunk;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.github.opencubicchunks.cubicchunks.chunk.CubicAquifer;
import io.github.opencubicchunks.cubicchunks.chunk.NonAtomicWorldgenRandom;
import io.github.opencubicchunks.cubicchunks.server.CubicLevelHeightAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.BaseStoneSource;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.BaseStoneSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseModifier;
import net.minecraft.world.level.levelgen.NoiseSampler;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.SurfaceNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseBasedChunkGenerator {
    @Mutable @Shadow @Final protected Supplier<NoiseGeneratorSettings> settings;

    @Shadow @Final private int cellHeight;

    @Mutable @Shadow @Final private int cellCountY;

    @Shadow @Final private BaseStoneSource baseStoneSource;
    @Shadow @Final protected BlockState defaultFluid;

    @Shadow public abstract int getSeaLevel();

    @Shadow @Final private SurfaceNoise surfaceNoise;

    @Inject(method = "<init>(Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/biome/BiomeSource;JLjava/util/function/Supplier;)V", at = @At("RETURN"))
    private void init(BiomeSource biomeSource, BiomeSource biomeSource2, long l, Supplier<NoiseGeneratorSettings> supplier, CallbackInfo ci) {
        // access to through the registry is slow: vanilla accesses settings directly from the supplier in the constructor anyway
        NoiseGeneratorSettings suppliedSettings = this.settings.get();
        this.settings = () -> suppliedSettings;
    }

    @Redirect(method = "fillFromNoise", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
    private int alwaysUseChunkMinBuildHeight(int a, int b, Executor executor, StructureFeatureManager accessor, ChunkAccess chunk) {
        if (((CubicLevelHeightAccessor) chunk).generates2DChunks()) {
            return Math.max(a, b);
        }
        return b;
    }

    @Redirect(method = "fillFromNoise", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseSettings;minY()I"))
    private int modifyMinY(NoiseSettings noiseSettings, Executor executor, StructureFeatureManager structureFeatureManager, ChunkAccess chunkAccess) {
        if (((CubicLevelHeightAccessor) chunkAccess).generates2DChunks()) {
            return noiseSettings.minY();
        }

        return chunkAccess.getMinBuildHeight();
    }

    @Inject(method = "fillFromNoise", at = @At("HEAD"))
    private void changeCellSize(Executor executor, StructureFeatureManager structureFeatureManager, ChunkAccess chunkAccess, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (((CubicLevelHeightAccessor) chunkAccess).generates2DChunks()) {
            return;
        }
        this.cellCountY = chunkAccess.getHeight() / this.cellHeight;
    }

    @Inject(method = "doFill", at = @At("HEAD"))
    private void changeCellSize2(StructureFeatureManager structureFeatureManager, ChunkAccess chunkAccess, int i, int j, CallbackInfoReturnable<ChunkAccess> cir) {
        if (((CubicLevelHeightAccessor) chunkAccess).generates2DChunks()) {
            return;
        }
        this.cellCountY = chunkAccess.getHeight() / this.cellHeight;
    }

    @Redirect(method = "fillFromNoise", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;intFloorDiv(II)I", ordinal = 1))
    private int alwaysUseChunkMaxHeight(int i, int j, Executor executor, StructureFeatureManager structureFeatureManager, ChunkAccess chunkAccess) {
        if (((CubicLevelHeightAccessor) chunkAccess).generates2DChunks()) {
            return Mth.intFloorDiv(i, j);
        }

        return Mth.intFloorDiv(chunkAccess.getMaxBuildHeight() - chunkAccess.getMinBuildHeight(), cellHeight);
    }

    @Inject(method = "setBedrock", at = @At(value = "HEAD"), cancellable = true)
    private void cancelBedrockPlacement(ChunkAccess chunk, Random random, CallbackInfo ci) {
        if (((CubicLevelHeightAccessor) chunk).generates2DChunks()) {
            return;
        }
        ci.cancel();
    }

    // replace with non-atomic random for optimized random number generation
    @Redirect(method = "buildSurfaceAndBedrock", at = @At(value = "NEW", target = "net/minecraft/world/level/levelgen/WorldgenRandom"))
    private WorldgenRandom createCarverRandom() {
        return new NonAtomicWorldgenRandom();
    }

    @Inject(method = "buildSurfaceAndBedrock", at = @At("HEAD"), cancellable = true)
    private void fastBuildSurfaceAndBedrock(WorldGenRegion region, ChunkAccess chunk, CallbackInfo ci) {
        if (!((CubicLevelHeightAccessor) chunk).isCubic()) {
            return;
        }
        ci.cancel();

        ChunkPos chunkPos = chunk.getPos();
        int chunkX = chunkPos.x;
        int chunkZ = chunkPos.z;
        WorldgenRandom worldgenRandom = new NonAtomicWorldgenRandom();
        worldgenRandom.setBaseChunkSeed(chunkX, chunkZ);
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        long seed = region.getSeed();

        int fastMinSurfaceHeight = settings.get().getMinSurfaceLevel();

        if (chunk.getMinBuildHeight() > fastMinSurfaceHeight) {
            fastMinSurfaceHeight = chunk.getMinBuildHeight();
        } else if (chunk.getMaxBuildHeight() < fastMinSurfaceHeight) {
            fastMinSurfaceHeight = Integer.MAX_VALUE;
        }

        int seaLevel = this.getSeaLevel();
        for (int moveX = 0; moveX < 16; ++moveX) {
            for (int moveZ = 0; moveZ < 16; ++moveZ) {
                int worldX = minBlockX + moveX;
                int worldZ = minBlockZ + moveZ;
                int worldSurfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, moveX, moveZ) + 1;
                double surfaceNoise = this.surfaceNoise.getSurfaceNoiseValue((double) worldX * 0.0625D, (double) worldZ * 0.0625D, 0.0625D, (double) moveX * 0.0625D) * 15.0D;

                region.getBiome(mutable.set(minBlockX + moveX, worldSurfaceHeight, minBlockZ + moveZ))
                    .buildSurfaceAt(worldgenRandom, chunk, worldX, worldZ, worldSurfaceHeight, surfaceNoise, baseStoneSource.getBaseBlock(mutable), this.defaultFluid, seaLevel,
                        fastMinSurfaceHeight, seed);
            }
        }
    }

    @Redirect(
        method = "getAquifer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/Aquifer;create(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/synth/NormalNoise;"
                + "Lnet/minecraft/world/level/levelgen/synth/NormalNoise;Lnet/minecraft/world/level/levelgen/synth/NormalNoise;Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;"
                + "Lnet/minecraft/world/level/levelgen/NoiseSampler;II)Lnet/minecraft/world/level/levelgen/Aquifer;"
        )
    )
    private Aquifer createNoiseAquifer(
        ChunkPos chunkPos, NormalNoise barrierNoise, NormalNoise levelNoise, NormalNoise lavaNoise,
        NoiseGeneratorSettings noiseGeneratorSettings, NoiseSampler noiseSampler, int minY, int sizeY) {
        return new CubicAquifer(chunkPos, barrierNoise, levelNoise, lavaNoise, noiseGeneratorSettings, minY);
    }

    @Redirect(method = "updateNoiseAndGenerateBaseState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseModifier;modifyNoise(DIII)D"))
    private double dontModifyNoiseIfAboveCurrentCube(NoiseModifier noiseModifier, double weight, int x, int y, int z, Beardifier structures, Aquifer aquiferSampler,
                                                     BaseStoneSource blockInterpolator, NoiseModifier noiseModifier2, int i, int j, int k, double d) {
        if (aquiferSampler instanceof CubicAquifer cubicAquifer && y >= (cubicAquifer.getMinY() + cubicAquifer.getSizeY())) {
            return weight;
        }
        return noiseModifier.modifyNoise(weight, x, y, z);
    }
}
