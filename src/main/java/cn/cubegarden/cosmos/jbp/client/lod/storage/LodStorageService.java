package cn.cubegarden.cosmos.jbp.client.lod.storage;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous facade over {@link LodStorageSession}: every RocksDB call runs on a dedicated I/O executor
 * so neither the game tick nor the render thread blocks on storage. Operations queued before
 * {@link #closeAsync()} complete first; later calls fail with {@link CancellationException}.
 */
public final class LodStorageService {
    private final LodStorageSession session;
    private final Executor io;
    private final Object lifecycle = new Object();
    private boolean closing;
    private CompletableFuture<Void> closed;

    public LodStorageService(LodStorageSession session, Executor ioExecutor) {
        this.session = session;
        this.io = ioExecutor;
    }

    private interface IoCall<T> {
        T run() throws IOException;
    }

    private <T> CompletableFuture<T> submit(IoCall<T> call) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (closing) {
                result.completeExceptionally(new CancellationException("LOD storage service is closing"));
                return result;
            }
            enqueue(call, result);
        }
        return result;
    }

    private <T> void enqueue(IoCall<T> call, CompletableFuture<T> result) {
        try {
            io.execute(() -> {
                try {
                    result.complete(call.run());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(rejected);
        }
    }

    /** Raw upstream-format section bytes, or null when the section is not stored. */
    public CompletableFuture<byte[]> getSectionData(long key) {
        return submit(() -> session.getSectionData(key));
    }

    public CompletableFuture<Void> setSectionData(long key, byte[] sectionBytes) {
        ByteBuffer copy = ByteBuffer.wrap(sectionBytes.clone());
        return submit(() -> {
            session.setSectionData(key, copy);
            return null;
        });
    }

    public CompletableFuture<Void> deleteSectionData(long key) {
        return submit(() -> {
            session.deleteSectionData(key);
            return null;
        });
    }

    /** All stored positions of an LoD level. */
    public CompletableFuture<long[]> listPositions(int level) {
        return submit(() -> {
            LongArrayList positions = new LongArrayList();
            session.iteratePositions(level, positions::add);
            return positions.toLongArray();
        });
    }

    public CompletableFuture<Void> putIdMapping(int id, byte[] mapping) {
        ByteBuffer copy = ByteBuffer.wrap(mapping.clone());
        return submit(() -> {
            session.putIdMapping(id, copy);
            return null;
        });
    }

    public CompletableFuture<Map<Integer, byte[]>> getIdMappingsData() {
        return submit(session::getIdMappingsData);
    }

    public CompletableFuture<Void> flush() {
        return submit(() -> {
            session.flush();
            return null;
        });
    }

    /**
     * Synchronously flushes and closes the session on the calling thread. Only valid on the I/O thread (or
     * when no operation was ever queued), e.g. to release a scope that became stale before publication;
     * subsequent operations fail with {@link CancellationException}.
     */
    public void closeNow() throws IOException {
        synchronized (lifecycle) {
            if (closed != null) {
                return;
            }
            closing = true;
            closed = new CompletableFuture<>();
        }
        try {
            session.flush();
        } catch (IOException | RuntimeException flushFailure) {
            try {
                session.close();
            } catch (IOException | RuntimeException closeFailure) {
                flushFailure.addSuppressed(closeFailure);
            }
            closed.completeExceptionally(flushFailure);
            throw flushFailure;
        }
        try {
            session.close();
            closed.complete(null);
        } catch (IOException | RuntimeException closeFailure) {
            closed.completeExceptionally(closeFailure);
            throw closeFailure;
        }
    }

    /**
     * Stops accepting work, then closes the storage session (which releases the scope lock) on the I/O
     * executor after all previously queued operations. Idempotent; the future completes when the scope
     * is released.
     */
    public CompletableFuture<Void> closeAsync() {
        synchronized (lifecycle) {
            if (closed != null) {
                return closed;
            }
            closing = true;
            closed = new CompletableFuture<>();
            CompletableFuture<Void> target = closed;
            try {
                io.execute(() -> {
                    try {
                        session.flush();
                    } catch (IOException | RuntimeException flushFailure) {
                        //Closing must still release the backend and scope; flush failure is reported on close
                        try {
                            session.close();
                        } catch (IOException | RuntimeException closeFailure) {
                            flushFailure.addSuppressed(closeFailure);
                        }
                        target.completeExceptionally(flushFailure);
                        return;
                    }
                    try {
                        session.close();
                        target.complete(null);
                    } catch (IOException | RuntimeException closeFailure) {
                        target.completeExceptionally(closeFailure);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                target.completeExceptionally(rejected);
            }
            return target;
        }
    }
}
