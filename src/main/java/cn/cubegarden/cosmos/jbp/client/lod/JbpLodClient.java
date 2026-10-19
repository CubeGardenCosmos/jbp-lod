package cn.cubegarden.cosmos.jbp.client.lod;

import cn.cubegarden.cosmos.jbp.client.lod.data.ingest.LiveChunkCapture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelProvider;
import cn.cubegarden.cosmos.jbp.client.lod.runtime.LodRuntime;
import cn.cubegarden.cosmos.jbp.client.lod.vk.render.VulkanLodRenderer;
import cn.cubegarden.cosmos.jbp.client.lod.runtime.LodSession;
import cn.cubegarden.cosmos.jbp.client.lod.scope.LodPaths;
import cn.cubegarden.cosmos.jbp.client.lod.scope.LodScopeManager;
import cn.cubegarden.cosmos.jbp.client.lod.scope.ReflectiveNodeIdProvider;
import cn.cubegarden.cosmos.jbp.client.lod.storage.LodStorageSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle and storage-scope entrypoint. After a scope lock is acquired a {@link LodStorageSession} and
 * the node/section {@link LodSession} are opened, and the resulting {@link LodRuntime} is published for the
 * renderer. All scope, RocksDB and teardown I/O runs on one dedicated storage thread so neither the game
 * tick nor the render thread blocks on it; ordering on that thread guarantees a replaced session releases
 * its scope lock before the next one is acquired.
 */
public final class JbpLodClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");
    private static final ReflectiveNodeIdProvider NODE_ID_PROVIDER = new ReflectiveNodeIdProvider();
    private static final Set<LodScopeManager.Status> REPORTED_INACTIVE = EnumSet.noneOf(LodScopeManager.Status.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 15;
    /** Dev convenience: import the current single-player dimension at session open. */
    public static final String IMPORT_WORLD_PROPERTY = "jbp.lod.importWorld";
    private static final ExecutorService STORAGE_IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "JBP-LOD Storage IO");
        thread.setDaemon(true);
        return thread;
    });

    /** Guards {@link #activeRuntime} and {@link #startToken}. */
    private static final Object STATE = new Object();
    private static LodRuntime activeRuntime;
    private static long startToken;
    private static ClientLevel sessionLevel;
    private static CompletableFuture<Void> pendingRelease = CompletableFuture.completedFuture(null);

    /**
     * The runtime of the active scope, once its storage session has opened; empty while inactive, while the
     * scope is still opening, and after teardown began. Render-thread callers must not retain it past the
     * frame in which they obtained it unless they also compare {@link LodRuntime#generation()}.
     */
    public static Optional<LodRuntime> activeRuntime() {
        synchronized (STATE) {
            return Optional.ofNullable(activeRuntime);
        }
    }

    private static final int DEV_QUIT_TICKS = Integer.getInteger("jbp.lod.devQuitAfterTicks", 0);
    private static int devTicks;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[JBP-LOD] Initialized client scope + storage session support (Minecraft 26.3 / Java 25).");
        ModelProvider.registerLifecycle();
        VulkanLodRenderer.registerLifecycle();
        LiveChunkCapture.register();
        LodCommands.register();
        DevScript.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != sessionLevel) {
                if (client.level == null) stopSession("world unloaded");
                else startSession(client);
            }
            LiveChunkCapture.tick(client.level, activeRuntime().orElse(null));
            // Dev smoke automation: -Djbp.lod.devQuitAfterTicks=N stops the client N ticks after the world loads.
            if (client.level != null && DEV_QUIT_TICKS > 0 && ++devTicks == DEV_QUIT_TICKS) {
                LOGGER.info("[JBP-LOD] devQuitAfterTicks reached; stopping the client.");
                client.stop();
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> startSession(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stopSession("server disconnect"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopSession("client shutdown");
            awaitStorageShutdown();
        });
    }

    private record StartRequest(boolean singleplayer, boolean developmentEnvironment, String developmentScopeId,
                                String developmentNodeId, String worldId, String nodeId, String configuredBase,
                                Path gameDirectory, String dimension, int minSection, int maxSection,
                                Path importRegionDirectory, RegistryAccess importRegistries) {
    }

    private static void startSession(Minecraft client) {
        sessionLevel = client.level;
        long token;
        synchronized (STATE) {
            token = ++startToken;
        }
        closeActiveRuntime("session replacement");

        boolean developmentEnvironment = FabricLoader.getInstance().isDevelopmentEnvironment()
                || "true".equalsIgnoreCase(String.valueOf(systemProperty("jbp.lod.devBuild")).trim());
        var integratedServer = client.getSingleplayerServer();
        boolean singleplayer = integratedServer != null;
        String developmentScopeId = developmentEnvironment
                ? systemProperty(LodPaths.DEVELOPMENT_SCOPE_PROPERTY) : null;
        String developmentNodeId = developmentEnvironment
                ? systemProperty(LodPaths.DEVELOPMENT_NODE_PROPERTY) : null;
        String worldId = null;
        String nodeId = null;
        if (singleplayer) {
            if (developmentEnvironment && developmentScopeId != null
                    && "true".equalsIgnoreCase(developmentScopeId.trim())) {
                worldId = LodPaths.worldFolderId(integratedServer.getWorldPath(LevelResource.ROOT));
            }
        } else {
            String providerNodeId = developmentNodeId == null ? NODE_ID_PROVIDER.currentNodeId() : null;
            nodeId = LodScopeManager.selectNodeId(developmentEnvironment, developmentNodeId, providerNodeId);
        }

        //World/dimension facts are game-thread state; capture them before leaving the game thread.
        String dimension = "default";
        int minSection = -1;
        int maxSection = 0;
        if (client.level != null) {
            dimension = client.level.dimension().identifier().toString();
            minSection = client.level.getMinSectionY() >> 5;
            maxSection = (client.level.getMaxSectionY() - 1) >> 5;
        }

        //Auto-import is opt-in and resolved on the game thread; the importer does all I/O on its own threads.
        Path importRegionDirectory = null;
        RegistryAccess importRegistries = null;
        boolean importWorld = "true".equalsIgnoreCase(
                String.valueOf(systemProperty(IMPORT_WORLD_PROPERTY)).trim());
        if (importWorld && singleplayer && client.level != null) {
            importRegionDirectory = DimensionType.getStorageFolder(client.level.dimension(),
                    integratedServer.getWorldPath(LevelResource.ROOT)).resolve("region");
            importRegistries = client.level.registryAccess();
        }

        StartRequest request = new StartRequest(singleplayer, developmentEnvironment, developmentScopeId,
                developmentNodeId, worldId, nodeId, configuredBase(), FabricLoader.getInstance().getGameDir(),
                dimension, minSection, maxSection, importRegionDirectory, importRegistries);
        pendingRelease.handle((ignored, failure) -> null)
                .thenRun(() -> STORAGE_IO.execute(() -> openOnStorageThread(request, token)));
    }

    /** Runs on the storage thread: scope lock, RocksDB open, node/section session, publication. */
    private static void openOnStorageThread(StartRequest request, long token) {
        if (!isCurrent(token)) {
            return;
        }
        LodScopeManager.StartResult result;
        try {
            result = LodScopeManager.start(request.singleplayer(), request.developmentEnvironment(),
                    request.developmentScopeId(), request.worldId(), request.nodeId(), request.configuredBase(),
                    request.gameDirectory());
        } catch (RuntimeException failure) {
            LOGGER.warn("[JBP-LOD] Scope start failed.", failure);
            return;
        }
        if (!result.active()) {
            reportInactive(result);
            return;
        }
        if (result.developmentScope()) {
            LOGGER.warn("[JBP-LOD] DEV singleplayer scope active at {} (exclusive lock acquired).",
                    result.scopeDirectory());
        } else {
            if (request.developmentEnvironment() && request.developmentNodeId() != null) {
                LOGGER.warn("[JBP-LOD] DEV multiplayer node override active for {}.", result.scopeDirectory());
            }
            LOGGER.info("[JBP-LOD] Locked node scope {}.", result.scopeDirectory());
        }

        LodStorageSession storageSession;
        try {
            //LodStorageSession.open owns the scope from here and releases it itself if opening fails
            storageSession = LodStorageSession.open(result.session(), request.dimension());
        } catch (IOException | RuntimeException | LinkageError failure) {
            LOGGER.warn("[JBP-LOD] Could not open the LOD storage session for {}; scope released.",
                    result.scopeDirectory(), failure);
            return;
        }
        if (!isCurrent(token)) {
            closeStaleStorage(storageSession);
            return;
        }

        LodSession session;
        try {
            session = new LodSession(storageSession, STORAGE_IO, request.dimension(), request.minSection(),
                    request.maxSection());
        } catch (IOException | RuntimeException | Error failure) {
            LOGGER.warn("[JBP-LOD] Could not create the LOD node session; releasing scope.", failure);
            closeStaleStorage(storageSession);
            return;
        }
        LodRuntime runtime = new LodRuntime(session);
        boolean published;
        synchronized (STATE) {
            published = startToken == token;
            if (published) {
                activeRuntime = runtime;
            }
        }
        if (!published) {
            try {
                session.closeNow();
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("[JBP-LOD] Failed to release a superseded LOD session.", failure);
            }
            return;
        }
        LOGGER.info("[JBP-LOD] LOD storage session {} open for world '{}' at {}.", session.generation(),
                request.dimension(), session.worldDirectory());
        if (request.importRegionDirectory() != null && request.importRegistries() != null) {
            try {
                session.startWorldImport(request.importRegionDirectory(), request.importRegistries());
            } catch (RuntimeException failure) {
                LOGGER.warn("[JBP-LOD] Could not start the automatic world import.", failure);
            }
        }
    }

    private static void closeStaleStorage(LodStorageSession storageSession) {
        try {
            storageSession.close();
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[JBP-LOD] Failed to release a superseded storage session.", failure);
        }
    }

    private static boolean isCurrent(long token) {
        synchronized (STATE) {
            return startToken == token;
        }
    }

    private static void stopSession(String reason) {
        sessionLevel = null;
        LiveChunkCapture.clear();
        synchronized (STATE) {
            startToken++;//Invalidates any scope/session still opening on the storage thread
        }
        closeActiveRuntime(reason);
    }

    /** Game thread: queues GPU retirement, then storage close + scope release on the storage thread. */
    private static void closeActiveRuntime(String reason) {
        CompletableFuture<Void> drained = LiveChunkCapture.detach();
        LodRuntime previous;
        synchronized (STATE) {
            previous = activeRuntime;
            activeRuntime = null;
        }
        if (previous == null) {
            return;
        }
        previous.session().setIngestDrain(drained);
        CompletableFuture<Void> released = previous.close();
        // Handled per release so one failure cannot complete the barrier before the others have finished.
        pendingRelease = CompletableFuture.allOf(pendingRelease, released.handle((ignored, failure) -> null));
        released.whenComplete((ignored, failure) -> {
            if (failure != null) {
                LOGGER.warn("[JBP-LOD] Failed to release scope on {}.", reason, failure);
            } else {
                LOGGER.info("[JBP-LOD] Released scope on {}.", reason);
            }
        });
    }

    private static void awaitStorageShutdown() {
        try {
            pendingRelease.get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            // Storage I/O must keep running: shutting it down would reject the queued close and leave the
            // RocksDB handle and scope lock held.
            LOGGER.warn("[JBP-LOD] Scope release did not complete within {}s at shutdown; leaving storage thread running so it can finish.", SHUTDOWN_WAIT_SECONDS);
            return;
        } catch (java.util.concurrent.ExecutionException failure) {
            LOGGER.warn("[JBP-LOD] Scope release failed at shutdown.", failure);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        STORAGE_IO.shutdown();
        try {
            if (!STORAGE_IO.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("[JBP-LOD] Storage thread did not finish within {}s at shutdown.", SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reportInactive(LodScopeManager.StartResult result) {
        synchronized (REPORTED_INACTIVE) {
            if (!REPORTED_INACTIVE.add(result.status())) {
                return;
            }
        }
        switch (result.status()) {
            case INACTIVE_SINGLEPLAYER ->
                    LOGGER.info("[JBP-LOD] Inactive: singleplayer scopes require an enabled development environment.");
            case INACTIVE_DEV_SCOPE_UNKNOWN ->
                    LOGGER.info("[JBP-LOD] Inactive: dev singleplayer scope has no world folder or custom ID.");
            case INACTIVE_NODE_UNKNOWN -> LOGGER.info("[JBP-LOD] Inactive: the JBP node ID is missing or blank.");
            case INVALID_DEV_SCOPE -> LOGGER.warn("[JBP-LOD] Inactive: the dev scope ID cannot form a safe directory.");
            case INVALID_NODE_ID -> LOGGER.warn("[JBP-LOD] Inactive: the JBP node ID cannot form a safe node directory.");
            case LOCK_CONFLICT -> LOGGER.warn("[JBP-LOD] Inactive: another JBP-LOD instance holds the scope lock {}.",
                    result.scopeDirectory() == null ? "(scope unavailable)" : result.scopeDirectory().resolve(LodScopeManager.LOCK_FILE_NAME));
            case IO_FAILURE -> LOGGER.warn("[JBP-LOD] Inactive: could not prepare or lock the scope directory.", result.failure());
            case ACTIVE -> throw new IllegalStateException("active scope must not be reported inactive");
        }
    }

    private static String configuredBase() {
        return systemProperty(LodPaths.BASE_DIRECTORY_PROPERTY);
    }

    private static String systemProperty(String name) {
        try {
            return System.getProperty(name);
        } catch (SecurityException e) {
            return null;
        }
    }
}
