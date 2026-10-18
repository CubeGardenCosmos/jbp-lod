package cn.cubegarden.cosmos.jbp.client.lod.model;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier;
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** Counts client resource reloads (F3+T, resource-pack changes); baked models are stale after one. */
final class ModelLifecycle {
    private static final AtomicLong RESOURCE_GENERATION = new AtomicLong();
    private static boolean registered;

    private ModelLifecycle() {
    }

    static synchronized void registerOnce() {
        if (registered) {
            return;
        }
        registered = true;
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new IdentifiableResourceReloadListener() {
                    private final Identifier id = Identifier.fromNamespaceAndPath("jbp-lod", "model_bakery");

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

    static long resourceGeneration() {
        return RESOURCE_GENERATION.get();
    }
}
