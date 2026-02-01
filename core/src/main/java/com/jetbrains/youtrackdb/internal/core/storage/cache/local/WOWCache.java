/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableEntry;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.concur.lock.LockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ReadersWriterSpinLock;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.common.thread.ThreadPoolExecutors;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidStorageEncryptionKeyException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLog;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.AsyncFile;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Write part of disk cache which is used to collect pages which were changed on read cache and
 * store them to the disk in background thread. In current implementation only single background
 * thread is used to store all changed data, despite of SSD parallelization capabilities we suppose
 * that better to write data in single big chunk by one thread than by many small chunks from many
 * threads introducing contention and multi threading overhead. Another reasons for usage of only
 * one thread are
 *
 * <ol>
 *   <li>That we should give room for readers to read data during data write phase
 *   <li>It provides much less synchronization overhead
 * </ol>
 *
 * <p>Background thread is running by with predefined intervals. Such approach allows SSD GC to use
 * pauses to make some clean up of half empty erase blocks. Also write cache is used for checking of
 * free space left on disk and putting of database in "read mode" if space limit is reached and to
 * perform fuzzy checkpoints. Write cache holds two different type of pages, pages which are shared
 * with read cache and pages which belong only to write cache (so called exclusive pages). Files in
 * write cache are accessed by id , there are two types of ids, internal used inside of write cache
 * and external used outside of write cache. Presence of two types of ids is caused by the fact that
 * read cache is global across all storages but each storage has its own write cache. So all ids of
 * files should be global across whole read cache. External id is created from internal id by
 * prefixing of internal id (in byte presentation) with bytes of write cache id which is unique
 * across all storages opened inside of single JVM. Write cache accepts external ids as file ids and
 * converts them to internal ids inside of its methods.
 *
 * @since 7/23/13
 */
public final class WOWCache extends AbstractWriteCache
    implements WriteCache, CachePointer.WritersListener {

  private static final Logger logger = LoggerFactory.getLogger(WOWCache.class);


  private static final String ALGORITHM_NAME = "AES";
  private static final String TRANSFORMATION = "AES/CTR/NoPadding";

  private static final ThreadLocal<Cipher> CIPHER =
      ThreadLocal.withInitial(WOWCache::getCipherInstance);

  /**
   * Extension for the file which contains mapping between file name and file id
   */
  private static final String NAME_ID_MAP_EXTENSION = ".cm";

  /**
   * Name for file which contains first version of binary format
   */
  private static final String NAME_ID_MAP_V1 = "name_id_map" + NAME_ID_MAP_EXTENSION;

  /**
   * Name for file which contains second version of binary format. Second version of format contains
   * not only file name which is used in write cache but also file name which is used in file system
   * so those two names may be different which allows usage of case sensitive file names.
   */
  private static final String NAME_ID_MAP_V2 = "name_id_map_v2" + NAME_ID_MAP_EXTENSION;

  /**
   * Name for file which contains third version of binary format. Third version of format contains
   * not only file name which is used in write cache but also file name which is used in file system
   * so those two names may be different which allows usage of case sensitive file names. All this
   * information is wrapped by XX_HASH code which followed by content length, so any damaged records
   * are filtered out during loading of storage.
   */
  private static final String NAME_ID_MAP_V3 = "name_id_map_v3" + NAME_ID_MAP_EXTENSION;

  /**
   * Name of file temporary which contains third version of binary format. Temporary file is used to
   * prevent situation when DB is crashed because of migration to third version of binary format and
   * data are lost.
   *
   * @see #NAME_ID_MAP_V3
   * @see #storedNameIdMapToV3()
   */
  private static final String NAME_ID_MAP_V3_T = "name_id_map_v3_t" + NAME_ID_MAP_EXTENSION;

  /**
   * Name of the file which is used to compact file registry on close. All compacted data will be
   * written first to this file and then file will be atomically moved on the place of existing
   * registry.
   */
  private static final String NAME_ID_MAP_V2_BACKUP =
      "name_id_map_v2_backup" + NAME_ID_MAP_EXTENSION;

  /**
   * Maximum length of the row in file registry
   *
   * @see #NAME_ID_MAP_V3
   */
  private static final int MAX_FILE_RECORD_LEN = 16 << 10;

  /**
   * Marks pages which have a checksum stored.
   */
  public static final long MAGIC_NUMBER_WITH_CHECKSUM = 0xFACB03FEL;

  /**
   * Marks pages which have a checksum stored and data encrypted
   */
  public static final long MAGIC_NUMBER_WITH_CHECKSUM_ENCRYPTED = 0x1L;

  /**
   * Marks pages which have no checksum stored.
   */
  private static final long MAGIC_NUMBER_WITHOUT_CHECKSUM = 0xEF30BCAFL;

  /**
   * Marks pages which have no checksum stored but have data encrypted
   */
  private static final long MAGIC_NUMBER_WITHOUT_CHECKSUM_ENCRYPTED = 0x2L;

  private static final int MAGIC_NUMBER_OFFSET = 0;

  public static final int CHECKSUM_OFFSET = MAGIC_NUMBER_OFFSET + LongSerializer.LONG_SIZE;

  private static final int PAGE_OFFSET_TO_CHECKSUM_FROM =
      LongSerializer.LONG_SIZE + IntegerSerializer.INT_SIZE;

  private static final int CHUNK_SIZE = 64 * 1024 * 1024;

  /**
   * Executor which runs in single thread all tasks are related to flush of write cache data.
   */
  private static final ScheduledExecutorService commitExecutor;

  static {
    commitExecutor =
        ThreadPoolExecutors.newSingleThreadScheduledPool(
            "YouTrackDB Write Cache Flush Task", AbstractStorage.storageThreadGroup);
  }

  /**
   * Limit of free space on disk after which database will be switched to "read only" mode
   */
  private final long freeSpaceLimit =
      GlobalConfiguration.DISK_CACHE_FREE_SPACE_LIMIT.getValueAsLong() * 1024L * 1024L;

  /**
   * Listeners which are called once we detect that some of the pages of files are broken.
   */
  private final List<WeakReference<PageIsBrokenListener>> pageIsBrokenListeners =
      new CopyOnWriteArrayList<>();

  /**
   * Path to the storage root directory where all files served by write cache will be stored
   */
  private final Path storagePath;

  private final FileStore fileStore;

  /**
   * Container of all files are managed by write cache. That is special type of container which
   * ensures that only limited amount of files is open at the same time and opens closed files upon
   * request
   */
  private final ClosableLinkedContainer<Long, File> files;

  /**
   * The main storage of pages for write cache. If pages is hold by write cache it should be present
   * in this map. Map is ordered by position to speed up flush of pages to the disk
   */
  private final ConcurrentHashMap<PageKey, CachePointer> writeCachePages =
      new ConcurrentHashMap<>();

  /**
   * Storage for the pages which are hold only by write cache and are not shared with read cache.
   */
  private final ConcurrentSkipListSet<PageKey> exclusiveWritePages = new ConcurrentSkipListSet<>();

  /**
   * Container for dirty pages. Dirty pages table is concept taken from ARIES protocol. It contains
   * earliest LSNs of operations on each page which is potentially changed but not flushed to the
   * disk. It allows us by calculation of minimal LSN contained by this container calculate which
   * part of write ahead log may be already truncated. "dirty pages" itself is managed using
   * following algorithm.
   *
   * <ol>
   *   <li>Just after acquiring the exclusive lock on page we fetch LSN of latest record logged into
   *       WAL
   *   <li>If page with given index is absent into table we add it to this container
   * </ol>
   *
   * <p>Because we add last WAL LSN if we are going to modify page, it means that we can calculate
   * smallest LSN of operation which is not flushed to the log yet without locking of all operations
   * on database. There is may be situation when thread locks the page but did not add LSN to the
   * dirty pages table yet. If at the moment of start of iteration over the dirty pages table we
   * have a non empty dirty pages table it means that new operation on page will have LSN bigger
   * than any LSN already stored in table. If dirty pages table is empty at the moment of iteration
   * it means at the moment of start of iteration all page changes were flushed to the disk.
   */
  private final ConcurrentHashMap<PageKey, LogSequenceNumber> dirtyPages =
      new ConcurrentHashMap<>();

  /**
   * Copy of content of {@link #dirtyPages} table at the moment when
   * {@link #convertSharedDirtyPagesToLocal()} was called. This field is not thread safe because it
   * is used inside of tasks which are running inside of {@link #commitExecutor} thread. It is used
   * to keep results of postprocessing of {@link #dirtyPages} table. Every time we invoke
   * {@link #convertSharedDirtyPagesToLocal()} all content of dirty pages is removed and copied to
   * current field and {@link #localDirtyPagesBySegment} filed. Such approach is possible because
   * {@link #dirtyPages} table is filled by many threads but is read only from inside of
   * {@link #commitExecutor} thread.
   */
  private final HashMap<PageKey, LogSequenceNumber> localDirtyPages = new HashMap<>();

  /**
   * Copy of content of {@link #dirtyPages} table sorted by log segment and pages sorted by page
   * index.
   *
   * @see #localDirtyPages for details
   */
  private final TreeMap<Long, TreeSet<PageKey>> localDirtyPagesBySegment = new TreeMap<>();

  /**
   * Approximate amount of all pages contained by write cache at the moment
   */
  private final AtomicLong writeCacheSize = new AtomicLong();

  /**
   * Amount of exclusive pages are hold by write cache.
   */
  private final AtomicLong exclusiveWriteCacheSize = new AtomicLong();

  /**
   * Size of single page in cache in bytes.
   */
  private final int pageSize;

  /**
   * WAL instance
   */
  private final WriteAheadLog writeAheadLog;

  /**
   * Lock manager is used to acquire locks in RW mode for cases when we are going to read or write
   * page from write cache.
   */
  private final LockManager<PageKey> lockManager = new PartitionedLockManager<>();

  /**
   * We acquire lock managed by this manager in read mode if we need to read data from files, and in
   * write mode if we add/remove/truncate file.
   */
  private final ReadersWriterSpinLock filesLock = new ReadersWriterSpinLock();

  /**
   * Mapping between case sensitive file names are used in write cache and file's internal id. Name
   * of file in write cache is case sensitive and can be different from file name which is used to
   * store file in file system.
   */
  private final ConcurrentMap<String, Integer> nameIdMap = new ConcurrentHashMap<>();

  /**
   * Mapping between file's internal ids and case sensitive file names are used in write cache. Name
   * of file in write cache is case sensitive and can be different from file name which is used to
   * store file in file system.
   */
  private final ConcurrentMap<Integer, String> idNameMap = new ConcurrentHashMap<>();

  private final Random fileIdGen = new Random();

  /**
   * Path to the file which contains metadata for the files registered in storage.
   */
  private Path nameIdMapHolderPath;

  /**
   * Write cache id , which should be unique across all storages.
   */
  private final int id;

  /**
   * Pool of direct memory <code>ByteBuffer</code>s. We can not use them directly because they do
   * not have deallocator.
   */
  private final ByteBufferPool bufferPool;

  private final String storageName;
  private final String doubleWriteLogFileName;

  private volatile ChecksumMode checksumMode;

  /**
   * Error thrown during data flush. Once error registered no more write operations are allowed.
   */
  private Throwable flushError;

  /**
   * IV is used for AES encryption
   */
  private final byte[] iv;

  /**
   * Key is used for AES encryption
   */
  private final byte[] aesKey;

  private final int exclusiveWriteCacheMaxSize;

  private final boolean callFsync;

  private final int chunkSize;

  private final long pagesFlushInterval;
  private volatile boolean stopFlush;
  private volatile Future<?> flushFuture;

  private final ConcurrentHashMap<ExclusiveFlushTask, CountDownLatch> triggeredTasks =
      new ConcurrentHashMap<>();

  private final int shutdownTimeout;

  /**
   * Listeners which are called when exception in background data flush thread is happened.
   */
  private final List<WeakReference<BackgroundExceptionListener>> backgroundExceptionListeners =
      new CopyOnWriteArrayList<>();

  /**
   * Double write log which is used in write cache to prevent page tearing in case of server crash.
   */
  private final DoubleWriteLog doubleWriteLog;

  private boolean closed;
  private final ExecutorService executor;

  private final boolean logFileDeletion;

  public WOWCache(
      final int pageSize,
      final boolean logFileDeletion,
      final ByteBufferPool bufferPool,
      final WriteAheadLog writeAheadLog,
      final DoubleWriteLog doubleWriteLog,
      final long pagesFlushInterval,
      final int shutdownTimeout,
      final long exclusiveWriteCacheMaxSize,
      final Path storagePath,
      final String storageName,
      final ClosableLinkedContainer<Long, File> files,
      final int id, String doubleWriteLogFileName,
      final ChecksumMode checksumMode,
      final byte[] iv,
      final byte[] aesKey,
      final boolean callFsync,
      ExecutorService executor) {

    this.logFileDeletion = logFileDeletion;
    this.doubleWriteLogFileName = doubleWriteLogFileName;
    if (aesKey != null && aesKey.length != 16 && aesKey.length != 24 && aesKey.length != 32) {
      throw new InvalidStorageEncryptionKeyException(storageName,
          "Invalid length of the encryption key, provided size is " + aesKey.length);
    }

    if (aesKey != null && iv == null) {
      throw new InvalidStorageEncryptionKeyException(storageName, "IV can not be null");
    }

    this.shutdownTimeout = shutdownTimeout;
    this.pagesFlushInterval = pagesFlushInterval;
    this.iv = iv;
    this.aesKey = aesKey;
    this.callFsync = callFsync;

    filesLock.acquireWriteLock();
    try {
      this.closed = true;

      this.id = id;
      this.files = files;
      this.chunkSize = CHUNK_SIZE / pageSize;

      this.pageSize = pageSize;
      this.writeAheadLog = writeAheadLog;
      this.bufferPool = bufferPool;

      this.checksumMode = checksumMode;
      this.exclusiveWriteCacheMaxSize = normalizeMemory(exclusiveWriteCacheMaxSize, pageSize);

      this.storagePath = storagePath;
      try {
        this.fileStore = Files.getFileStore(this.storagePath);
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(storageName, "Error during retrieving of file store"), e,
            storageName);
      }

      this.storageName = storageName;

      this.doubleWriteLog = doubleWriteLog;

      if (pagesFlushInterval > 0) {
        flushFuture =
            commitExecutor.schedule(
                new PeriodicFlushTask(this), pagesFlushInterval, TimeUnit.MILLISECONDS);
      }
      this.executor = executor;
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  /**
   * Loads files already registered in storage. Has to be called before usage of this cache
   */
  public void loadRegisteredFiles() throws IOException, java.lang.InterruptedException {
    filesLock.acquireWriteLock();
    try {
      initNameIdMapping();

      doubleWriteLog.open(storageName, doubleWriteLogFileName, storagePath, pageSize);

      closed = false;
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  /**
   * Adds listener which is triggered if exception is cast inside background flush data thread.
   *
   * @param listener Listener to trigger
   */
  @Override
  public void addBackgroundExceptionListener(final BackgroundExceptionListener listener) {
    backgroundExceptionListeners.add(new WeakReference<>(listener));
  }

  /**
   * Removes listener which is triggered if exception is cast inside background flush data thread.
   *
   * @param listener Listener to remove
   */
  @Override
  public void removeBackgroundExceptionListener(final BackgroundExceptionListener listener) {
    final List<WeakReference<BackgroundExceptionListener>> itemsToRemove = new ArrayList<>(1);

    for (final var ref : backgroundExceptionListeners) {
      final var l = ref.get();
      if (l != null && l.equals(listener)) {
        itemsToRemove.add(ref);
      }
    }

    backgroundExceptionListeners.removeAll(itemsToRemove);
  }

  /**
   * Fires event about exception is thrown in data flush thread
   */
  private void fireBackgroundDataFlushExceptionEvent(final Throwable e) {
    for (final var ref : backgroundExceptionListeners) {
      final var listener = ref.get();
      if (listener != null) {
        listener.onException(e);
      }
    }
  }

  private static int normalizeMemory(final long maxSize, final int pageSize) {
    final var tmpMaxSize = maxSize / pageSize;
    if (tmpMaxSize >= Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    } else {
      return (int) tmpMaxSize;
    }
  }

  /**
   * Directory which contains all files managed by write cache.
   *
   * @return Directory which contains all files managed by write cache or <code>null</code> in case
   * of in memory database.
   */
  @Override
  public Path getRootDirectory() {
    return storagePath;
  }

  /**
   * @inheritDoc
   */
  @Override
  public void addPageIsBrokenListener(final PageIsBrokenListener listener) {
    pageIsBrokenListeners.add(new WeakReference<>(listener));
  }

  /**
   * @inheritDoc
   */
  @Override
  public void removePageIsBrokenListener(final PageIsBrokenListener listener) {
    final List<WeakReference<PageIsBrokenListener>> itemsToRemove = new ArrayList<>(1);

    for (final var ref : pageIsBrokenListeners) {
      final var pageIsBrokenListener = ref.get();

      if (pageIsBrokenListener == null || pageIsBrokenListener.equals(listener)) {
        itemsToRemove.add(ref);
      }
    }

    pageIsBrokenListeners.removeAll(itemsToRemove);
  }

  private void callPageIsBrokenListeners(final String fileName, final long pageIndex) {
    for (final var pageIsBrokenListenerWeakReference :
        pageIsBrokenListeners) {
      final var listener = pageIsBrokenListenerWeakReference.get();
      if (listener != null) {
        try {
          listener.pageIsBroken(fileName, pageIndex);
        } catch (final Exception e) {
          LogManager.instance()
              .error(
                  this,
                  "Error during notification of page is broken for storage " + storageName,
                  e);
        }
      }
    }
  }

  @Override
  public long bookFileId(final String fileName) {
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      final var fileId = nameIdMap.get(fileName);
      if (fileId != null) {
        if (fileId < 0) {
          return composeFileId(id, -fileId);
        } else {
          throw new StorageException(storageName,
              "File " + fileName + " has already been added to the storage");
        }
      }
      while (true) {
        final var nextId = fileIdGen.nextInt(Integer.MAX_VALUE - 1) + 1;
        if (!idNameMap.containsKey(nextId) && !idNameMap.containsKey(-nextId)) {
          nameIdMap.put(fileName, -nextId);
          idNameMap.put(-nextId, fileName);
          return composeFileId(id, nextId);
        }
      }
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  /**
   * @inheritDoc
   */
  @Override
  public int pageSize() {
    return pageSize;
  }

  @Override
  public long loadFile(final String fileName) throws IOException {
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      var fileId = nameIdMap.get(fileName);
      final File fileClassic;

      // check that file is already registered
      if (!(fileId == null || fileId < 0)) {
        final var externalId = composeFileId(id, fileId);
        fileClassic = files.get(externalId);

        if (fileClassic != null) {
          return externalId;
        } else {
          throw new StorageException(storageName,
              "File with given name " + fileName + " only partially registered in storage");
        }
      }

      if (fileId == null) {
        while (true) {
          final var nextId = fileIdGen.nextInt(Integer.MAX_VALUE - 1) + 1;
          if (!idNameMap.containsKey(nextId) && !idNameMap.containsKey(-nextId)) {
            fileId = nextId;
            break;
          }
        }
      } else {
        idNameMap.remove(fileId);
        fileId = -fileId;
      }

      fileClassic = createFileInstance(fileName, fileId);

      if (!fileClassic.exists()) {
        throw new StorageException(storageName,
            "File with name " + fileName + " does not exist in storage " + storageName);
      } else {
        // REGISTER THE FILE
        LogManager.instance()
            .debug(
                this,
                "File '"
                    + fileName
                    + "' is not registered in 'file name - id' map, but exists in file system."
                    + " Registering it", logger);

        openFile(storageName, fileClassic);

        final var externalId = composeFileId(id, fileId);
        files.add(externalId, fileClassic);

        nameIdMap.put(fileName, fileId);
        idNameMap.put(fileId, fileName);

        writeNameIdEntry(new NameFileIdEntry(fileName, fileId, fileClassic.getName()), true);

        return externalId;
      }
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName, "Load file was interrupted"), e, storageName);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public long addFile(final String fileName) throws IOException {
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      var fileId = nameIdMap.get(fileName);
      final File fileClassic;

      if (fileId != null && fileId >= 0) {
        throw new StorageException(storageName,
            "File with name " + fileName + " already exists in storage " + storageName);
      }

      if (fileId == null) {
        while (true) {
          final var nextId = fileIdGen.nextInt(Integer.MAX_VALUE - 1) + 1;
          if (!idNameMap.containsKey(nextId) && !idNameMap.containsKey(-nextId)) {
            fileId = nextId;
            break;
          }
        }
      } else {
        idNameMap.remove(fileId);
        fileId = -fileId;
      }

      fileClassic = createFileInstance(fileName, fileId);
      createFile(fileClassic, callFsync);

      final var externalId = composeFileId(id, fileId);
      files.add(externalId, fileClassic);

      nameIdMap.put(fileName, fileId);
      idNameMap.put(fileId, fileName);

      writeNameIdEntry(new NameFileIdEntry(fileName, fileId, fileClassic.getName()), true);

      return externalId;
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName, "File add was interrupted"), e, storageName);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public long fileIdByName(final String fileName) {
    final var intId = nameIdMap.get(fileName);

    if (intId == null || intId < 0) {
      return -1;
    }

    return composeFileId(id, intId);
  }

  @Override
  public int internalFileId(final long fileId) {
    return extractFileId(fileId);
  }

  @Override
  public long externalFileId(final int fileId) {
    return composeFileId(id, fileId);
  }

  @Override
  public Long getMinimalNotFlushedSegment() {
    final var future = commitExecutor.submit(new FindMinDirtySegment(this));
    try {
      return future.get();
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void updateDirtyPagesTable(
      final CachePointer pointer, final LogSequenceNumber startLSN) {
    final var fileId = pointer.getFileId();
    final long pageIndex = pointer.getPageIndex();

    final var pageKey = new PageKey(internalFileId(fileId), pageIndex);

    LogSequenceNumber dirtyLSN;
    if (startLSN != null) {
      dirtyLSN = startLSN;
    } else {
      dirtyLSN = writeAheadLog.end();
    }

    if (dirtyLSN == null) {
      dirtyLSN = new LogSequenceNumber(0, 0);
    }

    dirtyPages.putIfAbsent(pageKey, dirtyLSN);
  }

  @Override
  public void create() {
  }

  @Override
  public void open() {
  }

  @Override
  public long addFile(final String fileName, long fileId) throws IOException {
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      File fileClassic;

      final var existingFileId = nameIdMap.get(fileName);

      final var intId = extractFileId(fileId);

      if (existingFileId != null && existingFileId >= 0) {
        if (existingFileId == intId) {
          throw new StorageException(storageName,
              "File with name '" + fileName + "'' already exists in storage '" + storageName + "'");
        } else {
          throw new StorageException(storageName,
              "File with given name '"
                  + fileName
                  + "' already exists but has different id "
                  + existingFileId
                  + " vs. proposed "
                  + fileId);
        }
      }

      fileId = composeFileId(id, intId);
      fileClassic = files.get(fileId);

      if (fileClassic != null) {
        if (!fileClassic.getName().equals(createInternalFileName(fileName, intId))) {
          throw new StorageException(storageName,
              "File with given id exists but has different name "
                  + fileClassic.getName()
                  + " vs. proposed "
                  + fileName);
        }

        fileClassic.shrink(0);

        if (callFsync) {
          fileClassic.synch();
        }
      } else {
        fileClassic = createFileInstance(fileName, intId);
        createFile(fileClassic, callFsync);

        files.add(fileId, fileClassic);
      }

      idNameMap.remove(-intId);

      nameIdMap.put(fileName, intId);
      idNameMap.put(intId, fileName);

      writeNameIdEntry(new NameFileIdEntry(fileName, intId, fileClassic.getName()), true);

      return fileId;
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName, "File add was interrupted"), e, storageName);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public boolean checkLowDiskSpace() throws IOException {
    final var freeSpace = fileStore.getUsableSpace();
    return freeSpace < freeSpaceLimit;
  }

  @Override
  public void syncDataFiles(final long segmentId) throws IOException {
    filesLock.acquireReadLock();
    try {
      checkForClose();

      doubleWriteLog.startCheckpoint();
      try {
        for (final var intId : nameIdMap.values()) {
          if (intId < 0) {
            continue;
          }

          if (callFsync) {
            final var fileId = composeFileId(id, intId);
            final var entry = files.acquire(fileId);
            try {
              final var fileClassic = entry.get();
              fileClassic.synch();
            } finally {
              files.release(entry);
            }
          }
        }

        writeAheadLog.flush();
        writeAheadLog.cutAllSegmentsSmallerThan(segmentId);
      } finally {
        doubleWriteLog.endCheckpoint();
      }
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName, "Fuzzy checkpoint was interrupted"),
          e, storageName);
    } finally {
      filesLock.releaseReadLock();
    }
  }

  @Override
  public void flushTillSegment(final long segmentId) {
    final var future = commitExecutor.submit(new FlushTillSegmentTask(this, segmentId));
    try {
      future.get();
    } catch (final Exception e) {
      throw DatabaseException.wrapException(
          new StorageException(storageName, "Error during data flush"), e, storageName);
    }
  }

  @Override
  public boolean exists(final String fileName) {
    filesLock.acquireReadLock();
    try {
      checkForClose();

      final var intId = nameIdMap.get(fileName);
      if (intId != null && intId >= 0) {
        final var fileClassic = files.get(externalFileId(intId));

        if (fileClassic == null) {
          return false;
        }
        return fileClassic.exists();
      }
      return false;
    } finally {
      filesLock.releaseReadLock();
    }
  }

  @Override
  public boolean exists(long fileId) {
    filesLock.acquireReadLock();
    try {
      checkForClose();

      final var intId = extractFileId(fileId);
      fileId = composeFileId(id, intId);

      final var file = files.get(fileId);
      if (file == null) {
        return false;
      }
      return file.exists();
    } finally {
      filesLock.releaseReadLock();
    }
  }

  @Override
  public void restoreModeOn() throws IOException {
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      doubleWriteLog.restoreModeOn();
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public void restoreModeOff() {
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      doubleWriteLog.restoreModeOff();
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public void checkCacheOverflow() throws java.lang.InterruptedException {
    while (exclusiveWriteCacheSize.get() > exclusiveWriteCacheMaxSize) {
      final var cacheBoundaryLatch = new CountDownLatch(1);
      final var completionLatch = new CountDownLatch(1);
      final var exclusiveFlushTask =
          new ExclusiveFlushTask(this, cacheBoundaryLatch, completionLatch);

      triggeredTasks.put(exclusiveFlushTask, completionLatch);
      commitExecutor.submit(exclusiveFlushTask);

      cacheBoundaryLatch.await();
    }
  }

  @Override
  public void store(final long fileId, final long pageIndex, final CachePointer dataPointer) {
    final var intId = extractFileId(fileId);

    filesLock.acquireReadLock();
    try {
      checkForClose();

      final var pageKey = new PageKey(intId, pageIndex);

      final var groupLock = lockManager.acquireExclusiveLock(pageKey);
      try {
        final var pagePointer = writeCachePages.get(pageKey);

        if (pagePointer == null) {
          doPutInCache(dataPointer, pageKey);
        } else {
          assert pagePointer.equals(dataPointer);
        }

      } finally {
        groupLock.unlock();
      }

    } finally {
      filesLock.releaseReadLock();
    }
  }

  private void doPutInCache(final CachePointer dataPointer, final PageKey pageKey) {
    writeCachePages.put(pageKey, dataPointer);

    writeCacheSize.incrementAndGet();

    dataPointer.setWritersListener(this);
    dataPointer.incrementWritersReferrer();
  }

  @Override
  public Map<String, Long> files() {
    filesLock.acquireReadLock();
    try {
      checkForClose();

      final var result = new Object2LongOpenHashMap<String>(1_000);
      result.defaultReturnValue(-1);

      for (final var entry : nameIdMap.entrySet()) {
        if (entry.getValue() > 0) {
          result.put(entry.getKey(), composeFileId(id, entry.getValue()));
        }
      }

      return result;
    } finally {
      filesLock.releaseReadLock();
    }
  }

  @Override
  public CachePointer load(
      final long fileId,
      final long startPageIndex,
      final ModifiableBoolean cacheHit,
      final boolean verifyChecksums)
      throws IOException {
    final var intId = extractFileId(fileId);
    filesLock.acquireReadLock();
    try {
      checkForClose();

      final var pageKey = new PageKey(intId, startPageIndex);
      final var pageLock = lockManager.acquireSharedLock(pageKey);

      // check if page already presented in write cache
      final var pagePointer = writeCachePages.get(pageKey);

      // page is not cached load it from file
      if (pagePointer == null) {
        try {
          // load requested page and preload requested amount of pages
          final var filePagePointer =
              loadFileContent(intId, startPageIndex, verifyChecksums);
          if (filePagePointer != null) {
            filePagePointer.incrementReadersReferrer();
          }

          return filePagePointer;
        } finally {
          pageLock.unlock();
        }
      }

      pagePointer.incrementReadersReferrer();
      pageLock.unlock();

      cacheHit.setValue(true);

      return pagePointer;
    } finally {
      filesLock.releaseReadLock();
    }
  }

  @Override
  public int allocateNewPage(final long fileId) throws IOException {
    int pageIndex;
    filesLock.acquireReadLock();
    try {
      checkForClose();

      final var entry = files.acquire(fileId);
      try {
        final var fileClassic = entry.get();
        final var allocatedPosition = fileClassic.allocateSpace(pageSize);
        final var allocationIndex = allocatedPosition / pageSize;

        pageIndex = (int) allocationIndex;
        if (pageIndex < 0) {
          throw new IllegalStateException("Illegal page index value " + pageIndex);
        }

      } finally {
        files.release(entry);
      }
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName, "Allocation of page was interrupted"), e, storageName);
    } finally {
      filesLock.releaseReadLock();
    }
    commitExecutor.submit(new EnsurePageIsValidInFileTask(internalFileId(fileId), pageIndex, this));

    return pageIndex;
  }

  @Override
  public void addOnlyWriters(final long fileId, final long pageIndex) {
    exclusiveWriteCacheSize.incrementAndGet();
    exclusiveWritePages.add(new PageKey(extractFileId(fileId), pageIndex));
  }

  @Override
  public void removeOnlyWriters(final long fileId, final long pageIndex) {
    exclusiveWriteCacheSize.decrementAndGet();
    exclusiveWritePages.remove(new PageKey(extractFileId(fileId), pageIndex));
  }

  @Override
  public void flush(final long fileId) {
    final var future =
        commitExecutor.submit(
            new FileFlushTask(this, Collections.singleton(extractFileId(fileId))));
    try {
      future.get();
    } catch (final java.lang.InterruptedException e) {
      Thread.currentThread().interrupt();
      throw BaseException.wrapException(
          new ThreadInterruptedException("File flush was interrupted"), e, storageName);
    } catch (final Exception e) {
      throw BaseException.wrapException(
          new WriteCacheException(storageName, "File flush was abnormally terminated"), e,
          storageName);
    }
  }

  @Override
  public void flush() {

    final var future = commitExecutor.submit(new FileFlushTask(this, nameIdMap.values()));
    try {
      future.get();
    } catch (final java.lang.InterruptedException e) {
      Thread.currentThread().interrupt();
      throw BaseException.wrapException(
          new ThreadInterruptedException("File flush was interrupted"), e, storageName);
    } catch (final Exception e) {
      throw BaseException.wrapException(
          new WriteCacheException(storageName, "File flush was abnormally terminated"), e,
          storageName);
    }
  }

  @Override
  public long getFilledUpTo(long fileId) {
    final var intId = extractFileId(fileId);
    fileId = composeFileId(id, intId);

    filesLock.acquireReadLock();
    try {
      checkForClose();

      var file = files.get(fileId);
      return file.getFileSize() / pageSize;
    } finally {
      filesLock.releaseReadLock();
    }
  }

  @Override
  public long getExclusiveWriteCachePagesSize() {
    return exclusiveWriteCacheSize.get();
  }

  @Override
  public void deleteFile(final long fileId) throws IOException {
    final var intId = extractFileId(fileId);

    filesLock.acquireWriteLock();
    try {
      checkForClose();

      final RawPair<String, String> file;
      final var future =
          commitExecutor.submit(new DeleteFileTask(this, fileId));
      try {
        file = future.get();
      } catch (final java.lang.InterruptedException e) {
        throw BaseException.wrapException(
            new ThreadInterruptedException("File data removal was interrupted"), e, storageName);
      } catch (final Exception e) {
        throw BaseException.wrapException(
            new WriteCacheException(storageName, "File data removal was abnormally terminated"), e,
            storageName);
      }

      if (file != null) {
        writeNameIdEntry(new NameFileIdEntry(file.first(), -intId, file.second()), true);
      }
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public void truncateFile(long fileId) throws IOException {
    final var intId = extractFileId(fileId);
    fileId = composeFileId(id, intId);

    filesLock.acquireWriteLock();
    try {
      checkForClose();

      removeCachedPages(intId);
      final var entry = files.acquire(fileId);
      try {
        entry.get().shrink(0);
      } finally {
        files.release(entry);
      }
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName, "File truncation was interrupted"),
          e, storageName);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public boolean fileIdsAreEqual(final long firsId, final long secondId) {
    final var firstIntId = extractFileId(firsId);
    final var secondIntId = extractFileId(secondId);

    return firstIntId == secondIntId;
  }

  @Override
  public void renameFile(long fileId, final String newFileName) throws IOException {
    final var intId = extractFileId(fileId);
    fileId = composeFileId(id, intId);

    filesLock.acquireWriteLock();
    try {
      checkForClose();

      final var entry = files.acquire(fileId);

      if (entry == null) {
        return;
      }

      final String oldOsFileName;
      final var newOsFileName = createInternalFileName(newFileName, intId);

      try {
        final var file = entry.get();
        oldOsFileName = file.getName();

        final var newFile = storagePath.resolve(newOsFileName);
        file.renameTo(newFile);
      } finally {
        files.release(entry);
      }

      final var oldFileName = idNameMap.get(intId);

      nameIdMap.remove(oldFileName);
      nameIdMap.put(newFileName, intId);

      idNameMap.put(intId, newFileName);

      writeNameIdEntry(new NameFileIdEntry(oldFileName, -1, oldOsFileName), false);
      writeNameIdEntry(new NameFileIdEntry(newFileName, intId, newOsFileName), true);
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName, "Rename of file was interrupted"),
          e, storageName);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public void replaceFileId(final long fileId, final long newFileId) throws IOException {
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      final var file = files.remove(fileId);
      final var newFile = files.remove(newFileId);

      final var intFileId = extractFileId(fileId);
      final var newIntFileId = extractFileId(newFileId);

      final var fileName = idNameMap.get(intFileId);
      final var newFileName = idNameMap.remove(newIntFileId);

      if (!file.isOpen()) {
        file.open();
      }
      if (!newFile.isOpen()) {
        newFile.open();
      }

      // invalidate old entries
      writeNameIdEntry(new NameFileIdEntry(fileName, 0, ""), false);
      writeNameIdEntry(new NameFileIdEntry(newFileName, 0, ""), false);

      // add new one
      writeNameIdEntry(new NameFileIdEntry(newFileName, intFileId, file.getName()), true);

      file.delete();

      files.add(fileId, newFile);

      idNameMap.put(intFileId, newFileName);
      nameIdMap.remove(fileName);
      nameIdMap.put(newFileName, intFileId);
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName, "Replace of file was interrupted"),
          e, storageName);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  private void stopFlush() {
    stopFlush = true;

    for (final var completionLatch : triggeredTasks.values()) {
      try {
        if (!completionLatch.await(shutdownTimeout, TimeUnit.MINUTES)) {
          throw new WriteCacheException(storageName,
              "Can not shutdown data flush for storage " + storageName);
        }
      } catch (final java.lang.InterruptedException e) {
        throw BaseException.wrapException(
            new WriteCacheException(storageName,
                "Flush of the data for storage " + storageName + " has been interrupted"),
            e, storageName);
      }
    }

    if (flushFuture != null) {
      try {
        flushFuture.get(shutdownTimeout, TimeUnit.MINUTES);
      } catch (final java.lang.InterruptedException | CancellationException e) {
        // ignore
      } catch (final ExecutionException e) {
        throw BaseException.wrapException(
            new WriteCacheException(storageName,
                "Error in execution of data flush for storage " + storageName),
            e, storageName);
      } catch (final TimeoutException e) {
        throw BaseException.wrapException(
            new WriteCacheException(storageName,
                "Can not shutdown data flush for storage " + storageName),
            e, storageName);
      }
    }
  }

  @Override
  public long[] close() throws IOException {
    flush();
    stopFlush();

    filesLock.acquireWriteLock();
    try {
      if (closed) {
        return new long[0];
      }

      closed = true;

      final var fileIds = nameIdMap.values();

      final var closedIds = new LongArrayList(1_000);
      final var idFileNameMap = new Int2ObjectOpenHashMap<String>(1_000);

      for (final var intId : fileIds) {
        if (intId >= 0) {
          final var extId = composeFileId(id, intId);
          final var fileClassic = files.remove(extId);

          idFileNameMap.put(intId.intValue(), fileClassic.getName());
          fileClassic.close();
          closedIds.add(extId);
        }
      }

      final var nameIdMapBackupPath = storagePath.resolve(NAME_ID_MAP_V2_BACKUP);
      try (final var nameIdMapHolder =
          FileChannel.open(
              nameIdMapBackupPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE)) {
        nameIdMapHolder.truncate(0);

        for (final var entry : nameIdMap.entrySet()) {
          final String fileName;

          if (entry.getValue() >= 0) {
            fileName = idFileNameMap.get(entry.getValue().intValue());
          } else {
            fileName = entry.getKey();
          }

          writeNameIdEntry(
              nameIdMapHolder,
              new NameFileIdEntry(entry.getKey(), entry.getValue(), fileName),
              false);
        }

        nameIdMapHolder.force(true);
      }

      try {
        Files.move(
            nameIdMapBackupPath,
            nameIdMapHolderPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(nameIdMapBackupPath, nameIdMapHolderPath, StandardCopyOption.REPLACE_EXISTING);
      }

      doubleWriteLog.close();

      nameIdMap.clear();
      idNameMap.clear();

      return closedIds.toLongArray();
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  private void checkForClose() {
    if (closed) {
      throw new StorageException(storageName, "Write cache is closed and can not be used");
    }
  }

  @Override
  public void close(long fileId, final boolean flush) {
    final var intId = extractFileId(fileId);
    fileId = composeFileId(id, intId);

    filesLock.acquireWriteLock();
    try {
      checkForClose();

      if (flush) {
        flush(intId);
      } else {
        removeCachedPages(intId);
      }

      if (!files.close(fileId)) {
        throw new StorageException(storageName,
            "Can not close file with id " + internalFileId(fileId) + " because it is still in use");
      }
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Nullable
  @Override
  public String restoreFileById(final long fileId) throws IOException {
    final var intId = extractFileId(fileId);
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      for (final var entry : nameIdMap.entrySet()) {
        if (entry.getValue() == -intId) {
          addFile(entry.getKey(), fileId);
          return entry.getKey();
        }
      }
    } finally {
      filesLock.releaseWriteLock();
    }

    return null;
  }

  @Override
  public PageDataVerificationError[] checkStoredPages(
      final CommandOutputListener commandOutputListener) {
    final var notificationTimeOut = 5000;

    final List<PageDataVerificationError> errors = new ArrayList<>(0);

    filesLock.acquireWriteLock();
    try {
      checkForClose();

      for (final var intId : nameIdMap.values()) {
        if (intId < 0) {
          continue;
        }

        checkFileStoredPages(commandOutputListener, notificationTimeOut, errors, intId);
      }

      return errors.toArray(new PageDataVerificationError[0]);
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(new StorageException(storageName, "Thread was interrupted"),
          e, storageName);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  private void checkFileStoredPages(
      final CommandOutputListener commandOutputListener,
      @SuppressWarnings("SameParameterValue") final int notificationTimeOut,
      final List<PageDataVerificationError> errors,
      final Integer intId)
      throws java.lang.InterruptedException {
    boolean fileIsCorrect;
    final var externalId = composeFileId(id, intId);
    final var entry = files.acquire(externalId);
    final var fileClassic = entry.get();
    final var fileName = idNameMap.get(intId);

    try {
      if (commandOutputListener != null) {
        commandOutputListener.onMessage("Flashing file " + fileName + "... ");
      }

      flush(intId);

      if (commandOutputListener != null) {
        commandOutputListener.onMessage(
            "Start verification of content of " + fileName + "file ...\n");
      }

      var time = System.currentTimeMillis();

      final var filledUpTo = fileClassic.getFileSize();
      fileIsCorrect = true;

      for (long pos = 0; pos < filledUpTo; pos += pageSize) {
        var checkSumIncorrect = false;
        var magicNumberIncorrect = false;

        final var data = new byte[pageSize];

        final var pointer = bufferPool.acquireDirect(true, Intention.CHECK_FILE_STORAGE);
        try {
          final var byteBuffer = pointer.getNativeByteBuffer();
          fileClassic.read(pos, byteBuffer, true);
          byteBuffer.rewind();
          byteBuffer.get(data);
        } finally {
          bufferPool.release(pointer);
        }

        final var magicNumber =
            LongSerializer.INSTANCE.deserializeNative(data, MAGIC_NUMBER_OFFSET);

        if (magicNumber != MAGIC_NUMBER_WITH_CHECKSUM
            && magicNumber != MAGIC_NUMBER_WITHOUT_CHECKSUM
            && magicNumber != MAGIC_NUMBER_WITH_CHECKSUM_ENCRYPTED
            && magicNumber != MAGIC_NUMBER_WITHOUT_CHECKSUM_ENCRYPTED) {
          magicNumberIncorrect = true;
          if (commandOutputListener != null) {
            commandOutputListener.onMessage(
                "Error: Magic number for page "
                    + (pos / pageSize)
                    + " in file '"
                    + fileName
                    + "' does not match!\n");
          }
          fileIsCorrect = false;
        }

        if (magicNumber != MAGIC_NUMBER_WITHOUT_CHECKSUM) {
          final var storedCRC32 =
              IntegerSerializer.deserializeNative(data, CHECKSUM_OFFSET);

          final var crc32 = new CRC32();
          crc32.update(
              data, PAGE_OFFSET_TO_CHECKSUM_FROM, data.length - PAGE_OFFSET_TO_CHECKSUM_FROM);
          final var calculatedCRC32 = (int) crc32.getValue();

          if (storedCRC32 != calculatedCRC32) {
            checkSumIncorrect = true;
            if (commandOutputListener != null) {
              commandOutputListener.onMessage(
                  "Error: Checksum for page "
                      + (pos / pageSize)
                      + " in file '"
                      + fileName
                      + "' is incorrect!\n");
            }
            fileIsCorrect = false;
          }
        }

        if (magicNumberIncorrect || checkSumIncorrect) {
          errors.add(
              new PageDataVerificationError(
                  magicNumberIncorrect, checkSumIncorrect, pos / pageSize, fileName));
        }

        if (commandOutputListener != null
            && System.currentTimeMillis() - time > notificationTimeOut) {
          time = notificationTimeOut;
          commandOutputListener.onMessage((pos / pageSize) + " pages were processed...\n");
        }
      }
    } catch (final IOException ioe) {
      if (commandOutputListener != null) {
        commandOutputListener.onMessage(
            "Error: Error during processing of file '"
                + fileName
                + "'. "
                + ioe.getMessage()
                + "\n");
      }

      fileIsCorrect = false;
    } finally {
      files.release(entry);
    }

    if (!fileIsCorrect) {
      if (commandOutputListener != null) {
        commandOutputListener.onMessage(
            "Verification of file '" + fileName + "' is finished with errors.\n");
      }
    } else {
      if (commandOutputListener != null) {
        commandOutputListener.onMessage(
            "Verification of file '" + fileName + "' is successfully finished.\n");
      }
    }
  }

  @Override
  public long[] delete() throws IOException {
    final var result = new LongArrayList(1_024);
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      for (final int internalFileId : nameIdMap.values()) {
        if (internalFileId < 0) {
          continue;
        }

        final var externalId = composeFileId(id, internalFileId);

        final RawPair<String, String> file;
        final var future =
            commitExecutor.submit(new DeleteFileTask(this, externalId));
        try {
          file = future.get();
        } catch (final java.lang.InterruptedException e) {
          throw BaseException.wrapException(
              new ThreadInterruptedException("File data removal was interrupted"), e, storageName);
        } catch (final Exception e) {
          throw BaseException.wrapException(
              new WriteCacheException(storageName, "File data removal was abnormally terminated"),
              e, storageName);
        }

        if (file != null) {
          result.add(externalId);
        }
      }

      if (nameIdMapHolderPath != null) {
        if (Files.exists(nameIdMapHolderPath)) {
          Files.delete(nameIdMapHolderPath);
        }

        nameIdMapHolderPath = null;
      }
    } finally {
      filesLock.releaseWriteLock();
    }

    stopFlush();
    doubleWriteLog.close();

    return result.toLongArray();
  }

  @Override
  public String fileNameById(final long fileId) {
    final var intId = extractFileId(fileId);

    return idNameMap.get(intId);
  }

  @Nullable
  @Override
  public String nativeFileNameById(final long fileId) {
    final var fileClassic = files.get(fileId);
    if (fileClassic != null) {
      return fileClassic.getName();
    }

    return null;
  }

  @Override
  public int getId() {
    return id;
  }

  private static void openFile(String storageName, final File fileClassic) {
    if (fileClassic.exists()) {
      if (!fileClassic.isOpen()) {
        fileClassic.open();
      }
    } else {
      throw new StorageException(storageName, "File " + fileClassic + " does not exist.");
    }
  }

  private static void createFile(final File fileClassic, final boolean callFsync)
      throws IOException {
    if (!fileClassic.exists()) {
      fileClassic.create();
    } else {
      if (!fileClassic.isOpen()) {
        fileClassic.open();
      }
      fileClassic.shrink(0);
    }

    if (callFsync) {
      fileClassic.synch();
    }
  }

  private void initNameIdMapping() throws IOException, java.lang.InterruptedException {
    if (!Files.exists(storagePath)) {
      Files.createDirectories(storagePath);
    }

    final var nameIdMapHolderV1 = storagePath.resolve(NAME_ID_MAP_V1);
    final var nameIdMapHolderV2 = storagePath.resolve(NAME_ID_MAP_V2);
    final var nameIdMapHolderV3 = storagePath.resolve(NAME_ID_MAP_V3);

    if (Files.exists(nameIdMapHolderV1)) {
      if (Files.exists(nameIdMapHolderV2)) {
        Files.delete(nameIdMapHolderV2);
      }
      if (Files.exists(nameIdMapHolderV3)) {
        Files.delete(nameIdMapHolderV3);
      }

      try (final var nameIdMapHolder =
          FileChannel.open(nameIdMapHolderV1, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
        readNameIdMapV1(nameIdMapHolder);
      }

      Files.delete(nameIdMapHolderV1);
    } else if (Files.exists(nameIdMapHolderV2)) {
      if (Files.exists(nameIdMapHolderV3)) {
        Files.delete(nameIdMapHolderV3);
      }

      try (final var nameIdMapHolder =
          FileChannel.open(nameIdMapHolderV2, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
        readNameIdMapV2(nameIdMapHolder);
      }

      Files.delete(nameIdMapHolderV2);
    }

    nameIdMapHolderPath = nameIdMapHolderV3;
    if (Files.exists(nameIdMapHolderPath)) {
      try (final var nameIdMapHolder =
          FileChannel.open(
              nameIdMapHolderPath, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
        readNameIdMapV3(nameIdMapHolder);
      }
    } else {
      storedNameIdMapToV3();
    }
  }

  private void storedNameIdMapToV3() throws IOException {
    final var nameIdMapHolderFileV3T = storagePath.resolve(NAME_ID_MAP_V3_T);

    if (Files.exists(nameIdMapHolderFileV3T)) {
      Files.delete(nameIdMapHolderFileV3T);
    }

    final var v3NameIdMapHolder =
        FileChannel.open(
            nameIdMapHolderFileV3T,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ);

    for (final var nameIdEntry : nameIdMap.entrySet()) {
      if (nameIdEntry.getValue() >= 0) {
        final var fileClassic = files.get(externalFileId(nameIdEntry.getValue()));
        final var fileSystemName = fileClassic.getName();

        final var nameFileIdEntry =
            new NameFileIdEntry(nameIdEntry.getKey(), nameIdEntry.getValue(), fileSystemName);
        writeNameIdEntry(v3NameIdMapHolder, nameFileIdEntry, false);
      } else {
        final var nameFileIdEntry =
            new NameFileIdEntry(nameIdEntry.getKey(), nameIdEntry.getValue(), "");
        writeNameIdEntry(v3NameIdMapHolder, nameFileIdEntry, false);
      }
    }

    v3NameIdMapHolder.force(true);
    v3NameIdMapHolder.close();

    try {
      Files.move(
          nameIdMapHolderFileV3T,
          storagePath.resolve(NAME_ID_MAP_V3),
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(nameIdMapHolderFileV3T, storagePath.resolve(NAME_ID_MAP_V3));
    }
  }

  private File createFileInstance(final String fileName, final int fileId) {
    final var internalFileName = createInternalFileName(fileName, fileId);
    return new AsyncFile(
        storagePath.resolve(internalFileName),
        pageSize,
        logFileDeletion,
        this.executor,
        storageName);
  }

  private static String createInternalFileName(final String fileName, final int fileId) {
    final var extSeparator = fileName.lastIndexOf('.');

    String prefix;
    if (extSeparator < 0) {
      prefix = fileName;
    } else if (extSeparator == 0) {
      prefix = "";
    } else {
      prefix = fileName.substring(0, extSeparator);
    }

    final String suffix;
    if (extSeparator < 0 || extSeparator == fileName.length() - 1) {
      suffix = "";
    } else {
      suffix = fileName.substring(extSeparator + 1);
    }

    prefix = prefix + "_" + fileId;

    if (extSeparator >= 0) {
      return prefix + "." + suffix;
    }

    return prefix;
  }

  /**
   * Read information about files are registered inside of write cache/storage File consist of rows
   * of variable length which contains following entries:
   *
   * <ol>
   *   <li>XX_HASH code of the content of the row excluding first two entries.
   *   <li>Length of the content of the row excluding of two entries above.
   *   <li>Internal file id, may be positive or negative depends on whether file is removed or not
   *   <li>Name of file inside of write cache, this name is case sensitive
   *   <li>Name of file which is used inside file system it can be different from name of file used
   *       inside write cache
   * </ol>
   */
  private void readNameIdMapV3(FileChannel nameIdMapHolder)
      throws IOException, java.lang.InterruptedException {
    nameIdMap.clear();

    long localFileCounter = -1;

    nameIdMapHolder.position(0);

    NameFileIdEntry nameFileIdEntry;

    final var idFileNameMap = new Int2ObjectOpenHashMap<String>(1_000);

    while ((nameFileIdEntry = readNextNameIdEntryV3(nameIdMapHolder)) != null) {
      final long absFileId = Math.abs(nameFileIdEntry.getFileId());

      if (localFileCounter < absFileId) {
        localFileCounter = absFileId;
      }

      if (absFileId != 0) {
        nameIdMap.put(nameFileIdEntry.getName(), nameFileIdEntry.getFileId());
        idNameMap.put(nameFileIdEntry.getFileId(), nameFileIdEntry.getName());

        idFileNameMap.put(nameFileIdEntry.getFileId(), nameFileIdEntry.getFileSystemName());
      } else {
        nameIdMap.remove(nameFileIdEntry.getName());
        idNameMap.remove(nameFileIdEntry.getFileId());
        idFileNameMap.remove(nameFileIdEntry.getFileId());
      }
    }

    for (final var nameIdEntry : nameIdMap.entrySet()) {
      final int fileId = nameIdEntry.getValue();

      if (fileId >= 0) {
        final var externalId = composeFileId(id, nameIdEntry.getValue());

        if (files.get(externalId) == null) {
          final var path =
              storagePath.resolve(idFileNameMap.get((nameIdEntry.getValue().intValue())));
          final var file =
              new AsyncFile(path, pageSize, logFileDeletion, this.executor, storageName);

          if (file.exists()) {
            file.open();
            files.add(externalId, file);
          } else {
            idNameMap.remove(fileId);

            nameIdMap.put(nameIdEntry.getKey(), -fileId);
            idNameMap.put(-fileId, nameIdEntry.getKey());
          }
        }
      }
    }
  }

  /**
   * Read information about files are registered inside of write cache/storage File consist of rows
   * of variable length which contains following entries:
   *
   * <ol>
   *   <li>Internal file id, may be positive or negative depends on whether file is removed or not
   *   <li>Name of file inside of write cache, this name is case sensitive
   *   <li>Name of file which is used inside file system it can be different from name of file used
   *       inside write cache
   * </ol>
   */
  private void readNameIdMapV2(FileChannel nameIdMapHolder)
      throws IOException, java.lang.InterruptedException {
    nameIdMap.clear();

    long localFileCounter = -1;

    nameIdMapHolder.position(0);

    NameFileIdEntry nameFileIdEntry;

    final var idFileNameMap = new Int2ObjectOpenHashMap<String>(1_000);

    while ((nameFileIdEntry = readNextNameIdEntryV2(nameIdMapHolder)) != null) {
      final long absFileId = Math.abs(nameFileIdEntry.getFileId());

      if (localFileCounter < absFileId) {
        localFileCounter = absFileId;
      }

      if (absFileId != 0) {
        nameIdMap.put(nameFileIdEntry.getName(), nameFileIdEntry.getFileId());
        idNameMap.put(nameFileIdEntry.getFileId(), nameFileIdEntry.getName());

        idFileNameMap.put(nameFileIdEntry.getFileId(), nameFileIdEntry.getFileSystemName());
      } else {
        nameIdMap.remove(nameFileIdEntry.getName());
        idNameMap.remove(nameFileIdEntry.getFileId());

        idFileNameMap.remove(nameFileIdEntry.getFileId());
      }
    }

    for (final var nameIdEntry : nameIdMap.entrySet()) {
      final int fileId = nameIdEntry.getValue();

      if (fileId > 0) {
        final var externalId = composeFileId(id, nameIdEntry.getValue());

        if (files.get(externalId) == null) {
          final var path =
              storagePath.resolve(idFileNameMap.get((nameIdEntry.getValue().intValue())));
          final var file =
              new AsyncFile(path, pageSize, logFileDeletion, this.executor, storageName);

          if (file.exists()) {
            file.open();
            files.add(externalId, file);
          } else {
            idNameMap.remove(fileId);

            nameIdMap.put(nameIdEntry.getKey(), -fileId);
            idNameMap.put(-fileId, nameIdEntry.getKey());
          }
        }
      }
    }
  }

  private void readNameIdMapV1(final FileChannel nameIdMapHolder)
      throws IOException, java.lang.InterruptedException {
    // older versions of ODB incorrectly logged file deletions
    // some deleted files have the same id
    // because we reuse ids of removed files when we re-create them
    // we need to fix this situation
    final var filesWithNegativeIds =
        new Int2ObjectOpenHashMap<Set<String>>(1_000);

    nameIdMap.clear();

    long localFileCounter = -1;

    nameIdMapHolder.position(0);

    NameFileIdEntry nameFileIdEntry;
    while ((nameFileIdEntry = readNextNameIdEntryV1(nameIdMapHolder)) != null) {

      final long absFileId = Math.abs(nameFileIdEntry.getFileId());
      if (localFileCounter < absFileId) {
        localFileCounter = absFileId;
      }

      final var existingId = nameIdMap.get(nameFileIdEntry.getName());

      if (existingId != null && existingId < 0) {
        final var files = filesWithNegativeIds.get(existingId.intValue());

        if (files != null) {
          files.remove(nameFileIdEntry.getName());
          if (files.isEmpty()) {
            filesWithNegativeIds.remove(existingId.intValue());
          }
        }
      }

      if (nameFileIdEntry.getFileId() < 0) {
        var files = filesWithNegativeIds.get(nameFileIdEntry.getFileId());

        if (files == null) {
          files = new HashSet<>(8);
          files.add(nameFileIdEntry.getName());
          filesWithNegativeIds.put(nameFileIdEntry.getFileId(), files);
        } else {
          files.add(nameFileIdEntry.getName());
        }
      }

      nameIdMap.put(nameFileIdEntry.getName(), nameFileIdEntry.getFileId());
      idNameMap.put(nameFileIdEntry.getFileId(), nameFileIdEntry.getName());
    }

    for (final var nameIdEntry : nameIdMap.entrySet()) {
      if (nameIdEntry.getValue() >= 0) {
        final var externalId = composeFileId(id, nameIdEntry.getValue());

        if (files.get(externalId) == null) {
          final File fileClassic =
              new AsyncFile(
                  storagePath.resolve(nameIdEntry.getKey()),
                  pageSize,
                  logFileDeletion,
                  this.executor,
                  storageName);

          if (fileClassic.exists()) {
            fileClassic.open();
            files.add(externalId, fileClassic);
          } else {
            final var fileId = nameIdMap.get(nameIdEntry.getKey());

            if (fileId != null && fileId > 0) {
              nameIdMap.put(nameIdEntry.getKey(), -fileId);

              idNameMap.remove(fileId);
              idNameMap.put(-fileId, nameIdEntry.getKey());
            }
          }
        }
      }
    }

    final Set<String> fixedFiles = new HashSet<>(8);

    for (final var entry : filesWithNegativeIds.int2ObjectEntrySet()) {
      final var files = entry.getValue();

      if (files.size() > 1) {
        idNameMap.remove(entry.getIntKey());

        for (final var fileName : files) {
          int fileId;

          while (true) {
            final var nextId = fileIdGen.nextInt(Integer.MAX_VALUE - 1) + 1;
            if (!idNameMap.containsKey(nextId) && !idNameMap.containsKey(-nextId)) {
              fileId = nextId;
              break;
            }
          }

          nameIdMap.put(fileName, -fileId);
          idNameMap.put(-fileId, fileName);

          fixedFiles.add(fileName);
        }
      }
    }

    if (!fixedFiles.isEmpty()) {
      LogManager.instance()
          .warn(
              this,
              "Removed files "
                  + fixedFiles
                  + " had duplicated ids. Problem is fixed automatically.");
    }
  }

  @Nullable
  private static NameFileIdEntry readNextNameIdEntryV1(FileChannel nameIdMapHolder) throws IOException {
    try {
      var buffer = ByteBuffer.allocate(IntegerSerializer.INT_SIZE);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final var nameSize = buffer.getInt();
      buffer = ByteBuffer.allocate(nameSize + LongSerializer.LONG_SIZE);

      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final var name = StringSerializer.staticDeserializeFromByteBufferObject(buffer);
      final var fileId = (int) buffer.getLong();

      return new NameFileIdEntry(name, fileId);
    } catch (final EOFException ignore) {
      return null;
    }
  }

  @Nullable
  private static NameFileIdEntry readNextNameIdEntryV2(FileChannel nameIdMapHolder) throws IOException {
    try {
      var buffer = ByteBuffer.allocate(2 * IntegerSerializer.INT_SIZE);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final var fileId = buffer.getInt();
      final var nameSize = buffer.getInt();

      buffer = ByteBuffer.allocate(nameSize);

      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final var name = StringSerializer.staticDeserializeFromByteBufferObject(buffer);

      buffer = ByteBuffer.allocate(IntegerSerializer.INT_SIZE);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final var fileNameSize = buffer.getInt();

      buffer = ByteBuffer.allocate(fileNameSize);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final var fileName = StringSerializer.staticDeserializeFromByteBufferObject(buffer);

      return new NameFileIdEntry(name, fileId, fileName);

    } catch (final EOFException ignore) {
      return null;
    }
  }

  @Nullable
  private NameFileIdEntry readNextNameIdEntryV3(FileChannel nameIdMapHolder) throws IOException {
    try {
      final var xxHashLen = 8;
      final var recordSizeLen = 4;

      var buffer = ByteBuffer.allocate(xxHashLen + recordSizeLen);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final var storedXxHash = buffer.getLong();
      final var recordLen = buffer.getInt();

      if (recordLen > MAX_FILE_RECORD_LEN) {
        LogManager.instance()
            .error(
                this,
                "Maximum record length in file registry can not exceed %d bytes. "
                    + "But actual record length %d.  Storage name : %s",
                null,
                MAX_FILE_RECORD_LEN,
                storageName,
                recordLen);
        return null;
      }

      buffer = ByteBuffer.allocate(recordLen);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final var xxHash = DiskStorage.XX_HASH_64.hash(buffer, 0, recordLen,
          DiskStorage.XX_HASH_SEED);
      if (xxHash != storedXxHash) {
        LogManager.instance()
            .error(
                this, "Hash of the file registry is broken. Storage name : %s", null, storageName);
        return null;
      }

      final var fileId = buffer.getInt();
      final var name = StringSerializer.staticDeserializeFromByteBufferObject(buffer);
      final var fileName = StringSerializer.staticDeserializeFromByteBufferObject(buffer);

      return new NameFileIdEntry(name, fileId, fileName);

    } catch (final EOFException ignore) {
      return null;
    }
  }

  private void writeNameIdEntry(final NameFileIdEntry nameFileIdEntry, final boolean sync)
      throws IOException {
    try (final var nameIdMapHolder =
        FileChannel.open(nameIdMapHolderPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      writeNameIdEntry(nameIdMapHolder, nameFileIdEntry, sync);
    }
  }

  private void writeNameIdEntry(
      final FileChannel nameIdMapHolder, final NameFileIdEntry nameFileIdEntry, final boolean sync)
      throws IOException {
    final var xxHashSize = 8;
    final var recordLenSize = 4;

    final var nameSize = StringSerializer.staticGetObjectSize(nameFileIdEntry.getName());
    final var fileNameSize = StringSerializer.staticGetObjectSize(
        nameFileIdEntry.getFileSystemName());

    // file id size + file name + file system name + xx_hash size + record_size size
    final var serializedRecord =
        ByteBuffer.allocate(
            IntegerSerializer.INT_SIZE + nameSize + fileNameSize + xxHashSize + recordLenSize);

    serializedRecord.position(xxHashSize + recordLenSize);

    // serialize file id
    serializedRecord.putInt(nameFileIdEntry.getFileId());

    // serialize file name
    StringSerializer.staticSerializeInByteBufferObject(nameFileIdEntry.getName(),
        serializedRecord);

    // serialize file system name
    StringSerializer.staticSerializeInByteBufferObject(nameFileIdEntry.getFileSystemName(),
        serializedRecord);

    final var recordLen = serializedRecord.position() - xxHashSize - recordLenSize;
    if (recordLen > MAX_FILE_RECORD_LEN) {
      throw new StorageException(storageName,
          "Maximum record length in file registry can not exceed "
              + MAX_FILE_RECORD_LEN
              + " bytes. But actual record length "
              + recordLen);
    }
    serializedRecord.putInt(xxHashSize, recordLen);

    final var xxHash =
        DiskStorage.XX_HASH_64.hash(serializedRecord, xxHashSize + recordLenSize, recordLen,
            DiskStorage.XX_HASH_SEED);
    serializedRecord.putLong(0, xxHash);

    serializedRecord.position(0);

    IOUtils.writeByteBuffer(serializedRecord, nameIdMapHolder, nameIdMapHolder.size());
    //noinspection ResultOfMethodCallIgnored
    nameIdMapHolder.write(serializedRecord);

    if (sync) {
      nameIdMapHolder.force(true);
    }
  }

  private void removeCachedPages(final int fileId) {
    final var future = commitExecutor.submit(new RemoveFilePagesTask(this, fileId));
    try {
      future.get();
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new ThreadInterruptedException("File data removal was interrupted"), e, storageName);
    } catch (final Exception e) {
      throw BaseException.wrapException(
          new WriteCacheException(storageName, "File data removal was abnormally terminated"), e,
          storageName);
    }
  }

  @Nullable
  private CachePointer loadFileContent(
      final int internalFileId, final long pageIndex, final boolean verifyChecksums)
      throws IOException {
    final var fileId = composeFileId(id, internalFileId);
    try {
      final var entry = files.acquire(fileId);
      try {
        final var fileClassic = entry.get();
        if (fileClassic == null) {
          throw new IllegalArgumentException(
              "File with id " + internalFileId + " not found in WOW Cache");
        }

        final var pagePosition = pageIndex * pageSize;
        final var pageEndPosition = pagePosition + pageSize;

        // if page is not stored in the file may be page is stored in double write log
        if (fileClassic.getFileSize() >= pageEndPosition) {
          var pointer = bufferPool.acquireDirect(true, Intention.LOAD_PAGE_FROM_DISK);
          var buffer = pointer.getNativeByteBuffer();

          assert buffer.position() == 0;
          assert buffer.order() == ByteOrder.nativeOrder();

          fileClassic.read(pagePosition, buffer, false);

          if (verifyChecksums
              && (checksumMode == ChecksumMode.StoreAndVerify
              || checksumMode == ChecksumMode.StoreAndThrow
              || checksumMode == ChecksumMode.StoreAndSwitchReadOnlyMode)) {
            // if page is broken inside of data file we check double write log
            if (!verifyMagicChecksumAndDecryptPage(buffer, internalFileId, pageIndex)) {
              final var doubleWritePointer =
                  doubleWriteLog.loadPage(internalFileId, (int) pageIndex, bufferPool);

              if (doubleWritePointer == null) {
                assertPageIsBroken(pageIndex, fileId, pointer);
              } else {
                bufferPool.release(pointer);

                buffer = doubleWritePointer.getNativeByteBuffer();
                assert buffer.position() == 0;
                pointer = doubleWritePointer;

                if (!verifyMagicChecksumAndDecryptPage(buffer, internalFileId, pageIndex)) {
                  assertPageIsBroken(pageIndex, fileId, pointer);
                }
              }
            }
          }

          buffer.position(0);
          return new CachePointer(pointer, bufferPool, fileId, (int) pageIndex);
        } else {
          final var pointer =
              doubleWriteLog.loadPage(internalFileId, (int) pageIndex, bufferPool);
          if (pointer != null) {
            final var buffer = pointer.getNativeByteBuffer();
            assert buffer.position() == 0;

            if (verifyChecksums
                && (checksumMode == ChecksumMode.StoreAndVerify
                || checksumMode == ChecksumMode.StoreAndThrow
                || checksumMode == ChecksumMode.StoreAndSwitchReadOnlyMode)) {
              if (!verifyMagicChecksumAndDecryptPage(buffer, internalFileId, pageIndex)) {
                assertPageIsBroken(pageIndex, fileId, pointer);
              }
            }
          }

          return null;
        }
      } finally {
        files.release(entry);
      }
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName, "Data load was interrupted"), e, storageName);
    }
  }

  private void assertPageIsBroken(long pageIndex, long fileId, Pointer pointer) {
    final var message =
        "Magic number verification failed for page `"
            + pageIndex
            + "` of `"
            + fileNameById(fileId)
            + "`.";
    LogManager.instance().error(this, "%s", null, message);

    if (checksumMode == ChecksumMode.StoreAndThrow) {
      bufferPool.release(pointer);
      throw new StorageException(storageName, message);
    } else if (checksumMode == ChecksumMode.StoreAndSwitchReadOnlyMode) {
      dumpStackTrace(message);
      callPageIsBrokenListeners(fileNameById(fileId), pageIndex);
    }
  }

  private void addMagicChecksumAndEncryption(
      final int intId, final int pageIndex, final ByteBuffer buffer) {
    assert buffer.order() == ByteOrder.nativeOrder();

    if (checksumMode != ChecksumMode.Off) {
      buffer.position(PAGE_OFFSET_TO_CHECKSUM_FROM);
      final var crc32 = new CRC32();
      crc32.update(buffer);
      final var computedChecksum = (int) crc32.getValue();

      buffer.position(CHECKSUM_OFFSET);
      buffer.putInt(computedChecksum);
    }

    if (aesKey != null) {
      var magicNumber = buffer.getLong(MAGIC_NUMBER_OFFSET);

      var updateCounter = magicNumber >>> 8;
      updateCounter++;

      if (checksumMode == ChecksumMode.Off) {
        magicNumber = (updateCounter << 8) | MAGIC_NUMBER_WITHOUT_CHECKSUM_ENCRYPTED;
      } else {
        magicNumber = (updateCounter << 8) | MAGIC_NUMBER_WITH_CHECKSUM_ENCRYPTED;
      }

      buffer.putLong(MAGIC_NUMBER_OFFSET, magicNumber);
      doEncryptionDecryption(intId, pageIndex, Cipher.ENCRYPT_MODE, buffer, updateCounter);
    } else {
      buffer.putLong(
          MAGIC_NUMBER_OFFSET,
          checksumMode == ChecksumMode.Off
              ? MAGIC_NUMBER_WITHOUT_CHECKSUM
              : MAGIC_NUMBER_WITH_CHECKSUM);
    }
  }

  private void doEncryptionDecryption(
      final int intId,
      final int pageIndex,
      final int mode,
      final ByteBuffer buffer,
      final long updateCounter) {
    try {
      final var cipher = CIPHER.get();
      final SecretKey aesKey = new SecretKeySpec(this.aesKey, ALGORITHM_NAME);

      final var updatedIv = new byte[iv.length];

      for (var i = 0; i < IntegerSerializer.INT_SIZE; i++) {
        updatedIv[i] = (byte) (iv[i] ^ ((pageIndex >>> i) & 0xFF));
      }

      for (var i = 0; i < IntegerSerializer.INT_SIZE; i++) {
        updatedIv[i + IntegerSerializer.INT_SIZE] =
            (byte) (iv[i + IntegerSerializer.INT_SIZE] ^ ((intId >>> i) & 0xFF));
      }

      for (var i = 0; i < LongSerializer.LONG_SIZE - 1; i++) {
        updatedIv[i + 2 * IntegerSerializer.INT_SIZE] =
            (byte) (iv[i + 2 * IntegerSerializer.INT_SIZE] ^ ((updateCounter >>> i) & 0xFF));
      }

      updatedIv[updatedIv.length - 1] = iv[iv.length - 1];

      cipher.init(mode, aesKey, new IvParameterSpec(updatedIv));

      final var outBuffer =
          ByteBuffer.allocate(buffer.capacity() - CHECKSUM_OFFSET).order(ByteOrder.nativeOrder());

      buffer.position(CHECKSUM_OFFSET);
      cipher.doFinal(buffer, outBuffer);

      buffer.position(CHECKSUM_OFFSET);
      outBuffer.position(0);
      buffer.put(outBuffer);

    } catch (InvalidKeyException e) {
      throw BaseException.wrapException(
          new InvalidStorageEncryptionKeyException(storageName, e.getMessage()),
          e, storageName);
    } catch (InvalidAlgorithmParameterException e) {
      throw new IllegalArgumentException("Invalid IV.", e);
    } catch (IllegalBlockSizeException | BadPaddingException | ShortBufferException e) {
      throw new IllegalStateException("Unexpected exception during CRT encryption.", e);
    }
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean verifyMagicChecksumAndDecryptPage(
      final ByteBuffer buffer, final int intId, final long pageIndex) {
    assert buffer.order() == ByteOrder.nativeOrder();

    buffer.position(MAGIC_NUMBER_OFFSET);
    final var magicNumber = buffer.getLong();

    if ((aesKey == null && magicNumber != MAGIC_NUMBER_WITH_CHECKSUM)
        || (magicNumber != MAGIC_NUMBER_WITH_CHECKSUM
        && (magicNumber & 0xFF) != MAGIC_NUMBER_WITH_CHECKSUM_ENCRYPTED)) {
      if ((aesKey == null && magicNumber != MAGIC_NUMBER_WITHOUT_CHECKSUM)
          || (magicNumber != MAGIC_NUMBER_WITHOUT_CHECKSUM
          && (magicNumber & 0xFF) != MAGIC_NUMBER_WITHOUT_CHECKSUM_ENCRYPTED)) {
        return false;
      }

      if (aesKey != null && (magicNumber & 0xFF) == MAGIC_NUMBER_WITHOUT_CHECKSUM_ENCRYPTED) {
        doEncryptionDecryption(
            intId, (int) pageIndex, Cipher.DECRYPT_MODE, buffer, magicNumber >>> 8);
      }

      return true;
    }

    if (aesKey != null && (magicNumber & 0xFF) == MAGIC_NUMBER_WITH_CHECKSUM_ENCRYPTED) {
      doEncryptionDecryption(
          intId, (int) pageIndex, Cipher.DECRYPT_MODE, buffer, magicNumber >>> 8);
    }

    buffer.position(CHECKSUM_OFFSET);
    final var storedChecksum = buffer.getInt();

    buffer.position(PAGE_OFFSET_TO_CHECKSUM_FROM);
    final var crc32 = new CRC32();
    crc32.update(buffer);
    final var computedChecksum = (int) crc32.getValue();

    return computedChecksum == storedChecksum;
  }

  private void dumpStackTrace(final String message) {
    final var stringWriter = new StringWriter();
    final var printWriter = new PrintWriter(stringWriter);

    printWriter.println(message);
    final var exception = new Exception();
    exception.printStackTrace(printWriter);
    printWriter.flush();

    LogManager.instance().error(this, stringWriter.toString(), null);
  }

  private void fsyncFiles() throws java.lang.InterruptedException, IOException {
    for (int intFileId : idNameMap.keySet()) {
      if (intFileId >= 0) {
        final var extFileId = externalFileId(intFileId);

        final var fileEntry = files.acquire(extFileId);
        // such thing can happen during db restore
        if (fileEntry == null) {
          continue;
        }
        try {
          final var fileClassic = fileEntry.get();
          fileClassic.synch();
        } finally {
          files.release(fileEntry);
        }
      }
    }

    doubleWriteLog.truncate();
  }

  void doRemoveCachePages(int internalFileId) {
    final var entryIterator =
        writeCachePages.entrySet().iterator();
    while (entryIterator.hasNext()) {
      final var entry = entryIterator.next();
      final var pageKey = entry.getKey();

      if (pageKey.fileId() == internalFileId) {
        final var pagePointer = entry.getValue();
        final var groupLock = lockManager.acquireExclusiveLock(pageKey);
        try {
          pagePointer.acquireExclusiveLock();
          try {
            pagePointer.decrementWritersReferrer();
            pagePointer.setWritersListener(null);
            writeCacheSize.decrementAndGet();

            removeFromDirtyPages(pageKey);
          } finally {
            pagePointer.releaseExclusiveLock();
          }

          entryIterator.remove();
        } finally {
          groupLock.unlock();
        }
      }
    }
  }

  public void setChecksumMode(final ChecksumMode checksumMode) { // for testing purposes only
    this.checksumMode = checksumMode;
  }

  private void flushExclusivePagesIfNeeded() throws java.lang.InterruptedException, IOException {
    final var ewcSize = exclusiveWriteCacheSize.get();
    assert ewcSize >= 0;

    if (ewcSize >= 0.8 * exclusiveWriteCacheMaxSize) {
      flushExclusiveWriteCache(null, ewcSize);
    }
  }

  @Nullable
  public Long executeFindDirtySegment() {
    if (flushError != null) {
      final var iAdditionalArgs = new Object[]{flushError.getMessage()};
      LogManager.instance()
          .error(
              this,
              "Can not calculate minimum LSN because of issue during data write, %s",
              null,
              iAdditionalArgs);
      return null;
    }

    convertSharedDirtyPagesToLocal();

    if (localDirtyPagesBySegment.isEmpty()) {
      return null;
    }

    return localDirtyPagesBySegment.firstKey();
  }

  private void convertSharedDirtyPagesToLocal() {
    for (final var entry : dirtyPages.entrySet()) {
      final var localLSN = localDirtyPages.get(entry.getKey());

      if (localLSN == null || localLSN.compareTo(entry.getValue()) > 0) {
        localDirtyPages.put(entry.getKey(), entry.getValue());

        final var segment = entry.getValue().getSegment();
        var pages = localDirtyPagesBySegment.get(segment);
        if (pages == null) {
          pages = new TreeSet<>();
          pages.add(entry.getKey());

          localDirtyPagesBySegment.put(segment, pages);
        } else {
          pages.add(entry.getKey());
        }
      }
    }

    for (final var entry : localDirtyPages.entrySet()) {
      dirtyPages.remove(entry.getKey(), entry.getValue());
    }
  }

  private void removeFromDirtyPages(final PageKey pageKey) {
    dirtyPages.remove(pageKey);

    final var lsn = localDirtyPages.remove(pageKey);
    if (lsn != null) {
      final var segment = lsn.getSegment();
      final var pages = localDirtyPagesBySegment.get(segment);
      assert pages != null;

      final var removed = pages.remove(pageKey);
      if (pages.isEmpty()) {
        localDirtyPagesBySegment.remove(segment);
      }

      assert removed;
    }
  }

  private void flushWriteCacheFromMinLSN(
      final long segStart, final long segEnd, final int pagesFlushLimit)
      throws java.lang.InterruptedException, IOException {
    // first we try to find page which contains the oldest not flushed changes
    // that is needed to allow to compact WAL as earlier as possible
    convertSharedDirtyPagesToLocal();

    var copiedPages = 0;

    var chunks = new ArrayList<ArrayList<WritePageContainer>>(16);
    var chunk = new ArrayList<WritePageContainer>(16);

    var currentSegment = segStart;

    var chunksSize = 0;

    var fileIdSizeMap = new Int2LongOpenHashMap();
    fileIdSizeMap.defaultReturnValue(-1);

    LogSequenceNumber maxFullLogLSN = null;
    flushCycle:
    while (chunksSize < pagesFlushLimit) {
      final var segmentPages = localDirtyPagesBySegment.get(currentSegment);

      if (segmentPages == null) {
        currentSegment++;

        if (currentSegment >= segEnd) {
          break;
        }

        continue;
      }

      final var lsnPagesIterator = segmentPages.iterator();
      final List<PageKey> pageKeysToFlush = new ArrayList<>(pagesFlushLimit);

      while (lsnPagesIterator.hasNext() && pageKeysToFlush.size() < pagesFlushLimit - chunksSize) {
        final var pageKey = lsnPagesIterator.next();
        var fileId = pageKey.fileId();
        var fileSize = fileIdSizeMap.get(fileId);

        if (fileSize == -1) {
          fileSize = files.get(externalFileId(fileId)).getUnderlyingFileSize();

          if ((fileSize & (pageSize - 1)) != 0) {
            throw new StorageException(storageName,
                "Storage : "
                    + storageName
                    + ". File size is not multiple of page size. File id : "
                    + fileId);
          }
          fileIdSizeMap.put(fileId, fileSize);
        }

        var diff = (pageKey.pageIndex() * pageSize - fileSize) / pageSize;
        // it is important to do not create holes in the file
        // otherwise restore process after crash will be aborted
        // because of invalid data
        if (diff > 0) {
          diff = Math.min(diff, pagesFlushLimit - chunksSize - pageKeysToFlush.size());
          var startPageIndex = fileSize / pageSize;

          for (var i = 0; i < diff; i++) {
            pageKeysToFlush.add(new PageKey(fileId, startPageIndex + i));
          }
        }

        var pageEnd = pageKey.pageIndex() * pageSize + pageSize;
        if (pageEnd > fileSize) {
          fileIdSizeMap.put(fileId, pageEnd);
        }

        if (pageKeysToFlush.size() >= pagesFlushLimit - chunksSize) {
          break;
        }
        pageKeysToFlush.add(pageKey);
      }

      long lastPageIndex = -1;
      long lastFileId = -1;

      for (final var pageKey : pageKeysToFlush) {
        if (lastFileId == -1) {
          if (!chunk.isEmpty()) {
            throw new IllegalStateException("Chunk is not empty !");
          }
        } else {
          if (lastPageIndex == -1) {
            throw new IllegalStateException("Last page index is -1");
          }

          if (lastFileId != pageKey.fileId() || lastPageIndex != pageKey.pageIndex() - 1) {
            if (!chunk.isEmpty()) {
              chunks.add(chunk);
              chunksSize += chunk.size();
              chunk = new ArrayList<>();
            }
          }
        }

        final var pointer = writeCachePages.get(pageKey);

        if (pointer == null) {
          // we marked page as dirty but did not put it in cache yet
          if (!chunk.isEmpty()) {
            chunks.add(chunk);
          }

          break flushCycle;
        }

        if (pointer.tryAcquireSharedLock()) {
          final long version;
          final LogSequenceNumber fullLogLSN;

          final var directPointer =
              bufferPool.acquireDirect(false, Intention.COPY_PAGE_DURING_FLUSH);
          final var copy = directPointer.getNativeByteBuffer();
          assert copy.position() == 0;
          try {
            version = pointer.getVersion();
            final var buffer = pointer.getBuffer();

            fullLogLSN = pointer.getEndLSN();

            assert buffer != null;
            assert buffer.position() == 0;
            assert copy.position() == 0;

            copy.put(0, buffer, 0, buffer.capacity());

            removeFromDirtyPages(pageKey);

            copiedPages++;
          } finally {
            pointer.releaseSharedLock();
          }

          if (fullLogLSN != null
              && (maxFullLogLSN == null || fullLogLSN.compareTo(maxFullLogLSN) > 0)) {
            maxFullLogLSN = fullLogLSN;
          }

          copy.position(0);

          chunk.add(new WritePageContainer(version, copy, directPointer, pointer));

          if (chunksSize + chunk.size() >= pagesFlushLimit) {
            chunks.add(chunk);
            chunksSize += chunk.size();
            chunk = new ArrayList<>(16);

            lastPageIndex = -1;
            lastFileId = -1;
          } else {
            lastPageIndex = pageKey.pageIndex();
            lastFileId = pageKey.fileId();
          }
        } else {
          if (!chunk.isEmpty()) {
            chunks.add(chunk);
            chunksSize += chunk.size();
            chunk = new ArrayList<>(16);
          }

          lastPageIndex = -1;
          lastFileId = -1;

          var fileSize = files.get(externalFileId(pageKey.fileId())).getUnderlyingFileSize();
          if (pageKey.pageIndex() * pageSize >= fileSize) {
            // if we can not write at least one page outside of the size of the file on disk
            // we should stop the process because otherwise hole in the file during restore
            // after crash will be treated as a invalid data and restore process will be aborted

            break flushCycle;
          }
        }
      }

      if (!chunk.isEmpty()) {
        chunks.add(chunk);
        chunksSize += chunk.size();
        chunk = new ArrayList<>(16);
      }
    }

    final var flushedPages = flushPages(chunks, maxFullLogLSN);
    if (copiedPages != flushedPages) {
      throw new IllegalStateException(
          "Copied pages (" + copiedPages + " ) != flushed pages (" + flushedPages + ")");
    }
  }

  void writeValidPageInFile(int internalFileId, int pageIndex) {
    if (flushError != null) {
      LogManager.instance()
          .error(
              this,
              "Can not write valid page in file because of the problems with data write, %s",
              null,
              flushError.getMessage());
    }

    if (stopFlush) {
      return;
    }

    try {
      var pagePosition = (long) pageIndex * pageSize;
      var entry = files.acquire(externalFileId(internalFileId));
      try {
        var file = entry.get();
        if (file.getUnderlyingFileSize() <= pagePosition) {
          var pointer =
              DirectMemoryAllocator.instance()
                  .allocate(pageSize, true, Intention.ADD_NEW_PAGE_IN_FILE);
          try {
            var buffer = pointer.getNativeByteBuffer();
            DurablePage.setLogSequenceNumberForPage(buffer, new LogSequenceNumber(-1, -1));
            addMagicChecksumAndEncryption(internalFileId, pageIndex, buffer);

            buffer.position(0);
            file.write(pagePosition, buffer);
          } finally {
            DirectMemoryAllocator.instance().deallocate(pointer);
          }
        }
      } finally {
        files.release(entry);
      }
    } catch (final IOException | java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException(storageName,
              "Storage : "
                  + storageName
                  + "Error during of writing initial blank page for file  "
                  + idNameMap.get(internalFileId)),
          e, storageName);
    }
  }

  private int flushPages(
      final ArrayList<ArrayList<WritePageContainer>> chunks, final LogSequenceNumber fullLogLSN)
      throws java.lang.InterruptedException, IOException {
    if (chunks.isEmpty()) {
      return 0;
    }

    if (fullLogLSN != null) {
      var flushedLSN = writeAheadLog.getFlushedLsn();
      while (flushedLSN == null || flushedLSN.compareTo(fullLogLSN) < 0) {
        writeAheadLog.flush();
        flushedLSN = writeAheadLog.getFlushedLsn();
      }
    }

    final boolean fsyncFiles;

    var flushedPages = 0;

    final var containerPointers = new ArrayList<Pointer>(chunks.size());
    final var containerBuffers = new ArrayList<ByteBuffer>(chunks.size());
    final var chunkPageIndexes = new IntArrayList(chunks.size());
    final var chunkFileIds = new IntArrayList(chunks.size());

    final var buffersByFileId =
        new Long2ObjectOpenHashMap<ArrayList<RawPairLongObject<ByteBuffer>>>();
    try {
      flushedPages =
          copyPageChunksIntoTheBuffers(
              chunks,
              flushedPages,
              containerPointers,
              containerBuffers,
              buffersByFileId,
              chunkPageIndexes,
              chunkFileIds);
      fsyncFiles = doubleWriteLog.write(containerBuffers, chunkFileIds, chunkPageIndexes);
      writePageChunksToFiles(buffersByFileId);
    } finally {
      for (final var containerPointer : containerPointers) {
        if (containerPointer != null) {
          DirectMemoryAllocator.instance().deallocate(containerPointer);
        }
      }
    }

    if (fsyncFiles) {
      fsyncFiles();
    }

    removeWrittenPagesFromCache(chunks);

    return flushedPages;
  }

  private void removeWrittenPagesFromCache(ArrayList<ArrayList<WritePageContainer>> chunks) {
    for (final List<WritePageContainer> chunk : chunks) {
      for (var chunkPage : chunk) {
        final var pointer = chunkPage.originalPagePointer;

        final var pageKey =
            new PageKey(internalFileId(pointer.getFileId()), pointer.getPageIndex());
        final var version = chunkPage.pageVersion;

        final var lock = lockManager.acquireExclusiveLock(pageKey);
        try {
          if (!pointer.tryAcquireSharedLock()) {
            continue;
          }

          try {
            if (version == pointer.getVersion()) {
              var removed = writeCachePages.remove(pageKey);
              if (removed == null) {
                throw new IllegalStateException("Page is not found in write cache");
              }

              writeCacheSize.decrementAndGet();

              pointer.decrementWritersReferrer();
              pointer.setWritersListener(null);
            }
          } finally {
            pointer.releaseSharedLock();
          }
        } finally {
          lock.unlock();
        }

        bufferPool.release(chunkPage.pageCopyDirectMemoryPointer);
      }
    }
  }

  private int copyPageChunksIntoTheBuffers(
      ArrayList<ArrayList<WritePageContainer>> chunks,
      int flushedPages,
      ArrayList<Pointer> containerPointers,
      ArrayList<ByteBuffer> containerBuffers,
      Long2ObjectOpenHashMap<ArrayList<RawPairLongObject<ByteBuffer>>> buffersByFileId,
      IntArrayList chunkPageIndexes,
      IntArrayList chunkFileIds) {
    for (final List<WritePageContainer> chunk : chunks) {
      if (chunk.isEmpty()) {
        continue;
      }
      flushedPages += chunk.size();

      final var containerPointer =
          DirectMemoryAllocator.instance()
              .allocate(
                  chunk.size() * pageSize, false, Intention.ALLOCATE_CHUNK_TO_WRITE_DATA_IN_BATCH);
      final var containerBuffer = containerPointer.getNativeByteBuffer();

      containerPointers.add(containerPointer);
      containerBuffers.add(containerBuffer);
      assert containerBuffer.position() == 0;

      for (var chunkPage : chunk) {
        final var buffer = chunkPage.copyOfPage;

        final var pointer = chunkPage.originalPagePointer;

        addMagicChecksumAndEncryption(
            extractFileId(pointer.getFileId()), pointer.getPageIndex(), buffer);

        buffer.position(0);
        containerBuffer.put(buffer);
      }

      final var firstPage = chunk.getFirst();
      final var firstCachePointer = firstPage.originalPagePointer;

      final var fileId = firstCachePointer.getFileId();
      final var pageIndex = firstCachePointer.getPageIndex();

      var fileBuffers = buffersByFileId.computeIfAbsent(fileId, (id) -> new ArrayList<>());
      fileBuffers.add(new RawPairLongObject<>(((long) pageIndex) * pageSize, containerBuffer));

      chunkPageIndexes.add(pageIndex);
      chunkFileIds.add(internalFileId(fileId));
    }
    return flushedPages;
  }

  private void writePageChunksToFiles(
      Long2ObjectOpenHashMap<ArrayList<RawPairLongObject<ByteBuffer>>> buffersByFileId)
      throws java.lang.InterruptedException, IOException {
    final List<ClosableEntry<Long, File>> acquiredFiles = new ArrayList<>(buffersByFileId.size());
    final List<IOResult> ioResults = new ArrayList<>(buffersByFileId.size());

    Long2ObjectOpenHashMap.Entry<ArrayList<RawPairLongObject<ByteBuffer>>> entry;
    Iterator<Long2ObjectMap.Entry<ArrayList<RawPairLongObject<ByteBuffer>>>> filesIterator;

    filesIterator = buffersByFileId.long2ObjectEntrySet().iterator();
    entry = null;
    // acquire as much files as possible and flush data
    while (true) {
      if (entry == null) {
        if (filesIterator.hasNext()) {
          entry = filesIterator.next();
        } else {
          break;
        }
      }

      final var fileEntry = files.tryAcquire(entry.getLongKey());
      if (fileEntry != null) {
        final var file = fileEntry.get();

        var bufferList = entry.getValue();

        ioResults.add(file.write(bufferList));
        acquiredFiles.add(fileEntry);

        entry = null;
      } else {
        if (ioResults.size() != acquiredFiles.size()) {
          throw new IllegalStateException("Not all data are written to the files.");
        }

        if (!ioResults.isEmpty()) {
          for (final var ioResult : ioResults) {
            ioResult.await();
          }

          for (final var closableEntry : acquiredFiles) {
            files.release(closableEntry);
          }

          ioResults.clear();
          acquiredFiles.clear();
        } else {
          Thread.yield();
        }
      }
    }

    if (ioResults.size() != acquiredFiles.size()) {
      throw new IllegalStateException("Not all data are written to the files.");
    }

    if (!ioResults.isEmpty()) {
      for (final var ioResult : ioResults) {
        ioResult.await();
      }

      for (final var closableEntry : acquiredFiles) {
        files.release(closableEntry);
      }
    }
  }

  private void flushExclusiveWriteCache(final CountDownLatch latch, long pagesToFlushLimit)
      throws java.lang.InterruptedException, IOException {
    // method flushes at least chunkSize pages that exist in write cache but do not exist
    // in read cache. It is important to do not flush more than chunkSize pages at once because
    // it can lead to the situation when a lot of RAM will be consumed during page flush.
    // Method iterates over pages that do not exist in read cache in order sorted by file and
    // position
    // of page in file. It is important to do not create holes in the file
    // otherwise restore process after crash will be aborted
    // because of invalid data.
    // Pages are gathered together in chunks of adjacent pages in order to minimize the number of
    // IO operations. Once amount of pages needed to be flushed reaches chunk size it is flushed and
    // process continued till requested amount of pages not flushed.

    // amount of dirty pages that exist only in write cache
    final var ewcSize = exclusiveWriteCacheSize.get();

    // we flush at least chunkSize pages but no more than amount of exclusive pages.
    pagesToFlushLimit = Math.min(Math.max(pagesToFlushLimit, chunkSize), ewcSize);

    var chunks = new ArrayList<ArrayList<WritePageContainer>>(16);
    var chunk = new ArrayList<WritePageContainer>(16);

    // latch is hold by write thread if limit of pages in write cache exceeded
    // it is important to release it once we lower amount of pages in write cache to the limit.
    if (latch != null && ewcSize <= exclusiveWriteCacheMaxSize) {
      latch.countDown();
    }

    LogSequenceNumber maxFullLogLSN = null;

    // cache of files sizes.
    var fileSizeMap = new Int2LongOpenHashMap();
    fileSizeMap.defaultReturnValue(-1);

    var flushedPages = 0;
    var copiedPages = 0;

    // total amount of pages in chunks that precedes current/active chunk
    var prevChunksSize = 0;
    long lastFileId = -1;
    long lastPageIndex = -1;

    var iterator = exclusiveWritePages.iterator();
    flushCycle:
    while (flushedPages < pagesToFlushLimit) {
      // if nothing to flush we should add the last gathered chunk and stop cycle.
      if (!iterator.hasNext()) {
        if (!chunk.isEmpty()) {
          chunks.add(chunk);
          chunk = new ArrayList<>(16);
        }

        break;
      }

      final var pageKeyToFlush = iterator.next();
      var fileSize = fileSizeMap.get(pageKeyToFlush.fileId());
      if (fileSize < 0) {
        fileSize = files.get(externalFileId(pageKeyToFlush.fileId())).getUnderlyingFileSize();
        fileSizeMap.put(pageKeyToFlush.fileId(), fileSize);
      }

      var pagesToFlush = new ArrayList<PageKey>();
      pagesToFlush.add(pageKeyToFlush);

      var diff = (pageKeyToFlush.pageIndex() * pageSize - fileSize) / pageSize;
      // it is important to do not create holes in the file
      // otherwise restore process after crash will be aborted
      if (diff > 0) {
        var startPageIndex = fileSize / pageSize;
        for (var i = 0; i < diff; i++) {
          pagesToFlush.add(new PageKey(pageKeyToFlush.fileId(), startPageIndex + i));
        }
      }
      // pages are sorted by position in index, so file size will be correctly incremented
      var pageEnd = pageKeyToFlush.pageIndex() * pageSize + pageSize;
      if (pageEnd > fileSize) {
        fileSizeMap.put(pageKeyToFlush.fileId(), pageEnd);
      }

      for (var pageKey : pagesToFlush) {
        final var pointer = writeCachePages.get(pageKey);
        // because accesses between maps not synchronized there could be eventual consistency
        // between exclusiveWritePages and writeCachePages. We treat writeCachePages as source of
        // truth.
        if (pointer == null) {
          var file = files.get(externalFileId(pageKey.fileId()));
          if (file.getUnderlyingFileSize() < (pageKey.pageIndex() + 1) * pageSize) {
            // if we can not write at least one page outside the size of the file on disk
            // we should stop the process because otherwise hole in the file during restore
            // after crash will be treated as an invalid data and restore process will be
            // aborted
            if (!chunk.isEmpty()) {
              chunks.add(chunk);
              chunk = new ArrayList<>(16);
            }

            flushedPages += flushPages(chunks, maxFullLogLSN);
            if (latch != null && exclusiveWriteCacheSize.get() <= exclusiveWriteCacheMaxSize) {
              latch.countDown();
            }

            // reset flush cycle
            chunks.clear();
            prevChunksSize = 0;
            iterator = exclusiveWritePages.iterator();
            fileSizeMap.clear();
            continue flushCycle;
          }
        } else {
          if (pointer.tryAcquireSharedLock()) {
            final LogSequenceNumber fullLSN;

            final var directPointer =
                bufferPool.acquireDirect(false, Intention.COPY_PAGE_DURING_EXCLUSIVE_PAGE_FLUSH);
            final var copy = directPointer.getNativeByteBuffer();
            assert copy.position() == 0;
            long version;
            try {
              version = pointer.getVersion();
              final var buffer = pointer.getBuffer();

              fullLSN = pointer.getEndLSN();

              assert buffer != null;
              assert buffer.position() == 0;
              assert copy.position() == 0;

              copy.put(0, buffer, 0, buffer.capacity());

              removeFromDirtyPages(pageKey);

              copiedPages++;
            } finally {
              pointer.releaseSharedLock();
            }

            if (fullLSN != null
                && (maxFullLogLSN == null || maxFullLogLSN.compareTo(fullLSN) < 0)) {
              maxFullLogLSN = fullLSN;
            }

            assert copy.position() == 0;

            if (!chunk.isEmpty()) {
              if (lastFileId != pointer.getFileId()
                  || lastPageIndex != pointer.getPageIndex() - 1) {
                chunks.add(chunk);
                prevChunksSize += chunk.size();
                chunk = new ArrayList<>(16);
              }
            }

            if (prevChunksSize + chunk.size() >= chunkSize) {
              if (!chunk.isEmpty()) {
                chunks.add(chunk);
                chunk = new ArrayList<>(16);
              }

              flushedPages += flushPages(chunks, maxFullLogLSN);

              chunks.clear();
              prevChunksSize = 0;
            }

            chunk.add(new WritePageContainer(version, copy, directPointer, pointer));

            lastFileId = pointer.getFileId();
            lastPageIndex = pointer.getPageIndex();
          } else {
            if (!chunk.isEmpty()) {
              chunks.add(chunk);
              prevChunksSize += chunk.size();

              chunk = new ArrayList<>(16);
            }

            var underlyingFileSize =
                files.get(externalFileId(pageKey.fileId())).getUnderlyingFileSize();
            // chunk size is reached flush limit, or we have a risk to have a hole in the file
            // that will lead to error during restore after crash process
            // we need to check only prevChunksSize because current chunk is empty
            if (prevChunksSize >= this.chunkSize
                || underlyingFileSize < (pageKey.pageIndex() + 1) * pageSize) {
              flushedPages += flushPages(chunks, maxFullLogLSN);

              chunks.clear();
              prevChunksSize = 0;

              if (latch != null && exclusiveWriteCacheSize.get() <= exclusiveWriteCacheMaxSize) {
                latch.countDown();
              }
            }

            if (underlyingFileSize < (pageKey.pageIndex() + 1) * pageSize) {
              // reset flush cycle, we can not afford holes in files
              iterator = exclusiveWritePages.iterator();
              fileSizeMap.clear();
              continue flushCycle;
            }
          }
        }
      }
    }

    if (!chunk.isEmpty()) {
      chunks.add(chunk);
    }

    flushedPages += flushPages(chunks, maxFullLogLSN);
    if (copiedPages != flushedPages) {
      throw new IllegalStateException(
          "Copied pages (" + copiedPages + " ) != flushed pages (" + flushedPages + ")");
    }
  }

  public Void executeFileFlush(IntOpenHashSet fileIdSet)
      throws java.lang.InterruptedException, IOException {
    if (flushError != null) {
      final var iAdditionalArgs = new Object[]{flushError.getMessage()};
      LogManager.instance()
          .error(
              this,
              "Can not flush file data because of issue during data write, %s",
              null,
              iAdditionalArgs);
      return null;
    }

    writeAheadLog.flush();

    final var pagesToFlush = new TreeSet<PageKey>();
    for (final var entry : writeCachePages.entrySet()) {
      final var pageKey = entry.getKey();
      if (fileIdSet.contains(pageKey.fileId())) {
        pagesToFlush.add(pageKey);
      }
    }

    LogSequenceNumber maxLSN = null;

    final var chunks = new ArrayList<ArrayList<WritePageContainer>>(chunkSize);
    for (final var pageKey : pagesToFlush) {
      if (fileIdSet.contains(pageKey.fileId())) {
        final var pagePointer = writeCachePages.get(pageKey);
        final var pageLock = lockManager.acquireExclusiveLock(pageKey);
        try {
          if (!pagePointer.tryAcquireSharedLock()) {
            continue;
          }
          try {
            final var buffer = pagePointer.getBuffer();

            final var directPointer = bufferPool.acquireDirect(false, Intention.FILE_FLUSH);
            final var copy = directPointer.getNativeByteBuffer();
            assert copy.position() == 0;

            assert buffer != null;
            assert buffer.position() == 0;
            copy.put(0, buffer, 0, buffer.capacity());

            final var endLSN = pagePointer.getEndLSN();

            if (endLSN != null && (maxLSN == null || endLSN.compareTo(maxLSN) > 0)) {
              maxLSN = endLSN;
            }

            var chunk = new ArrayList<WritePageContainer>(1);
            chunk.add(
                new WritePageContainer(pagePointer.getVersion(), copy, directPointer, pagePointer));
            chunks.add(chunk);

            removeFromDirtyPages(pageKey);
          } finally {
            pagePointer.releaseSharedLock();
          }
        } finally {
          pageLock.unlock();
        }

        if (chunks.size() >= 4 * chunkSize) {
          flushPages(chunks, maxLSN);
          chunks.clear();
        }
      }
    }

    flushPages(chunks, maxLSN);

    if (callFsync) {
      var fileIdIterator = fileIdSet.intIterator();
      while (fileIdIterator.hasNext()) {
        final var finalId = composeFileId(id, fileIdIterator.nextInt());
        final var entry = files.acquire(finalId);
        if (entry != null) {
          try {
            entry.get().synch();
          } finally {
            files.release(entry);
          }
        }
      }
    }

    return null;
  }

  private static Cipher getCipherInstance() {
    try {
      return Cipher.getInstance(TRANSFORMATION);
    } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
      throw BaseException.wrapException(
          new SecurityException((String) null,
              "Implementation of encryption " + TRANSFORMATION + " is absent"),
          e, (String) null);
    }
  }

  @Override
  public String getStorageName() {
    return storageName;
  }

  @Nullable
  public RawPair<String, String> executeDeleteFile(long externalFileId)
      throws IOException, java.lang.InterruptedException {
    final var internalFileId = extractFileId(externalFileId);
    final var fileId = composeFileId(id, internalFileId);

    doRemoveCachePages(internalFileId);

    final var fileClassic = files.remove(fileId);

    if (fileClassic != null) {
      if (fileClassic.exists()) {
        fileClassic.delete();
      }

      final var name = idNameMap.get(internalFileId);

      idNameMap.remove(internalFileId);

      nameIdMap.put(name, -internalFileId);
      idNameMap.put(-internalFileId, name);

      return new RawPair<>(fileClassic.getName(), name);
    }

    return null;
  }

  public void executePeriodicFlush(PeriodicFlushTask task) {
    if (stopFlush) {
      return;
    }

    var flushInterval = pagesFlushInterval;

    try {
      if (flushError != null) {
        final var iAdditionalArgs = new Object[]{flushError.getMessage()};
        LogManager.instance()
            .error(
                this,
                "Can not flush data because of issue during data write, %s",
                null,
                iAdditionalArgs);
        return;
      }

      try {
        if (writeCachePages.isEmpty()) {
          return;
        }

        var ewcSize = exclusiveWriteCacheSize.get();
        if (ewcSize >= 0) {
          flushExclusiveWriteCache(null, Math.min(ewcSize, 4L * chunkSize));

          if (exclusiveWriteCacheSize.get() > 0) {
            flushInterval = 1;
          }
        }

        final var begin = writeAheadLog.begin();
        final var end = writeAheadLog.end();
        final var segments = end.getSegment() - begin.getSegment() + 1;

        if (segments > 1) {
          convertSharedDirtyPagesToLocal();

          var firstSegment = localDirtyPagesBySegment.firstEntry();
          if (firstSegment != null) {
            final long firstSegmentIndex = firstSegment.getKey();
            if (firstSegmentIndex < end.getSegment()) {
              flushWriteCacheFromMinLSN(firstSegmentIndex, firstSegmentIndex + 1, chunkSize);
            }
          }

          firstSegment = localDirtyPagesBySegment.firstEntry();
          if (firstSegment != null && firstSegment.getKey() < end.getSegment()) {
            flushInterval = 1;
          }
        }
      } catch (final Error | Exception t) {
        LogManager.instance().error(this, "Exception during data flush", t);
        WOWCache.this.fireBackgroundDataFlushExceptionEvent(t);
        flushError = t;
      }
    } finally {
      if (flushInterval > 0 && !stopFlush) {
        flushFuture = commitExecutor.schedule(task, flushInterval, TimeUnit.MILLISECONDS);
      }
    }
  }

  public void executeFlush(CountDownLatch cacheBoundaryLatch, CountDownLatch completionLatch) {
    if (stopFlush) {
      return;
    }

    try {
      if (flushError != null) {
        final var iAdditionalArgs = new Object[]{flushError.getMessage()};
        LogManager.instance()
            .error(
                this,
                "Can not flush data because of issue during data write, %s",
                null,
                iAdditionalArgs);
        return;
      }

      if (writeCachePages.isEmpty()) {
        return;
      }

      final var ewcSize = exclusiveWriteCacheSize.get();

      assert ewcSize >= 0;

      if (cacheBoundaryLatch != null && ewcSize <= exclusiveWriteCacheMaxSize) {
        cacheBoundaryLatch.countDown();
      }

      if (ewcSize > exclusiveWriteCacheMaxSize) {
        flushExclusiveWriteCache(cacheBoundaryLatch, chunkSize);
      }

    } catch (final Error | Exception t) {
      LogManager.instance().error(this, "Exception during data flush", t);
      WOWCache.this.fireBackgroundDataFlushExceptionEvent(t);
      flushError = t;
    } finally {
      if (cacheBoundaryLatch != null) {
        cacheBoundaryLatch.countDown();
      }

      if (completionLatch != null) {
        completionLatch.countDown();
      }
    }
  }

  public Void executeFlushTillSegment(long segmentId)
      throws java.lang.InterruptedException, IOException {
    if (flushError != null) {
      final var iAdditionalArgs = new Object[]{flushError.getMessage()};
      LogManager.instance()
          .error(
              this,
              "Can not flush data till provided segment because of issue during data write, %s",
              null,
              iAdditionalArgs);
      return null;
    }

    convertSharedDirtyPagesToLocal();
    var firstEntry = localDirtyPagesBySegment.firstEntry();

    if (firstEntry == null) {
      return null;
    }

    long minDirtySegment = firstEntry.getKey();
    while (minDirtySegment < segmentId) {
      flushExclusivePagesIfNeeded();

      flushWriteCacheFromMinLSN(writeAheadLog.begin().getSegment(), segmentId, chunkSize);

      firstEntry = localDirtyPagesBySegment.firstEntry();

      if (firstEntry == null) {
        return null;
      }

      minDirtySegment = firstEntry.getKey();
    }

    return null;
  }

  private record WritePageContainer(long pageVersion, ByteBuffer copyOfPage,
                                    Pointer pageCopyDirectMemoryPointer,
                                    CachePointer originalPagePointer) {

  }
}
