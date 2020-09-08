package io.github.opencubicchunks.cubicchunks.mixin.core.common;

import com.mojang.datafixers.util.Either;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.chunk.IBigCube;
import io.github.opencubicchunks.cubicchunks.chunk.IChunkManager;
import io.github.opencubicchunks.cubicchunks.chunk.ICubeHolder;
import io.github.opencubicchunks.cubicchunks.chunk.cube.BigCube;
import io.github.opencubicchunks.cubicchunks.chunk.cube.CubeStatus;
import io.github.opencubicchunks.cubicchunks.chunk.graph.CCTicketType;
import io.github.opencubicchunks.cubicchunks.chunk.ticket.ITicketManager;
import io.github.opencubicchunks.cubicchunks.chunk.util.CubePos;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.ChunkManagerAccess;
import io.github.opencubicchunks.cubicchunks.server.IServerChunkProvider;
import io.github.opencubicchunks.cubicchunks.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.lighting.ICubeLightProvider;
import net.minecraft.profiler.IProfiler;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.server.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(ServerChunkProvider.class)
public abstract class MixinServerChunkProvider implements IServerChunkProvider, ICubeLightProvider {
    @Final @Shadow private TicketManager distanceManager;
    @Final @Shadow public ChunkManager chunkMap;
    @Shadow @Final public ServerWorld level;
    @Shadow @Final private ServerChunkProvider.ChunkExecutor mainThreadProcessor;
    @Shadow @Final private Thread mainThread;

    @Shadow protected abstract boolean chunkAbsent(@Nullable ChunkHolder chunkHolderIn, int p_217224_2_);

    @Shadow public abstract int getLoadedChunksCount();

    @Shadow @Final private static List<ChunkStatus> CHUNK_STATUSES;
    private final long[] recentCubePositions = new long[4];
    private final ChunkStatus[] recentCubeStatuses = new ChunkStatus[4];
    private final IBigCube[] recentCubes = new IBigCube[4];

    @Override
    public <T> void addCubeRegionTicket(TicketType<T> type, CubePos pos, int distance, T value) {
        ((ITicketManager) this.distanceManager).addCubeRegionTicket(type, pos, distance, value);
    }

    public <T> void removeCubeRegionTicket(TicketType<T> type, CubePos pos, int distance, T value) {
        ((ITicketManager) this.distanceManager).removeCubeRegionTicket(type, pos, distance, value);
    }

    @Override public int getTickingGeneratedCubes() {
        return ((IChunkManager) chunkMap).getTickingGeneratedCubes();
    }

    @Nullable
    @Override
    public IBigCube getCube(int cubeX, int cubeY, int cubeZ, ChunkStatus requiredStatus, boolean load) {
        if (Thread.currentThread() != this.mainThread) {
            return CompletableFuture.supplyAsync(() -> {
                return this.getCube(cubeX, cubeY, cubeZ, requiredStatus, load);
            }, this.mainThreadProcessor).join();
        } else {
            IProfiler iprofiler = this.level.getProfiler();
            iprofiler.incrementCounter("getCube");
            long i = CubePos.asLong(cubeX, cubeY, cubeZ);

            for(int j = 0; j < 4; ++j) {
                if (i == this.recentCubePositions[j] && requiredStatus == this.recentCubeStatuses[j]) {
                    IBigCube icube = this.recentCubes[j];
                    if (icube != null || !load) {
                        return icube;
                    }
                }
            }

            iprofiler.incrementCounter("getChunkCacheMiss");
            CompletableFuture<Either<IBigCube, ChunkHolder.IChunkLoadingError>> completablefuture = this.getCubeFutureMainThread(cubeX, cubeY, cubeZ,
                    requiredStatus,
                    load);
            this.mainThreadProcessor.managedBlock(completablefuture::isDone);
            IBigCube icube = completablefuture.join().map((p_222874_0_) -> {
                return p_222874_0_;
            }, (p_222870_1_) -> {
                if (load) {
                    throw Util.pauseInIde(new IllegalStateException("Chunk not there when requested: " + p_222870_1_));
                } else {
                    return null;
                }
            });
            this.storeCubeInCache(i, icube, requiredStatus);
            return icube;
        }
    }

    @Nullable
    public BigCube getCubeNow(int cubeX, int cubeY, int cubeZ) {
        if (Thread.currentThread() != this.mainThread) {
            return null;
        } else {
            this.level.getProfiler().incrementCounter("getChunkNow");
            long posAsLong = CubePos.asLong(cubeX, cubeY, cubeZ);

            for(int j = 0; j < 4; ++j) {
                if (posAsLong == this.recentCubePositions[j] && this.recentCubeStatuses[j] == ChunkStatus.FULL) {
                    IBigCube icube = this.recentCubes[j];
                    return icube instanceof BigCube ? (BigCube)icube : null;
                }
            }

            ChunkHolder chunkholder = this.getVisibleCubeIfPresent(posAsLong);
            if (chunkholder == null) {
                return null;
            } else {
                Either<IBigCube, ChunkHolder.IChunkLoadingError> either =
                        ((ICubeHolder)chunkholder).getCubeFutureIfPresent(ChunkStatus.FULL).getNow(null);
                if (either == null) {
                    return null;
                } else {
                    IBigCube icube1 = either.left().orElse(null);
                    if (icube1 != null) {
                        this.storeCubeInCache(posAsLong, icube1, ChunkStatus.FULL);
                        if (icube1 instanceof BigCube) {
                            return (BigCube)icube1;
                        }
                    }
                    return null;
                }
            }
        }
    }

    // forceChunk
    @Override
    public void forceCube(CubePos pos, boolean add) {
        ((ITicketManager)this.distanceManager).updateCubeForced(pos, add);
    }

    // func_217233_c, getChunkFutureMainThread
    private CompletableFuture<Either<IBigCube, ChunkHolder.IChunkLoadingError>> getCubeFutureMainThread(int cubeX, int cubeY, int cubeZ,
                                                                                              ChunkStatus requiredStatus, boolean load) {
        CubePos cubePos = CubePos.of(cubeX, cubeY, cubeZ);
        long i = cubePos.asLong();
        int j = 33 + CubeStatus.getDistance(requiredStatus);
        ChunkHolder chunkholder = this.getVisibleCubeIfPresent(i);
        if (load) {
            ((ITicketManager)this.distanceManager).addCubeTicket(CCTicketType.CCUNKNOWN, cubePos, j, cubePos);
            if (this.chunkAbsent(chunkholder, j)) {
                IProfiler iprofiler = this.level.getProfiler();
                iprofiler.push("chunkLoad");
                this.runCubeDistanceManagerUpdates();
                chunkholder = this.getVisibleCubeIfPresent(i);
                iprofiler.pop();
                if (this.chunkAbsent(chunkholder, j)) {
                    throw Util.pauseInIde(new IllegalStateException("No chunk holder after ticket has been added"));
                }
            }
        }

        return this.chunkAbsent(chunkholder, j) ? ICubeHolder.MISSING_CUBE_FUTURE : ((ICubeHolder)chunkholder).getOrScheduleCubeFuture(requiredStatus,
                this.chunkMap);
    }

    // func_217213_a, getVisibleChunkIfPresent
    private ChunkHolder getVisibleCubeIfPresent(long cubePosIn)
    {
        return ((IChunkManager)this.chunkMap).getImmutableCubeHolder(cubePosIn);
    }

    // func_225315_a, storeInCache
    private void storeCubeInCache(long newPositionIn, IBigCube newCubeIn, ChunkStatus newStatusIn) {
        for(int i = 3; i > 0; --i) {
            this.recentCubePositions[i] = this.recentCubePositions[i - 1];
            this.recentCubeStatuses[i] = this.recentCubeStatuses[i - 1];
            this.recentCubes[i] = this.recentCubes[i - 1];
        }

        this.recentCubePositions[0] = newPositionIn;
        this.recentCubeStatuses[0] = newStatusIn;
        this.recentCubes[0] = newCubeIn;
    }

    @Inject(method = "runDistanceManagerUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/server/ServerChunkProvider;clearCache()V"))
    private void onRefeshAndInvalidate(CallbackInfoReturnable<Boolean> cir)
    {
        this.clearCubeCache();
    }

    // func_217235_l, runDistanceManagerUpdates
    private boolean runCubeDistanceManagerUpdates() {
        boolean flag = this.distanceManager.runAllUpdates(this.chunkMap);
        boolean flag1 = ((ChunkManagerAccess)this.chunkMap).invokePromoteChunkMap();
        if (!flag && !flag1) {
            return false;
        } else {
            this.clearCubeCache();
            return true;
        }
    }

    private void clearCubeCache() {
        Arrays.fill(this.recentCubePositions, CubicChunks.SECTIONPOS_SENTINEL);
        Arrays.fill(this.recentCubeStatuses, null);
        Arrays.fill(this.recentCubes, null);
    }

    @Override
    @Nullable
    public IBlockReader getCubeForLighting(int sectionX, int sectionY, int sectionZ) {
        long cubePosAsLong = CubePos.of(Coords.sectionToCube(sectionX), Coords.sectionToCube(sectionY), Coords.sectionToCube(sectionZ)).asLong();
        ChunkHolder chunkholder = ((IChunkManager)this.chunkMap).getImmutableCubeHolder(cubePosAsLong);
        if (chunkholder == null) {
            return null;
        } else {
            int j = CHUNK_STATUSES.size() - 1;

            while(true) {
                ChunkStatus chunkstatus = CHUNK_STATUSES.get(j);
                Optional<IBigCube> optional = ((ICubeHolder)chunkholder).getCubeFutureIfPresentUnchecked(chunkstatus).getNow(ICubeHolder.MISSING_CUBE).left();
                if (optional.isPresent()) {
                    return optional.get();
                }

                if (chunkstatus == ChunkStatus.LIGHT.getParent()) {
                    return null;
                }

                --j;
            }
        }
    }

    /**
     * @author Barteks2x
     * @reason sections
     */
    @Overwrite
    public void blockChanged(BlockPos pos) {
        ChunkHolder chunkholder = ((IChunkManager) this.chunkMap).getCubeHolder(CubePos.from(pos).asLong());
        if (chunkholder != null) {
            // markBlockChanged
            chunkholder.blockChanged(new BlockPos(Coords.localX(pos), Coords.localY(pos), Coords.localZ(pos)));
        }
    }

    @Inject(method = "tickChunks",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/server/ChunkManager;getChunks()Ljava/lang/Iterable;"))
    private void tickSections(CallbackInfo ci) {

        ((IChunkManager) this.chunkMap).getCubes().forEach((cubeHolder) -> {
            Optional<BigCube> optional =
                    ((ICubeHolder) cubeHolder).getCubeEntityTickingFuture().getNow(ICubeHolder.UNLOADED_CUBE).left();
            if (optional.isPresent()) {
                BigCube section = optional.get();
                this.level.getProfiler().push("broadcast");
                ((ICubeHolder) cubeHolder).broadcastChanges(section);
                this.level.getProfiler().pop();
            }
        });
    }

    /**
     * @author Barteks2x
     * @reason debug string
     */
    @Overwrite
    public String gatherStats() {
        return "ServerChunkCache: " + this.getLoadedChunksCount() + " | " + ((IChunkManager) chunkMap).sizeCubes();
    }

}