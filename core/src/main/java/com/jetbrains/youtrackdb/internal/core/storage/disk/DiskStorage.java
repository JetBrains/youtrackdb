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

package com.jetbrains.youtrackdb.internal.core.storage.disk;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.engine.local.EngineLocalPaginated;
import com.jetbrains.youtrackdb.internal.core.exception.BackupInProgressException;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidStorageEncryptionKeyException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLog;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogGL;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMap;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.FreeSpaceMap;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StartupMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageStartupMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbsoluteChange;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectBooleanPair;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.output.ProxyOutputStream;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiskStorage extends AbstractStorage {

  public static final XXHash64 XX_HASH_64 = XXHashFactory.fastestInstance().hash64();
  public static final long XX_HASH_SEED = 0xAEF5634;

  private static final Logger logger = LoggerFactory.getLogger(DiskStorage.class);

  private static final String BACKUP_LOCK = "backup.ibl";

  private static final String ALGORITHM_NAME = "AES";
  private static final String TRANSFORMATION = "AES/CTR/NoPadding";


  private static final ThreadLocal<Cipher> CIPHER =
      ThreadLocal.withInitial(DiskStorage::getCipherInstance);

  private static final String IBU_EXTENSION = ".ibu";
  /// The metadata is located at the tail and has the following format:
  ///
  /// 1. Version of the backup metadata format is stored as a short value.
  /// 2. Database UUID (generated during database creation) is stored as two long values with 16
  /// bytes.
  /// 3. Number of the backup unit in sequence (stored as an int value).
  /// 4. Start LSN - value of LSN (exclusive) from which delta changes in the backup are stored. It
  /// is stored as long + int values, stored as (-1, -1) for the first (full) backup.
  /// 5. End LSN - the value of the last change stored in the backup (inclusive).
  /// 6. Last transaction ID (idGen counter) at the time of backup. Stored as a long value.
  /// 7. The hash code of the file's content. Stored as a long value. The XX_HASH algorithm is used
  /// for hash code calculation.
  private static final int IBU_METADATA_SIZE =
      Short.BYTES + 2 * Long.BYTES + Integer.BYTES + 2 * (Long.BYTES + Integer.BYTES)
          + Long.BYTES + Long.BYTES;

  private static final int IBU_METADATA_VERSION_OFFSET = 0;

  private static final int IBU_METADATA_UUID_LOW_OFFSET = IBU_METADATA_VERSION_OFFSET + Short.BYTES;
  private static final int IBU_METADATA_UUID_HIGH_OFFSET =
      IBU_METADATA_UUID_LOW_OFFSET + Long.BYTES;

  private static final int IBU_METADATA_SEQUENCE_OFFSET =
      IBU_METADATA_UUID_HIGH_OFFSET + Long.BYTES;

  private static final int IBU_METADATA_FIRST_LSN_SEGMENT_OFFSET =
      IBU_METADATA_SEQUENCE_OFFSET + Integer.BYTES;
  private static final int IBU_METADATA_START_LSN_POSITION_OFFSET =
      IBU_METADATA_FIRST_LSN_SEGMENT_OFFSET + Long.BYTES;

  private static final int IBU_METADATA_LAST_LSN_SEGMENT_OFFSET =
      IBU_METADATA_START_LSN_POSITION_OFFSET + Integer.BYTES;
  private static final int IBU_METADATA_LAST_LSN_POSITION_OFFSET =
      IBU_METADATA_LAST_LSN_SEGMENT_OFFSET + Long.BYTES;
  private static final int IBU_METADATA_LAST_TX_ID_OFFSET =
      IBU_METADATA_LAST_LSN_POSITION_OFFSET + Integer.BYTES;
  private static final int IBU_METADATA_HASH_CODE_OFFSET =
      IBU_METADATA_LAST_TX_ID_OFFSET + Long.BYTES;

  private static final int CURRENT_BACKUP_FORMAT_VERSION = 2;
  private static final String CONF_ENTRY_NAME = "database.ocf";
  private static final String BACKUP_DATEFORMAT = "yyyy-MM-dd-HH-mm-ss";
  private static final String CONF_UTF_8_ENTRY_NAME = "database_utf8.ocf";
  private static final int UUID_LENGTH = 36;


  private static final String ENCRYPTION_IV = "encryption.iv";

  protected static final long IV_SEED = 234120934;

  private static final String IV_EXT = ".iv";
  protected static final String IV_NAME = "data" + IV_EXT;

  private static final String[] ALL_FILE_EXTENSIONS = {
      ".cm",
      ".ocf",
      ".pls",
      ".pcl",
      ".oda",
      ".odh",
      ".otx",
      ".ocs",
      ".oef",
      ".oem",
      ".oet",
      ".fl",
      ".flb",
      IV_EXT,
      CASDiskWriteAheadLog.WAL_SEGMENT_EXTENSION,
      CASDiskWriteAheadLog.MASTER_RECORD_EXTENSION,
      CollectionPositionMap.DEF_EXTENSION,
      LinkCollectionsBTreeManagerShared.FILE_EXTENSION,
      CollectionBasedStorageConfiguration.MAP_FILE_EXTENSION,
      CollectionBasedStorageConfiguration.DATA_FILE_EXTENSION,
      CollectionBasedStorageConfiguration.TREE_DATA_FILE_EXTENSION,
      CollectionBasedStorageConfiguration.TREE_NULL_FILE_EXTENSION,
      BTreeMultiValueIndexEngine.DATA_FILE_EXTENSION,
      BTreeMultiValueIndexEngine.M_CONTAINER_EXTENSION,
      DoubleWriteLogGL.EXTENSION,
      FreeSpaceMap.DEF_EXTENSION
  };

  private static final int ONE_KB = 1024;

  private final int deleteMaxRetries;
  private final int deleteWaitTime;

  private final StorageStartupMetadata startupMetadata;

  private final Path storagePath;
  private final ClosableLinkedContainer<Long, File> files;

  private Future<?> fuzzyCheckpointTask;

  private final long walMaxSegSize;
  private final long doubleWriteLogMaxSegSize;

  protected volatile byte[] iv;

  public DiskStorage(
      final String name,
      final String filePath,
      final int id,
      final ReadCache readCache,
      final ClosableLinkedContainer<Long, File> files,
      final long walMaxSegSize,
      long doubleWriteLogMaxSegSize,
      YouTrackDBInternalEmbedded context) {
    super(name, filePath, id, context);

    this.walMaxSegSize = walMaxSegSize;
    this.files = files;
    this.doubleWriteLogMaxSegSize = doubleWriteLogMaxSegSize;
    this.readCache = readCache;

    final var sp =
        SystemVariableResolver.resolveSystemVariables(
            FileUtils.getPath(new java.io.File(url).getPath()));

    storagePath = Paths.get(sp).normalize().toAbsolutePath();

    deleteMaxRetries = GlobalConfiguration.FILE_DELETE_RETRY.getValueAsInteger();
    deleteWaitTime = GlobalConfiguration.FILE_DELETE_DELAY.getValueAsInteger();

    startupMetadata =
        new StorageStartupMetadata(
            storagePath.resolve("dirty.fl"), storagePath.resolve("dirty.flb"));
  }

  @SuppressWarnings("CanBeFinal")
  @Override
  public void create(final ContextConfiguration contextConfiguration) {
    try {
      stateLock.writeLock().lock();
      try {
        doCreate(contextConfiguration);
      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (final RuntimeException e) {
      throw logAndPrepareForRethrow(e);
    } catch (final Error e) {
      throw logAndPrepareForRethrow(e);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }

    var fsyncAfterCreate =
        contextConfiguration.getValueAsBoolean(
            GlobalConfiguration.STORAGE_MAKE_FULL_CHECKPOINT_AFTER_CREATE);
    if (fsyncAfterCreate) {
      synch();
    }

    final var additionalArgs = new Object[]{getURL(), YouTrackDBConstants.getVersion()};
    LogManager.instance()
        .info(this, "Storage '%s' is created under YouTrackDB distribution : %s", additionalArgs);
  }

  @Override
  protected void doCreate(ContextConfiguration contextConfiguration)
      throws IOException, java.lang.InterruptedException {
    final var storageFolder = storagePath;
    if (!Files.exists(storageFolder)) {
      Files.createDirectories(storageFolder);
    }

    super.doCreate(contextConfiguration);
  }

  @Override
  public final boolean exists() {
    try {
      if (status == STATUS.OPEN || isInError() || status == STATUS.MIGRATION) {
        return true;
      }

      return exists(storagePath);
    } catch (final RuntimeException e) {
      throw logAndPrepareForRethrow(e);
    } catch (final Error e) {
      throw logAndPrepareForRethrow(e, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public String getURL() {
    return EngineLocalPaginated.NAME + ":" + url;
  }

  public final Path getStoragePath() {
    return storagePath;
  }

  @Override
  public String getType() {
    return EngineLocalPaginated.NAME;
  }

  @Override
  protected LogSequenceNumber copyWALToBackup(
      final ZipOutputStream zipOutputStream, final long startSegment) throws IOException {

    java.io.File[] nonActiveSegments;

    LogSequenceNumber lastLSN;
    final var freezeId = getAtomicOperationsManager().freezeWriteOperations(null);
    try {
      lastLSN = writeAheadLog.end();
      writeAheadLog.flush();
      writeAheadLog.appendNewSegment();
      nonActiveSegments = writeAheadLog.nonActiveSegments(startSegment);
    } finally {
      getAtomicOperationsManager().unfreezeWriteOperations(freezeId);
    }

    for (final var nonActiveSegment : nonActiveSegments) {
      try (final var fileInputStream = new FileInputStream(nonActiveSegment)) {
        try (final var bufferedInputStream =
            new BufferedInputStream(fileInputStream)) {
          final var entry = new ZipEntry(nonActiveSegment.getName());
          zipOutputStream.putNextEntry(entry);
          try {
            final var buffer = new byte[4096];

            int br;

            while ((br = bufferedInputStream.read(buffer)) >= 0) {
              zipOutputStream.write(buffer, 0, br);
            }
          } finally {
            zipOutputStream.closeEntry();
          }
        }
      }
    }

    return lastLSN;
  }

  @Override
  protected java.io.File createWalTempDirectory() {
    final var walDirectory =
        new java.io.File(storagePath.toFile(), "walIncrementalBackupRestoreDirectory");

    if (walDirectory.exists()) {
      FileUtils.deleteRecursively(walDirectory);
    }

    if (!walDirectory.mkdirs()) {
      throw new StorageException(name,
          "Can not create temporary directory to store files created during incremental backup");
    }

    return walDirectory;
  }

  private static void addFileToDirectory(final String name, final InputStream stream,
      final java.io.File directory)
      throws IOException {
    final var buffer = new byte[4096];

    var rb = -1;
    var bl = 0;

    final var walBackupFile = new java.io.File(directory, name);
    if (!walBackupFile.toPath().normalize().startsWith(directory.toPath().normalize())) {
      throw new IllegalStateException("Bad zip entry " + name);
    }

    try (final var outputStream = new FileOutputStream(walBackupFile)) {
      try (final var bufferedOutputStream =
          new BufferedOutputStream(outputStream)) {
        do {
          while (bl < buffer.length && (rb = stream.read(buffer, bl, buffer.length - bl)) > -1) {
            bl += rb;
          }

          bufferedOutputStream.write(buffer, 0, bl);
          bl = 0;

        } while (rb >= 0);
      }
    }
  }

  @Override
  protected WriteAheadLog createWalFromIBUFiles(
      final java.io.File directory,
      final ContextConfiguration contextConfiguration,
      final Locale locale,
      byte[] iv)
      throws IOException {
    final var aesKeyEncoded =
        contextConfiguration.getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY);
    final var aesKey =
        Optional.ofNullable(aesKeyEncoded)
            .map(keyEncoded -> Base64.getDecoder().decode(keyEncoded))
            .orElse(null);

    return new CASDiskWriteAheadLog(
        name,
        storagePath,
        directory.toPath(),
        contextConfiguration.getValueAsString(ContextConfiguration.WAL_BASE_NAME,
            ContextConfiguration.WAL_DEFAULT_NAME),
        contextConfiguration.getValueAsInteger(GlobalConfiguration.WAL_CACHE_SIZE),
        contextConfiguration.getValueAsInteger(GlobalConfiguration.WAL_BUFFER_SIZE),
        aesKey,
        iv,
        contextConfiguration.getValueAsLong(GlobalConfiguration.WAL_SEGMENTS_INTERVAL)
            * 60
            * 1_000_000_000L,
        contextConfiguration.getValueAsInteger(GlobalConfiguration.WAL_MAX_SEGMENT_SIZE)
            * 1024
            * 1024L,
        10,
        true,
        locale,
        GlobalConfiguration.WAL_MAX_SIZE.getValueAsLong() * 1024 * 1024,
        contextConfiguration.getValueAsInteger(GlobalConfiguration.WAL_COMMIT_TIMEOUT),
        contextConfiguration.getValueAsBoolean(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT),
        contextConfiguration.getValueAsBoolean(GlobalConfiguration.STORAGE_CALL_FSYNC),
        contextConfiguration.getValueAsBoolean(
            GlobalConfiguration.STORAGE_PRINT_WAL_PERFORMANCE_STATISTICS),
        contextConfiguration.getValueAsInteger(
            GlobalConfiguration.STORAGE_PRINT_WAL_PERFORMANCE_INTERVAL));
  }

  @Override
  protected StartupMetadata checkIfStorageDirty() throws IOException {
    if (startupMetadata.exists()) {
      startupMetadata.open(YouTrackDBConstants.getRawVersion());
    } else {
      startupMetadata.create(YouTrackDBConstants.getRawVersion());
      startupMetadata.makeDirty(YouTrackDBConstants.getRawVersion());
    }

    return new StartupMetadata(startupMetadata.getLastTxId());
  }

  @Override
  protected void initConfiguration(
      final ContextConfiguration contextConfiguration,
      AtomicOperation atomicOperation)
      throws IOException {
    configuration = new CollectionBasedStorageConfiguration(this);
    ((CollectionBasedStorageConfiguration) configuration)
        .load(contextConfiguration, atomicOperation);
  }

  @Override
  protected Map<String, Object> preCloseSteps() {
    final var params = super.preCloseSteps();

    if (fuzzyCheckpointTask != null) {
      fuzzyCheckpointTask.cancel(false);
    }

    return params;
  }

  @Override
  protected void preCreateSteps() throws IOException {
    startupMetadata.create(YouTrackDBConstants.getRawVersion());
  }

  @Override
  protected void postCloseSteps(
      final boolean onDelete, final boolean internalError, final long lastTxId) throws IOException {
    if (onDelete) {
      startupMetadata.delete();
    } else {
      if (!internalError) {
        startupMetadata.setLastTxId(lastTxId);
        startupMetadata.clearDirty();
      }
      startupMetadata.close();
    }
  }

  @Override
  protected void postDeleteSteps() {
    var databasePath =
        SystemVariableResolver.resolveSystemVariables(url);
    deleteFilesFromDisc(name, deleteMaxRetries, deleteWaitTime, databasePath);
  }

  public static void deleteFilesFromDisc(
      final String name, final int maxRetries, final int waitTime, final String databaseDirectory) {
    var dbDir = new java.io.File(databaseDirectory);
    if (!dbDir.exists() || !dbDir.isDirectory()) {
      dbDir = dbDir.getParentFile();
    }

    // RETRIES
    for (var i = 0; i < maxRetries; ++i) {
      if (dbDir != null && dbDir.exists() && dbDir.isDirectory()) {
        var notDeletedFiles = 0;

        final var storageFiles = dbDir.listFiles();
        if (storageFiles == null) {
          continue;
        }

        // TRY TO DELETE ALL THE FILES
        for (final var f : storageFiles) {
          // DELETE ONLY THE SUPPORTED FILES
          for (final var ext : ALL_FILE_EXTENSIONS) {
            if (f.getPath().endsWith(ext)) {
              if (!f.delete()) {
                notDeletedFiles++;
              }
              break;
            }
          }
        }

        if (notDeletedFiles == 0) {
          // TRY TO DELETE ALSO THE DIRECTORY IF IT'S EMPTY
          if (!dbDir.delete()) {
            LogManager.instance()
                .error(
                    DiskStorage.class,
                    "Cannot delete storage directory with path "
                        + dbDir.getAbsolutePath()
                        + " because directory is not empty. Files: "
                        + Arrays.toString(dbDir.listFiles()),
                    null);
          }
          return;
        }
      } else {
        return;
      }
      LogManager.instance()
          .debug(
              DiskStorage.class,
              "Cannot delete database files because they are still locked by the YouTrackDB process:"
                  + " waiting %d ms and retrying %d/%d...",
              logger, waitTime,
              i,
              maxRetries);
    }

    throw new StorageException(name,
        "Cannot delete database '"
            + name
            + "' located in: "
            + dbDir
            + ". Database files seem locked");
  }

  @Override
  protected void makeStorageDirty() throws IOException {
    startupMetadata.makeDirty(YouTrackDBConstants.getRawVersion());
  }

  @Override
  protected void clearStorageDirty() throws IOException {
    if (!isInError()) {
      startupMetadata.clearDirty();
    }
  }

  @Override
  protected boolean isDirty() {
    return startupMetadata.isDirty();
  }

  @Override
  protected String getOpenedAtVersion() {
    return startupMetadata.getOpenedAtVersion();
  }


  @Override
  protected void initIv() throws IOException {
    try (final var ivFile =
        new RandomAccessFile(storagePath.resolve(IV_NAME).toAbsolutePath().toFile(), "rw")) {
      final var iv = new byte[16];

      final var random = new SecureRandom();
      random.nextBytes(iv);

      final var hashFactory = XXHashFactory.fastestInstance();
      final var hash64 = hashFactory.hash64();

      final var hash = hash64.hash(iv, 0, iv.length, IV_SEED);
      ivFile.write(iv);
      ivFile.writeLong(hash);
      ivFile.getFD().sync();

      this.iv = iv;
    }
  }

  @Override
  protected void readIv() throws IOException {
    final var ivPath = storagePath.resolve(IV_NAME).toAbsolutePath();
    if (!Files.exists(ivPath)) {
      LogManager.instance().info(this, "IV file is absent, will create new one.");
      initIv();
      return;
    }

    try (final var ivFile = new RandomAccessFile(ivPath.toFile(), "r")) {
      final var iv = new byte[16];
      ivFile.readFully(iv);

      final var storedHash = ivFile.readLong();

      final var hashFactory = XXHashFactory.fastestInstance();
      final var hash64 = hashFactory.hash64();

      final var expectedHash = hash64.hash(iv, 0, iv.length, IV_SEED);
      if (storedHash != expectedHash) {
        throw new StorageException(name, "iv data are broken");
      }

      this.iv = iv;
    }
  }

  @Override
  protected byte[] getIv() {
    return iv;
  }

  @Override
  protected void initWalAndDiskCache(final ContextConfiguration contextConfiguration)
      throws IOException, java.lang.InterruptedException {
    final var aesKeyEncoded =
        contextConfiguration.getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY);
    final var aesKey =
        Optional.ofNullable(aesKeyEncoded)
            .map(keyEncoded -> Base64.getDecoder().decode(keyEncoded))
            .orElse(null);

    fuzzyCheckpointTask =
        fuzzyCheckpointExecutor.scheduleWithFixedDelay(
            new PeriodicFuzzyCheckpoint(this),
            contextConfiguration.getValueAsInteger(
                GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL),
            contextConfiguration.getValueAsInteger(
                GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL),
            TimeUnit.SECONDS);

    final var configWalPath =
        contextConfiguration.getValueAsString(GlobalConfiguration.WAL_LOCATION);
    final Path walPath;
    if (configWalPath == null) {
      walPath = null;
    } else {
      walPath = Paths.get(configWalPath);
    }

    writeAheadLog =
        new CASDiskWriteAheadLog(
            name,
            storagePath,
            walPath,
            contextConfiguration.getValueAsString(ContextConfiguration.WAL_BASE_NAME,
                ContextConfiguration.WAL_DEFAULT_NAME),
            contextConfiguration.getValueAsInteger(GlobalConfiguration.WAL_CACHE_SIZE),
            contextConfiguration.getValueAsInteger(GlobalConfiguration.WAL_BUFFER_SIZE),
            aesKey,
            iv,
            contextConfiguration.getValueAsLong(GlobalConfiguration.WAL_SEGMENTS_INTERVAL)
                * 60
                * 1_000_000_000L,
            walMaxSegSize,
            10,
            true,
            Locale.getDefault(),
            contextConfiguration.getValueAsLong(GlobalConfiguration.WAL_MAX_SIZE) * 1024 * 1024,
            contextConfiguration.getValueAsInteger(GlobalConfiguration.WAL_COMMIT_TIMEOUT),
            contextConfiguration.getValueAsBoolean(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT),
            contextConfiguration.getValueAsBoolean(GlobalConfiguration.STORAGE_CALL_FSYNC),
            contextConfiguration.getValueAsBoolean(
                GlobalConfiguration.STORAGE_PRINT_WAL_PERFORMANCE_STATISTICS),
            contextConfiguration.getValueAsInteger(
                GlobalConfiguration.STORAGE_PRINT_WAL_PERFORMANCE_INTERVAL));
    writeAheadLog.addCheckpointListener(this);

    final var pageSize =
        contextConfiguration.getValueAsInteger(GlobalConfiguration.DISK_CACHE_PAGE_SIZE) * ONE_KB;
    final var diskCacheSize =
        contextConfiguration.getValueAsLong(GlobalConfiguration.DISK_CACHE_SIZE) * 1024 * 1024;
    final var writeCacheSize =
        (long)
            (contextConfiguration.getValueAsInteger(GlobalConfiguration.DISK_WRITE_CACHE_PART)
                / 100.0
                * diskCacheSize);

    final DoubleWriteLog doubleWriteLog;
    if (contextConfiguration.getValueAsBoolean(
        GlobalConfiguration.STORAGE_USE_DOUBLE_WRITE_LOG)) {
      doubleWriteLog = new DoubleWriteLogGL(doubleWriteLogMaxSegSize);
    } else {
      doubleWriteLog = new DoubleWriteLogNoOP();
    }

    final var wowCache =
        new WOWCache(
            pageSize,
            contextConfiguration.getValueAsBoolean(GlobalConfiguration.FILE_LOG_DELETION),
            ByteBufferPool.instance(null),
            writeAheadLog,
            doubleWriteLog,
            contextConfiguration.getValueAsInteger(
                GlobalConfiguration.DISK_WRITE_CACHE_PAGE_FLUSH_INTERVAL),
            contextConfiguration.getValueAsInteger(GlobalConfiguration.WAL_SHUTDOWN_TIMEOUT),
            writeCacheSize,
            storagePath,
            getName(),
            files,
            getId(),
            contextConfiguration.getValueAsString(ContextConfiguration.DOUBLE_WRITE_LOG_NAME,
                ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME),
            contextConfiguration.getValueAsEnum(
                GlobalConfiguration.STORAGE_CHECKSUM_MODE, ChecksumMode.class),
            iv,
            aesKey,
            contextConfiguration.getValueAsBoolean(GlobalConfiguration.STORAGE_CALL_FSYNC),
            context.getIoExecutor());

    wowCache.loadRegisteredFiles();
    wowCache.addBackgroundExceptionListener(this);
    wowCache.addPageIsBrokenListener(this);

    writeCache = wowCache;
  }

  public static boolean exists(final Path path) {
    try {
      final var exists = new boolean[1];
      if (Files.exists(path.normalize().toAbsolutePath())) {
        try (final var stream = Files.newDirectoryStream(path)) {
          stream.forEach(
              (p) -> {
                final var fileName = p.getFileName().toString();
                if (fileName.equals("database.ocf")
                    || (fileName.startsWith("config") && fileName.endsWith(".bd"))
                    || fileName.startsWith("dirty.fl")
                    || fileName.startsWith("dirty.flb")) {
                  exists[0] = true;
                }
              });
        }
        return exists[0];
      }

      return false;
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(null, "Error during fetching list of files"), e, (String) null);
    }
  }

  @Override
  public String fullBackup(Path backupDirectory) {
    return fullBackup(new IBULocalFileNamesSupplier(backupDirectory, name, uuid.toString()),
        new IBULocalFileOutputStreamSupplier(backupDirectory, name),
        new IBULocalFileRemover(backupDirectory, name));
  }

  @Override
  public String fullBackup(Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, OutputStream> ibuOutputStreamSupplier, Consumer<String> ibuFileRemover) {
    var dbUuidString = uuid.toString();
    var ibuFilesIterator = IteratorUtils.filter(ibuFilesSupplier.get(), fileName ->
        fileName.startsWith(dbUuidString) && fileName.endsWith(IBU_EXTENSION));
    var ibuFiles = IteratorUtils.list(ibuFilesIterator);

    for (var ibuFile : ibuFiles) {
      ibuFileRemover.accept(ibuFile);
    }

    return backup(org.apache.commons.collections4.IteratorUtils::emptyIterator,
        ibuFileName -> {
          throw new UnsupportedOperationException("File " + ibuFileName + " does not exist.");
        }, ibuOutputStreamSupplier,
        ibuFileName -> {
          throw new UnsupportedOperationException("File " + ibuFileName + " does not exist.");
        }
    );
  }

  @Override
  public String backup(final Path backupDirectory) {
    checkBackupIsNotPerformedInStorageDir(backupDirectory);
    try {
      if (!Files.exists(backupDirectory)) {
        Files.createDirectories(backupDirectory);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(new DatabaseException(name,
          "Can not create directories are needed to perform database backup."), e, name);
    }

    var dbUUIDString = uuid.toString();
    final var fileLockPath = backupDirectory.resolve(dbUUIDString + "-" + BACKUP_LOCK);
    try (final var lockChannel = FileChannel.open(fileLockPath, StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, StandardOpenOption.READ)) {
      try (var ignored = lockChannel.lock()) {
        var currentTimeMillis = System.currentTimeMillis();

        var timeStampBuffer = ByteBuffer.allocate(Long.BYTES);
        if (lockChannel.size() == Long.BYTES) {
          IOUtils.readByteBuffer(timeStampBuffer, lockChannel);
          timeStampBuffer.rewind();

          var lastBackupMillis = timeStampBuffer.getLong();
          var minimalBackupTime = TimeUnit.MILLISECONDS.convert(
              configuration.getContextConfiguration()
                  .getValueAsInteger(GlobalConfiguration.STORAGE_BACKUP_MINIMUM_TIMEOUT_INTERVAL),
              TimeUnit.SECONDS);
          if (currentTimeMillis - lastBackupMillis < minimalBackupTime) {
            Thread.sleep(minimalBackupTime);
          }
        } else if (lockChannel.size() > 0) {
          lockChannel.truncate(0);
          lockChannel.force(true);

          var minimalBackupTime = TimeUnit.SECONDS.convert(configuration.getContextConfiguration()
                  .getValueAsInteger(GlobalConfiguration.STORAGE_BACKUP_MINIMUM_TIMEOUT_INTERVAL),
              TimeUnit.MILLISECONDS);
          Thread.sleep(minimalBackupTime);
        }

        var newIbuFileName = backup(
            new IBULocalFileNamesSupplier(backupDirectory, name, dbUUIDString),
            new IBULocalFileInputStreamSupplier(backupDirectory, name),
            new IBULocalFileOutputStreamSupplier(backupDirectory, name),
            new IBULocalFileRemover(backupDirectory, name));

        timeStampBuffer.rewind();
        timeStampBuffer.putLong(System.currentTimeMillis());
        IOUtils.writeByteBuffer(timeStampBuffer, lockChannel, 0);
        lockChannel.force(true);

        return newIbuFileName;
      } catch (OverlappingFileLockException ofle) {
        LogManager.instance().error(this, "Can not lock file '%s'."
                + " File likely already locked by another process that performs database backup.",
            ofle, fileLockPath, ofle);
        throw ofle;
      } catch (InterruptedException e) {
        throw BaseException.wrapException(new DatabaseException(name, "Backup was interrupted"), e,
            name);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new DatabaseException(name, "Error during creation of database backup."), e,
          name);
    }
  }

  @Override
  public String backup(Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier,
      Function<String, OutputStream> ibuOutputStreamSupplier,
      Consumer<String> ibuFileRemover) {
    checkOpennessAndMigration();

    String ibuNextFile;
    stateLock.readLock().lock();
    try {
      checkOpennessAndMigration();

      if (backupLock.tryLock()) {
        try {
          var uuid = getUuid();
          var uuidString = uuid.toString();

          var existingFiles =
              IteratorUtils.list(IteratorUtils.filter(ibuFilesSupplier.get(),
                  fileName -> fileName.startsWith(uuidString)));

          existingFiles.sort(new IBUFileNamesComparator());

          BackupMetadata backupMetadata = null;

          while (!existingFiles.isEmpty()) {
            var ibuLastFile = existingFiles.removeLast();
            try (var ibuStream = ibuInputStreamSupplier.apply(ibuLastFile)) {
              backupMetadata = validateFileAndFetchBackupMetadata(ibuLastFile, getName(), uuid,
                  ibuStream, null);
            }

            if (backupMetadata == null) {
              LogManager.instance()
                  .error(this, "Backup unit file %s is broken and will be removed.", null,
                      ibuLastFile);
              ibuFileRemover.accept(ibuLastFile);
            } else {
              break;
            }
          }

          int nextFileIndex;
          LogSequenceNumber fromLsn = null;

          if (backupMetadata == null) {
            nextFileIndex = 0;
          } else {
            nextFileIndex = backupMetadata.sequenceNumber + 1;
            fromLsn = backupMetadata.endLsn;
          }

          ibuNextFile = createIbuFileName(nextFileIndex, uuidString);
          LogManager.instance()
              .info(this, "Backup unit file %s will be created.", ibuNextFile);
          try (var fileStream = ibuOutputStreamSupplier.apply(ibuNextFile)) {
            var xxHashStream = new XXHashOutputStream(fileStream);
            var lastLsn = storeBackupDataToStream(xxHashStream, fromLsn);

            writeBackupMetadata(xxHashStream, uuid, nextFileIndex, fromLsn, lastLsn,
                getIdGen().getLastId());
          }

          LogManager.instance()
              .info(this,
                  "Backup of database '%s' is completed. Backup unit file %s was created.",
                  name, ibuNextFile);
        } finally {
          backupLock.unlock();
        }
      } else {
        throw new BackupInProgressException(name,
            "You are trying to start incremental backup but it is in progress now, please wait till it will be finished");
      }
    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new DatabaseException(name, "Error during creation of database backup."), ioe, name);
    } finally {
      stateLock.readLock().unlock();
    }

    return ibuNextFile;
  }

  private static void writeBackupMetadata(XXHashOutputStream xxHashStream, UUID uuid,
      int nextFileIndex, LogSequenceNumber fromLsn, LogSequenceNumber lastLsn,
      long lastTxId) throws IOException {
    var dataOutputStream = new DataOutputStream(xxHashStream);
    dataOutputStream.writeShort(CURRENT_BACKUP_FORMAT_VERSION);

    dataOutputStream.writeLong(uuid.getLeastSignificantBits());
    dataOutputStream.writeLong(uuid.getMostSignificantBits());

    dataOutputStream.writeInt(nextFileIndex);
    if (fromLsn == null) {
      dataOutputStream.writeLong(-1);
      dataOutputStream.writeInt(-1);
    } else {
      dataOutputStream.writeLong(fromLsn.getSegment());
      dataOutputStream.writeInt(fromLsn.getPosition());
    }
    dataOutputStream.writeLong(lastLsn.getSegment());
    dataOutputStream.writeInt(lastLsn.getPosition());

    dataOutputStream.writeLong(lastTxId);

    dataOutputStream.flush();

    var hashCode = xxHashStream.xxHash64.getValue();
    dataOutputStream.writeLong(hashCode);
    dataOutputStream.flush();
  }

  private String createIbuFileName(int backupNumber, String uuid) {
    var date = new Date();
    var strDate = new SimpleDateFormat(BACKUP_DATEFORMAT, Locale.ROOT).format(date);
    return uuid + "-" + strDate + "-" + backupNumber + "-" + name + IBU_EXTENSION;
  }

  @Nullable
  protected static BackupMetadata validateFileAndFetchBackupMetadata(String ibuFileName,
      String storageName,
      @Nullable UUID dbUUID,
      @Nonnull InputStream inputStream, @Nullable OutputStream copyStream)
      throws IOException {
    byte[] metaDataCandidate = null;

    try (var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(XX_HASH_SEED)) {

      var buffer = new byte[(64 << 10)];
      var read = 0;

      while (true) {
        read = inputStream.read(buffer);

        if (read == -1) {
          break;
        } else if (read == 0) {
          continue;
        }

        if (metaDataCandidate == null) {
          if (read < IBU_METADATA_SIZE) {
            //read till we will not have to read metadata information
            var bytesLeftToRead = IBU_METADATA_SIZE - read;
            while (bytesLeftToRead > 0) {
              var r = inputStream.read(buffer, read, buffer.length - read);
              if (r == -1) {
                LogManager.instance().warn(DiskStorage.class, storageName,
                    "Size of the file %s is less than needed to store information about metadata. "
                        + "Size should be at least %d but real size is %d.", ibuFileName,
                    IBU_METADATA_SIZE, read);
                return null;
              }

              read += r;
              bytesLeftToRead -= r;
            }
          }

          metaDataCandidate = new byte[IBU_METADATA_SIZE];
          System.arraycopy(buffer, read - IBU_METADATA_SIZE, metaDataCandidate, 0,
              IBU_METADATA_SIZE);

          //calculate hash code everything except metadata
          //hash code from metadata will be calculated at the end
          xxHash64.update(buffer, 0, read - IBU_METADATA_SIZE);
        } else {
          if (read >= IBU_METADATA_SIZE) {
            //tail of data, not metadata for sure
            xxHash64.update(metaDataCandidate, 0, IBU_METADATA_SIZE);
            //hash code from metadata will be calculated at the end
            xxHash64.update(buffer, 0, read - IBU_METADATA_SIZE);
            //potential metadata content
            System.arraycopy(buffer, read - IBU_METADATA_SIZE,
                metaDataCandidate, 0, IBU_METADATA_SIZE);
          } else {
            //part of metadata that will be replaced
            xxHash64.update(metaDataCandidate, 0, read);
            //shift metadata to the left
            System.arraycopy(metaDataCandidate, read, metaDataCandidate, 0,
                IBU_METADATA_SIZE - read);
            //add new bytes that can be treated as metadata if they are the last ones
            System.arraycopy(buffer, 0, metaDataCandidate, IBU_METADATA_SIZE - read, read);
          }
        }

        if (copyStream != null && read > 0) {
          copyStream.write(buffer, 0, read);
        }
      }

      if (metaDataCandidate == null) {
        LogManager.instance()
            .warn(DiskStorage.class, storageName, "File %s does not contain backup metadata.",
                ibuFileName);
        return null;
      }

      xxHash64.update(metaDataCandidate, 0, IBU_METADATA_HASH_CODE_OFFSET);

      var metadataVersion = ShortSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_VERSION_OFFSET);
      var metadataUUIDLowerBits = LongSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_UUID_LOW_OFFSET);
      var metadataUUIDHigherBits = LongSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_UUID_HIGH_OFFSET);
      var metadataSequenceNumber = IntegerSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_SEQUENCE_OFFSET);
      var metadataStartLsnSegment = LongSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_FIRST_LSN_SEGMENT_OFFSET);
      var metadataStartLsnPosition = IntegerSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_START_LSN_POSITION_OFFSET);
      var metadataLastLsnSegment = LongSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_LAST_LSN_SEGMENT_OFFSET);
      var metadataEndLsnPosition = IntegerSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_LAST_LSN_POSITION_OFFSET);
      var metadataLastTxId = LongSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_LAST_TX_ID_OFFSET);
      var metadataHashCode = LongSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_HASH_CODE_OFFSET);

      var calculatedHashCode = xxHash64.getValue();
      xxHash64.close();

      if (calculatedHashCode != metadataHashCode) {
        LogManager.instance()
            .warn(DiskStorage.class, storageName,
                "Hash code of the file %s is broken. Calculated hash code is %d but stored hash code is %d.",
                ibuFileName, calculatedHashCode, metadataHashCode);
        return null;
      }

      if (dbUUID != null) {
        if (dbUUID.getLeastSignificantBits() != metadataUUIDLowerBits
            || dbUUID.getMostSignificantBits() != metadataUUIDHigherBits) {
          var storedUUID = new UUID(metadataUUIDLowerBits, metadataUUIDHigherBits);
          LogManager.instance()
              .warn(DiskStorage.class, storageName,
                  "UUID of the file %s stored in metadata %s does not match DB UUID %s.",
                  ibuFileName, storedUUID, dbUUID);
          return null;
        }
      }

      if (ibuFileName.length() < UUID_LENGTH) {
        LogManager.instance().warn(DiskStorage.class, storageName,
            "File name %s does not contain DB UUID.", ibuFileName);
        return null;
      }

      if (metadataVersion != CURRENT_BACKUP_FORMAT_VERSION) {
        LogManager.instance()
            .warn(DiskStorage.class, storageName,
                "Version of the file %s stored in metadata %d does not match supported version %d.",
                ibuFileName, metadataVersion, CURRENT_BACKUP_FORMAT_VERSION);
        return null;
      }

      UUID fileNameUUID;
      try {
        fileNameUUID = UUID.fromString(ibuFileName.substring(0, UUID_LENGTH));
      } catch (IllegalArgumentException e) {
        LogManager.instance().warn(DiskStorage.class, storageName,
            "UUID of the file %s is incorrect.", ibuFileName);
        return null;
      }

      if (fileNameUUID.getLeastSignificantBits() != metadataUUIDLowerBits
          || fileNameUUID.getMostSignificantBits() != metadataUUIDHigherBits) {
        LogManager.instance()
            .warn(DiskStorage.class, storageName,
                "UUID of the file %s does not match DB UUID %s.", ibuFileName, dbUUID);
      }

      //uuid-date-sequence number
      var sequenceNumberStart = UUID_LENGTH + 1 + BACKUP_DATEFORMAT.length() + 1;
      var afterSequenceDashIndex = ibuFileName.indexOf('-', sequenceNumberStart);
      if (afterSequenceDashIndex == -1) {
        LogManager.instance()
            .warn(DiskStorage.class, storageName,
                "File %s does not contain backup sequence number.", ibuFileName);
      }

      int sequenceNumber;
      try {
        sequenceNumber = Integer.parseInt(
            ibuFileName.substring(sequenceNumberStart, afterSequenceDashIndex));
      } catch (NumberFormatException e) {
        LogManager.instance()
            .warn(DiskStorage.class, storageName,
                "Sequence number of the file %s is incorrect.", ibuFileName);
        return null;
      }

      if (metadataSequenceNumber != sequenceNumber) {
        LogManager.instance()
            .warn(DiskStorage.class, storageName,
                "Sequence number of the file %s stored in metadata %s does not match DB "
                    + "sequence number %s.", ibuFileName, sequenceNumber, metadataSequenceNumber);
        return null;
      }

      LogSequenceNumber startLsn = null;
      if (metadataStartLsnSegment != -1 && metadataStartLsnPosition != -1) {
        startLsn = new LogSequenceNumber(metadataStartLsnSegment, metadataStartLsnPosition);
      }
      if (metadataLastLsnSegment == -1 || metadataEndLsnPosition == -1) {
        LogManager.instance()
            .warn(DiskStorage.class, storageName,
                "Last LSN of the file %s stored in metadata is incorrect.",
                ibuFileName);
        return null;
      }

      var lastLsn = new LogSequenceNumber(metadataLastLsnSegment, metadataEndLsnPosition);

      return new BackupMetadata(metadataVersion, fileNameUUID, sequenceNumber, startLsn, lastLsn,
          metadataLastTxId);
    }
  }

  private LogSequenceNumber storeBackupDataToStream(OutputStream stream,
      LogSequenceNumber fromLsn)
      throws IOException {
    final var zipOutputStream = new ZipOutputStream(stream,
        Charset.forName(configuration.getCharset()));
    try {
      final long startSegment;
      final LogSequenceNumber freezeLsn;
      final var newSegmentFreezeId = atomicOperationsManager.freezeWriteOperations(null);
      try {
        final var startLsn = writeAheadLog.end();
        if (startLsn != null) {
          freezeLsn = startLsn;
        } else {
          freezeLsn = new LogSequenceNumber(0, 0);
        }

        writeAheadLog.addCutTillLimit(freezeLsn);

        writeAheadLog.appendNewSegment();
        startSegment = writeAheadLog.activeSegment();
      } finally {
        atomicOperationsManager.unfreezeWriteOperations(newSegmentFreezeId);
      }

      try {
        backupIv(zipOutputStream);

        final var encryptionIv = new byte[16];
        final var secureRandom = new SecureRandom();
        secureRandom.nextBytes(encryptionIv);

        backupIBUEncryptionIv(zipOutputStream, encryptionIv);

        final var aesKeyEncoded =
            configuration
                .getContextConfiguration()
                .getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY);
        final var aesKey =
            aesKeyEncoded == null ? null : Base64.getDecoder().decode(aesKeyEncoded);

        if (aesKey != null
            && aesKey.length != 16
            && aesKey.length != 24
            && aesKey.length != 32) {
          throw new InvalidStorageEncryptionKeyException(name,
              "Invalid length of the encryption key, provided size is " + aesKey.length);
        }

        var lastLsn = backupPagesWithChanges(fromLsn, zipOutputStream, encryptionIv, aesKey);
        final var lastWALLsn =
            copyWALToBackup(zipOutputStream, startSegment);

        if (lastWALLsn != null && (lastLsn == null || lastWALLsn.compareTo(lastLsn) > 0)) {
          lastLsn = lastWALLsn;
        }

        return lastLsn;
      } finally {
        writeAheadLog.removeCutTillLimit(freezeLsn);
      }
    } finally {
      try {
        zipOutputStream.finish();
        zipOutputStream.flush();
      } catch (IOException e) {
        LogManager.instance().warn(this, "Failed to flush data during incremental backup. ", e);
      }
    }
  }

  private void checkBackupIsNotPerformedInStorageDir(final Path backupDirectory) {
    var invalid = backupDirectory.equals(storagePath);
    if (invalid) {
      throw new StorageException(name, "Backup cannot be performed in the storage path");
    }
  }

  private void doEncryptionDecryption(
      final int mode,
      final byte[] aesKey,
      final long pageIndex,
      final long fileId,
      final byte[] backUpPage,
      final byte[] encryptionIv) {
    try {
      final var cipher = CIPHER.get();
      final SecretKey secretKey = new SecretKeySpec(aesKey, ALGORITHM_NAME);

      final var updatedIv = new byte[16];
      for (var i = 0; i < LongSerializer.LONG_SIZE; i++) {
        updatedIv[i] = (byte) (encryptionIv[i] ^ ((pageIndex >>> i) & 0xFF));
      }

      for (var i = 0; i < LongSerializer.LONG_SIZE; i++) {
        updatedIv[i + LongSerializer.LONG_SIZE] =
            (byte) (encryptionIv[i + LongSerializer.LONG_SIZE] ^ ((fileId >>> i) & 0xFF));
      }

      cipher.init(mode, secretKey, new IvParameterSpec(updatedIv));

      final var data =
          cipher.doFinal(
              backUpPage, LongSerializer.LONG_SIZE,
              backUpPage.length - LongSerializer.LONG_SIZE);
      System.arraycopy(
          data,
          0,
          backUpPage,
          LongSerializer.LONG_SIZE,
          backUpPage.length - LongSerializer.LONG_SIZE);
    } catch (InvalidKeyException e) {
      throw BaseException.wrapException(
          new InvalidStorageEncryptionKeyException(name, e.getMessage()),
          e, name);
    } catch (InvalidAlgorithmParameterException e) {
      throw new IllegalArgumentException("Invalid IV.", e);
    } catch (IllegalBlockSizeException | BadPaddingException e) {
      throw new IllegalStateException("Unexpected exception during CRT encryption.", e);
    }
  }

  private static void backupIBUEncryptionIv(final ZipOutputStream zipOutputStream,
      final byte[] encryptionIv)
      throws IOException {
    final var zipEntry = new ZipEntry(ENCRYPTION_IV);
    zipOutputStream.putNextEntry(zipEntry);

    zipOutputStream.write(encryptionIv);
    zipOutputStream.closeEntry();
  }

  private void backupIv(final ZipOutputStream zipOutputStream) throws IOException {
    final var zipEntry = new ZipEntry(IV_NAME);
    zipOutputStream.putNextEntry(zipEntry);

    zipOutputStream.write(this.iv);
    zipOutputStream.closeEntry();
  }

  private static byte[] restoreIv(final ZipInputStream zipInputStream) throws IOException {
    final var iv = new byte[16];
    IOUtils.readFully(zipInputStream, iv, 0, iv.length);

    return iv;
  }

  private LogSequenceNumber backupPagesWithChanges(
      final LogSequenceNumber changeLsn,
      final ZipOutputStream stream,
      final byte[] encryptionIv,
      final byte[] aesKey)
      throws IOException {
    var lastLsn = changeLsn;

    final var files = writeCache.files();
    final var pageSize = writeCache.pageSize();

    for (var entry : files.entrySet()) {
      final var fileName = entry.getKey();

      long fileId = entry.getValue();
      fileId = writeCache.externalFileId(writeCache.internalFileId(fileId));

      final var filledUpTo = writeCache.getFilledUpTo(fileId);
      final var zipEntry = new ZipEntry(fileName);

      stream.putNextEntry(zipEntry);

      final var binaryFileId = new byte[LongSerializer.LONG_SIZE];
      LongSerializer.serializeLiteral(fileId, binaryFileId, 0);
      stream.write(binaryFileId, 0, binaryFileId.length);

      for (var pageIndex = 0; pageIndex < filledUpTo; pageIndex++) {
        final var cacheEntry =
            readCache.silentLoadForRead(fileId, pageIndex, writeCache, true);
        cacheEntry.acquireSharedLock();
        try {
          var cachePointer = cacheEntry.getCachePointer();
          assert cachePointer != null;

          var cachePointerBuffer = cachePointer.getBuffer();
          assert cachePointerBuffer != null;

          final var pageLsn =
              DurablePage.getLogSequenceNumberFromPage(cachePointerBuffer);

          if (changeLsn == null || pageLsn.compareTo(changeLsn) > 0) {

            final var data = new byte[pageSize + LongSerializer.LONG_SIZE];
            LongSerializer.serializeNative(pageIndex, data, 0);
            DurablePage.getPageData(cachePointerBuffer, data, LongSerializer.LONG_SIZE,
                pageSize);

            if (aesKey != null) {
              doEncryptionDecryption(
                  Cipher.ENCRYPT_MODE, aesKey, fileId, pageIndex, data, encryptionIv);
            }

            stream.write(data);

            if (lastLsn == null || pageLsn.compareTo(lastLsn) > 0) {
              lastLsn = pageLsn;
            }
          }
        } finally {
          cacheEntry.releaseSharedLock();
          readCache.releaseFromRead(cacheEntry);
        }
      }

      stream.closeEntry();
    }

    return lastLsn;
  }

  @Override
  public void restoreFromBackup(Path backupDirectory, String expectedUUID) {
    restoreFromBackup(() -> {
      try (var filesStream = Files.list(backupDirectory)) {
        return filesStream.filter(path -> {
          if (Files.isDirectory(path)) {
            return false;
          }

          var fileName = path.getFileName();
          var strFileName = fileName.toString();
          return strFileName.endsWith(IBU_EXTENSION) && (expectedUUID == null
              || strFileName.startsWith(
              expectedUUID));
        }).map(path -> path.getFileName().toString()).toList().iterator();
      } catch (IOException e) {
        throw BaseException.wrapException(new DatabaseException(name,
            "Can not list backup unit files in directory '" + backupDirectory + "'"), e, name);
      }
    }, ibuFileName -> {
      var ibuPath = backupDirectory.resolve(ibuFileName);
      try {
        return new BufferedInputStream(
            Files.newInputStream(backupDirectory.resolve(ibuFileName)));
      } catch (IOException e) {
        throw BaseException.wrapException(new DatabaseException(name,
            "Can open backup unit file " + ibuPath + " to read it."), e, name);
      }
    }, expectedUUID);
  }

  @Override
  public void restoreFromBackup(Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier, @Nullable String expectedUUID) {
    stateLock.writeLock().lock();
    try {
      UUID uuidExpectedInBackup = null;
      if (expectedUUID != null) {
        expectedUUID = expectedUUID.trim();

        if (!expectedUUID.isEmpty()) {
          try {
            uuidExpectedInBackup = UUID.fromString(expectedUUID);
          } catch (IllegalArgumentException e) {
            LogManager.instance()
                .error(this, "Expected UUID of the backup %s is incorrect.", e, expectedUUID);
            throw e;
          }
        }
      }

      final var aesKeyEncoded =
          configuration
              .getContextConfiguration()
              .getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY);
      final var aesKey =
          aesKeyEncoded == null ? null : Base64.getDecoder().decode(aesKeyEncoded);

      if (aesKey != null && aesKey.length != 16 && aesKey.length != 24 && aesKey.length != 32) {
        throw new InvalidStorageEncryptionKeyException(name,
            "Invalid length of the encryption key, provided size is " + aesKey.length);
      }

      List<String> ibuFiles;

      if (expectedUUID != null) {
        var expectedUUIDString = expectedUUID;
        ibuFiles = IteratorUtils.list(IteratorUtils.filter(ibuFilesSupplier.get(),
            fileName -> fileName.startsWith(expectedUUIDString)));
      } else {
        ibuFiles = IteratorUtils.list(ibuFilesSupplier.get());
      }

      ibuFiles.sort(new IBUFileNamesComparator());
      var tempIBUFiles = new ArrayList<ObjectBooleanPair<Path>>(ibuFiles.size());
      var tmpDirectory = Files.createTempDirectory(name + "-ytdb-backup");
      LogManager.instance()
          .info(this, "Temporary directory for backup restore created in %s.",
              tmpDirectory.toAbsolutePath());

      UUID metadataUUID = null;
      LogSequenceNumber lastLsn = null;
      long backupLastTxId = -1;
      try {
        for (var ibuFile : ibuFiles) {
          var tmpIBUFile = tmpDirectory.resolve(ibuFile);

          var isFullBackup = false;
          try (var copyStream = Files.newOutputStream(tmpIBUFile)) {
            try (var bufferedCopyStream = new BufferedOutputStream(copyStream)) {

              BackupMetadata backupMetadata = null;
              try (var ibuStream = ibuInputStreamSupplier.apply(ibuFile)) {
                backupMetadata = validateFileAndFetchBackupMetadata(ibuFile, getName(),
                    uuidExpectedInBackup, ibuStream, bufferedCopyStream);
              }

              if (backupMetadata == null) {
                throw new DatabaseException(name, "Backup unit file " + ibuFile
                    + " contains invalid content, restore from this backup is impossible.");
              }
              if (metadataUUID == null) {
                metadataUUID = backupMetadata.databaseId;
              } else if (!metadataUUID.equals(backupMetadata.databaseId)) {
                throw new DatabaseException(name, "Backup unit files from different databases "
                    + "cannot be restored in the same database.");
              }

              if (lastLsn != null) {
                if (backupMetadata.startLsn == null) {
                  throw new DatabaseException(name,
                      "There are two full backups in the backup, restore is impossible. ");
                }

                if (!backupMetadata.startLsn.equals(lastLsn)) {
                  throw new DatabaseException(
                      "Backup files are not contiguous, some changes are missing, restore is impossible.");
                }
              }

              isFullBackup = backupMetadata.startLsn == null;
              lastLsn = backupMetadata.endLsn;
              if (backupMetadata.lastTxId > backupLastTxId) {
                backupLastTxId = backupMetadata.lastTxId;
              }
            }
          }

          tempIBUFiles.add(new ObjectBooleanImmutablePair<>(tmpIBUFile, isFullBackup));
        }

        if (tempIBUFiles.isEmpty()) {
          throw new DatabaseException(name, "No backup unit files found in the backup.");
        }
        var firstBackupUnit = tempIBUFiles.getFirst();
        if (!firstBackupUnit.rightBoolean()) {
          throw new DatabaseException(name,
              "Full backup file is absent in the backup, restore is "
                  + "impossible.");
        }

        var result = preprocessingIncrementalRestore();
        for (var ibuFilePair : tempIBUFiles) {
          var ibuPath = ibuFilePair.left();

          try (var inputStream = Files.newInputStream(ibuPath)) {
            try (var bufferedInputStream = new BufferedInputStream(inputStream)) {
              var isFullBackup = ibuFilePair.rightBoolean();
              restoreFromIncrementalBackup(
                  result.charset,
                  result.locale,
                  result.contextConfiguration,
                  aesKey,
                  bufferedInputStream,
                  isFullBackup);
            }
          }
        }

        if (backupLastTxId >= 0 && backupLastTxId >= getIdGen().getLastId()) {
          getIdGen().setStartId(backupLastTxId + 1);
        }

        postProcessIncrementalRestore(result.contextConfiguration);
      } finally {
        PathUtils.deleteDirectory(tmpDirectory);
        LogManager.instance().info(this, "Temporary directory for backup restore %s deleted.",
            tmpDirectory.toAbsolutePath());
      }


    } catch (IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during restore from backup"), e, name);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private IncrementalRestorePreprocessingResult preprocessingIncrementalRestore()
      throws IOException {
    final var contextConfiguration = configuration.getContextConfiguration();
    final var charset = configuration.getCharset();
    final var locale = configuration.getLocaleInstance();

    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> {
          closeCollections();
          closeIndexes(atomicOperation);
          ((CollectionBasedStorageConfiguration) configuration).close(atomicOperation);
        });

    configuration = null;

    return new IncrementalRestorePreprocessingResult(contextConfiguration, charset, locale);
  }

  private void postProcessIncrementalRestore(ContextConfiguration contextConfiguration)
      throws IOException {
    if (CollectionBasedStorageConfiguration.exists(writeCache)) {
      configuration = new CollectionBasedStorageConfiguration(this);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation ->
              ((CollectionBasedStorageConfiguration) configuration)
                  .load(contextConfiguration, atomicOperation));
    } else {
      configuration = new CollectionBasedStorageConfiguration(this);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation ->
              ((CollectionBasedStorageConfiguration) configuration)
                  .load(contextConfiguration, atomicOperation));
    }

    atomicOperationsManager.executeInsideAtomicOperation(this::openCollections);
    linkCollectionsBTreeManager.close();

    atomicOperationsManager.executeInsideAtomicOperation(linkCollectionsBTreeManager::load);
    atomicOperationsManager.executeInsideAtomicOperation(this::openIndexes);

    flushAllData();

    atomicOperationsManager.executeInsideAtomicOperation(this::generateDatabaseInstanceId);
  }

  private void restoreFromIncrementalBackup(
      final String charset,
      final Locale locale,
      final ContextConfiguration contextConfiguration,
      final byte[] aesKey,
      final InputStream inputStream,
      final boolean isFull)
      throws IOException {
    final List<String> currentFiles = new ArrayList<>(writeCache.files().keySet());
    final var bufferedInputStream = new BufferedInputStream(inputStream);
    final var zipInputStream =
        new ZipInputStream(bufferedInputStream, Charset.forName(charset));
    final var pageSize = writeCache.pageSize();

    ZipEntry zipEntry;
    LogSequenceNumber maxLsn = null;

    List<String> processedFiles = new ArrayList<>();
    if (isFull) {
      final var files = writeCache.files();
      for (var entry : files.entrySet()) {
        final var fileId = writeCache.fileIdByName(entry.getKey());

        assert entry.getValue().equals(fileId);
        readCache.deleteFile(fileId, writeCache);
      }
    }

    final var walTempDir = createWalTempDirectory();

    byte[] encryptionIv = null;
    byte[] walIv = null;

    entryLoop:
    while ((zipEntry = zipInputStream.getNextEntry()) != null) {
      switch (zipEntry.getName()) {
        case IV_NAME -> {
          walIv = restoreIv(zipInputStream);
          continue;
        }
        case ENCRYPTION_IV -> {
          encryptionIv = restoreEncryptionIv(zipInputStream);
          continue;
        }
        case CONF_ENTRY_NAME -> {
          replaceConfiguration(zipInputStream);

          continue;
        }
      }

      if (zipEntry.getName().equalsIgnoreCase("database_instance.uuid")) {
        continue;
      }

      if (zipEntry.getName().equals(CONF_UTF_8_ENTRY_NAME)) {
        replaceConfiguration(zipInputStream);

        continue;
      }

      if (zipEntry
          .getName()
          .toLowerCase(locale)
          .endsWith(CASDiskWriteAheadLog.WAL_SEGMENT_EXTENSION)) {
        final var walName = zipEntry.getName();
        final var segmentIndex =
            walName.lastIndexOf(
                '.',
                walName.length() - CASDiskWriteAheadLog.WAL_SEGMENT_EXTENSION.length() - 1);
        if (segmentIndex < 0) {
          throw new IllegalStateException("Can not find index of WAL segment");
        }

        addFileToDirectory(
            contextConfiguration.getValueAsString(ContextConfiguration.WAL_BASE_NAME,
                ContextConfiguration.WAL_DEFAULT_NAME) + walName.substring(segmentIndex),
            zipInputStream, walTempDir);
        continue;
      }

      if (aesKey != null && encryptionIv == null) {
        throw new SecurityException(name, "IV can not be null if encryption key is provided");
      }

      final var binaryFileId = new byte[LongSerializer.LONG_SIZE];
      IOUtils.readFully(zipInputStream, binaryFileId, 0, binaryFileId.length);

      final var expectedFileId = LongSerializer.deserializeLiteral(binaryFileId, 0);
      long fileId;

      var rootDirectory = storagePath;
      var zipEntryPath = rootDirectory.resolve(zipEntry.getName()).normalize();

      if (!zipEntryPath.startsWith(rootDirectory)) {
        throw new IllegalStateException("Bad zip entry " + zipEntry.getName());
      }
      if (!zipEntryPath.getParent().equals(rootDirectory)) {
        throw new IllegalStateException("Bad zip entry " + zipEntry.getName());
      }

      var fileName = zipEntryPath.getFileName().toString();
      if (!writeCache.exists(fileName)) {
        fileId = readCache.addFile(fileName, expectedFileId, writeCache);
      } else {
        fileId = writeCache.fileIdByName(fileName);
      }

      if (!writeCache.fileIdsAreEqual(expectedFileId, fileId)) {
        throw new StorageException(name,
            "Can not restore database from backup because expected and actual file ids are not the"
                + " same");
      }

      while (true) {
        final var data = new byte[pageSize + LongSerializer.LONG_SIZE];

        var rb = 0;

        while (rb < data.length) {
          final var b = zipInputStream.read(data, rb, data.length - rb);

          if (b == -1) {
            if (rb > 0) {
              throw new StorageException(name, "Can not read data from file " + fileName);
            } else {
              processedFiles.add(fileName);
              continue entryLoop;
            }
          }

          rb += b;
        }

        final var pageIndex = LongSerializer.deserializeNative(data, 0);

        if (aesKey != null) {
          doEncryptionDecryption(
              Cipher.DECRYPT_MODE, aesKey, expectedFileId, pageIndex, data, encryptionIv);
        }

        var cacheEntry = readCache.loadForWrite(fileId, pageIndex, writeCache, true, null);

        if (cacheEntry == null) {
          do {
            if (cacheEntry != null) {
              readCache.releaseFromWrite(cacheEntry, writeCache, true);
            }

            cacheEntry = readCache.allocateNewPage(fileId, writeCache, null);
          } while (cacheEntry.getPageIndex() != pageIndex);
        }

        try {
          final var buffer = cacheEntry.getCachePointer().getBuffer();
          assert buffer != null;
          final var backedUpPageLsn =
              DurablePage.getLogSequenceNumber(LongSerializer.LONG_SIZE, data);
          if (isFull) {
            buffer.put(0, data, LongSerializer.LONG_SIZE,
                data.length - LongSerializer.LONG_SIZE);

            if (maxLsn == null || maxLsn.compareTo(backedUpPageLsn) < 0) {
              maxLsn = backedUpPageLsn;
            }
          } else {
            final var currentPageLsn =
                DurablePage.getLogSequenceNumberFromPage(buffer);
            if (backedUpPageLsn.compareTo(currentPageLsn) > 0) {
              buffer.put(
                  0, data, LongSerializer.LONG_SIZE, data.length - LongSerializer.LONG_SIZE);

              if (maxLsn == null || maxLsn.compareTo(backedUpPageLsn) < 0) {
                maxLsn = backedUpPageLsn;
              }
            }
          }

        } finally {
          readCache.releaseFromWrite(cacheEntry, writeCache, true);
        }
      }
    }

    currentFiles.removeAll(processedFiles);

    for (var file : currentFiles) {
      if (writeCache.exists(file)) {
        final var fileId = writeCache.fileIdByName(file);
        readCache.deleteFile(fileId, writeCache);
      }
    }

    try (final var restoreLog =
        createWalFromIBUFiles(walTempDir, contextConfiguration, locale, walIv)) {
      if (restoreLog != null) {
        final var beginLsn = restoreLog.begin();
        restoreFrom(restoreLog, beginLsn);
      }
    }

    if (maxLsn != null && writeAheadLog != null) {
      writeAheadLog.moveLsnAfter(maxLsn);
    }

    FileUtils.deleteRecursively(walTempDir);
  }

  private byte[] restoreEncryptionIv(final ZipInputStream zipInputStream) throws IOException {
    final var iv = new byte[16];
    var read = 0;
    while (read < iv.length) {
      final var localRead = zipInputStream.read(iv, read, iv.length - read);

      if (localRead < 0) {
        throw new StorageException(name,
            "End of stream is reached but IV data were not completely read");
      }

      read += localRead;
    }

    return iv;
  }

  @Override
  public int getAbsoluteLinkBagCounter(RID ownerId, String fieldName, RID key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public AbsoluteChange getLinkBagCounter(DatabaseSessionEmbedded session,
      RecordIdInternal identity,
      String fieldName, RID rid) {
    throw new UnsupportedOperationException();
  }

  private static void replaceConfiguration(ZipInputStream zipInputStream) throws IOException {
    var buffer = new byte[1024];

    var rb = 0;
    while (true) {
      final var b = zipInputStream.read(buffer, rb, buffer.length - rb);

      if (b == -1) {
        break;
      }

      rb += b;

      if (rb == buffer.length) {
        var oldBuffer = buffer;

        buffer = new byte[buffer.length << 1];
        System.arraycopy(oldBuffer, 0, buffer, 0, oldBuffer.length);
      }
    }
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

  private record IncrementalRestorePreprocessingResult(
      ContextConfiguration contextConfiguration,
      String charset,
      Locale locale) {

  }

  protected record BackupMetadata(int backupFormatVersion,
                                  UUID databaseId,
                                  int sequenceNumber,
                                  LogSequenceNumber startLsn,
                                  LogSequenceNumber endLsn,
                                  long lastTxId) {

  }

  private static final class XXHashOutputStream extends ProxyOutputStream {

    private final StreamingXXHash64 xxHash64 =
        XXHashFactory.fastestInstance().newStreamingHash64(XX_HASH_SEED);

    XXHashOutputStream(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] bts) throws IOException {
      xxHash64.update(bts, 0, bts.length);
      super.write(bts);
    }

    @Override
    public void write(byte[] bts, int st, int end) throws IOException {
      xxHash64.update(bts, st, end - st);
      super.write(bts, st, end);
    }

    @Override
    public void write(int b) throws IOException {
      xxHash64.update(new byte[]{(byte) b}, 0, 1);
      super.write(b);
    }

    @Override
    public void close() throws IOException {
      xxHash64.close();
      super.close();
    }
  }

  private static final class IBUFileNamesComparator implements Comparator<String> {

    @Override
    public int compare(String firstIbuName, String secondIbuName) {
      var firstNameLastDashIndex = firstIbuName.lastIndexOf('-');
      if (firstNameLastDashIndex == -1) {
        throw new IllegalArgumentException("Invalid backup unit file name: " + firstIbuName);
      }
      var firstNameWithoutDbName = firstIbuName.substring(0, firstNameLastDashIndex);

      var secondNameLastDashIndex = secondIbuName.lastIndexOf('-');
      if (secondNameLastDashIndex == -1) {
        throw new IllegalArgumentException("Invalid backup unit file name: " + secondIbuName);
      }

      var secondNameWithoutDbName = secondIbuName.substring(0, secondNameLastDashIndex);
      return firstNameWithoutDbName.compareTo(secondNameWithoutDbName);
    }
  }

  private record IBULocalFileNamesSupplier(Path backupDirectory, String databaseName,
                                           String dbUUID) implements Supplier<Iterator<String>> {

    @Override
    public Iterator<String> get() {
      try (var filesStream = Files.list(backupDirectory)) {
        return filesStream.filter(path -> {
          if (Files.isDirectory(path)) {
            return false;
          }

          var fileName = path.getFileName().toString();
          return fileName.endsWith(IBU_EXTENSION) && fileName.startsWith(dbUUID);
        }).map(path -> path.getFileName().toString()).toList().iterator();
      } catch (IOException e) {
        throw BaseException.wrapException(new DatabaseException(databaseName,
                "Can not list backup unit files in directory '" + backupDirectory + "'"), e,
            databaseName);
      }
    }
  }

  private record IBULocalFileInputStreamSupplier(Path backupDirectory,
                                                 String databaseName) implements
      Function<String, InputStream> {

    @Override
    public InputStream apply(String ibuFileName) {
      var ibuPath = backupDirectory.resolve(ibuFileName);
      try {
        return new BufferedInputStream(
            Files.newInputStream(backupDirectory.resolve(ibuFileName)));
      } catch (IOException e) {
        throw BaseException.wrapException(new DatabaseException(databaseName,
            "Can open backup unit file " + ibuPath + " to read it."), e, databaseName);
      }
    }
  }

  private record IBULocalFileOutputStreamSupplier(Path backupDirectory,
                                                  String databaseName) implements
      Function<String, OutputStream> {

    @Override
    public OutputStream apply(String ibuFileName) {
      var ibuPath = backupDirectory.resolve(ibuFileName);
      try {
        OutputStream os;
        var fileChannel = FileChannel.open(ibuPath, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        try {
          os = Channels.newOutputStream(fileChannel);
        } catch (Exception e) {
          fileChannel.close();
          throw e;
        }
        return new ProxyOutputStream(os) {
          @Override
          public void close() throws IOException {
            fileChannel.force(true);
            super.close();

            //trying to fsync directory changes making it visible to other processes
            try (var directoryChannel = FileChannel.open(backupDirectory)) {
              directoryChannel.force(true);
            } catch (IOException ignored) {
              //ignore if attempt is failed.
            }

          }
        };
      } catch (IOException e) {
        throw BaseException.wrapException(
            new DatabaseException(databaseName,
                "Can create new backup unit file " + ibuPath + " ."),
            e, databaseName);
      }
    }
  }

  private record IBULocalFileRemover(Path backupDirectory, String databaseName) implements
      Consumer<String> {

    @Override
    public void accept(String ibuFileName) {
      try {
        var ibuFilePath = backupDirectory.resolve(ibuFileName);
        Files.deleteIfExists(ibuFilePath);
        LogManager.instance().info(this, "Deleted backup unit file " + ibuFilePath);

        //trying to fsync directory changes making it visible to other processes
        try (var directoryChannel = FileChannel.open(backupDirectory)) {
          directoryChannel.force(true);
        } catch (IOException ignored) {
          //ignore if attempt is failed.
        }

      } catch (IOException e) {
        throw BaseException.wrapException(new DatabaseException(databaseName,
            "Can not delete backup unit file " + ibuFileName + " ."), e, databaseName);
      }
    }
  }
}
