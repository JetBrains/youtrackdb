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
package com.jetbrains.youtrack.db.internal.core.storage.cache.local;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.internal.common.collection.closabledictionary.ClosableEntry;
import com.jetbrains.youtrack.db.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrack.db.internal.common.concur.lock.LockManager;
import com.jetbrains.youtrack.db.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ReadersWriterSpinLock;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.common.directmemory.Pointer;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.common.thread.ThreadPoolExecutors;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.common.util.RawPairLongObject;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidStorageEncryptionKeyException;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.exception.WriteCacheException;
import com.jetbrains.youtrack.db.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrack.db.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrack.db.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.doublewritelog.DoubleWriteLog;
import com.jetbrains.youtrack.db.internal.core.storage.fs.AsyncFile;
import com.jetbrains.youtrack.db.internal.core.storage.fs.File;
import com.jetbrains.youtrack.db.internal.core.storage.fs.IOResult;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.MetaDataRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
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
import java.util.Collection;
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
import java.util.concurrent.locks.Lock;
import java.util.zip.CRC32;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

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

  private static final XXHashFactory XX_HASH_FACTORY = XXHashFactory.fastestInstance();
  private static final XXHash64 XX_HASH_64 = XX_HASH_FACTORY.hash64();
  private static final long XX_HASH_SEED = 0xAEF5634;

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
  private static final int MAX_FILE_RECORD_LEN = 16 * 1024;

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
            "YouTrackDB Write Cache Flush Task", AbstractPaginatedStorage.storageThreadGroup);
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
   * Serialized is used to encode/decode names of files are managed by write cache.
   */
  private final BinarySerializer<String> stringSerializer;

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
      final BinarySerializer<String> stringSerializer,
      final ClosableLinkedContainer<Long, File> files,
      final int id,
      final ChecksumMode checksumMode,
      final byte[] iv,
      final byte[] aesKey,
      final boolean callFsync,
      ExecutorService executor) {

    this.logFileDeletion = logFileDeletion;
    if (aesKey != null && aesKey.length != 16 && aesKey.length != 24 && aesKey.length != 32) {
      throw new InvalidStorageEncryptionKeyException(
          "Invalid length of the encryption key, provided size is " + aesKey.length);
    }

    if (aesKey != null && iv == null) {
      throw new InvalidStorageEncryptionKeyException("IV can not be null");
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
            new StorageException("Error during retrieving of file store"), e);
      }

      this.stringSerializer = stringSerializer;
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

      doubleWriteLog.open(storageName, storagePath, pageSize);

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

    for (final WeakReference<BackgroundExceptionListener> ref : backgroundExceptionListeners) {
      final BackgroundExceptionListener l = ref.get();
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
    for (final WeakReference<BackgroundExceptionListener> ref : backgroundExceptionListeners) {
      final BackgroundExceptionListener listener = ref.get();
      if (listener != null) {
        listener.onException(e);
      }
    }
  }

  private static int normalizeMemory(final long maxSize, final int pageSize) {
    final long tmpMaxSize = maxSize / pageSize;
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

    for (final WeakReference<PageIsBrokenListener> ref : pageIsBrokenListeners) {
      final PageIsBrokenListener pageIsBrokenListener = ref.get();

      if (pageIsBrokenListener == null || pageIsBrokenListener.equals(listener)) {
        itemsToRemove.add(ref);
      }
    }

    pageIsBrokenListeners.removeAll(itemsToRemove);
  }

  private void callPageIsBrokenListeners(final String fileName, final long pageIndex) {
    for (final WeakReference<PageIsBrokenListener> pageIsBrokenListenerWeakReference :
        pageIsBrokenListeners) {
      final PageIsBrokenListener listener = pageIsBrokenListenerWeakReference.get();
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

      final Integer fileId = nameIdMap.get(fileName);
      if (fileId != null) {
        if (fileId < 0) {
          return composeFileId(id, -fileId);
        } else {
          throw new StorageException(
              "File " + fileName + " has already been added to the storage");
        }
      }
      while (true) {
        final int nextId = fileIdGen.nextInt(Integer.MAX_VALUE - 1) + 1;
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

      Integer fileId = nameIdMap.get(fileName);
      final File fileClassic;

      // check that file is already registered
      if (!(fileId == null || fileId < 0)) {
        final long externalId = composeFileId(id, fileId);
        fileClassic = files.get(externalId);

        if (fileClassic != null) {
          return externalId;
        } else {
          throw new StorageException(
              "File with given name " + fileName + " only partially registered in storage");
        }
      }

      if (fileId == null) {
        while (true) {
          final int nextId = fileIdGen.nextInt(Integer.MAX_VALUE - 1) + 1;
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
        throw new StorageException(
            "File with name " + fileName + " does not exist in storage " + storageName);
      } else {
        // REGISTER THE FILE
        LogManager.instance()
            .debug(
                this,
                "File '"
                    + fileName
                    + "' is not registered in 'file name - id' map, but exists in file system."
                    + " Registering it");

        openFile(fileClassic);

        final long externalId = composeFileId(id, fileId);
        files.add(externalId, fileClassic);

        nameIdMap.put(fileName, fileId);
        idNameMap.put(fileId, fileName);

        writeNameIdEntry(new NameFileIdEntry(fileName, fileId, fileClassic.getName()), true);

        return externalId;
      }
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(new StorageException("Load file was interrupted"), e);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public long addFile(final String fileName) throws IOException {
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      Integer fileId = nameIdMap.get(fileName);
      final File fileClassic;

      if (fileId != null && fileId >= 0) {
        throw new StorageException(
            "File with name " + fileName + " already exists in storage " + storageName);
      }

      if (fileId == null) {
        while (true) {
          final int nextId = fileIdGen.nextInt(Integer.MAX_VALUE - 1) + 1;
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

      final long externalId = composeFileId(id, fileId);
      files.add(externalId, fileClassic);

      nameIdMap.put(fileName, fileId);
      idNameMap.put(fileId, fileName);

      writeNameIdEntry(new NameFileIdEntry(fileName, fileId, fileClassic.getName()), true);

      return externalId;
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(new StorageException("File add was interrupted"), e);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public long fileIdByName(final String fileName) {
    final Integer intId = nameIdMap.get(fileName);

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
    final Future<Long> future = commitExecutor.submit(new FindMinDirtySegment(this));
    try {
      return future.get();
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void updateDirtyPagesTable(
      final CachePointer pointer, final LogSequenceNumber startLSN) {
    final long fileId = pointer.getFileId();
    final long pageIndex = pointer.getPageIndex();

    final PageKey pageKey = new PageKey(internalFileId(fileId), pageIndex);

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

      final Integer existingFileId = nameIdMap.get(fileName);

      final int intId = extractFileId(fileId);

      if (existingFileId != null && existingFileId >= 0) {
        if (existingFileId == intId) {
          throw new StorageException(
              "File with name '" + fileName + "'' already exists in storage '" + storageName + "'");
        } else {
          throw new StorageException(
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
          throw new StorageException(
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
      throw BaseException.wrapException(new StorageException("File add was interrupted"), e);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public boolean checkLowDiskSpace() throws IOException {
    final long freeSpace = fileStore.getUsableSpace();
    return freeSpace < freeSpaceLimit;
  }

  public void syncDataFiles(final long segmentId, final byte[] lastMetadata) throws IOException {
    filesLock.acquireReadLock();
    try {
      checkForClose();

      doubleWriteLog.startCheckpoint();
      try {
        if (lastMetadata != null) {
          writeAheadLog.log(new MetaDataRecord(lastMetadata));
        }

        for (final Integer intId : nameIdMap.values()) {
          if (intId < 0) {
            continue;
          }

          if (callFsync) {
            final long fileId = composeFileId(id, intId);
            final ClosableEntry<Long, File> entry = files.acquire(fileId);
            try {
              final File fileClassic = entry.get();
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
      throw BaseException.wrapException(new StorageException("Fuzzy checkpoint was interrupted"),
          e);
    } finally {
      filesLock.releaseReadLock();
    }
  }

  @Override
  public void flushTillSegment(final long segmentId) {
    final Future<Void> future = commitExecutor.submit(new FlushTillSegmentTask(this, segmentId));
    try {
      future.get();
    } catch (final Exception e) {
      throw DatabaseException.wrapException(new StorageException("Error during data flush"), e);
    }
  }

  @Override
  public boolean exists(final String fileName) {
    filesLock.acquireReadLock();
    try {
      checkForClose();

      final Integer intId = nameIdMap.get(fileName);
      if (intId != null && intId >= 0) {
        final File fileClassic = files.get(externalFileId(intId));

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

      final int intId = extractFileId(fileId);
      fileId = composeFileId(id, intId);

      final File file = files.get(fileId);
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
      final CountDownLatch cacheBoundaryLatch = new CountDownLatch(1);
      final CountDownLatch completionLatch = new CountDownLatch(1);
      final ExclusiveFlushTask exclusiveFlushTask =
          new ExclusiveFlushTask(this, cacheBoundaryLatch, completionLatch);

      triggeredTasks.put(exclusiveFlushTask, completionLatch);
      commitExecutor.submit(exclusiveFlushTask);

      cacheBoundaryLatch.await();
    }
  }

  @Override
  public void store(final long fileId, final long pageIndex, final CachePointer dataPointer) {
    final int intId = extractFileId(fileId);

    filesLock.acquireReadLock();
    try {
      checkForClose();

      final PageKey pageKey = new PageKey(intId, pageIndex);

      final Lock groupLock = lockManager.acquireExclusiveLock(pageKey);
      try {
        final CachePointer pagePointer = writeCachePages.get(pageKey);

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

      final Object2LongOpenHashMap<String> result = new Object2LongOpenHashMap<>(1_000);
      result.defaultReturnValue(-1);

      for (final Map.Entry<String, Integer> entry : nameIdMap.entrySet()) {
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
    final int intId = extractFileId(fileId);
    filesLock.acquireReadLock();
    try {
      checkForClose();

      final PageKey pageKey = new PageKey(intId, startPageIndex);
      final Lock pageLock = lockManager.acquireSharedLock(pageKey);

      // check if page already presented in write cache
      final CachePointer pagePointer = writeCachePages.get(pageKey);

      // page is not cached load it from file
      if (pagePointer == null) {
        try {
          // load requested page and preload requested amount of pages
          final CachePointer filePagePointer =
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

      final ClosableEntry<Long, File> entry = files.acquire(fileId);
      try {
        final File fileClassic = entry.get();
        final long allocatedPosition = fileClassic.allocateSpace(pageSize);
        final long allocationIndex = allocatedPosition / pageSize;

        pageIndex = (int) allocationIndex;
        if (pageIndex < 0) {
          throw new IllegalStateException("Illegal page index value " + pageIndex);
        }

      } finally {
        files.release(entry);
      }
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new StorageException("Allocation of page was interrupted"), e);
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
    final Future<Void> future =
        commitExecutor.submit(
            new FileFlushTask(this, Collections.singleton(extractFileId(fileId))));
    try {
      future.get();
    } catch (final java.lang.InterruptedException e) {
      Thread.currentThread().interrupt();
      throw BaseException.wrapException(
          new ThreadInterruptedException("File flush was interrupted"), e);
    } catch (final Exception e) {
      throw BaseException.wrapException(
          new WriteCacheException("File flush was abnormally terminated"), e);
    }
  }

  @Override
  public void flush() {

    final Future<Void> future = commitExecutor.submit(new FileFlushTask(this, nameIdMap.values()));
    try {
      future.get();
    } catch (final java.lang.InterruptedException e) {
      Thread.currentThread().interrupt();
      throw BaseException.wrapException(
          new ThreadInterruptedException("File flush was interrupted"), e);
    } catch (final Exception e) {
      throw BaseException.wrapException(
          new WriteCacheException("File flush was abnormally terminated"), e);
    }
  }

  @Override
  public long getFilledUpTo(long fileId) {
    final int intId = extractFileId(fileId);
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
    final int intId = extractFileId(fileId);

    filesLock.acquireWriteLock();
    try {
      checkForClose();

      final RawPair<String, String> file;
      final Future<RawPair<String, String>> future =
          commitExecutor.submit(new DeleteFileTask(this, fileId));
      try {
        file = future.get();
      } catch (final java.lang.InterruptedException e) {
        throw BaseException.wrapException(
            new ThreadInterruptedException("File data removal was interrupted"), e);
      } catch (final Exception e) {
        throw BaseException.wrapException(
            new WriteCacheException("File data removal was abnormally terminated"), e);
      }

      if (file != null) {
        writeNameIdEntry(new NameFileIdEntry(file.first, -intId, file.second), true);
      }
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public void truncateFile(long fileId) throws IOException {
    final int intId = extractFileId(fileId);
    fileId = composeFileId(id, intId);

    filesLock.acquireWriteLock();
    try {
      checkForClose();

      removeCachedPages(intId);
      final ClosableEntry<Long, File> entry = files.acquire(fileId);
      try {
        entry.get().shrink(0);
      } finally {
        files.release(entry);
      }
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(new StorageException("File truncation was interrupted"), e);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public boolean fileIdsAreEqual(final long firsId, final long secondId) {
    final int firstIntId = extractFileId(firsId);
    final int secondIntId = extractFileId(secondId);

    return firstIntId == secondIntId;
  }

  @Override
  public void renameFile(long fileId, final String newFileName) throws IOException {
    final int intId = extractFileId(fileId);
    fileId = composeFileId(id, intId);

    filesLock.acquireWriteLock();
    try {
      checkForClose();

      final ClosableEntry<Long, File> entry = files.acquire(fileId);

      if (entry == null) {
        return;
      }

      final String oldOsFileName;
      final String newOsFileName = createInternalFileName(newFileName, intId);

      try {
        final File file = entry.get();
        oldOsFileName = file.getName();

        final Path newFile = storagePath.resolve(newOsFileName);
        file.renameTo(newFile);
      } finally {
        files.release(entry);
      }

      final String oldFileName = idNameMap.get(intId);

      nameIdMap.remove(oldFileName);
      nameIdMap.put(newFileName, intId);

      idNameMap.put(intId, newFileName);

      writeNameIdEntry(new NameFileIdEntry(oldFileName, -1, oldOsFileName), false);
      writeNameIdEntry(new NameFileIdEntry(newFileName, intId, newOsFileName), true);
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(new StorageException("Rename of file was interrupted"), e);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public void replaceFileId(final long fileId, final long newFileId) throws IOException {
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      final File file = files.remove(fileId);
      final File newFile = files.remove(newFileId);

      final int intFileId = extractFileId(fileId);
      final int newIntFileId = extractFileId(newFileId);

      final String fileName = idNameMap.get(intFileId);
      final String newFileName = idNameMap.remove(newIntFileId);

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
      throw BaseException.wrapException(new StorageException("Replace of file was interrupted"), e);
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  private void stopFlush() {
    stopFlush = true;

    for (final CountDownLatch completionLatch : triggeredTasks.values()) {
      try {
        if (!completionLatch.await(shutdownTimeout, TimeUnit.MINUTES)) {
          throw new WriteCacheException("Can not shutdown data flush for storage " + storageName);
        }
      } catch (final java.lang.InterruptedException e) {
        throw BaseException.wrapException(
            new WriteCacheException(
                "Flush of the data for storage " + storageName + " has been interrupted"),
            e);
      }
    }

    if (flushFuture != null) {
      try {
        flushFuture.get(shutdownTimeout, TimeUnit.MINUTES);
      } catch (final java.lang.InterruptedException | CancellationException e) {
        // ignore
      } catch (final ExecutionException e) {
        throw BaseException.wrapException(
            new WriteCacheException(
                "Error in execution of data flush for storage " + storageName),
            e);
      } catch (final TimeoutException e) {
        throw BaseException.wrapException(
            new WriteCacheException("Can not shutdown data flush for storage " + storageName), e);
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

      final Collection<Integer> fileIds = nameIdMap.values();

      final LongArrayList closedIds = new LongArrayList(1_000);
      final Int2ObjectOpenHashMap<String> idFileNameMap = new Int2ObjectOpenHashMap<>(1_000);

      for (final Integer intId : fileIds) {
        if (intId >= 0) {
          final long extId = composeFileId(id, intId);
          final File fileClassic = files.remove(extId);

          idFileNameMap.put(intId.intValue(), fileClassic.getName());
          fileClassic.close();
          closedIds.add(extId);
        }
      }

      final Path nameIdMapBackupPath = storagePath.resolve(NAME_ID_MAP_V2_BACKUP);
      try (final FileChannel nameIdMapHolder =
          FileChannel.open(
              nameIdMapBackupPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE)) {
        nameIdMapHolder.truncate(0);

        for (final Map.Entry<String, Integer> entry : nameIdMap.entrySet()) {
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
      throw new StorageException("Write cache is closed and can not be used");
    }
  }

  @Override
  public void close(long fileId, final boolean flush) {
    final int intId = extractFileId(fileId);
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
        throw new StorageException(
            "Can not close file with id " + internalFileId(fileId) + " because it is still in use");
      }
    } finally {
      filesLock.releaseWriteLock();
    }
  }

  @Override
  public String restoreFileById(final long fileId) throws IOException {
    final int intId = extractFileId(fileId);
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      for (final Map.Entry<String, Integer> entry : nameIdMap.entrySet()) {
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
    final int notificationTimeOut = 5000;

    final List<PageDataVerificationError> errors = new ArrayList<>(0);

    filesLock.acquireWriteLock();
    try {
      checkForClose();

      for (final Integer intId : nameIdMap.values()) {
        if (intId < 0) {
          continue;
        }

        checkFileStoredPages(commandOutputListener, notificationTimeOut, errors, intId);
      }

      return errors.toArray(new PageDataVerificationError[0]);
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(new StorageException("Thread was interrupted"), e);
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
    final long externalId = composeFileId(id, intId);
    final ClosableEntry<Long, File> entry = files.acquire(externalId);
    final File fileClassic = entry.get();
    final String fileName = idNameMap.get(intId);

    try {
      if (commandOutputListener != null) {
        commandOutputListener.onMessage("Flashing file " + fileName + "... ");
      }

      flush(intId);

      if (commandOutputListener != null) {
        commandOutputListener.onMessage(
            "Start verification of content of " + fileName + "file ...\n");
      }

      long time = System.currentTimeMillis();

      final long filledUpTo = fileClassic.getFileSize();
      fileIsCorrect = true;

      for (long pos = 0; pos < filledUpTo; pos += pageSize) {
        boolean checkSumIncorrect = false;
        boolean magicNumberIncorrect = false;

        final byte[] data = new byte[pageSize];

        final Pointer pointer = bufferPool.acquireDirect(true, Intention.CHECK_FILE_STORAGE);
        try {
          final ByteBuffer byteBuffer = pointer.getNativeByteBuffer();
          fileClassic.read(pos, byteBuffer, true);
          byteBuffer.rewind();
          byteBuffer.get(data);
        } finally {
          bufferPool.release(pointer);
        }

        final long magicNumber =
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
          final int storedCRC32 =
              IntegerSerializer.INSTANCE.deserializeNative(data, CHECKSUM_OFFSET);

          final CRC32 crc32 = new CRC32();
          crc32.update(
              data, PAGE_OFFSET_TO_CHECKSUM_FROM, data.length - PAGE_OFFSET_TO_CHECKSUM_FROM);
          final int calculatedCRC32 = (int) crc32.getValue();

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
    final LongArrayList result = new LongArrayList(1_024);
    filesLock.acquireWriteLock();
    try {
      checkForClose();

      for (final int internalFileId : nameIdMap.values()) {
        if (internalFileId < 0) {
          continue;
        }

        final long externalId = composeFileId(id, internalFileId);

        final RawPair<String, String> file;
        final Future<RawPair<String, String>> future =
            commitExecutor.submit(new DeleteFileTask(this, externalId));
        try {
          file = future.get();
        } catch (final java.lang.InterruptedException e) {
          throw BaseException.wrapException(
              new ThreadInterruptedException("File data removal was interrupted"), e);
        } catch (final Exception e) {
          throw BaseException.wrapException(
              new WriteCacheException("File data removal was abnormally terminated"), e);
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
    final int intId = extractFileId(fileId);

    return idNameMap.get(intId);
  }

  @Override
  public String nativeFileNameById(final long fileId) {
    final File fileClassic = files.get(fileId);
    if (fileClassic != null) {
      return fileClassic.getName();
    }

    return null;
  }

  @Override
  public int getId() {
    return id;
  }

  private static void openFile(final File fileClassic) {
    if (fileClassic.exists()) {
      if (!fileClassic.isOpen()) {
        fileClassic.open();
      }
    } else {
      throw new StorageException("File " + fileClassic + " does not exist.");
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

    final Path nameIdMapHolderV1 = storagePath.resolve(NAME_ID_MAP_V1);
    final Path nameIdMapHolderV2 = storagePath.resolve(NAME_ID_MAP_V2);
    final Path nameIdMapHolderV3 = storagePath.resolve(NAME_ID_MAP_V3);

    if (Files.exists(nameIdMapHolderV1)) {
      if (Files.exists(nameIdMapHolderV2)) {
        Files.delete(nameIdMapHolderV2);
      }
      if (Files.exists(nameIdMapHolderV3)) {
        Files.delete(nameIdMapHolderV3);
      }

      try (final FileChannel nameIdMapHolder =
          FileChannel.open(nameIdMapHolderV1, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
        readNameIdMapV1(nameIdMapHolder);
      }

      Files.delete(nameIdMapHolderV1);
    } else if (Files.exists(nameIdMapHolderV2)) {
      if (Files.exists(nameIdMapHolderV3)) {
        Files.delete(nameIdMapHolderV3);
      }

      try (final FileChannel nameIdMapHolder =
          FileChannel.open(nameIdMapHolderV2, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
        readNameIdMapV2(nameIdMapHolder);
      }

      Files.delete(nameIdMapHolderV2);
    }

    nameIdMapHolderPath = nameIdMapHolderV3;
    if (Files.exists(nameIdMapHolderPath)) {
      try (final FileChannel nameIdMapHolder =
          FileChannel.open(
              nameIdMapHolderPath, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
        readNameIdMapV3(nameIdMapHolder);
      }
    } else {
      storedNameIdMapToV3();
    }
  }

  private void storedNameIdMapToV3() throws IOException {
    final Path nameIdMapHolderFileV3T = storagePath.resolve(NAME_ID_MAP_V3_T);

    if (Files.exists(nameIdMapHolderFileV3T)) {
      Files.delete(nameIdMapHolderFileV3T);
    }

    final FileChannel v3NameIdMapHolder =
        FileChannel.open(
            nameIdMapHolderFileV3T,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ);

    for (final Map.Entry<String, Integer> nameIdEntry : nameIdMap.entrySet()) {
      if (nameIdEntry.getValue() >= 0) {
        final File fileClassic = files.get(externalFileId(nameIdEntry.getValue()));
        final String fileSystemName = fileClassic.getName();

        final NameFileIdEntry nameFileIdEntry =
            new NameFileIdEntry(nameIdEntry.getKey(), nameIdEntry.getValue(), fileSystemName);
        writeNameIdEntry(v3NameIdMapHolder, nameFileIdEntry, false);
      } else {
        final NameFileIdEntry nameFileIdEntry =
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
    final String internalFileName = createInternalFileName(fileName, fileId);
    return new AsyncFile(
        storagePath.resolve(internalFileName),
        pageSize,
        logFileDeletion,
        this.executor,
        storageName);
  }

  private static String createInternalFileName(final String fileName, final int fileId) {
    final int extSeparator = fileName.lastIndexOf('.');

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

    final Int2ObjectOpenHashMap<String> idFileNameMap = new Int2ObjectOpenHashMap<>(1_000);

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

    for (final Map.Entry<String, Integer> nameIdEntry : nameIdMap.entrySet()) {
      final int fileId = nameIdEntry.getValue();

      if (fileId >= 0) {
        final long externalId = composeFileId(id, nameIdEntry.getValue());

        if (files.get(externalId) == null) {
          final Path path =
              storagePath.resolve(idFileNameMap.get((nameIdEntry.getValue().intValue())));
          final AsyncFile file =
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

    final Int2ObjectOpenHashMap<String> idFileNameMap = new Int2ObjectOpenHashMap<>(1_000);

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

    for (final Map.Entry<String, Integer> nameIdEntry : nameIdMap.entrySet()) {
      final int fileId = nameIdEntry.getValue();

      if (fileId > 0) {
        final long externalId = composeFileId(id, nameIdEntry.getValue());

        if (files.get(externalId) == null) {
          final Path path =
              storagePath.resolve(idFileNameMap.get((nameIdEntry.getValue().intValue())));
          final AsyncFile file =
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
    final Int2ObjectOpenHashMap<Set<String>> filesWithNegativeIds =
        new Int2ObjectOpenHashMap<>(1_000);

    nameIdMap.clear();

    long localFileCounter = -1;

    nameIdMapHolder.position(0);

    NameFileIdEntry nameFileIdEntry;
    while ((nameFileIdEntry = readNextNameIdEntryV1(nameIdMapHolder)) != null) {

      final long absFileId = Math.abs(nameFileIdEntry.getFileId());
      if (localFileCounter < absFileId) {
        localFileCounter = absFileId;
      }

      final Integer existingId = nameIdMap.get(nameFileIdEntry.getName());

      if (existingId != null && existingId < 0) {
        final Set<String> files = filesWithNegativeIds.get(existingId.intValue());

        if (files != null) {
          files.remove(nameFileIdEntry.getName());
          if (files.isEmpty()) {
            filesWithNegativeIds.remove(existingId.intValue());
          }
        }
      }

      if (nameFileIdEntry.getFileId() < 0) {
        Set<String> files = filesWithNegativeIds.get(nameFileIdEntry.getFileId());

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

    for (final Map.Entry<String, Integer> nameIdEntry : nameIdMap.entrySet()) {
      if (nameIdEntry.getValue() >= 0) {
        final long externalId = composeFileId(id, nameIdEntry.getValue());

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
            final Integer fileId = nameIdMap.get(nameIdEntry.getKey());

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

    for (final Int2ObjectMap.Entry<Set<String>> entry : filesWithNegativeIds.int2ObjectEntrySet()) {
      final Set<String> files = entry.getValue();

      if (files.size() > 1) {
        idNameMap.remove(entry.getIntKey());

        for (final String fileName : files) {
          int fileId;

          while (true) {
            final int nextId = fileIdGen.nextInt(Integer.MAX_VALUE - 1) + 1;
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

  private NameFileIdEntry readNextNameIdEntryV1(FileChannel nameIdMapHolder) throws IOException {
    try {
      ByteBuffer buffer = ByteBuffer.allocate(IntegerSerializer.INT_SIZE);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final int nameSize = buffer.getInt();
      buffer = ByteBuffer.allocate(nameSize + LongSerializer.LONG_SIZE);

      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final String name = stringSerializer.deserializeFromByteBufferObject(buffer);
      final int fileId = (int) buffer.getLong();

      return new NameFileIdEntry(name, fileId);
    } catch (final EOFException ignore) {
      return null;
    }
  }

  private NameFileIdEntry readNextNameIdEntryV2(FileChannel nameIdMapHolder) throws IOException {
    try {
      ByteBuffer buffer = ByteBuffer.allocate(2 * IntegerSerializer.INT_SIZE);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final int fileId = buffer.getInt();
      final int nameSize = buffer.getInt();

      buffer = ByteBuffer.allocate(nameSize);

      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final String name = stringSerializer.deserializeFromByteBufferObject(buffer);

      buffer = ByteBuffer.allocate(IntegerSerializer.INT_SIZE);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final int fileNameSize = buffer.getInt();

      buffer = ByteBuffer.allocate(fileNameSize);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final String fileName = stringSerializer.deserializeFromByteBufferObject(buffer);

      return new NameFileIdEntry(name, fileId, fileName);

    } catch (final EOFException ignore) {
      return null;
    }
  }

  private NameFileIdEntry readNextNameIdEntryV3(FileChannel nameIdMapHolder) throws IOException {
    try {
      final int xxHashLen = 8;
      final int recordSizeLen = 4;

      ByteBuffer buffer = ByteBuffer.allocate(xxHashLen + recordSizeLen);
      IOUtils.readByteBuffer(buffer, nameIdMapHolder);
      buffer.rewind();

      final long storedXxHash = buffer.getLong();
      final int recordLen = buffer.getInt();

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

      final long xxHash = XX_HASH_64.hash(buffer, 0, recordLen, XX_HASH_SEED);
      if (xxHash != storedXxHash) {
        LogManager.instance()
            .error(
                this, "Hash of the file registry is broken. Storage name : %s", null, storageName);
        return null;
      }

      final int fileId = buffer.getInt();
      final String name = stringSerializer.deserializeFromByteBufferObject(buffer);
      final String fileName = stringSerializer.deserializeFromByteBufferObject(buffer);

      return new NameFileIdEntry(name, fileId, fileName);

    } catch (final EOFException ignore) {
      return null;
    }
  }

  private void writeNameIdEntry(final NameFileIdEntry nameFileIdEntry, final boolean sync)
      throws IOException {
    try (final FileChannel nameIdMapHolder =
        FileChannel.open(nameIdMapHolderPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      writeNameIdEntry(nameIdMapHolder, nameFileIdEntry, sync);
    }
  }

  private void writeNameIdEntry(
      final FileChannel nameIdMapHolder, final NameFileIdEntry nameFileIdEntry, final boolean sync)
      throws IOException {
    final int xxHashSize = 8;
    final int recordLenSize = 4;

    final int nameSize = stringSerializer.getObjectSize(nameFileIdEntry.getName());
    final int fileNameSize = stringSerializer.getObjectSize(nameFileIdEntry.getFileSystemName());

    // file id size + file name + file system name + xx_hash size + record_size size
    final ByteBuffer serializedRecord =
        ByteBuffer.allocate(
            IntegerSerializer.INT_SIZE + nameSize + fileNameSize + xxHashSize + recordLenSize);

    serializedRecord.position(xxHashSize + recordLenSize);

    // serialize file id
    IntegerSerializer.INSTANCE.serializeInByteBufferObject(
        nameFileIdEntry.getFileId(), serializedRecord);

    // serialize file name
    stringSerializer.serializeInByteBufferObject(nameFileIdEntry.getName(), serializedRecord);

    // serialize file system name
    stringSerializer.serializeInByteBufferObject(
        nameFileIdEntry.getFileSystemName(), serializedRecord);

    final int recordLen = serializedRecord.position() - xxHashSize - recordLenSize;
    if (recordLen > MAX_FILE_RECORD_LEN) {
      throw new StorageException(
          "Maximum record length in file registry can not exceed "
              + MAX_FILE_RECORD_LEN
              + " bytes. But actual record length "
              + recordLen);
    }
    serializedRecord.putInt(xxHashSize, recordLen);

    final long xxHash =
        XX_HASH_64.hash(serializedRecord, xxHashSize + recordLenSize, recordLen, XX_HASH_SEED);
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
    final Future<Void> future = commitExecutor.submit(new RemoveFilePagesTask(this, fileId));
    try {
      future.get();
    } catch (final java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new ThreadInterruptedException("File data removal was interrupted"), e);
    } catch (final Exception e) {
      throw BaseException.wrapException(
          new WriteCacheException("File data removal was abnormally terminated"), e);
    }
  }

  private CachePointer loadFileContent(
      final int internalFileId, final long pageIndex, final boolean verifyChecksums)
      throws IOException {
    final long fileId = composeFileId(id, internalFileId);
    try {
      final ClosableEntry<Long, File> entry = files.acquire(fileId);
      try {
        final File fileClassic = entry.get();
        if (fileClassic == null) {
          throw new IllegalArgumentException(
              "File with id " + internalFileId + " not found in WOW Cache");
        }

        final long pagePosition = pageIndex * pageSize;
        final long pageEndPosition = pagePosition + pageSize;

        // if page is not stored in the file may be page is stored in double write log
        if (fileClassic.getFileSize() >= pageEndPosition) {
          Pointer pointer = bufferPool.acquireDirect(true, Intention.LOAD_PAGE_FROM_DISK);
          ByteBuffer buffer = pointer.getNativeByteBuffer();

          assert buffer.position() == 0;
          assert buffer.order() == ByteOrder.nativeOrder();

          fileClassic.read(pagePosition, buffer, false);

          if (verifyChecksums
              && (checksumMode == ChecksumMode.StoreAndVerify
              || checksumMode == ChecksumMode.StoreAndThrow
              || checksumMode == ChecksumMode.StoreAndSwitchReadOnlyMode)) {
            // if page is broken inside of data file we check double write log
            if (!verifyMagicChecksumAndDecryptPage(buffer, internalFileId, pageIndex)) {
              final Pointer doubleWritePointer =
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
          final Pointer pointer =
              doubleWriteLog.loadPage(internalFileId, (int) pageIndex, bufferPool);
          if (pointer != null) {
            final ByteBuffer buffer = pointer.getNativeByteBuffer();
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
      throw BaseException.wrapException(new StorageException("Data load was interrupted"), e);
    }
  }

  private void assertPageIsBroken(long pageIndex, long fileId, Pointer pointer) {
    final String message =
        "Magic number verification failed for page `"
            + pageIndex
            + "` of `"
            + fileNameById(fileId)
            + "`.";
    LogManager.instance().error(this, "%s", null, message);

    if (checksumMode == ChecksumMode.StoreAndThrow) {
      bufferPool.release(pointer);
      throw new StorageException(message);
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
      final CRC32 crc32 = new CRC32();
      crc32.update(buffer);
      final int computedChecksum = (int) crc32.getValue();

      buffer.position(CHECKSUM_OFFSET);
      buffer.putInt(computedChecksum);
    }

    if (aesKey != null) {
      long magicNumber = buffer.getLong(MAGIC_NUMBER_OFFSET);

      long updateCounter = magicNumber >>> 8;
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
      final Cipher cipher = CIPHER.get();
      final SecretKey aesKey = new SecretKeySpec(this.aesKey, ALGORITHM_NAME);

      final byte[] updatedIv = new byte[iv.length];

      for (int i = 0; i < IntegerSerializer.INT_SIZE; i++) {
        updatedIv[i] = (byte) (iv[i] ^ ((pageIndex >>> i) & 0xFF));
      }

      for (int i = 0; i < IntegerSerializer.INT_SIZE; i++) {
        updatedIv[i + IntegerSerializer.INT_SIZE] =
            (byte) (iv[i + IntegerSerializer.INT_SIZE] ^ ((intId >>> i) & 0xFF));
      }

      for (int i = 0; i < LongSerializer.LONG_SIZE - 1; i++) {
        updatedIv[i + 2 * IntegerSerializer.INT_SIZE] =
            (byte) (iv[i + 2 * IntegerSerializer.INT_SIZE] ^ ((updateCounter >>> i) & 0xFF));
      }

      updatedIv[updatedIv.length - 1] = iv[iv.length - 1];

      cipher.init(mode, aesKey, new IvParameterSpec(updatedIv));

      final ByteBuffer outBuffer =
          ByteBuffer.allocate(buffer.capacity() - CHECKSUM_OFFSET).order(ByteOrder.nativeOrder());

      buffer.position(CHECKSUM_OFFSET);
      cipher.doFinal(buffer, outBuffer);

      buffer.position(CHECKSUM_OFFSET);
      outBuffer.position(0);
      buffer.put(outBuffer);

    } catch (InvalidKeyException e) {
      throw BaseException.wrapException(new InvalidStorageEncryptionKeyException(e.getMessage()),
          e);
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
    final long magicNumber = LongSerializer.INSTANCE.deserializeFromByteBufferObject(buffer);

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
    final int storedChecksum = IntegerSerializer.INSTANCE.deserializeFromByteBufferObject(buffer);

    buffer.position(PAGE_OFFSET_TO_CHECKSUM_FROM);
    final CRC32 crc32 = new CRC32();
    crc32.update(buffer);
    final int computedChecksum = (int) crc32.getValue();

    return computedChecksum == storedChecksum;
  }

  private void dumpStackTrace(final String message) {
    final StringWriter stringWriter = new StringWriter();
    final PrintWriter printWriter = new PrintWriter(stringWriter);

    printWriter.println(message);
    final Exception exception = new Exception();
    exception.printStackTrace(printWriter);
    printWriter.flush();

    LogManager.instance().error(this, stringWriter.toString(), null);
  }

  private void fsyncFiles() throws java.lang.InterruptedException, IOException {
    for (int intFileId : idNameMap.keySet()) {
      if (intFileId >= 0) {
        final long extFileId = externalFileId(intFileId);

        final ClosableEntry<Long, File> fileEntry = files.acquire(extFileId);
        // such thing can happen during db restore
        if (fileEntry == null) {
          continue;
        }
        try {
          final File fileClassic = fileEntry.get();
          fileClassic.synch();
        } finally {
          files.release(fileEntry);
        }
      }
    }

    doubleWriteLog.truncate();
  }

  void doRemoveCachePages(int internalFileId) {
    final Iterator<Map.Entry<PageKey, CachePointer>> entryIterator =
        writeCachePages.entrySet().iterator();
    while (entryIterator.hasNext()) {
      final Map.Entry<PageKey, CachePointer> entry = entryIterator.next();
      final PageKey pageKey = entry.getKey();

      if (pageKey.fileId == internalFileId) {
        final CachePointer pagePointer = entry.getValue();
        final Lock groupLock = lockManager.acquireExclusiveLock(pageKey);
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
    final long ewcSize = exclusiveWriteCacheSize.get();
    assert ewcSize >= 0;

    if (ewcSize >= 0.8 * exclusiveWriteCacheMaxSize) {
      flushExclusiveWriteCache(null, ewcSize);
    }
  }

  public Long executeFindDirtySegment() {
    if (flushError != null) {
      final Object[] iAdditionalArgs = new Object[]{flushError.getMessage()};
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
    for (final Map.Entry<PageKey, LogSequenceNumber> entry : dirtyPages.entrySet()) {
      final LogSequenceNumber localLSN = localDirtyPages.get(entry.getKey());

      if (localLSN == null || localLSN.compareTo(entry.getValue()) > 0) {
        localDirtyPages.put(entry.getKey(), entry.getValue());

        final long segment = entry.getValue().getSegment();
        TreeSet<PageKey> pages = localDirtyPagesBySegment.get(segment);
        if (pages == null) {
          pages = new TreeSet<>();
          pages.add(entry.getKey());

          localDirtyPagesBySegment.put(segment, pages);
        } else {
          pages.add(entry.getKey());
        }
      }
    }

    for (final Map.Entry<PageKey, LogSequenceNumber> entry : localDirtyPages.entrySet()) {
      dirtyPages.remove(entry.getKey(), entry.getValue());
    }
  }

  private void removeFromDirtyPages(final PageKey pageKey) {
    dirtyPages.remove(pageKey);

    final LogSequenceNumber lsn = localDirtyPages.remove(pageKey);
    if (lsn != null) {
      final long segment = lsn.getSegment();
      final TreeSet<PageKey> pages = localDirtyPagesBySegment.get(segment);
      assert pages != null;

      final boolean removed = pages.remove(pageKey);
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

    int copiedPages = 0;

    ArrayList<ArrayList<WritePageContainer>> chunks = new ArrayList<>(16);
    ArrayList<WritePageContainer> chunk = new ArrayList<>(16);

    long currentSegment = segStart;

    int chunksSize = 0;

    var fileIdSizeMap = new Int2LongOpenHashMap();
    fileIdSizeMap.defaultReturnValue(-1);

    LogSequenceNumber maxFullLogLSN = null;
    flushCycle:
    while (chunksSize < pagesFlushLimit) {
      final TreeSet<PageKey> segmentPages = localDirtyPagesBySegment.get(currentSegment);

      if (segmentPages == null) {
        currentSegment++;

        if (currentSegment >= segEnd) {
          break;
        }

        continue;
      }

      final Iterator<PageKey> lsnPagesIterator = segmentPages.iterator();
      final List<PageKey> pageKeysToFlush = new ArrayList<>(pagesFlushLimit);

      while (lsnPagesIterator.hasNext() && pageKeysToFlush.size() < pagesFlushLimit - chunksSize) {
        final PageKey pageKey = lsnPagesIterator.next();
        var fileId = pageKey.fileId;
        var fileSize = fileIdSizeMap.get(fileId);

        if (fileSize == -1) {
          fileSize = files.get(externalFileId(fileId)).getUnderlyingFileSize();

          if ((fileSize & (pageSize - 1)) != 0) {
            throw new StorageException(
                "Storage : "
                    + storageName
                    + ". File size is not multiple of page size. File id : "
                    + fileId);
          }
          fileIdSizeMap.put(fileId, fileSize);
        }

        var diff = (pageKey.pageIndex * pageSize - fileSize) / pageSize;
        // it is important to do not create holes in the file
        // otherwise restore process after crash will be aborted
        // because of invalid data
        if (diff > 0) {
          diff = Math.min(diff, pagesFlushLimit - chunksSize - pageKeysToFlush.size());
          var startPageIndex = fileSize / pageSize;

          for (int i = 0; i < diff; i++) {
            pageKeysToFlush.add(new PageKey(fileId, startPageIndex + i));
          }
        }

        var pageEnd = pageKey.pageIndex * pageSize + pageSize;
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

      for (final PageKey pageKey : pageKeysToFlush) {
        if (lastFileId == -1) {
          if (!chunk.isEmpty()) {
            throw new IllegalStateException("Chunk is not empty !");
          }
        } else {
          if (lastPageIndex == -1) {
            throw new IllegalStateException("Last page index is -1");
          }

          if (lastFileId != pageKey.fileId || lastPageIndex != pageKey.pageIndex - 1) {
            if (!chunk.isEmpty()) {
              chunks.add(chunk);
              chunksSize += chunk.size();
              chunk = new ArrayList<>();
            }
          }
        }

        final CachePointer pointer = writeCachePages.get(pageKey);

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

          final Pointer directPointer =
              bufferPool.acquireDirect(false, Intention.COPY_PAGE_DURING_FLUSH);
          final ByteBuffer copy = directPointer.getNativeByteBuffer();
          assert copy.position() == 0;
          try {
            version = pointer.getVersion();
            final ByteBuffer buffer = pointer.getBuffer();

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
            lastPageIndex = pageKey.pageIndex;
            lastFileId = pageKey.fileId;
          }
        } else {
          if (!chunk.isEmpty()) {
            chunks.add(chunk);
            chunksSize += chunk.size();
            chunk = new ArrayList<>(16);
          }

          lastPageIndex = -1;
          lastFileId = -1;

          var fileSize = files.get(externalFileId(pageKey.fileId)).getUnderlyingFileSize();
          if (pageKey.pageIndex * pageSize >= fileSize) {
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

    final int flushedPages = flushPages(chunks, maxFullLogLSN);
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
          new StorageException(
              "Storage : "
                  + storageName
                  + "Error during of writing initial blank page for file  "
                  + idNameMap.get(internalFileId)),
          e);
    }
  }

  private int flushPages(
      final ArrayList<ArrayList<WritePageContainer>> chunks, final LogSequenceNumber fullLogLSN)
      throws java.lang.InterruptedException, IOException {
    if (chunks.isEmpty()) {
      return 0;
    }

    if (fullLogLSN != null) {
      LogSequenceNumber flushedLSN = writeAheadLog.getFlushedLsn();
      while (flushedLSN == null || flushedLSN.compareTo(fullLogLSN) < 0) {
        writeAheadLog.flush();
        flushedLSN = writeAheadLog.getFlushedLsn();
      }
    }

    final boolean fsyncFiles;

    int flushedPages = 0;

    final ArrayList<Pointer> containerPointers = new ArrayList<>(chunks.size());
    final ArrayList<ByteBuffer> containerBuffers = new ArrayList<>(chunks.size());
    final IntArrayList chunkPageIndexes = new IntArrayList(chunks.size());
    final IntArrayList chunkFileIds = new IntArrayList(chunks.size());

    final Long2ObjectOpenHashMap<ArrayList<RawPairLongObject<ByteBuffer>>> buffersByFileId =
        new Long2ObjectOpenHashMap<>();
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
      for (final Pointer containerPointer : containerPointers) {
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
        final CachePointer pointer = chunkPage.originalPagePointer;

        final PageKey pageKey =
            new PageKey(internalFileId(pointer.getFileId()), pointer.getPageIndex());
        final long version = chunkPage.pageVersion;

        final Lock lock = lockManager.acquireExclusiveLock(pageKey);
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

      final Pointer containerPointer =
          DirectMemoryAllocator.instance()
              .allocate(
                  chunk.size() * pageSize, false, Intention.ALLOCATE_CHUNK_TO_WRITE_DATA_IN_BATCH);
      final ByteBuffer containerBuffer = containerPointer.getNativeByteBuffer();

      containerPointers.add(containerPointer);
      containerBuffers.add(containerBuffer);
      assert containerBuffer.position() == 0;

      for (var chunkPage : chunk) {
        final ByteBuffer buffer = chunkPage.copyOfPage;

        final CachePointer pointer = chunkPage.originalPagePointer;

        addMagicChecksumAndEncryption(
            extractFileId(pointer.getFileId()), pointer.getPageIndex(), buffer);

        buffer.position(0);
        containerBuffer.put(buffer);
      }

      final var firstPage = chunk.get(0);
      final CachePointer firstCachePointer = firstPage.originalPagePointer;

      final long fileId = firstCachePointer.getFileId();
      final int pageIndex = firstCachePointer.getPageIndex();

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

      final ClosableEntry<Long, File> fileEntry = files.tryAcquire(entry.getLongKey());
      if (fileEntry != null) {
        final File file = fileEntry.get();

        var bufferList = entry.getValue();

        ioResults.add(file.write(bufferList));
        acquiredFiles.add(fileEntry);

        entry = null;
      } else {
        if (ioResults.size() != acquiredFiles.size()) {
          throw new IllegalStateException("Not all data are written to the files.");
        }

        if (!ioResults.isEmpty()) {
          for (final IOResult ioResult : ioResults) {
            ioResult.await();
          }

          for (final ClosableEntry<Long, File> closableEntry : acquiredFiles) {
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
      for (final IOResult ioResult : ioResults) {
        ioResult.await();
      }

      for (final ClosableEntry<Long, File> closableEntry : acquiredFiles) {
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
    final long ewcSize = exclusiveWriteCacheSize.get();

    // we flush at least chunkSize pages but no more than amount of exclusive pages.
    pagesToFlushLimit = Math.min(Math.max(pagesToFlushLimit, chunkSize), ewcSize);

    ArrayList<ArrayList<WritePageContainer>> chunks = new ArrayList<>(16);
    ArrayList<WritePageContainer> chunk = new ArrayList<>(16);

    // latch is hold by write thread if limit of pages in write cache exceeded
    // it is important to release it once we lower amount of pages in write cache to the limit.
    if (latch != null && ewcSize <= exclusiveWriteCacheMaxSize) {
      latch.countDown();
    }

    LogSequenceNumber maxFullLogLSN = null;

    // cache of files sizes.
    var fileSizeMap = new Int2LongOpenHashMap();
    fileSizeMap.defaultReturnValue(-1);

    int flushedPages = 0;
    int copiedPages = 0;

    // total amount of pages in chunks that precedes current/active chunk
    int prevChunksSize = 0;
    long lastFileId = -1;
    long lastPageIndex = -1;

    Iterator<PageKey> iterator = exclusiveWritePages.iterator();
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

      final PageKey pageKeyToFlush = iterator.next();
      var fileSize = fileSizeMap.get(pageKeyToFlush.fileId);
      if (fileSize < 0) {
        fileSize = files.get(externalFileId(pageKeyToFlush.fileId)).getUnderlyingFileSize();
        fileSizeMap.put(pageKeyToFlush.fileId, fileSize);
      }

      var pagesToFlush = new ArrayList<PageKey>();
      pagesToFlush.add(pageKeyToFlush);

      var diff = (pageKeyToFlush.pageIndex * pageSize - fileSize) / pageSize;
      // it is important to do not create holes in the file
      // otherwise restore process after crash will be aborted
      if (diff > 0) {
        var startPageIndex = fileSize / pageSize;
        for (int i = 0; i < diff; i++) {
          pagesToFlush.add(new PageKey(pageKeyToFlush.fileId, startPageIndex + i));
        }
      }
      // pages are sorted by position in index, so file size will be correctly incremented
      var pageEnd = pageKeyToFlush.pageIndex * pageSize + pageSize;
      if (pageEnd > fileSize) {
        fileSizeMap.put(pageKeyToFlush.fileId, pageEnd);
      }

      for (var pageKey : pagesToFlush) {
        final CachePointer pointer = writeCachePages.get(pageKey);
        // because accesses between maps not synchronized there could be eventual consistency
        // between exclusiveWritePages and writeCachePages. We treat writeCachePages as source of
        // truth.
        if (pointer == null) {
          var file = files.get(externalFileId(pageKey.fileId));
          if (file.getUnderlyingFileSize() < (pageKey.pageIndex + 1) * pageSize) {
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

            final Pointer directPointer =
                bufferPool.acquireDirect(false, Intention.COPY_PAGE_DURING_EXCLUSIVE_PAGE_FLUSH);
            final ByteBuffer copy = directPointer.getNativeByteBuffer();
            assert copy.position() == 0;
            long version;
            try {
              version = pointer.getVersion();
              final ByteBuffer buffer = pointer.getBuffer();

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
                files.get(externalFileId(pageKey.fileId)).getUnderlyingFileSize();
            // chunk size is reached flush limit, or we have a risk to have a hole in the file
            // that will lead to error during restore after crash process
            // we need to check only prevChunksSize because current chunk is empty
            if (prevChunksSize >= this.chunkSize
                || underlyingFileSize < (pageKey.pageIndex + 1) * pageSize) {
              flushedPages += flushPages(chunks, maxFullLogLSN);

              chunks.clear();
              prevChunksSize = 0;

              if (latch != null && exclusiveWriteCacheSize.get() <= exclusiveWriteCacheMaxSize) {
                latch.countDown();
              }
            }

            if (underlyingFileSize < (pageKey.pageIndex + 1) * pageSize) {
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
      final Object[] iAdditionalArgs = new Object[]{flushError.getMessage()};
      LogManager.instance()
          .error(
              this,
              "Can not flush file data because of issue during data write, %s",
              null,
              iAdditionalArgs);
      return null;
    }

    writeAheadLog.flush();

    final TreeSet<PageKey> pagesToFlush = new TreeSet<>();
    for (final Map.Entry<PageKey, CachePointer> entry : writeCachePages.entrySet()) {
      final PageKey pageKey = entry.getKey();
      if (fileIdSet.contains(pageKey.fileId)) {
        pagesToFlush.add(pageKey);
      }
    }

    LogSequenceNumber maxLSN = null;

    final ArrayList<ArrayList<WritePageContainer>> chunks = new ArrayList<>(chunkSize);
    for (final PageKey pageKey : pagesToFlush) {
      if (fileIdSet.contains(pageKey.fileId)) {
        final CachePointer pagePointer = writeCachePages.get(pageKey);
        final Lock pageLock = lockManager.acquireExclusiveLock(pageKey);
        try {
          if (!pagePointer.tryAcquireSharedLock()) {
            continue;
          }
          try {
            final ByteBuffer buffer = pagePointer.getBuffer();

            final Pointer directPointer = bufferPool.acquireDirect(false, Intention.FILE_FLUSH);
            final ByteBuffer copy = directPointer.getNativeByteBuffer();
            assert copy.position() == 0;

            assert buffer != null;
            assert buffer.position() == 0;
            copy.put(0, buffer, 0, buffer.capacity());

            final LogSequenceNumber endLSN = pagePointer.getEndLSN();

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
        final long finalId = composeFileId(id, fileIdIterator.nextInt());
        final ClosableEntry<Long, File> entry = files.acquire(finalId);
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
          new SecurityException("Implementation of encryption " + TRANSFORMATION + " is absent"),
          e);
    }
  }

  public RawPair<String, String> executeDeleteFile(long externalFileId)
      throws IOException, java.lang.InterruptedException {
    final int internalFileId = extractFileId(externalFileId);
    final long fileId = composeFileId(id, internalFileId);

    doRemoveCachePages(internalFileId);

    final File fileClassic = files.remove(fileId);

    if (fileClassic != null) {
      if (fileClassic.exists()) {
        fileClassic.delete();
      }

      final String name = idNameMap.get(internalFileId);

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

    long flushInterval = pagesFlushInterval;

    try {
      if (flushError != null) {
        final Object[] iAdditionalArgs = new Object[]{flushError.getMessage()};
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

        long ewcSize = exclusiveWriteCacheSize.get();
        if (ewcSize >= 0) {
          flushExclusiveWriteCache(null, Math.min(ewcSize, 4L * chunkSize));

          if (exclusiveWriteCacheSize.get() > 0) {
            flushInterval = 1;
          }
        }

        final LogSequenceNumber begin = writeAheadLog.begin();
        final LogSequenceNumber end = writeAheadLog.end();
        final long segments = end.getSegment() - begin.getSegment() + 1;

        if (segments > 1) {
          convertSharedDirtyPagesToLocal();

          Map.Entry<Long, TreeSet<PageKey>> firstSegment = localDirtyPagesBySegment.firstEntry();
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
        final Object[] iAdditionalArgs = new Object[]{flushError.getMessage()};
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

      final long ewcSize = exclusiveWriteCacheSize.get();

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
      final Object[] iAdditionalArgs = new Object[]{flushError.getMessage()};
      LogManager.instance()
          .error(
              this,
              "Can not flush data till provided segment because of issue during data write, %s",
              null,
              iAdditionalArgs);
      return null;
    }

    convertSharedDirtyPagesToLocal();
    Map.Entry<Long, TreeSet<PageKey>> firstEntry = localDirtyPagesBySegment.firstEntry();

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

  private static final class WritePageContainer {

    private final long pageVersion;
    private final ByteBuffer copyOfPage;

    private final Pointer pageCopyDirectMemoryPointer;

    private final CachePointer originalPagePointer;

    public WritePageContainer(
        long pageVersion,
        ByteBuffer copyOfPage,
        Pointer pageCopyDirectMemoryPointer,
        CachePointer originalPagePointer) {
      this.pageVersion = pageVersion;
      this.copyOfPage = copyOfPage;
      this.pageCopyDirectMemoryPointer = pageCopyDirectMemoryPointer;
      this.originalPagePointer = originalPagePointer;
    }
  }
}
