# JBP-LOD V3 storage backend decision

Status: desktop backend selected; Android ABI/bionic/16 KiB compatibility **待验证**. This is a repository-evidence decision, not a claim of Android support or a game/GPU run.

## Decision

Use the upstream Voxy desktop storage backend semantics with RocksDB JNI 10.2.1 as the PC default. Keep `StorageBackend`-equivalent ownership and record semantics: section positions are 64-bit world-section keys encoded as eight big-endian bytes, section values are the exact raw byte payload supplied by the upstream API, and ID-mapping keys are four-byte big-endian IDs with raw mapping bytes. Keep the upstream column-family separation for `world_sections` and `id_mappings`, default column family, compression/cache/filter configuration, flush and close behavior. JBP-LOD scopes each world database under its locked V0 scope; the scope session closes the database before releasing the lock.

A custom `sections.jbl`/replacement format is rejected as the primary backend: it would introduce a second schema and serialization path rather than porting upstream semantics. No replacement is selected absent a concrete desktop blocker. RocksDB's Android native suitability is an independent unmeasured question and does not change the PC choice.

## Evidence

- Sealed `mods/voxy` ships `org.rocksdb:rocksdbjni:10.2.1` as an included implementation dependency (`mods/voxy/build.gradle`, with the upstream comment to update to 10.10 after issue 14537 is fixed). Its storage implementation is `mods/voxy/src/main/java/me/cortex/voxy/common/config/storage/RocksDBStorageBackend.java`; the API contract is `StorageBackend.java`, `IMappingStorage.java`, and `IStoredSectionPositionIterator.java`. These source files are read-only references; JBP-LOD does not modify or directly re-export them.
- The JBP-LOD Gradle module includes `org.rocksdb:rocksdbjni:10.2.1` and the JBP implementation preserves upstream key/value behavior. The cached jar contains Linux x86-64 and AArch64, Windows x64, and macOS x86-64/AArch64 native entries. The repository's upstream jar packaging excludes unrelated native architectures from the desktop artifact. These are package contents only; they do not prove a binary loads on any device.
- The Android 16 KiB runtime requirements in [ADR 0019](../../../docs/decisions/0019-android-16k-page-aligned-runtime.md) demonstrate that an unaligned 4 KiB native `PT_LOAD` binary can fail on a 16 KiB Android page-size target and that the repository's remedy is evaluated per binary using source/derived hashes. The inspected RocksDB JNI jar does not contain an explicitly named Android/bionic native entry. No RocksDB ELF `PT_LOAD` alignment, bionic load, Android ARM64 packaging, or Galaxy S23 run has been measured here.

## Android gate

RocksDB Android ARM64 ABI/bionic loading, native inclusion in the app's `:game` classpath, ELF `PT_LOAD` alignment for 16 KiB pages, and cache I/O on target hardware remain **待验证**. Required evidence before asserting support: identify the exact packaged `.so`, record its source and derived hashes, inspect every `PT_LOAD` alignment, prove load on the target Android runtime/page size, and exercise storage operations in the app sandbox. If those checks expose a real blocker, evaluate a replacement that still preserves the upstream backend's keys, values, iteration, mapping, compression-independent compatibility, and session ownership.

No Minecraft process or Android target was launched for this decision record.
