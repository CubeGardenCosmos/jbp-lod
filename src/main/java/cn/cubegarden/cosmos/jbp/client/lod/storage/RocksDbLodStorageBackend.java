package cn.cubegarden.cosmos.jbp.client.lod.storage;

import org.rocksdb.AbstractImmutableNativeReference;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionPriority;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.DataBlockIndexType;
import org.rocksdb.HyperClockCache;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/** RocksDB JNI port of the sealed Voxy StorageBackend's key/value and column-family semantics. */
public final class RocksDbLodStorageBackend implements LodStorageBackend {
    private final RocksDB db;
    private final ColumnFamilyHandle worldSections;
    private final ColumnFamilyHandle idMappings;
    private final ReadOptions sectionReadOptions;
    private final WriteOptions sectionWriteOptions;
    private final List<AbstractImmutableNativeReference> closeList;
    private boolean closed;

    public RocksDbLodStorageBackend(Path path) throws IOException {
        RocksDB.loadLibrary();
        ColumnFamilyOptions defaultColumnOptions = null;
        ColumnFamilyOptions sectionColumnOptions = null;
        HyperClockCache blockCache = null;
        BloomFilter filter = null;
        DBOptions dbOptions = null;
        ReadOptions readOptions = null;
        WriteOptions writeOptions = null;
        RocksDB openedDb = null;
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try {
            defaultColumnOptions = new ColumnFamilyOptions()
                    .setCompressionType(CompressionType.ZSTD_COMPRESSION)
                    .optimizeForSmallDb();
            sectionColumnOptions = new ColumnFamilyOptions()
                    .setCompressionType(CompressionType.NO_COMPRESSION)
                    .setCompactionPriority(CompactionPriority.MinOverlappingRatio)
                    .setLevelCompactionDynamicLevelBytes(true)
                    .optimizeForPointLookup(128);
            blockCache = new HyperClockCache(128L * 1024L * 1024L, 0, 4, false);
            filter = new BloomFilter(10);
            sectionColumnOptions.setTableFormatConfig(new BlockBasedTableConfig()
                    .setCacheIndexAndFilterBlocksWithHighPriority(true)
                    .setBlockCache(blockCache)
                    .setDataBlockHashTableUtilRatio(0.75)
                    .setDataBlockIndexType(DataBlockIndexType.kDataBlockBinaryAndHash)
                    .setFilterPolicy(filter));

            List<ColumnFamilyDescriptor> descriptors = List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultColumnOptions),
                    new ColumnFamilyDescriptor("world_sections".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            sectionColumnOptions),
                    new ColumnFamilyDescriptor("id_mappings".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            defaultColumnOptions));
            dbOptions = new DBOptions()
                    .setAvoidUnnecessaryBlockingIO(true)
                    .setIncreaseParallelism(2)
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true)
                    .setMaxTotalWalSize(128L * 1024L * 1024L);
            openedDb = RocksDB.open(dbOptions, path.toString(), descriptors, handles);
            readOptions = new ReadOptions();
            writeOptions = new WriteOptions();
            openedDb.flushWal(true);

            this.db = openedDb;
            this.worldSections = handles.get(1);
            this.idMappings = handles.get(2);
            this.sectionReadOptions = readOptions;
            this.sectionWriteOptions = writeOptions;
            this.closeList = new ArrayList<>(8 + handles.size());
            // Match upstream lifetime order: options/policies/handles close before RocksDB.
            this.closeList.add(dbOptions);
            this.closeList.add(defaultColumnOptions);
            this.closeList.add(sectionColumnOptions);
            this.closeList.add(readOptions);
            this.closeList.add(writeOptions);
            this.closeList.add(filter);
            this.closeList.add(blockCache);
            this.closeList.addAll(handles);
        } catch (RocksDBException | RuntimeException | LinkageError failure) {
            closeOnFailure(openedDb, handles, readOptions, writeOptions, dbOptions,
                    defaultColumnOptions, sectionColumnOptions, filter, blockCache);
            throw new IOException("Failed to open Voxy-compatible RocksDB LOD storage at " + path, failure);
        }
    }

    @Override
    public synchronized byte[] getSectionData(long key) throws IOException {
        ensureOpen();
        try {
            return db.get(worldSections, sectionReadOptions, longKey(key));
        } catch (RocksDBException failure) {
            throw storageFailure("read section", failure);
        }
    }

    @Override
    public synchronized void setSectionData(long key, ByteBuffer sectionBytes) throws IOException {
        ensureOpen();
        ByteBuffer source = sectionBytes.duplicate();
        byte[] value = new byte[source.remaining()];
        source.get(value);
        try {
            db.put(worldSections, sectionWriteOptions, longKey(key), value);
        } catch (RocksDBException failure) {
            throw storageFailure("write section", failure);
        }
    }

    @Override
    public synchronized void deleteSectionData(long key) throws IOException {
        ensureOpen();
        try {
            db.delete(worldSections, longKey(key));
        } catch (RocksDBException failure) {
            throw storageFailure("delete section", failure);
        }
    }

    @Override
    public synchronized void iteratePositions(int level, LongConsumer consumer) throws IOException {
        ensureOpen();
        if (level < -1 || level > 15) {
            throw new IllegalArgumentException("LOD level must be -1 (all) or in [0, 15]");
        }
        try (RocksIterator iterator = db.newIterator(worldSections, sectionReadOptions)) {
            if (level == -1) {
                iterator.seekToFirst();
            } else {
                iterator.seek(longKey(Integer.toUnsignedLong(level) << 60));
            }
            while (iterator.isValid()) {
                long key = bytesToLong(iterator.key());
                if (level != -1 && ((key >>> 60) & 0xfL) != level) {
                    break;
                }
                consumer.accept(key);
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException failure) {
            throw storageFailure("iterate section positions", failure);
        }
    }

    @Override
    public synchronized void putIdMapping(int id, ByteBuffer encodedMapping) throws IOException {
        ensureOpen();
        ByteBuffer source = encodedMapping.duplicate();
        byte[] value = new byte[source.remaining()];
        source.get(value);
        try {
            db.put(idMappings, intKey(id), value);
        } catch (RocksDBException failure) {
            throw storageFailure("write ID mapping", failure);
        }
    }

    @Override
    public synchronized Map<Integer, byte[]> getIdMappingsData() throws IOException {
        ensureOpen();
        Map<Integer, byte[]> result = new HashMap<>();
        try (RocksIterator iterator = db.newIterator(idMappings)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                result.put(bytesToInt(iterator.key()), iterator.value());
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException failure) {
            throw storageFailure("read ID mappings", failure);
        }
        return result;
    }

    @Override
    public synchronized void flush() throws IOException {
        ensureOpen();
        try {
            db.flushWal(true);
        } catch (RocksDBException failure) {
            throw storageFailure("flush WAL", failure);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        flush();
        closed = true;
        for (AbstractImmutableNativeReference reference : closeList) {
            reference.close();
        }
        try {
            db.closeE();
        } catch (RocksDBException failure) {
            throw storageFailure("close database", failure);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RocksDB LOD backend has been closed");
        }
    }

    private static IOException storageFailure(String operation, RocksDBException failure) {
        return new IOException("RocksDB LOD storage failed to " + operation, failure);
    }

    private static byte[] longKey(long value) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    private static long bytesToLong(byte[] value) {
        if (value.length != Long.BYTES) {
            throw new IllegalStateException("RocksDB LOD section key is not 8 bytes");
        }
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static byte[] intKey(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    private static int bytesToInt(byte[] value) {
        if (value.length != Integer.BYTES) {
            throw new IllegalStateException("RocksDB LOD mapping key is not 4 bytes");
        }
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static void closeOnFailure(RocksDB db, List<ColumnFamilyHandle> handles,
                                       ReadOptions read, WriteOptions write, DBOptions dbOptions,
                                       ColumnFamilyOptions defaults, ColumnFamilyOptions sections,
                                       BloomFilter filter, HyperClockCache cache) {
        for (ColumnFamilyHandle handle : handles) {
            handle.close();
        }
        if (read != null) read.close();
        if (write != null) write.close();
        if (dbOptions != null) dbOptions.close();
        if (defaults != null) defaults.close();
        if (sections != null) sections.close();
        if (filter != null) filter.close();
        if (cache != null) cache.close();
        if (db != null) db.close();
    }
}
