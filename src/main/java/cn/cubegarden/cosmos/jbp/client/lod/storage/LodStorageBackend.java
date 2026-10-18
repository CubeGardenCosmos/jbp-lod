package cn.cubegarden.cosmos.jbp.client.lod.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.LongConsumer;

/** StorageBackend/IMappingStorage/IStoredSectionPositionIterator semantics for a world session. */
public interface LodStorageBackend extends AutoCloseable {
    byte[] getSectionData(long key) throws IOException;

    void setSectionData(long key, ByteBuffer sectionBytes) throws IOException;

    void deleteSectionData(long key) throws IOException;

    void iteratePositions(int level, LongConsumer consumer) throws IOException;

    void putIdMapping(int id, ByteBuffer encodedMapping) throws IOException;

    Map<Integer, byte[]> getIdMappingsData() throws IOException;

    void flush() throws IOException;

    @Override
    void close() throws IOException;
}
