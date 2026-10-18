package cn.cubegarden.cosmos.jbp.client.lod.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts vanilla baked-model (and fluid) quads for one block state into per-view triangle geometry.
 * Port of the geometry half of upstream {@code bakery/SoftwareModelTextureBakery}: block models include every
 * directional quad group plus the null (unculled) group; fluids are tessellated through vanilla
 * {@link FluidRenderer} once per view with the same six-face air masking. Rasterization is done by
 * {@link VulkanModelBaker}.
 */
final class ModelGeometryBaker {
    private static final Direction[] BAKE_DIRECTIONS = {Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST, null};

    /** Raster flag bits returned with the geometry (upstream {@code renderToOutput} return value). */
    static final int FLAG_SHADED = 1;
    static final int FLAG_DARKENED = 2;
    static final int FLAG_TRANSLUCENT = 4;
    static final int FLAG_DISCARD = 8;

    record Geometry(VulkanModelBaker.ViewGeometry[] views, int rasterFlags) {
        /** True when no view has anything to rasterize, so all six faces keep upstream's zeroed output. */
        boolean isEmpty() {
            for (VulkanModelBaker.ViewGeometry view : views) {
                if (view != null) {
                    return false;
                }
            }
            return true;
        }
    }

    private final BakeVertexConsumer opaque = new BakeVertexConsumer();
    private final BakeVertexConsumer translucent = new BakeVertexConsumer(1/*has discard*/);
    private final FluidRenderer fluidRenderer;

    ModelGeometryBaker() {
        this.fluidRenderer = new FluidRenderer(Minecraft.getInstance().getModelManager().getFluidStateModelSet());
    }

    Geometry bake(BlockState state) {
        VulkanModelBaker.ViewGeometry[] views = new VulkanModelBaker.ViewGeometry[6];
        boolean anyShaded = false;
        boolean anyDarkened = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;

        if (!(state.getBlock() instanceof LiquidBlock)) {
            opaque.reset();
            translucent.reset();
            bakeBlockModel(state);
            anyShaded |= opaque.anyShaded | translucent.anyShaded;
            anyDarkened |= opaque.anyDarkendTex | translucent.anyDarkendTex;
            anyTranslucent |= !translucent.isEmpty();
            anyDiscard |= opaque.anyDiscard;
            if (!(opaque.isEmpty() && translucent.isEmpty())) {
                // One shared instance for all six views: the baker uploads identical instances once.
                VulkanModelBaker.ViewGeometry shared = triangulate();
                for (int view = 0; view < 6; view++) {
                    views[view] = shared;
                }
            }
        } else {
            for (int view = 0; view < 6; view++) {
                opaque.reset();
                translucent.reset();
                bakeFluidState(state, view);
                if (opaque.isEmpty() && translucent.isEmpty()) {
                    continue;
                }
                anyShaded |= opaque.anyShaded | translucent.anyShaded;
                anyDarkened |= opaque.anyDarkendTex | translucent.anyDarkendTex;
                anyTranslucent |= !translucent.isEmpty();
                anyDiscard |= opaque.anyDiscard;
                views[view] = triangulate();
            }
        }
        int flags = (anyShaded ? FLAG_SHADED : 0) | (anyDarkened ? FLAG_DARKENED : 0)
                | (anyTranslucent ? FLAG_TRANSLUCENT : 0) | (anyDiscard ? FLAG_DISCARD : 0);
        return new Geometry(views, flags);
    }

    private VulkanModelBaker.ViewGeometry triangulate() {
        int opaqueVertices = opaque.triangleVertexCount();
        int translucentVertices = translucent.triangleVertexCount();
        ByteBuffer bytes = ByteBuffer.allocate(
                Math.multiplyExact(opaqueVertices + translucentVertices, BakeVertexConsumer.VERTEX_SIZE))
                .order(ByteOrder.LITTLE_ENDIAN);
        opaque.appendTriangles(bytes);
        translucent.appendTriangles(bytes);
        return new VulkanModelBaker.ViewGeometry(bytes.array(), opaqueVertices, translucentVertices);
    }

    private void bakeBlockModel(BlockState state) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;
        }
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(new SingleThreadedRandomSource(42L), parts);
        boolean leaves = state.is(BlockTags.LEAVES);
        for (BlockStateModelPart part : parts) {
            for (Direction direction : BAKE_DIRECTIONS) {
                for (var quad : part.getQuads(direction)) {
                    (quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT ? translucent : opaque)
                            .quad(quad, leaves);
                }
            }
        }
    }

    private void bakeFluidState(BlockState state, int face) {
        fluidRenderer.tesselate(new BlockAndTintGetter() {
            @Override
            public LevelLightEngine getLightEngine() {
                return LevelLightEngine.EMPTY;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public CardinalLighting cardinalLighting() {
                return CardinalLighting.DEFAULT;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                // Called before the tinted quad is emitted: mark both consumers' subsequent quads as tinted.
                translucent.setDefaultMeta(translucent.getDefaultMeta() | 4);
                opaque.setDefaultMeta(opaque.getDefaultMeta() | 4);
                return -1;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }
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
        }, BlockPos.ZERO, layer -> {
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                return translucent;
            }
            if (layer == ChunkSectionLayer.CUTOUT) {
                opaque.setDefaultMeta(opaque.getDefaultMeta() | 1);
            } else {
                opaque.setDefaultMeta(opaque.getDefaultMeta() & ~1);
            }
            return opaque;
        }, state, state.getFluidState());
        translucent.setDefaultMeta(0);
        opaque.setDefaultMeta(0);
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var normal = Direction.from3DDataValue(face).getUnitVec3i();
        int dot = normal.getX() * pos.getX() + normal.getY() * pos.getY() + normal.getZ() * pos.getZ();
        return dot >= 1;
    }
}
