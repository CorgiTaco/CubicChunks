package io.github.opencubicchunks.cubicchunks.mixin.core.common.chunk.storage;

import java.io.File;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.mojang.datafixers.DataFixer;
import io.github.opencubicchunks.cubicchunks.chunk.storage.POIDeserializationContext;
import io.github.opencubicchunks.cubicchunks.server.CubicLevelHeightAccessor;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PoiManager.class)
public abstract class MixinPoiManager extends SectionStorage<PoiSection> implements POIDeserializationContext {


    public MixinPoiManager(File file, Function function, Function function2, DataFixer dataFixer,
                           DataFixTypes dataFixTypes, boolean bl, LevelHeightAccessor levelHeightAccessor) {
        super(file, function, function2, dataFixer, dataFixTypes, bl, levelHeightAccessor);
    }

    @Shadow protected abstract void updateFromSection(LevelChunkSection levelChunkSection, SectionPos sectionPos,
                                                      BiConsumer<BlockPos, PoiType> biConsumer);

    @Shadow private static boolean mayHavePoi(LevelChunkSection levelChunkSection) {
        throw new Error("Mixin did not apply");
    }

    @Inject(method = "getInSquare", at = @At("HEAD"), cancellable = true)
    private void getInSquare(Predicate<PoiType> typePredicate, BlockPos pos, int radius, PoiManager.Occupancy occupancy, CallbackInfoReturnable<Stream<PoiRecord>> cir) {
        if (!((CubicLevelHeightAccessor) this.levelHeightAccessor).isCubic()) {
            return;
        }

        int i = Math.floorDiv(radius, 16) + 1;
        cir.setReturnValue(SectionPos.cube(SectionPos.of(pos), i).flatMap((chunkPos) -> {
            return this.getInSections(typePredicate, chunkPos, occupancy);
        }).filter((poiRecord) -> {
            BlockPos blockPos2 = poiRecord.getPos();
            return Math.abs(blockPos2.getX() - pos.getX()) <= radius && Math.abs(blockPos2.getY() - pos.getY()) <= radius && Math.abs(blockPos2.getZ() - pos.getZ()) <= radius;
        }));
    }

    public Stream<PoiRecord> getInSections(Predicate<PoiType> predicate, SectionPos sectionPos, PoiManager.Occupancy occupationStatus) {
        return Stream.of(this.getOrLoad(sectionPos.asLong())).filter(Optional::isPresent).flatMap((optional -> optional.get().getRecords(predicate, occupationStatus)));
    }

    @Override public void checkConsistencyWithBlocksForCube(SectionPos sectionPos, LevelChunkSection levelChunkSection) {
        Util.ifElse(this.getOrLoad(sectionPos.asLong()), (poiSection) -> {
            poiSection.refresh((biConsumer) -> {
                if (mayHavePoi(levelChunkSection)) {
                    this.updateFromSection(levelChunkSection, sectionPos, biConsumer);
                }

            });
        }, () -> {
            if (mayHavePoi(levelChunkSection)) {
                PoiSection poiSection = this.getOrCreate(sectionPos.asLong());
                this.updateFromSection(levelChunkSection, sectionPos, poiSection::add);
            }
        });
    }
}
