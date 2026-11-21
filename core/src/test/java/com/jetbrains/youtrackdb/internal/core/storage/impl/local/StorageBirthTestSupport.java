package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Provides disk birth selection, birth observers and cache-only allocation fixtures.
 *
 * <p>Storage birth is the creation of a new storage image. Genesis is the creation of the initial
 * database metadata. A durability barrier forces earlier writes to durable media before later
 * work. The creation path runs genesis, then the durability barrier, and then the activation.
 *
 * <p>A test needs both boundaries to build the on-disk image of a crash at each boundary. The two
 * boundaries live inside one final method of the storage, so no subclass can reach them. This
 * class therefore exposes the package-private observer seam of the storage to a test of another
 * package.
 */
public final class StorageBirthTestSupport {

  private StorageBirthTestSupport() {
  }

  /**
   * Reserves a cache-only tail without scheduling blank-page writes. The caller pauses flushing
   * and initializes the blank tail pages before using them as component pages.
   */
  @SuppressWarnings("unchecked")
  public static void reserveUnwrittenTail(WOWCache cache, long fileId, long pages)
      throws Exception {
    var filesField = cache.getClass().getDeclaredField("files");
    filesField.setAccessible(true);
    var files = (ClosableLinkedContainer<Long, File>) filesField.get(cache);
    files.get(fileId).reserveSpaceThrough(pages * cache.pageSize());
    var tableField = cache.getClass().getDeclaredField("logicalFiles");
    tableField.setAccessible(true);
    var table = tableField.get(cache);
    var live = table.getClass().getDeclaredMethod("live", int.class);
    live.setAccessible(true);
    var state = live.invoke(table, cache.internalFileId(fileId));
    var extents = state.getClass().getDeclaredMethod("extents");
    extents.setAccessible(true);
    var counters = extents.invoke(state);
    var allocate = counters.getClass().getDeclaredMethod("allocateThrough", long.class);
    allocate.setAccessible(true);
    allocate.invoke(counters, pages);
  }

  /** Removes one file's hydration and map evidence while retaining its computed disk path. */
  @SuppressWarnings("unchecked")
  public static void detachRecoveryIdentity(WOWCache cache, long fileId) throws Exception {
    int internalId = cache.internalFileId(fileId);
    var name = com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames
        .layout28(
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity
                .decode(internalId));
    var filesField = WOWCache.class.getDeclaredField("files");
    filesField.setAccessible(true);
    var files = (ClosableLinkedContainer<Long, File>) filesField.get(cache);
    var file = files.remove(fileId);
    if (file != null) {
      file.close();
    }
    for (var fieldName : new String[] {"nameIdMap", "idNameMap"}) {
      var field = WOWCache.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      var map = (java.util.Map<?, ?>) field.get(cache);
      map.remove(fieldName.equals("nameIdMap") ? name : internalId);
    }
    var tableField = WOWCache.class.getDeclaredField("logicalFiles");
    tableField.setAccessible(true);
    var table = tableField.get(cache);
    var forget = table.getClass().getDeclaredMethod("forget", int.class);
    forget.setAccessible(true);
    forget.invoke(table, internalId);
  }

  /** Appends explicit stale legacy evidence without invoking the live registration path. */
  public static void seedNameIdMap(WOWCache cache, String name, int id, String physical)
      throws Exception {
    var entryClass = Class.forName(
        "com.jetbrains.youtrackdb.internal.core.storage.cache.local.NameFileIdEntry");
    var constructor = entryClass.getDeclaredConstructor(String.class, int.class, String.class);
    constructor.setAccessible(true);
    var writer = WOWCache.class.getDeclaredMethod("writeNameIdEntry",
        java.nio.channels.FileChannel.class, entryClass, boolean.class);
    writer.setAccessible(true);
    try (var channel = java.nio.channels.FileChannel.open(
        cache.getRootDirectory().resolve("name_id_map_v3.cm"),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.READ,
        java.nio.file.StandardOpenOption.WRITE)) {
      writer.invoke(cache, channel, constructor.newInstance(name, id, physical), false);
    }
  }

  /** Rejects even transient session-map access until the caller closes this scope. */
  public static AutoCloseable poisonSessionMaps(WOWCache cache) throws Exception {
    var fields = new java.lang.reflect.Field[2];
    var originals = new Object[2];
    var names = new String[] {"nameIdMap", "idNameMap"};
    for (int i = 0; i < fields.length; i++) {
      var field = WOWCache.class.getDeclaredField(names[i]);
      field.setAccessible(true);
      fields[i] = field;
      originals[i] = field.get(cache);
      field.set(cache, org.mockito.Mockito.mock(field.getType(), invocation -> {
        throw new AssertionError("Unexpected session-map access: " + invocation.getMethod());
      }));
    }
    return () -> {
      for (int i = 0; i < fields.length; i++) {
        fields[i].set(cache, originals[i]);
      }
    };
  }

  /** Removes every persisted name-id map variant before a map-free cache or storage open. */
  public static void removeNameIdMaps(java.nio.file.Path directory) throws java.io.IOException {
    for (var name : new String[] {"name_id_map.cm", "name_id_map_v2.cm",
        "name_id_map_v2_backup.cm", "name_id_map_v3.cm", "name_id_map_v3_t.cm"}) {
      java.nio.file.Files.deleteIfExists(directory.resolve(name));
      org.junit.Assert.assertFalse(java.nio.file.Files.exists(directory.resolve(name)));
    }
  }

  /** Selects one birth layout for one manager and database on the current thread. */
  public static AutoCloseable selectBirthFormat(
      com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl manager,
      String databaseName,
      com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat format) {
    return AbstractStorage.useBirthFormatForCurrentThread(
        (com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded) manager.internal,
        databaseName, format);
  }

  /**
   * Installs two observers of one storage birth on the current thread.
   *
   * @param afterGenesis  runs after genesis finished and before the durability barrier starts
   * @param afterBarrier  runs after the durability barrier finished and before the activation
   * @return the scope that removes both observers again
   */
  public static AutoCloseable observeBirthCompletion(
      final Consumer<AbstractStorage> afterGenesis,
      final Consumer<AbstractStorage> afterBarrier) {
    Objects.requireNonNull(afterGenesis, "afterGenesis");
    Objects.requireNonNull(afterBarrier, "afterBarrier");
    return AbstractStorage.useBirthCompletionObserverForCurrentThread(
        new AbstractStorage.BirthCompletionObserver() {

          @Override
          public void afterGenesisBeforeBarrier(final AbstractStorage storage) {
            afterGenesis.accept(storage);
          }

          @Override
          public void afterBarrierBeforeActivation(final AbstractStorage storage) {
            afterBarrier.accept(storage);
          }
        });
  }
}
