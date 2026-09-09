package io.nekohasekai.sagernet.database.preference

/**
 * Unit-test strategy for `RoomPreferenceDataStore` durability/README:
 *
 * The data path is covered at two levels:
 *
 * 1. `KvMemoryCacheTest` (pure JVM, no Android framework) pins the
 *    read-your-writes / pending-write-wins / cross-process-merge rules of
 *    [KvMemoryCache], which is the entire non-trivial logic of
 *    `RoomPreferenceDataStore`.
 * 2. Full-stack verification (Room + store + invalidation + the committed
 *    `writeCommitted` callback) is intentionally not a JVM robo-unit: it needs
 *    the SQLite driver, the Room `InvalidationTracker` dispatcher and
 *    `enableMultiInstanceInvalidation`, none of which exist on the host JVM.
 *    Those are covered by the `androidTest` directory (device/emulator) and by
 *    CI assembling the app with strict-mode main-thread-disk detection.
 *
 * The most valuable host-checkable guarantee beyond (1) is that the only
 * blocking entry point of the public store surface goes through
 * `Dispatchers.IO` — enforced by construction in
 * `RoomPreferenceDataStore.init` (see `runBlocking(Dispatchers.IO)`).
 */
object RoomPreferenceDataStoreTestContract
