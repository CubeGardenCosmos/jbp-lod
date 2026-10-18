package cn.cubegarden.cosmos.jbp.client.lod.storage;

import cn.cubegarden.cosmos.jbp.client.lod.scope.LodScopeManager;
import cn.cubegarden.cosmos.jbp.client.lod.scope.NodeIdSanitizer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.LongConsumer;

/** Owns one upstream-format world storage backend and its V0 exclusive scope lock. */
public final class LodStorageSession implements AutoCloseable {
    private final LodScopeManager.LodScopeSession scope;
    private final LodStorageBackend backend;
    private final Path worldDirectory;
    private boolean closed;

    private LodStorageSession(LodScopeManager.LodScopeSession scope,
                              LodStorageBackend backend, Path worldDirectory) {
        this.scope = scope;
        this.backend = backend;
        this.worldDirectory = worldDirectory;
    }

    /** Opens a world-specific RocksDB store under an active V0 scope and owns the scope until close. */
    public static LodStorageSession open(LodScopeManager.LodScopeSession scope, String worldIdentifier)
            throws IOException {
        if (scope == null) {
            throw new NullPointerException("active V0 scope is required");
        }
        try {
            String safeWorldId = NodeIdSanitizer.sanitize(worldIdentifier)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "world identifier cannot form a safe storage path"));
            Path worldDirectory = scope.scopeDirectory().resolve("worlds").resolve(safeWorldId);
            Files.createDirectories(worldDirectory);
            LodStorageBackend backend = new RocksDbLodStorageBackend(worldDirectory.resolve("rocksdb"));
            return new LodStorageSession(scope, backend, worldDirectory);
        } catch (IOException | RuntimeException | LinkageError failure) {
            try {
                scope.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public Path worldDirectory() {
        ensureOpen();
        return worldDirectory;
    }

    public byte[] getSectionData(long key) throws IOException {
        ensureOpen();
        return backend.getSectionData(key);
    }

    public void setSectionData(long key, ByteBuffer sectionBytes) throws IOException {
        ensureOpen();
        backend.setSectionData(key, sectionBytes);
    }

    public void deleteSectionData(long key) throws IOException {
        ensureOpen();
        backend.deleteSectionData(key);
    }

    public void iteratePositions(int level, LongConsumer consumer) throws IOException {
        ensureOpen();
        backend.iteratePositions(level, consumer);
    }

    public void putIdMapping(int id, ByteBuffer mapping) throws IOException {
        ensureOpen();
        backend.putIdMapping(id, mapping);
    }

    public Map<Integer, byte[]> getIdMappingsData() throws IOException {
        ensureOpen();
        return backend.getIdMappingsData();
    }

    public void flush() throws IOException {
        ensureOpen();
        backend.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            backend.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            scope.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("LOD storage session is closed");
        }
    }
}
