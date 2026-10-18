package cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin;

import cn.cubegarden.cosmos.jbp.client.lod.JbpLodClient;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.LodBoundingSectionStore;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.VulkanLodRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Starts every Sodium render-list build with an empty near-zone bounding section list (upstream
 * {@code MixinRenderSectionManager}); {@link MixinVisibleChunkCollector} fills it during the traversal.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinRenderSectionManager {

    @Inject(method = "renderOutOfGraph", at = @At("HEAD"))
    private void jbpLod$resetBoundingSections(CallbackInfo ci) {
        jbpLod$reset();
    }

    @Inject(method = "readRenderListFromTree", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/VisibleChunkCollector;<init>(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;I)V"))
    private void jbpLod$resetBoundingSectionsBeforeTraversal(CallbackInfo ci) {
        jbpLod$reset();
    }

    @Unique
    private static void jbpLod$reset() {
        if (VulkanLodRenderer.enabled() && JbpLodClient.activeRuntime().isPresent()) {
            LodBoundingSectionStore.instance().reset();
        }
    }
}
