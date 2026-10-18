package cn.cubegarden.cosmos.jbp.client.lod.data;

import cn.cubegarden.cosmos.jbp.client.lod.model.ModelStateSource;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Persistent block-state and biome id mapping (independent mappings combined in the section data mapping
 * id: light {@code <<56}, biome {@code <<47}, block {@code <<27}).
 *
 * <p>Source reference: sealed MCRcortex/voxy 263 {@code common/world/other/Mapper.java}. Mapping-id bit
 * layout, id assignment order, NBT entry encoding ({@code id}, {@code block_state}, {@code version} /
 * {@code biome_id}), data-fixer migration, leaves opacity override and forced-resave behavior are retained.
 * Differences: storage access goes through the caller-supplied persistence callbacks (writes are issued
 * asynchronously by the session; the initial mappings are loaded once on the storage thread) instead of a
 * blocking {@code IMappingStorage}.
 */
public final class IdMapper implements ModelStateSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;

    public static final long UNKNOWN_MAPPING = -1;
    public static final long AIR = 0;

    /** Persists one encoded mapping entry under the upstream key ({@code id | type<<30}). */
    public interface Persistence {
        void putIdMapping(int key, byte[] encoded);

        void flush();
    }

    private final Persistence storage;
    private final ReentrantLock blockLock = new ReentrantLock();
    private final ConcurrentHashMap<BlockState, StateEntry> block2stateEntry = new ConcurrentHashMap<>(2000, 0.75f, 10);
    private final ObjectArrayList<StateEntry> blockId2stateEntry = new ObjectArrayList<>();

    private final ReentrantLock biomeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, BiomeEntry> biome2biomeEntry = new ConcurrentHashMap<>(2000, 0.75f, 10);
    private final ObjectArrayList<BiomeEntry> biomeId2biomeEntry = new ObjectArrayList<>();

    private volatile Consumer<StateEntry> newStateCallback;
    private volatile Consumer<BiomeEntry> newBiomeCallback;

    /**
     * @param storedMappings the mappings read from storage (key {@code id | type<<30} to encoded entry)
     */
    public IdMapper(Persistence storage, Map<Integer, byte[]> storedMappings) {
        this.storage = storage;
        //Insert air since its a special entry (index 0)
        var airEntry = new StateEntry(0, Blocks.AIR.defaultBlockState());
        this.block2stateEntry.put(airEntry.state, airEntry);
        this.blockId2stateEntry.add(airEntry);

        this.load(storedMappings);
    }

    public static boolean isAir(long id) {
        //Note: air can mean void, cave or normal air, as the block state is remapped during ingesting
        return (id & (((1L << 20) - 1) << 27)) == 0;
    }

    public static int isNotAirInt(long id) {
        return Math.min(getBlockId(id), 1);
    }

    public static int getBlockId(long id) {
        return (int) ((id >> 27) & ((1 << 20) - 1));
    }

    public static int getBiomeId(long id) {
        return (int) ((id >> 47) & 0x1FF);
    }

    public static int getLightId(long id) {
        return (int) ((id >> 56) & 0xFF);
    }

    public static long withLight(long id, int light) {
        return (id & (~(0xFFL << 56))) | (Integer.toUnsignedLong(light & 0xFF) << 56);
    }

    public static long withBlockBiome(long id, int block, int biome) {
        return (id & (0xFFL << 56)) | (Integer.toUnsignedLong(block) << 27) | (Integer.toUnsignedLong(biome) << 47);
    }

    public static long withBlock(long id, int block) {
        return (id & ((0xFFL << 56) | (0x1FFL << 47))) | (Integer.toUnsignedLong(block) << 27);
    }

    public static long airWithLight(int light) {
        return Integer.toUnsignedLong(light & 0xFF) << 56;
    }

    public static long composeMappingId(byte light, int blockId, int biomeId) {
        if (blockId == AIR) {//Dont care about biome for air
            return Byte.toUnsignedLong(light) << 56;
        }
        return (Byte.toUnsignedLong(light) << 56) | (Integer.toUnsignedLong(biomeId) << 47)
                | (Integer.toUnsignedLong(blockId) << 27);
    }

    public void setStateCallback(Consumer<StateEntry> stateCallback) {
        this.newStateCallback = stateCallback;
    }

    public void setBiomeCallback(Consumer<BiomeEntry> biomeCallback) {
        this.newBiomeCallback = biomeCallback;
    }

    private void load(Map<Integer, byte[]> mappings) {
        List<StateEntry> sentries = new ArrayList<>();
        List<BiomeEntry> bentries = new ArrayList<>();
        List<Integer> sentryErrors = new ArrayList<>();

        boolean[] forceResave = new boolean[1];
        for (Map.Entry<Integer, byte[]> entry : mappings.entrySet()) {
            int entryType = entry.getKey() >>> 30;
            int id = entry.getKey() & ((1 << 30) - 1);
            if (entryType == BLOCK_STATE_TYPE) {
                var sentry = StateEntry.deserialize(id, entry.getValue(), forceResave);
                if (sentry.state.isAir()) {
                    LOGGER.error("Deserialization was air, removed block");
                    sentryErrors.add(id);
                    continue;
                }
                sentries.add(sentry);
                var oldEntry = this.block2stateEntry.putIfAbsent(sentry.state, sentry);
                if (oldEntry != null) {
                    LOGGER.warn("Multiple mappings for blockstate, using old state, expect things to possibly go really badly. {}:{}   {}:{}",
                            oldEntry.id, oldEntry.state, sentry.id, sentry.state);
                }
            } else if (entryType == BIOME_TYPE) {
                var bentry = BiomeEntry.deserialize(id, entry.getValue());
                bentries.add(bentry);
                if (this.biome2biomeEntry.put(bentry.biome, bentry) != null) {
                    throw new IllegalStateException("Multiple mappings for biome entry");
                }
            } else {
                throw new IllegalStateException("Unknown entryType");
            }
        }

        if (!sentryErrors.isEmpty()) {
            forceResave[0] |= true;
            //Insert garbage types into the mapping for those blocks (upstream behavior)
            var rand = new Random();
            for (int errorId : sentryErrors) {
                while (true) {
                    var state = new StateEntry(errorId,
                            Block.BLOCK_STATE_REGISTRY.byId(rand.nextInt(Block.BLOCK_STATE_REGISTRY.size() - 1)));
                    if (this.block2stateEntry.put(state.state, state) == null) {
                        sentries.add(state);
                        break;
                    }
                }
            }
        }

        //Insert into the arrays
        sentries.stream().sorted(Comparator.comparing(a -> a.id)).forEach(entry -> {
            if (this.blockId2stateEntry.size() != entry.id) {
                throw new IllegalStateException("Block entry not ordered");
            }
            this.blockId2stateEntry.add(entry);
        });

        bentries.stream().sorted(Comparator.comparing(a -> a.id)).forEach(entry -> {
            if (this.biomeId2biomeEntry.size() != entry.id) {
                throw new IllegalStateException("Biome entry not ordered. got " + entry.biome + " with id "
                        + entry.id + " expected id " + this.biomeId2biomeEntry.size());
            }
            this.biomeId2biomeEntry.add(entry);
        });

        if (forceResave[0]) {
            LOGGER.warn("Forced state resave triggered");
            this.forceResaveStates();
        }
    }

    public int getBlockStateCount() {
        this.blockLock.lock();
        try {
            return this.blockId2stateEntry.size();
        } finally {
            this.blockLock.unlock();
        }
    }

    private StateEntry registerNewBlockState(BlockState state) {
        StateEntry entry;
        this.blockLock.lock();
        try {
            entry = this.block2stateEntry.get(state);
            if (entry != null) {
                return entry;
            }
            entry = new StateEntry(this.blockId2stateEntry.size(), state);
            this.blockId2stateEntry.add(entry);
            this.block2stateEntry.put(state, entry);
        } finally {
            this.blockLock.unlock();
        }

        this.storage.putIdMapping(entry.id | (BLOCK_STATE_TYPE << 30), entry.serialize());

        Consumer<StateEntry> callback = this.newStateCallback;
        if (callback != null) callback.accept(entry);
        return entry;
    }

    private BiomeEntry registerNewBiome(String biome) {
        BiomeEntry entry;
        this.biomeLock.lock();
        try {
            entry = this.biome2biomeEntry.get(biome);
            if (entry != null) {
                return entry;
            }
            entry = new BiomeEntry(this.biomeId2biomeEntry.size(), biome);
            this.biomeId2biomeEntry.add(entry);
            this.biome2biomeEntry.put(biome, entry);
        } finally {
            this.biomeLock.unlock();
        }

        this.storage.putIdMapping(entry.id | (BIOME_TYPE << 30), entry.serialize());

        Consumer<BiomeEntry> callback = this.newBiomeCallback;
        if (callback != null) callback.accept(entry);
        return entry;
    }

    public long getBaseId(byte light, BlockState state, Holder<Biome> biome) {
        if (state.isAir()) return Byte.toUnsignedLong(light) << 56;//Special case and fast return for air
        return composeMappingId(light, this.getIdForBlockState(state), this.getIdForBiome(biome));
    }

    public BlockState getBlockStateFromBlockId(int blockId) {
        this.blockLock.lock();
        try {
            return this.blockId2stateEntry.get(blockId).state;
        } finally {
            this.blockLock.unlock();
        }
    }

    public int getIdForBlockState(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        var mapping = this.block2stateEntry.get(state);
        if (mapping == null) {
            mapping = this.registerNewBlockState(state);
        }
        return mapping.id;
    }

    public int getBlockStateOpacity(long mappingId) {
        return this.getBlockStateOpacity(getBlockId(mappingId));
    }

    public int getBlockStateOpacity(int blockId) {
        this.blockLock.lock();
        try {
            return this.blockId2stateEntry.get(blockId).opacity;
        } finally {
            this.blockLock.unlock();
        }
    }

    public int getIdForBiome(Holder<Biome> biome) {
        String biomeId = biome.unwrapKey().get().identifier().toString();
        var entry = this.biome2biomeEntry.get(biomeId);
        if (entry == null) {
            entry = this.registerNewBiome(biomeId);
        }
        return entry.id;
    }

    public StateEntry[] getStateEntries() {
        this.blockLock.lock();
        try {
            StateEntry[] out = new StateEntry[this.blockId2stateEntry.size()];
            for (int i = 0; i < out.length; i++) {
                StateEntry entry = this.blockId2stateEntry.get(i);
                if (entry.id != i) {
                    throw new IllegalStateException();
                }
                out[i] = entry;
            }
            return out;
        } finally {
            this.blockLock.unlock();
        }
    }

    public BiomeEntry[] getBiomeEntries() {
        this.biomeLock.lock();
        try {
            BiomeEntry[] out = new BiomeEntry[this.biomeId2biomeEntry.size()];
            for (int i = 0; i < out.length; i++) {
                BiomeEntry entry = this.biomeId2biomeEntry.get(i);
                if (entry.id != i) {
                    throw new IllegalStateException();
                }
                out[i] = entry;
            }
            return out;
        } finally {
            this.biomeLock.unlock();
        }
    }

    public void forceResaveStates() {
        var blocks = new ArrayList<>(this.block2stateEntry.values());
        var biomes = new ArrayList<>(this.biome2biomeEntry.values());

        for (var entry : blocks) {
            if (entry.state.isAir() && entry.id == 0) {
                continue;
            }
            if (this.blockId2stateEntry.indexOf(entry) != entry.id) {
                throw new IllegalStateException("State Id NOT THE SAME, very critically bad. arr:"
                        + this.blockId2stateEntry.indexOf(entry) + " entry: " + entry.id);
            }
            this.storage.putIdMapping(entry.id | (BLOCK_STATE_TYPE << 30), entry.serialize());
        }

        for (var entry : biomes) {
            if (this.biomeId2biomeEntry.indexOf(entry) != entry.id) {
                throw new IllegalStateException("Biome Id NOT THE SAME, very critically bad");
            }
            this.storage.putIdMapping(entry.id | (BIOME_TYPE << 30), entry.serialize());
        }

        this.storage.flush();
    }

    //==================================================================================================================
    // ModelStateSource

    @Override
    public BlockState blockStateForId(int blockId) {
        return this.getBlockStateFromBlockId(blockId);
    }

    @Override
    public int idForBlockState(BlockState state) {
        return this.getIdForBlockState(state);
    }

    @Override
    public int blockStateCount() {
        return this.getBlockStateCount();
    }

    //==================================================================================================================

    public static final class StateEntry {
        public final int id;
        public final BlockState state;
        public final int opacity;

        public StateEntry(int id, BlockState state) {
            this.id = id;
            this.state = state;
            //Override opacity of leaves to be solid
            if (state.getBlock() instanceof LeavesBlock) {
                this.opacity = 15;
            } else {
                this.opacity = state.getLightDampening();
            }
        }

        public byte[] serialize() {
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.put("block_state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, this.state).result().get());
                serialized.putInt("version", SharedConstants.getCurrentVersion().dataVersion().version());
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static final int DATA_VERSION = SharedConstants.getCurrentVersion().dataVersion().version();
        private static final int MIN_DATA_VERSION = 4671;//Min version is 1.21.11

        public static StateEntry deserialize(int id, byte[] data, boolean[] forceResave) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
                if (compound.getIntOr("id", -1) != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }

                var bsc = compound.get("block_state");
                if (bsc == null) {
                    throw new IllegalStateException("Expected a block state but it was null: " + compound);
                }
                int version = compound.getIntOr("version", MIN_DATA_VERSION);
                if (version != DATA_VERSION) {
                    forceResave[0] |= true;
                    LOGGER.info("Migrating block from version {} to {}", version, DATA_VERSION);
                    bsc = DataFixers.getDataFixer().update(References.BLOCK_STATE,
                            new Dynamic<>(NbtOps.INSTANCE, bsc), version, DATA_VERSION).getValue();
                }
                var state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);

                if (state.isError() && !state.hasResultOrPartial()) {
                    LOGGER.error("Could not decode blockstate, setting to air. id:{} error: {} tag:{}", id,
                            state.error().map(DataResult.Error::message), bsc);
                    return new StateEntry(id, Blocks.AIR.defaultBlockState());
                }
                if (state.isError() && state.hasResultOrPartial()) {
                    LOGGER.error("Decode result only had a partial value, using it anyway. id:{} tag:{} error: {}",
                            id, bsc, state.error().map(DataResult.Error::message));
                    forceResave[0] |= true;
                }
                return new StateEntry(id, state.getPartialOrThrow());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final class BiomeEntry {
        public final int id;
        public final String biome;

        public BiomeEntry(int id, String biome) {
            this.id = id;
            this.biome = biome;
        }

        public byte[] serialize() {
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.putString("biome_id", this.biome);
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static BiomeEntry deserialize(int id, byte[] data) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
                if (compound.getIntOr("id", -1) != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                String biome = compound.getStringOr("biome_id", null);
                return new BiomeEntry(id, biome);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
