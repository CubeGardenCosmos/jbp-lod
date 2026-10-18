package cn.cubegarden.cosmos.jbp.client.lod.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Vanilla {@link BlockTintSource} capture (upstream {@code ModelFactory} tint helpers). */
final class ModelTinting {
    private ModelTinting() {
    }

    /** Tint sources for a state, or null when it is untinted. Pure fluids use the fluid model's source. */
    static @Nullable List<BlockTintSource> tintSources(BlockState block) {
        if (block.getBlock() instanceof LiquidBlock) {
            var tintSource = Minecraft.getInstance().getModelManager().getFluidStateModelSet()
                    .get(block.getFluidState()).tintSource();
            return tintSource == null ? null : List.of(tintSource);
        }
        var tints = Minecraft.getInstance().getBlockColors().getTintSources(block);
        return tints.isEmpty() ? null : tints;
    }

    /** Colour in the given biome (opaque bit not applied; -1 when no source produces a colour). */
    static int colourInBiome(List<BlockTintSource> tintSources, BlockState state, Biome biome) {
        BlockAndTintGetter getter = new TintGetter(state, biome, null);
        for (BlockTintSource source : tintSources) {
            if (source != null) {
                int colour = source.colorInWorld(state, getter, BlockPos.ZERO);
                if (colour != -1) {
                    return colour;
                }
            }
        }
        return -1;
    }

    /** True when any source calls {@code getBlockTint} (a biome {@link ColorResolver}). */
    static boolean isBiomeDependent(List<BlockTintSource> tintSources, BlockState state) {
        boolean[] biomeDependent = new boolean[1];
        BlockAndTintGetter getter = new TintGetter(state, null, biomeDependent);
        for (BlockTintSource source : tintSources) {
            if (source != null) {
                source.colorInWorld(state, getter, BlockPos.ZERO);
            }
        }
        return biomeDependent[0];
    }
    private static final class TintGetter implements BlockAndTintGetter {
        private final BlockState state;
        private final @Nullable Biome biome;
        private final boolean @Nullable [] biomeDependentOut;

        private TintGetter(BlockState state, @Nullable Biome biome, boolean @Nullable [] biomeDependentOut) {
            this.state = state;
            this.biome = biome;
            this.biomeDependentOut = biomeDependentOut;
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            if (biomeDependentOut != null) {
                biomeDependentOut[0] = true;
                return 0;
            }
            return colorResolver.getColor(biome, 0, 0);
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return state;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return state.getFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    }
}
