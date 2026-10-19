package cn.cubegarden.cosmos.jbp.client.lod.importing;

import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.PalettedContainerRO.PackedData;
import net.minecraft.world.level.chunk.Strategy;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Uniform biome container used when an imported section has no {@code biomes} payload.
 *
 * <p>Source reference: sealed MCRcortex/voxy 263 {@code commonImpl/importers/WorldImporter} default
 * provider. Only {@link #get(int, int, int)} is used by this port's voxelization; the remaining methods
 * mirror the upstream implementation so the type is a complete {@link PalettedContainerRO}.
 */
final class DefaultBiomeContainer implements PalettedContainerRO<Holder<Biome>> {
    private final Holder<Biome> biome;

    DefaultBiomeContainer(Holder<Biome> biome) {
        this.biome = biome;
    }

    @Override
    public Holder<Biome> get(int x, int y, int z) {
        return this.biome;
    }

    @Override
    public void getAll(Consumer<Holder<Biome>> action) {
        action.accept(this.biome);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
    }

    @Override
    public int getSerializedSize() {
        return 0;
    }

    @Override
    public int bitsPerEntry() {
        return 0;
    }

    @Override
    public boolean maybeHas(Predicate<Holder<Biome>> predicate) {
        return predicate.test(this.biome);
    }

    @Override
    public void forEachInPalette(Consumer<Holder<Biome>> consumer) {
        consumer.accept(this.biome);
    }

    @Override
    public void count(PalettedContainer.CountConsumer<Holder<Biome>> counter) {
        counter.accept(this.biome, 1);
    }

    @Override
    public PalettedContainer<Holder<Biome>> copy() {
        return null;
    }

    @Override
    public PalettedContainer<Holder<Biome>> recreate() {
        return null;
    }

    @Override
    public PackedData<Holder<Biome>> pack(Strategy<Holder<Biome>> provider) {
        return null;
    }
}
