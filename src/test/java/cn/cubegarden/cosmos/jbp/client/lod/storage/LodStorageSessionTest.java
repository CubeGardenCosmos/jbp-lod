package cn.cubegarden.cosmos.jbp.client.lod.storage;

import cn.cubegarden.cosmos.jbp.client.lod.scope.LodScopeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodStorageSessionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rocksDbRoundTripsUpstreamPositionAndMappingEncoding() throws Exception {
        Path path = temporaryDirectory.resolve("rocksdb");
        long levelOne = 0x1000000000000001L;
        long levelTwo = 0x2000000000000002L;
        byte[] firstSection = {0, 1, 2, 3, -1};
        byte[] secondSection = {8, 9, 10};
        try (RocksDbLodStorageBackend backend = new RocksDbLodStorageBackend(path)) {
            backend.setSectionData(levelOne, ByteBuffer.wrap(firstSection));
            backend.setSectionData(levelTwo, ByteBuffer.wrap(secondSection));
            backend.putIdMapping(0x01020304, ByteBuffer.wrap(new byte[]{11, 12, 13}));
            backend.putIdMapping(-1, ByteBuffer.wrap(new byte[]{14, 15}));

            assertArrayEquals(firstSection, backend.getSectionData(levelOne));
            assertArrayEquals(secondSection, backend.getSectionData(levelTwo));
            assertNull(backend.getSectionData(7));
            List<Long> levelOnePositions = new ArrayList<>();
            backend.iteratePositions(1, levelOnePositions::add);
            assertEquals(List.of(levelOne), levelOnePositions);
            List<Long> allPositions = new ArrayList<>();
            backend.iteratePositions(-1, allPositions::add);
            assertEquals(List.of(levelOne, levelTwo), allPositions);
            Map<Integer, byte[]> mappings = backend.getIdMappingsData();
            assertArrayEquals(new byte[]{11, 12, 13}, mappings.get(0x01020304));
            assertArrayEquals(new byte[]{14, 15}, mappings.get(-1));
            backend.deleteSectionData(levelOne);
            assertNull(backend.getSectionData(levelOne));
            backend.flush();
        }

        try (RocksDbLodStorageBackend reopened = new RocksDbLodStorageBackend(path)) {
            assertNull(reopened.getSectionData(levelOne));
            assertArrayEquals(secondSection, reopened.getSectionData(levelTwo));
            Map<Integer, byte[]> persistedMappings = reopened.getIdMappingsData();
            assertEquals(2, persistedMappings.size());
            assertArrayEquals(new byte[]{11, 12, 13}, persistedMappings.get(0x01020304));
            assertArrayEquals(new byte[]{14, 15}, persistedMappings.get(-1));
        }
    }

    @Test
    void storageSessionOwnsScopeLockUntilRocksDbBackendCloses() throws Exception {
        LodScopeManager.StartResult firstScope = startScope("node-one");
        assertTrue(firstScope.active());
        LodStorageSession session = LodStorageSession.open(firstScope.session(), "world-identifier");
        assertTrue(session.worldDirectory().endsWith(Path.of("worlds", "world-identifier")));
        session.setSectionData(0x1000000000000001L, ByteBuffer.wrap(new byte[]{3, 4, 5}));

        LodScopeManager.StartResult conflictingScope = startScope("node-one");
        assertFalse(conflictingScope.active());
        assertEquals(LodScopeManager.Status.LOCK_CONFLICT, conflictingScope.status());

        session.close();
        LodScopeManager.StartResult reacquiredScope = startScope("node-one");
        assertTrue(reacquiredScope.active());
        try (LodStorageSession reopened = LodStorageSession.open(reacquiredScope.session(), "world-identifier")) {
            assertArrayEquals(new byte[]{3, 4, 5}, reopened.getSectionData(0x1000000000000001L));
        }
    }

    private LodScopeManager.StartResult startScope(String nodeId) {
        return LodScopeManager.start(false, false, null, null, nodeId,
                temporaryDirectory.resolve("cache").toString(), temporaryDirectory.resolve("game"));
    }
}
