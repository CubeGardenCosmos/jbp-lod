package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import cn.cubegarden.cosmos.jbp.client.lod.runtime.LodRuntime;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Game-thread loaded-chunk ledger, retained while asynchronous scope opening is in progress. */
public final class LiveChunkCapture {
    private static ClientLevel level;
    private static final Map<Long, LevelChunk> loaded = new HashMap<>();
    private static final Set<Long> dirty = new HashSet<>();
    private static LiveSectionProducer producer;
    private static long generation = -1;
    private LiveChunkCapture() { }
    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            useLevel(world);
            long key = chunk.getPos().pack(); loaded.put(key, chunk); dirty.add(key);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (world != level) return;
            long key = chunk.getPos().pack();
            if (producer != null) producer.capture(chunk);
            loaded.remove(key); dirty.remove(key);
        });
    }
    private static void useLevel(ClientLevel world) {
        if (level != world) { level = world; loaded.clear(); dirty.clear(); }
    }
    public static void changed(ClientLevel world, int x, int z) {
        if (world == level) dirty.add(net.minecraft.world.level.ChunkPos.pack(x, z));
    }
    public static void tick(ClientLevel world, LodRuntime runtime) {
        useLevel(world);
        if (runtime == null) return;
        if (generation != runtime.generation()) {
            generation = runtime.generation();
            producer = new LiveSectionProducer(runtime.session());
            dirty.addAll(loaded.keySet());
        }
        // Bound synchronous capture per tick; expensive conversion/storage remains off-thread.
        var iterator = dirty.iterator();
        int remaining = 2;
        while (remaining-- > 0 && iterator.hasNext()) {
            LevelChunk chunk = loaded.get(iterator.next()); iterator.remove();
            if (chunk != null) producer.capture(chunk);
        }
    }
    public static java.util.concurrent.CompletableFuture<Void> detach() {
        LiveSectionProducer previous = producer; producer = null; generation = -1;
        return previous == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : previous.closeAsync();
    }
    public static void clear() { level = null; loaded.clear(); dirty.clear(); }
}
