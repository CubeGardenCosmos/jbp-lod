package cn.cubegarden.cosmos.jbp.client.lod.scope;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Creates and exclusively locks a node or developer-only singleplayer scope. */
public final class LodScopeManager {
    public static final String LOCK_FILE_NAME = "lod.lock";

    private LodScopeManager() {
    }

    /** A {@code true} dev scope uses the world ID; another enabled value is treated as its custom ID. */
    public static StartResult start(boolean singleplayer, boolean developmentEnvironment, String developmentScopeId,
            String worldId, String nodeId, String configuredBase, Path gameDirectory) {
        if (singleplayer) {
            if (!developmentEnvironment || developmentScopeDisabled(developmentScopeId)) {
                return StartResult.inactive(Status.INACTIVE_SINGLEPLAYER);
            }
            String scopeId = usesWorldId(developmentScopeId) ? worldId : developmentScopeId;
            if (scopeId == null || scopeId.isBlank()) {
                return StartResult.inactive(Status.INACTIVE_DEV_SCOPE_UNKNOWN);
            }
            var sanitized = NodeIdSanitizer.sanitize(scopeId);
            if (sanitized.isEmpty()) {
                return StartResult.inactive(Status.INVALID_DEV_SCOPE);
            }
            return openScope("dev", sanitized.get(), true, configuredBase, gameDirectory);
        }
        if (nodeId == null || nodeId.isBlank()) {
            return StartResult.inactive(Status.INACTIVE_NODE_UNKNOWN);
        }
        var sanitized = NodeIdSanitizer.sanitize(nodeId);
        if (sanitized.isEmpty()) {
            return StartResult.inactive(Status.INVALID_NODE_ID);
        }
        return openScope("nodes", sanitized.get(), false, configuredBase, gameDirectory);
    }

    public static String selectNodeId(boolean developmentEnvironment, String developmentNodeId, String providerNodeId) {
        return developmentEnvironment && developmentNodeId != null ? developmentNodeId : providerNodeId;
    }

    private static boolean developmentScopeDisabled(String developmentScopeId) {
        return developmentScopeId == null || developmentScopeId.isBlank()
                || "false".equalsIgnoreCase(developmentScopeId.trim());
    }

    private static boolean usesWorldId(String developmentScopeId) {
        return "true".equalsIgnoreCase(developmentScopeId.trim());
    }

    private static StartResult openScope(String scopeType, String sanitizedId, boolean developmentScope,
            String configuredBase, Path gameDirectory) {
        FileChannel channel = null;
        Path scopeDirectory = null;
        try {
            Path base = LodPaths.baseDirectory(configuredBase, gameDirectory);
            scopeDirectory = base.resolve(scopeType).resolve(sanitizedId);
            Files.createDirectories(scopeDirectory);
            Path lockPath = scopeDirectory.resolve(LOCK_FILE_NAME);
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                closeQuietly(channel);
                return StartResult.inactive(Status.LOCK_CONFLICT, scopeDirectory, null);
            }
            return StartResult.active(new LodScopeSession(scopeDirectory, lock, channel), developmentScope);
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            return StartResult.inactive(Status.LOCK_CONFLICT, scopeDirectory, e);
        } catch (IOException | SecurityException | InvalidPathException e) {
            closeQuietly(channel);
            return StartResult.inactive(Status.IO_FAILURE, scopeDirectory, e);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The primary lock/open failure is the useful diagnosis.
            }
        }
    }

    public enum Status {
        ACTIVE,
        INACTIVE_SINGLEPLAYER,
        INACTIVE_DEV_SCOPE_UNKNOWN,
        INACTIVE_NODE_UNKNOWN,
        INVALID_DEV_SCOPE,
        INVALID_NODE_ID,
        LOCK_CONFLICT,
        IO_FAILURE
    }

    public record StartResult(Status status, LodScopeSession session, Path scopeDirectory, Throwable failure,
            boolean developmentScope) {
        private static StartResult active(LodScopeSession session, boolean developmentScope) {
            return new StartResult(Status.ACTIVE, session, session.scopeDirectory(), null, developmentScope);
        }

        private static StartResult inactive(Status status) {
            return inactive(status, null, null);
        }

        private static StartResult inactive(Status status, Path scopeDirectory, Throwable failure) {
            return new StartResult(status, null, scopeDirectory, failure, false);
        }

        public boolean active() {
            return session != null;
        }
    }

    public static final class LodScopeSession implements AutoCloseable {
        private final Path scopeDirectory;
        private final FileLock lock;
        private final FileChannel channel;
        private boolean closed;

        private LodScopeSession(Path scopeDirectory, FileLock lock, FileChannel channel) {
            this.scopeDirectory = scopeDirectory;
            this.lock = lock;
            this.channel = channel;
        }

        public Path scopeDirectory() {
            return scopeDirectory;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException e) {
                failure = e;
            }
            try {
                channel.close();
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
    }
}
