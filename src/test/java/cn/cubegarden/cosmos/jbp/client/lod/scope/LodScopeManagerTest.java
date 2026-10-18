package cn.cubegarden.cosmos.jbp.client.lod.scope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodScopeManagerTest {
    @TempDir
    Path gameDirectory;

    @Test
    void sanitizesNodeIdsAndRejectsUnsafeEmptyComponents() {
        assertEquals("node_A__", NodeIdSanitizer.sanitize("node A/ß").orElseThrow());
        assertEquals("x_y", NodeIdSanitizer.sanitize("x\uD83D\uDE00y").orElseThrow());
        assertTrue(NodeIdSanitizer.sanitize(null).isEmpty());
        assertTrue(NodeIdSanitizer.sanitize("").isEmpty());
        assertTrue(NodeIdSanitizer.sanitize("  \t").isEmpty());
        assertTrue(NodeIdSanitizer.sanitize(".").isEmpty());
        assertTrue(NodeIdSanitizer.sanitize("..").isEmpty());
    }

    @Test
    void resolvesConfiguredFallbackAndAndroidRoots() {
        assertEquals(gameDirectory.resolve(".lod"), LodPaths.baseDirectory(null, gameDirectory));
        assertEquals(gameDirectory.resolve(".lod"), LodPaths.baseDirectory("  ", gameDirectory));
        assertEquals(Path.of("/cache/lod"), LodPaths.baseDirectory("/cache/lod", gameDirectory));
        assertEquals(Path.of("/android/cache/lod"), LodPaths.androidBaseDirectory(Path.of("/android/cache")));
        assertEquals("My World", LodPaths.worldFolderId(Path.of("saves", "My World", ".")));
        assertNull(LodPaths.worldFolderId(null));
    }

    @Test
    void inactiveSessionsDoNotCreateDirectoriesOrOpenStorage() {
        var singleplayer = startNodeScope(true, "node-a");
        var unknownNode = startNodeScope(false, " \t");
        var invalidNode = startNodeScope(false, "..");

        assertEquals(LodScopeManager.Status.INACTIVE_SINGLEPLAYER, singleplayer.status());
        assertEquals(LodScopeManager.Status.INACTIVE_NODE_UNKNOWN, unknownNode.status());
        assertEquals(LodScopeManager.Status.INVALID_NODE_ID, invalidNode.status());
        assertFalse(singleplayer.active());
        assertFalse(unknownNode.active());
        assertFalse(invalidNode.active());
        assertFalse(Files.exists(gameDirectory.resolve(".lod")));
    }

    @Test
    void releaseSingleplayerStaysInactiveEvenWhenDevScopeIsRequested() {
        var result = LodScopeManager.start(true, false, "true", "local-world", null, null, gameDirectory);

        assertEquals(LodScopeManager.Status.INACTIVE_SINGLEPLAYER, result.status());
        assertFalse(result.active());
        assertFalse(Files.exists(gameDirectory.resolve(".lod")));
    }

    @Test
    void developmentSingleplayerUsesSanitizedWorldFolderAndExclusiveLock() throws IOException {
        var first = LodScopeManager.start(true, true, "true", "My Local World", null, null, gameDirectory);
        try (var firstSession = first.session()) {
            Path expectedDirectory = gameDirectory.resolve(".lod/dev/My_Local_World");
            assertEquals(LodScopeManager.Status.ACTIVE, first.status());
            assertTrue(first.active());
            assertTrue(first.developmentScope());
            assertEquals(expectedDirectory, first.scopeDirectory());
            assertTrue(Files.isRegularFile(expectedDirectory.resolve("lod.lock")));
            assertFalse(Files.exists(gameDirectory.resolve(".lod/nodes")));

            var conflict = LodScopeManager.start(true, true, "true", "My Local World", null, null, gameDirectory);
            assertEquals(LodScopeManager.Status.LOCK_CONFLICT, conflict.status());
            assertEquals(expectedDirectory, conflict.scopeDirectory());
            assertFalse(conflict.active());
        }
    }

    @Test
    void customDevelopmentScopeIsSanitizedAndUnsafeIdsAreRejected() throws IOException {
        var custom = LodScopeManager.start(true, true, "Local Dev/Map", "Ignored World", null, null, gameDirectory);
        try (var session = custom.session()) {
            assertEquals(LodScopeManager.Status.ACTIVE, custom.status());
            assertEquals(gameDirectory.resolve(".lod/dev/Local_Dev_Map"), custom.scopeDirectory());
            assertNotNull(session);
        }

        var unknownWorld = LodScopeManager.start(true, true, "true", null, null, null, gameDirectory);
        var dot = LodScopeManager.start(true, true, ".", "world", null, null, gameDirectory);
        var dotDot = LodScopeManager.start(true, true, "..", "world", null, null, gameDirectory);
        var disabled = LodScopeManager.start(true, true, "false", "world", null, null, gameDirectory);

        assertEquals(LodScopeManager.Status.INACTIVE_DEV_SCOPE_UNKNOWN, unknownWorld.status());
        assertEquals(LodScopeManager.Status.INVALID_DEV_SCOPE, dot.status());
        assertEquals(LodScopeManager.Status.INVALID_DEV_SCOPE, dotDot.status());
        assertEquals(LodScopeManager.Status.INACTIVE_SINGLEPLAYER, disabled.status());
    }

    @Test
    void developmentNodeOverrideWinsOnlyInDevelopmentEnvironment() {
        assertEquals("dev-node", LodScopeManager.selectNodeId(true, "dev-node", "provider-node"));
        assertEquals("provider-node", LodScopeManager.selectNodeId(false, "dev-node", "provider-node"));
        assertEquals("provider-node", LodScopeManager.selectNodeId(true, null, "provider-node"));
    }

    @Test
    void createsNodeDirectoryAndHoldsExclusiveLockUntilClose() throws IOException {
        var first = startNodeScope(false, "node A");
        try (var firstSession = first.session()) {
            assertEquals(LodScopeManager.Status.ACTIVE, first.status());
            assertTrue(first.active());
            assertEquals(gameDirectory.resolve(".lod/nodes/node_A"), first.scopeDirectory());
            assertTrue(Files.isRegularFile(first.scopeDirectory().resolve("lod.lock")));

            var conflict = startNodeScope(false, "node A");
            assertEquals(first.scopeDirectory(), conflict.scopeDirectory());
            assertEquals(LodScopeManager.Status.LOCK_CONFLICT, conflict.status());
            assertFalse(conflict.active());

            firstSession.close();
            var afterRelease = startNodeScope(false, "node A");
            try (var afterReleaseSession = afterRelease.session()) {
                assertEquals(LodScopeManager.Status.ACTIVE, afterRelease.status());
            }
        }
    }

    @Test
    void nodeIdsAreIsolatedIntoDifferentDirectories() throws IOException {
        var first = startNodeScope(false, "alpha");
        var second = startNodeScope(false, "beta");
        try (var firstSession = first.session(); var secondSession = second.session()) {
            assertEquals(LodScopeManager.Status.ACTIVE, first.status());
            assertEquals(LodScopeManager.Status.ACTIVE, second.status());
            assertEquals(gameDirectory.resolve(".lod/nodes/alpha"), first.scopeDirectory());
            assertEquals(gameDirectory.resolve(".lod/nodes/beta"), second.scopeDirectory());
            assertNotNull(firstSession);
            assertNotNull(secondSession);
        }
    }

    @Test
    void reflectiveProviderCachesMethodLookupButReadsEachCurrentValue() {
        TestNodeProvider.nodeId = "node-one";
        CountingClassLoader loader = new CountingClassLoader(TestNodeProvider.class.getName());
        var provider = new ReflectiveNodeIdProvider(TestNodeProvider.class.getName(), loader);

        assertEquals("node-one", provider.currentNodeId());
        TestNodeProvider.nodeId = "node-two";
        assertEquals("node-two", provider.currentNodeId());
        assertEquals(1, loader.providerLoadCount);
        assertNull(new ReflectiveNodeIdProvider().currentNodeId());

        assertNull(new ReflectiveNodeIdProvider("missing.optional.Provider", loader).currentNodeId());
        assertNull(new ReflectiveNodeIdProvider(BlankNodeProvider.class.getName(), loader).currentNodeId());
        assertNull(new ReflectiveNodeIdProvider(ThrowingNodeProvider.class.getName(), loader).currentNodeId());
        assertNull(new ReflectiveNodeIdProvider(ErrorNodeProvider.class.getName(), loader).currentNodeId());
    }

    private LodScopeManager.StartResult startNodeScope(boolean singleplayer, String nodeId) {
        return LodScopeManager.start(singleplayer, false, null, null, nodeId, null, gameDirectory);
    }

    public static final class TestNodeProvider {
        private static String nodeId;

        public static String currentNodeId() {
            return nodeId;
        }
    }

    public static final class BlankNodeProvider {
        public static String currentNodeId() {
            return "  ";
        }
    }

    public static final class ThrowingNodeProvider {
        public static String currentNodeId() {
            throw new IllegalStateException("provider unavailable");
        }
    }

    public static final class ErrorNodeProvider {
        public static String currentNodeId() {
            throw new AssertionError("provider failed");
        }
    }

    private static final class CountingClassLoader extends ClassLoader {
        private final String countedClass;
        private int providerLoadCount;

        private CountingClassLoader(String countedClass) {
            super(LodScopeManagerTest.class.getClassLoader());
            this.countedClass = countedClass;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(countedClass)) {
                providerLoadCount++;
            }
            return super.loadClass(name, resolve);
        }
    }
}
