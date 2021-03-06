package io.github.opencubicchunks.cubicchunks.mixin.core.client;

import io.github.opencubicchunks.cubicchunks.chunk.biome.ColumnBiomeContainer;
import io.github.opencubicchunks.cubicchunks.server.CubicLevelHeightAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private ClientLevel level;

    @Shadow public abstract ClientLevel getLevel();

    @Inject(method = "handleLevelChunk", at = @At("HEAD"), cancellable = true)
    public void handleLevelChunk(ClientboundLevelChunkPacket clientboundLevelChunkPacket, CallbackInfo ci) {
        if (level != null) {
            if (!((CubicLevelHeightAccessor) level).isCubic()) {
                return;
            }
        }
        ci.cancel();

        PacketUtils.ensureRunningOnSameThread(clientboundLevelChunkPacket, (ClientPacketListener) (Object) this, this.minecraft);
        int chunkX = clientboundLevelChunkPacket.getX();
        int chunkZ = clientboundLevelChunkPacket.getZ();

        @SuppressWarnings("ConstantConditions") //Not an NPE because this method is client-side.
        ColumnBiomeContainer biomeContainer = new ColumnBiomeContainer(minecraft.level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY), level, level);
        this.level.getChunkSource().replaceWithPacketData(chunkX, chunkZ, biomeContainer, clientboundLevelChunkPacket.getReadBuffer(), clientboundLevelChunkPacket.getHeightmaps(),
            clientboundLevelChunkPacket.getAvailableSections());
    }


    @Redirect(method = { "handleForgetLevelChunk", "handleLevelChunk" },
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxSection()I"))
    private int getFakeMaxSectionY(ClientLevel clientLevel) {
        if (!((CubicLevelHeightAccessor) clientLevel).isCubic()) {
            return clientLevel.getMaxSection();
        }

        return clientLevel.getMinSection() - 1; // disable the loop, cube packets do the necessary work
    }
}
