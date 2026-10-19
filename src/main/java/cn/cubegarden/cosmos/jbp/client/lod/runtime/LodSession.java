package cn.cubegarden.cosmos.jbp.client.lod.runtime;

import cn.cubegarden.cosmos.jbp.client.lod.data.IdMapper;
import cn.cubegarden.cosmos.jbp.client.lod.data.ingest.VoxelHierarchyWriter;
import cn.cubegarden.cosmos.jbp.client.lod.importing.WorldImporter;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.LodNodeService;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.RenderDistanceTracker;
import cn.cubegarden.cosmos.jbp.client.lod.storage.LodStorageService;
import cn.cubegarden.cosmos.jbp.client.lod.storage.LodStorageSession;
import cn.cubegarden.cosmos.jbp.client.lod.storage.StoredSectionMeshProvider;

import java.io.IOException;
import net.minecraft.core.RegistryAccess;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelFactory;

/**
 * Scope-bound CPU side of JBP-LOD: the asynchronous storage service, the node tree worker and the stored
 * section source. It owns the {@link LodStorageSession} (and through it the V0 scope lock): closing the
 * session releases the scope, so {@link #closeAsync()} is the last step of teardown.
 */
public final class LodSession {
    /** Periodic-log counters; mesh results and ingest feed them from their own threads. */
    private final LodStats stats = new LodStats();
    private final ScheduledExecutorService statsLogger = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "JBP-LOD stats"); thread.setDaemon(true); return thread;
    });
    /** Upstream node capacity ({@code 1 << 21}); a power of two, below the 2^24 id space. */
    public static final int NODE_CAPACITY = 1 << 21;
    /** Section slots; bounded by the shared frame-buffer contract ({@code maxSections <= 200000}). */
    public static final int SECTION_CAPACITY = 200_000;
    /** Packed-quad heap size; overridable with {@code -Djbp.lod.geometryMiB}. */
    public static final long GEOMETRY_CAPACITY_BYTES = geometryCapacity();
    /** Upstream default {@code sectionRenderDistance}, in 512-block top-level sections. */
    public static final int DEFAULT_RENDER_DISTANCE_SECTIONS = 16;
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");
    private static final int TOP_LEVEL_OPERATIONS_PER_FRAME = 40;
    private static final AtomicLong GENERATIONS = new AtomicLong();

    private final long generation = GENERATIONS.incrementAndGet();
    private final String worldIdentifier;
    private final Path scopeDirectory;
    private final LodStorageService storage;
    private final LodNodeService nodes;
    private final StoredSectionMeshProvider sections;
    private final VoxelHierarchyWriter hierarchy;
    private volatile WorldImporter worldImporter;
    private final ExecutorService meshExecutor;
    private final RenderDistanceTracker distanceTracker;
    private final IdMapper mapper;
    private final ModelProvider models;
    private final cn.cubegarden.cosmos.jbp.client.lod.compute.SectionMesher mesher;
    private int renderDistanceSections = DEFAULT_RENDER_DISTANCE_SECTIONS;
    private boolean closing;
    private CompletableFuture<Void> ingestDrain = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> closingFuture;

    /**
     * Builds the session around an opened storage session; MUST run on the storage thread (it reads the
     * stored id mappings synchronously). {@code minSec}/{@code maxSec} are the world's top level Y sections
     * ({@code minSectionY >> 5}, {@code (maxSectionY - 1) >> 5}).
     */
    public LodSession(LodStorageSession storageSession, Executor ioExecutor, String worldIdentifier,
                      int minSec, int maxSec) throws IOException {
        this.worldIdentifier = worldIdentifier;
        this.scopeDirectory = storageSession.worldDirectory();
        this.storage = new LodStorageService(storageSession, ioExecutor);
        this.mapper = new IdMapper(new IdMapper.Persistence() {
            @Override
            public void putIdMapping(int key, byte[] encoded) {
                storage.putIdMapping(key, encoded).exceptionally(failure -> {
                    LOGGER.warn("[JBP-LOD] Could not persist id mapping {}.", key, failure);
                    return null;
                });
            }

            @Override
            public void flush() {
                storage.flush().exceptionally(failure -> {
                    LOGGER.warn("[JBP-LOD] Could not flush id mappings.", failure);
                    return null;
                });
            }
        }, storageSession.getIdMappingsData());
        this.models = new ModelProvider(mapper);
        for (IdMapper.BiomeEntry biome : mapper.getBiomeEntries()) {
            models.addBiome(biome.id, biome.biome);
        }
        mapper.setBiomeCallback(biome -> models.addBiome(biome.id, biome.biome));

        AtomicInteger meshThreads = new AtomicInteger();
        this.meshExecutor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "JBP-LOD Mesh " + meshThreads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.sections = new StoredSectionMeshProvider(storage, meshExecutor, stats::sectionMeshed);
        try {
            this.nodes = new LodNodeService(NODE_CAPACITY, SECTION_CAPACITY, GEOMETRY_CAPACITY_BYTES, sections);
        } catch (RuntimeException | Error failure) {
            meshExecutor.shutdownNow();
            throw failure;
        }
        this.sections.bind(nodes);
        this.hierarchy = new VoxelHierarchyWriter(new VoxelHierarchyWriter.Store() {
            @Override
            public byte[] get(long key) {
                return storage.getSectionData(key).join();
            }

            @Override
            public void put(long key, byte[] encoded) {
                storage.setSectionData(key, encoded).join();
            }
        }, nodes::onSectionUpdated, mapper::getBlockStateOpacity);
        this.mesher = new cn.cubegarden.cosmos.jbp.client.lod.compute.SectionMesher(models, storage);
        this.sections.setMesher(mesher);
        this.distanceTracker = new RenderDistanceTracker(TOP_LEVEL_OPERATIONS_PER_FRAME, minSec, maxSec,
                nodes::insertTopLevelNode, nodes::removeTopLevelNode);
        this.distanceTracker.setRenderDistance(renderDistanceSections + 1);//the +1 covers the outer ring
        this.statsLogger.scheduleAtFixedRate(this::logStats, 10, 10, TimeUnit.SECONDS);
    }

    /** Refreshes the snapshot fields from their owners and emits one {@code [JBP-LOD] stats:} line. */
    private void logStats() {
        try {
            stats.nodeSnapshot(nodes.nodeCountSnapshot(), nodes.geometrySectionCountSnapshot(),
                    nodes.requestCountSnapshot());
            stats.meshSnapshot(mesher.distinctRequestedCount(), mesher.waitingCount(), sections.inFlightCount());
            stats.geometrySnapshot(nodes.geometryUsedBytesSnapshot(), nodes.geometryCapacityBytesSnapshot(),
                    SECTION_CAPACITY);
            stats.queueSnapshot(nodes.queueDepthsSnapshot());
            stats.nodeDiagnosticsSnapshot(nodes.diagnosticsSnapshot());
            ModelFactory factory = models.factory();
            if (factory != null) {
                stats.modelSnapshot(factory.bakedCount(), factory.inflightCount());
            }
            LOGGER.info("[JBP-LOD] stats: {}", stats.format());
        } catch (RuntimeException failure) {
            //A throw would cancel every later scheduled run, so report and keep the schedule alive
            LOGGER.warn("[JBP-LOD] stats log failed", failure);
        }
    }

    private static long geometryCapacity() {
        long mib = 512;
        try {
            String configured = System.getProperty("jbp.lod.geometryMiB");
            if (configured != null) {
                mib = Math.max(64, Math.min(4096, Long.parseLong(configured.trim())));
            }
        } catch (SecurityException | NumberFormatException ignored) {
            //Keep the default
        }
        return mib << 20;
    }

    /** Unique per session; changes whenever the scope/world storage is replaced. */
    public long generation() {
        return generation;
    }

    public String worldIdentifier() {
        return worldIdentifier;
    }

    /** The world-specific storage directory under the locked scope. */
    public Path worldDirectory() {
        return scopeDirectory;
    }

    public LodStorageService storage() {
        return storage;
    }

    public LodNodeService nodes() {
        return nodes;
    }

    /** Shared serialized hierarchy writer used by live capture and the background world importer. */
    public VoxelHierarchyWriter hierarchyWriter() {
        return hierarchy;
    }

    /** The latest background world importer, or null when none was started. */
    public WorldImporter worldImporter() {
        return worldImporter;
    }

    /**
     * Starts importing the given dimension's region directory on dedicated daemon threads. Returns the
     * running importer when one is already active; otherwise the new importer. Safe to call from the game
     * thread: no file I/O or NBT decoding happens here.
     */
    public synchronized WorldImporter startWorldImport(Path regionDirectory, RegistryAccess registries) {
        if (closing) {
            throw new IllegalStateException("session is closing");
        }
        WorldImporter current = this.worldImporter;
        if (current != null && current.state() == WorldImporter.State.RUNNING) {
            return current;
        }
        WorldImporter importer = new WorldImporter(this, regionDirectory, registries);
        this.worldImporter = importer;
        importer.start();
        return importer;
    }

    /** Persistent block-state/biome id mapping (also the model bakery's block-state source). */
    public IdMapper mapper() {
        return mapper;
    }

    /** Per-scope model bakery lifecycle; its render-thread methods follow {@link ModelProvider}'s contract. */
    public ModelProvider models() {
        return models;
    }

    /** Counters behind the periodic {@code [JBP-LOD] stats:} log. */
    public LodStats stats() {
        return stats;
    }

    /** Stored-section source with the upstream CPU mesher installed. */
    public StoredSectionMeshProvider sections() {
        return sections;
    }

    public int renderDistanceSections() {
        return renderDistanceSections;
    }

    /** Render thread: changes the tracked top-level distance (sections of 512 blocks). */
    public void setRenderDistanceSections(int sections) {
        if (sections < 1) {
            throw new IllegalArgumentException("render distance must be at least one section");
        }
        this.renderDistanceSections = sections;
        this.distanceTracker.setRenderDistance(sections + 1);
    }

    /** Render thread: feeds the camera position to the top-level node tracker. */
    public boolean updateCamera(double x, double z) {
        mesher.retryReady(models.epoch(), sections::request);
        return distanceTracker.setCenterAndProcess(x, z);
    }

    /**
     * Stops producers and closes storage synchronously on the calling thread, which MUST be the I/O thread.
     * Used to release a session that became stale before it was published.
     */
    public synchronized void closeNow() throws java.io.IOException {
        closing = true;
        closeImporter();
        sections.close();
        nodes.close();
        meshExecutor.shutdownNow();
        statsLogger.shutdownNow();
        storage.closeNow();
    }

    /** Registers the stopped producer's drain before render-thread runtime teardown. */
    public synchronized void setIngestDrain(CompletableFuture<Void> drain) {
        if (closing) throw new IllegalStateException("session is closing");
        ingestDrain = java.util.Objects.requireNonNull(drain, "drain");
    }

    /**
     * Stops producers, then closes storage (flush, RocksDB, scope lock) on the I/O executor after queued
     * operations. Never blocks the caller; the returned future completes when the scope is released.
     */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing) {
            return closingFuture == null ? storage.closeAsync() : closingFuture;
        }
        closing = true;
        sections.close();
        meshExecutor.shutdownNow();
        statsLogger.shutdownNow();
        closingFuture = ingestDrain.handle((unused, failure) -> null).thenCompose(unused -> {
            closeImporter();
            nodes.close();
            return storage.closeAsync();
        });
        return closingFuture;
    }

    /**
     * Stops the importer without waiting on it. Its already-queued storage operations precede the close
     * operation on the single I/O executor; later writes fail with {@link java.util.concurrent.CancellationException}
     * and end the worker, so neither the render thread nor the I/O executor blocks on it.
     */
    private void closeImporter() {
        WorldImporter importer = this.worldImporter;
        if (importer != null) {
            importer.cancel();
        }
    }
}
