package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier;
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks scope and resource generations that invalidate V4's device/target-specific resources. */
final class LodRenderLifecycle {
    private static final AtomicLong SCOPE_GENERATION = new AtomicLong();
    private static final AtomicLong RESOURCE_GENERATION = new AtomicLong();
    private static boolean registered;

    private LodRenderLifecycle() {
    }

    static synchronized void registerOnce() {
        if (registered) {
            return;
        }
        registered = true;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SCOPE_GENERATION.incrementAndGet());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SCOPE_GENERATION.incrementAndGet());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            SCOPE_GENERATION.incrementAndGet();
            VulkanLodRenderer.shutdown();
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {
            private final Identifier id = Identifier.fromNamespaceAndPath("jbp-lod", "vulkan_lod_resources");

            @Override
            public Identifier getFabricId() {
                return id;
            }

            @Override
            public CompletableFuture<Void> reload(SharedState sharedState, Executor backgroundExecutor,
                                                  PreparationBarrier barrier, Executor gameExecutor) {
                return barrier.wait(null).thenRunAsync(RESOURCE_GENERATION::incrementAndGet, gameExecutor);
            }
        });
    }

    static long scopeGeneration() {
        return SCOPE_GENERATION.get();
    }

    static long resourceGeneration() {
        return RESOURCE_GENERATION.get();
    }
}
