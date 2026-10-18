package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import cn.cubegarden.cosmos.jbp.client.lod.JbpLodClient;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.LodBoundingSectionStore;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.VulkanLodRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Records every built section Sodium visits into the near-zone bounding section list (upstream
 * {@code MixinVisibleChunkCollector}). The redirect keeps the region lookup unchanged.
 */
@Mixin(value = VisibleChunkCollector.class, remap = false)
public abstract class MixinVisibleChunkCollector {

    @Redirect(method = "visit", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;getForChunk(III)Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"),
            remap = false)
    private RenderRegion jbpLod$recordBuiltSection(RenderRegionManager instance, int x, int y, int z) {
        RenderRegion region = instance.getForChunk(x, y, z);
        if (VulkanLodRenderer.enabled() && JbpLodClient.activeRuntime().isPresent()
                && jbpLod$isBuilt(region, LocalSectionIndex.pack(x, y, z))) {
            LodBoundingSectionStore.instance().add(SectionPos.asLong(x, y, z));
        }
        return region;
    }

    @Unique
    private static boolean jbpLod$isBuilt(RenderRegion region, int localIndex) {
        return region != null && (region.getSectionFlags(localIndex) & RenderSectionFlags.MASK_IS_BUILT) != 0;
    }
}
