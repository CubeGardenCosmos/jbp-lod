package cn.cubegarden.cosmos.jbp.client.lod.importing;

import cn.cubegarden.cosmos.jbp.client.lod.data.IdMapper;
import cn.cubegarden.cosmos.jbp.client.lod.data.ingest.VoxelHierarchyWriter;
import cn.cubegarden.cosmos.jbp.client.lod.runtime.LodSession;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background importer for explored-but-unloaded terrain, ported from sealed Voxy {@code WorldImporter}.
 *
 * <p>Live capture only sees chunks the vanilla server loads, so terrain the player explored earlier can
 * never enter the LoD store in a later session. This importer reads the current dimension's Anvil region
 * files and feeds the same stored hierarchy as live capture.
 *
 * <p>Threading: all directory listing, file I/O, NBT decoding, voxelization and hierarchy writes run on
 * this object's daemon threads. The trigger only captures the region directory and registry access on the
 * game thread. Memory is bounded by a {@value #QUEUE_CAPACITY}-payload queue, two workers and
 * {@value #MAX_PAYLOAD_BYTES} bytes per compressed chunk (larger chunks are skipped and counted).
 *
 * <p>The hierarchy writer is shared with live capture and serializes each section's read-modify-write, so
 * imported octants and live octants cannot overwrite each other.
 */
public final class WorldImporter implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");
    /** Payloads waiting for a worker; 16 * 4 MiB is the hard compressed-data bound. */
    public static final int QUEUE_CAPACITY = 16;
    public static final int WORKER_COUNT = 2;
    public static final int MAX_PAYLOAD_BYTES = 4 << 20;
    private static final byte[] EMPTY = new byte[0];
    private static final int MAX_LOGGED_ERRORS = 20;

    public enum State {
        RUNNING, COMPLETED, CANCELLED, FAILED
    }

    private final LodSession session;
    private final Path regionDirectory;
    private final net.minecraft.core.RegistryAccess registries;
    private final BlockingQueue<RegionChunkReader.ChunkPayload> queue =
            new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicLong chunksRead = new AtomicLong();
    private final AtomicLong chunksImported = new AtomicLong();
    private final AtomicLong chunksSkipped = new AtomicLong();
    private final AtomicLong sectionsWritten = new AtomicLong();
    private final AtomicLong regionsRead = new AtomicLong();
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicInteger regions = new AtomicInteger();

    private volatile State state = State.RUNNING;
    private volatile boolean cancelled;
    private volatile boolean producerDone;
    private volatile String failure;
    private Thread coordinator;
    private Thread[] workers = new Thread[0];

    public WorldImporter(LodSession session, Path regionDirectory,
                         net.minecraft.core.RegistryAccess registries) {
        this.session = session;
        this.regionDirectory = regionDirectory;
        this.registries = registries;
    }

    /** Starts the import once. Returns false when this importer is already running. */
    public synchronized boolean start() {
        if (state == State.RUNNING && coordinator != null) {
            return false;
        }
        state = State.RUNNING;
        cancelled = false;
        producerDone = false;
        failure = null;
        coordinator = new Thread(this::run, "JBP-LOD World Import");
        coordinator.setDaemon(true);
        coordinator.start();
        return true;
    }

    /** Requests cancellation; background threads stop between chunks and drain. Never blocks. */
    public void cancel() {
        cancelled = true;
    }

    public State state() {
        return state;
    }

    public String failure() {
        return failure;
    }

    public String progress() {
        return "regions=" + regionsRead.get() + "/" + regions.get()
                + " chunks=" + chunksImported.get() + "/" + chunksRead.get()
                + " skipped=" + chunksSkipped.get() + " sections=" + sectionsWritten.get()
                + " errors=" + errors.get();
    }

    /** Waits for the importer threads; returns false when they are still alive after the timeout. */
    public boolean awaitTermination(long millis) {
        Thread coordinator = this.coordinator;
        Thread[] workers = this.workers;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
        try {
            if (coordinator != null) {
                long wait = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                coordinator.join(wait);
            }
            for (Thread worker : workers) {
                long wait = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                worker.join(wait);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (coordinator != null && coordinator.isAlive()) {
            return false;
        }
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        cancel();
        if (!awaitTermination(5_000L)) {
            LOGGER.warn("[JBP-LOD] World import did not stop within 5 s; waiting for the storage close.");
        }
    }

    //==================================================================================================================

    private void run() {
        long startedAt = System.nanoTime();
        try {
            ImportContext context = createContext();
            List<Path> files = listRegionFiles();
            regions.set(files.size());
            if (files.isEmpty()) {
                LOGGER.warn("[JBP-LOD] World import found no region files in {}.", this.regionDirectory);
            } else {
                LOGGER.info("[JBP-LOD] World import started: {} region file(s) in {} (queue={}, workers={}).",
                        files.size(), this.regionDirectory, QUEUE_CAPACITY, WORKER_COUNT);
            }
            startWorkers(context);
            long lastLog = System.nanoTime();
            for (Path file : files) {
                if (this.cancelled) {
                    break;
                }
                importRegion(file);
                regionsRead.incrementAndGet();
                long now = System.nanoTime();
                if (now - lastLog >= TimeUnit.SECONDS.toNanos(5)) {
                    lastLog = now;
                    LOGGER.info("[JBP-LOD] World import progress: {}", progress());
                }
            }
        } catch (Throwable failure) {
            this.failure = failure.toString();
            state = State.FAILED;
            LOGGER.warn("[JBP-LOD] World import failed: {}", failure.toString(), failure);
        } finally {
            producerDone = true;
            joinWorkers();
            if (state == State.RUNNING) {
                state = this.cancelled ? State.CANCELLED : State.COMPLETED;
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            LOGGER.info("[JBP-LOD] World import {}: {} elapsed={} ms", state, progress(), elapsedMillis);
        }
    }

    private ImportContext createContext() {
        var biomeRegistry = this.registries.lookupOrThrow(Registries.BIOME);
        Holder<Biome> plains = biomeRegistry.getOrThrow(Biomes.PLAINS);
        PalettedContainerFactory factory = PalettedContainerFactory.create(this.registries);
        return new ImportContext(
                factory.blockStatesContainerCodec(),
                factory.biomeContainerCodec(),
                new DefaultBiomeContainer(plains),
                this.session.mapper(),
                this.session.hierarchyWriter());
    }

    private List<Path> listRegionFiles() throws IOException {
        if (!Files.isDirectory(this.regionDirectory)) {
            throw new IOException("Region directory does not exist: " + this.regionDirectory);
        }
        try (var stream = Files.list(this.regionDirectory)) {
            List<Path> files = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(path -> RegionFileLayout.isRegionFile(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
            return files;
        }
    }

    private void startWorkers(ImportContext context) {
        Thread[] started = new Thread[WORKER_COUNT];
        for (int i = 0; i < started.length; i++) {
            started[i] = new Thread(() -> work(context), "JBP-LOD World Import " + (i + 1));
            started[i].setDaemon(true);
            started[i].start();
        }
        this.workers = started;
    }

    private void importRegion(Path file) {
        int[] coordinates = RegionFileLayout.regionCoordinates(file.getFileName().toString());
        if (coordinates == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            byte[] header = RegionChunkReader.readHeader(channel, size);
            if (header == null) {
                return;
            }
            for (RegionFileLayout.ChunkLocation location
                    : RegionFileLayout.parseHeader(header, coordinates[0], coordinates[1])) {
                if (this.cancelled) {
                    return;
                }
                RegionChunkReader.ChunkPayload payload;
                try {
                    payload = RegionChunkReader.readPayload(channel, size, location, MAX_PAYLOAD_BYTES);
                } catch (IOException readFailure) {
                    report("chunk read", file.getFileName() + " " + location, readFailure);
                    chunksSkipped.incrementAndGet();
                    continue;
                }
                if (payload == null) {
                    chunksSkipped.incrementAndGet();
                    continue;
                }
                chunksRead.incrementAndGet();
                enqueue(payload);
            }
        } catch (IOException failure) {
            report("region", file.getFileName().toString(), failure);
        }
    }

    private void enqueue(RegionChunkReader.ChunkPayload payload) {
        while (!this.cancelled) {
            try {
                if (this.queue.offer(payload, 200, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void work(ImportContext context) {
        while (!this.cancelled) {
            RegionChunkReader.ChunkPayload payload;
            try {
                payload = this.queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (payload == null) {
                if (this.producerDone && this.queue.isEmpty()) {
                    return;
                }
                continue;
            }
            try {
                importPayload(payload, context);
            } catch (Exception failure) {
                report("chunk import", "", failure);
            }
        }
    }

    private void importPayload(RegionChunkReader.ChunkPayload payload, ImportContext context) throws IOException {
        RegionFileVersion version = RegionFileVersion.fromId(payload.compression());
        if (version == null) {
            chunksSkipped.incrementAndGet();
            return;
        }
        CompoundTag chunk;
        try (DataInputStream in = new DataInputStream(version.wrap(new ByteArrayInputStream(payload.data())))) {
            chunk = NbtIo.read(in);
        }
        if (chunk == null) {
            chunksSkipped.incrementAndGet();
            return;
        }
        Optional<Integer> dataVersion = chunk.getInt("DataVersion");
        if (dataVersion.isEmpty()) {
            chunksSkipped.incrementAndGet();
            return;
        }
        int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        if (dataVersion.get() != currentVersion) {
            Optional<CompoundTag> upgraded = DataFixers.getDataFixer()
                    .update(References.CHUNK, new Dynamic<>(NbtOps.INSTANCE, chunk), dataVersion.get(),
                            currentVersion)
                    .getValue().asCompound();
            if (upgraded.isEmpty()) {
                chunksSkipped.incrementAndGet();
                return;
            }
            chunk = upgraded.get();
        }
        if (!chunk.contains("Status")) {
            chunksSkipped.incrementAndGet();
            return;
        }
        ChunkStatus status = ChunkStatus.byName(chunk.getStringOr("Status", null));
        if (status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) {
            chunksSkipped.incrementAndGet();
            return;
        }
        int chunkX = chunk.getIntOr("xPos", Integer.MIN_VALUE);
        int chunkZ = chunk.getIntOr("zPos", Integer.MIN_VALUE);
        if (chunkX == Integer.MIN_VALUE || chunkZ == Integer.MIN_VALUE) {
            chunksSkipped.incrementAndGet();
            return;
        }
        boolean imported = false;
        for (var element : chunk.getList("sections").orElseThrow()) {
            CompoundTag section = (CompoundTag) element;
            int sectionY = section.getIntOr("Y", Integer.MIN_VALUE);
            if (sectionY == Integer.MIN_VALUE) {
                continue;
            }
            imported |= importSection(context, chunkX, sectionY, chunkZ, section);
        }
        if (imported) {
            chunksImported.incrementAndGet();
        } else {
            chunksSkipped.incrementAndGet();
        }
    }

    private boolean importSection(ImportContext context, int chunkX, int sectionY, int chunkZ,
                                  CompoundTag section) {
        Optional<CompoundTag> blockStatesTag = section.getCompound("block_states");
        if (blockStatesTag.isEmpty()) {
            return false;
        }
        byte[] blockLightData = section.getByteArray("BlockLight").orElse(EMPTY);
        byte[] skyLightData = section.getByteArray("SkyLight").orElse(EMPTY);
        DataLayer blockLight = blockLightData.length != 0 ? new DataLayer(blockLightData) : null;
        DataLayer skyLight = skyLightData.length != 0 ? new DataLayer(skyLightData) : null;

        var blockStatesResult = context.blockStateCodec().parse(NbtOps.INSTANCE, blockStatesTag.get());
        if (!blockStatesResult.hasResultOrPartial()) {
            return false;
        }
        PalettedContainer<BlockState> blockStates = blockStatesResult.getPartialOrThrow();
        PalettedContainerRO<Holder<Biome>> biomes = context.defaultBiomes();
        Optional<CompoundTag> biomeTag = section.getCompound("biomes");
        if (biomeTag.isPresent()) {
            biomes = context.biomeCodec().parse(NbtOps.INSTANCE, biomeTag.get()).result()
                    .orElse(context.defaultBiomes());
        }

        long[] words = new long[4096];
        IdMapper mapper = context.mapper();
        for (int index = 0; index < words.length; index++) {
            int x = index & 15;
            int z = (index >> 4) & 15;
            int y = index >> 8;
            int illumination = (skyLight == null ? 0 : Math.min(15, skyLight.get(x, y, z)))
                    | ((blockLight == null ? 0 : Math.min(15, blockLight.get(x, y, z))) << 4);
            words[index] = mapper.getBaseId((byte) illumination, blockStates.get(x, y, z),
                    biomes.get(x >> 2, y >> 2, z >> 2));
        }
        context.hierarchy().write(chunkX, sectionY, chunkZ, words);
        sectionsWritten.incrementAndGet();
        return true;
    }

    private void joinWorkers() {
        for (Thread worker : this.workers) {
            try {
                worker.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void report(String what, String where, Throwable failure) {
        int count = errors.incrementAndGet();
        if (count <= MAX_LOGGED_ERRORS) {
            LOGGER.warn("[JBP-LOD] World import {} failed{}: {}", what,
                    where.isEmpty() ? "" : " (" + where + ")", failure.toString());
        } else if (count == MAX_LOGGED_ERRORS + 1) {
            LOGGER.warn("[JBP-LOD] World import: further errors are counted but not logged.");
        }
    }

    private record ImportContext(Codec<PalettedContainer<BlockState>> blockStateCodec,
                                 Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
                                 PalettedContainerRO<Holder<Biome>> defaultBiomes,
                                 IdMapper mapper, VoxelHierarchyWriter hierarchy) {
    }
}
