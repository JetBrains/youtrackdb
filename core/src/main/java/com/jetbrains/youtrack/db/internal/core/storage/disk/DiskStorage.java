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

package com.jetbrains.youtrack.db.internal.core.storage.disk;

import static com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.MASTER_RECORD_EXTENSION;
import static com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.WAL_SEGMENT_EXTENSION;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BackupInProgressException;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.compression.impl.ZIPCompressionUtil;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrack.db.internal.core.engine.local.EngineLocalPaginated;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidStorageEncryptionKeyException;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrack.db.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrack.db.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.doublewritelog.DoubleWriteLog;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogGL;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrack.db.internal.core.storage.collection.CollectionPositionMap;
import com.jetbrains.youtrack.db.internal.core.storage.collection.v2.FreeSpaceMap;
import com.jetbrains.youtrack.db.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.storage.fs.File;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.StartupMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.StorageStartupMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.AbsoluteChange;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
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
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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
import org.apache.commons.io.output.ProxyOutputStream;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiskStorage extends AbstractStorage {

  public static final XXHash64 XX_HASH_64 = XXHashFactory.fastestInstance().hash64();
  public static final long XX_HASH_SEED = 0xAEF5634;

  private static final Logger logger = LoggerFactory.getLogger(DiskStorage.class);

  private static final String INCREMENTAL_BACKUP_LOCK = "backup.ibl";

  private static final String ALGORITHM_NAME = "AES";
  private static final String TRANSFORMATION = "AES/CTR/NoPadding";

  private static final ThreadLocal<Cipher> CIPHER =
      ThreadLocal.withInitial(DiskStorage::getCipherInstance);

  private static final String IBU_EXTENSION = ".ibu";
  /// The metadata is located at the tail and has the following format:
  ///
  /// 1. Version of the backup metadata format is stored as a short value (currently, we have only
  /// version 1).
  /// 2. Database UUID (generated during database creation) is stored as two long values with 16
  /// bytes.
  /// 3. Number of the backup unit in sequence (stored as an int value).
  /// 4. Start LSN - value of LSN (exclusive) from which delta changes in the backup are stored. It
  /// is stored as long + int values, stored as (-1, -1) for the first (full) backup.
  /// 5. End LSN - the value of the last change stored in the backup (inclusive).
  /// 6. The hash code of the file's content. Stored as a long value. The XX_HASH algorithm is used
  /// for hash code calculation.
  private static final int IBU_METADATA_SIZE =
      Short.BYTES + 2 * Long.BYTES + Integer.BYTES + 2 * (Long.BYTES + Integer.BYTES) + Long.BYTES;

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
      IBU_METADATA_LAST_LSN_SEGMENT_OFFSET + Integer.BYTES;

  private static final int IBU_METADATA_HASH_CODE_OFFSET =
      IBU_METADATA_LAST_LSN_POSITION_OFFSET + Long.BYTES + Integer.BYTES;

  private static final int CURRENT_INCREMENTAL_BACKUP_FORMAT_VERSION = 1;
  private static final String CONF_ENTRY_NAME = "database.ocf";
  private static final String INCREMENTAL_BACKUP_DATEFORMAT = "yyyy-MM-dd-HH-mm-ss";
  private static final String CONF_UTF_8_ENTRY_NAME = "database_utf8.ocf";

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

    storagePath = Paths.get(IOUtils.getPathFromDatabaseName(sp)).normalize().toAbsolutePath();

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
  public final List<String> backup(
      DatabaseSessionInternal db, final OutputStream out,
      final Map<String, Object> options,
      final Callable<Object> callable,
      final CommandOutputListener iOutput,
      final int compressionLevel,
      final int bufferSize) {
    stateLock.readLock().lock();
    try {
      if (out == null) {
        throw new IllegalArgumentException("Backup output is null");
      }

      freeze(db, false);
      try {
        if (callable != null) {
          try {
            callable.call();
          } catch (final Exception e) {
            LogManager.instance().error(this, "Error on callback invocation during backup", e);
          }
        }
        LogSequenceNumber freezeLSN = null;
        if (writeAheadLog != null) {
          freezeLSN = writeAheadLog.begin();
          writeAheadLog.addCutTillLimit(freezeLSN);
        }

        try {
          final var bo = bufferSize > 0 ? new BufferedOutputStream(out, bufferSize) : out;
          try {
            try (final var zos = new ZipOutputStream(bo)) {
              zos.setComment("YouTrackDB Backup executed on " + new Date());
              zos.setLevel(compressionLevel);

              final var names =
                  ZIPCompressionUtil.compressDirectory(
                      storagePath.toString(),
                      zos,
                      new String[]{".fl", ".lock", DoubleWriteLogGL.EXTENSION},
                      iOutput);
              startupMetadata.addFileToArchive(zos, "dirty.fl");
              names.add("dirty.fl");
              return names;
            }
          } finally {
            if (bufferSize > 0) {
              bo.flush();
              bo.close();
            }
          }
        } finally {
          if (freezeLSN != null) {
            writeAheadLog.removeCutTillLimit(freezeLSN);
          }
        }

      } finally {
        release(db);
      }
    } catch (final RuntimeException e) {
      throw logAndPrepareForRethrow(e);
    } catch (final Error e) {
      throw logAndPrepareForRethrow(e, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void restore(
      final InputStream in,
      final Map<String, Object> options,
      final Callable<Object> callable,
      final CommandOutputListener iListener) {
    try {
      stateLock.writeLock().lock();
      try {
        if (!isClosedInternal()) {
          doShutdown();
        }

        final var dbDir =
            new java.io.File(
                IOUtils.getPathFromDatabaseName(
                    SystemVariableResolver.resolveSystemVariables(url)));
        final var storageFiles = dbDir.listFiles();
        if (storageFiles != null) {
          // TRY TO DELETE ALL THE FILES
          for (final var f : storageFiles) {
            // DELETE ONLY THE SUPPORTED FILES
            for (final var ext : ALL_FILE_EXTENSIONS) {
              if (f.getPath().endsWith(ext)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                break;
              }
            }
          }
        }
        Files.createDirectories(Paths.get(storagePath.toString()));
        ZIPCompressionUtil.uncompressDirectory(in, storagePath.toString(), iListener);

        final var newStorageFiles = dbDir.listFiles();
        if (newStorageFiles != null) {
          // TRY TO DELETE ALL THE FILES
          for (final var f : newStorageFiles) {
            if (f.getPath().endsWith(MASTER_RECORD_EXTENSION)) {
              final var renamed =
                  f.renameTo(new java.io.File(f.getParent(), getName() + MASTER_RECORD_EXTENSION));
              assert renamed;
            }
            if (f.getPath().endsWith(WAL_SEGMENT_EXTENSION)) {
              var walName = f.getName();
              final var segmentIndex =
                  walName.lastIndexOf('.', walName.length() - WAL_SEGMENT_EXTENSION.length() - 1);
              var ending = walName.substring(segmentIndex);
              final var renamed = f.renameTo(
                  new java.io.File(f.getParent(), getName() + ending));
              assert renamed;
            }
          }
        }

        if (callable != null) {
          try {
            callable.call();
          } catch (final Exception e) {
            LogManager.instance().error(this, "Error on calling callback on database restore", e);
          }
        }
      } finally {
        stateLock.writeLock().unlock();
      }

      open(new ContextConfiguration());
      atomicOperationsManager.executeInsideAtomicOperation(this::generateDatabaseInstanceId);
    } catch (final RuntimeException e) {
      throw logAndPrepareForRethrow(e);
    } catch (final Error e) {
      throw logAndPrepareForRethrow(e);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  @Override
  protected LogSequenceNumber copyWALToIncrementalBackup(
      final ZipOutputStream zipOutputStream, final long startSegment) throws IOException {

    java.io.File[] nonActiveSegments;

    LogSequenceNumber lastLSN;
    final var freezeId = getAtomicOperationsManager().freezeAtomicOperations(null);
    try {
      lastLSN = writeAheadLog.end();
      writeAheadLog.flush();
      writeAheadLog.appendNewSegment();
      nonActiveSegments = writeAheadLog.nonActiveSegments(startSegment);
    } finally {
      getAtomicOperationsManager().releaseAtomicOperations(freezeId);
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
        IOUtils.getPathFromDatabaseName(SystemVariableResolver.resolveSystemVariables(url));
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
  public void incrementalBackup(final Path backupDirectory) {
    checkBackupIsNotPerformedInStorageDir(backupDirectory);
    try {
      if (!Files.exists(backupDirectory)) {
        Files.createDirectories(backupDirectory);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(new DatabaseException(name,
          "Can not create directories are needed to perform database backup."), e, name);
    }

    var dbUUID = getUuid();
    var dbUUIDString = dbUUID.toString();
    final var fileLockPath = backupDirectory.resolve(dbUUIDString + "-" + INCREMENTAL_BACKUP_LOCK);
    try (final var lockChannel = FileChannel.open(fileLockPath, StandardOpenOption.CREATE,
        StandardOpenOption.WRITE)) {
      try (var ignored = lockChannel.lock()) {
        incrementalBackup(() -> {
          try (var filesStream = Files.list(backupDirectory)) {
            return filesStream.filter(path -> {
              if (Files.isDirectory(path)) {
                return false;
              }

              var fileName = path.getFileName();
              return fileName.endsWith(IBU_EXTENSION) && fileName.startsWith(dbUUIDString);
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
        }, ibuFileName -> {
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
              }
            };
          } catch (IOException e) {
            throw BaseException.wrapException(
                new DatabaseException(name, "Can create new backup unit file " + ibuPath + " ."), e,
                name);
          }
        }, ibuFileName -> {
          try {
            var ibuFilePath = backupDirectory.resolve(ibuFileName);
            Files.deleteIfExists(ibuFilePath);
          } catch (IOException e) {
            throw BaseException.wrapException(new DatabaseException(name,
                "Can not delete backup unit file " + ibuFileName + " ."), e, name);
          }
        });
      } catch (OverlappingFileLockException ofle) {
        LogManager.instance().error(this, "Can not lock file '%s'."
                + " File likely already locked by another process that performs database backup.",
            ofle, fileLockPath, ofle);
        throw ofle;
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new DatabaseException(name, "Error during creation of database backup."), e,
          name);
    }
  }


  @Override
  public void incrementalBackup(Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier,
      Function<String, OutputStream> ibuOutputStreamSupplier,
      Consumer<String> ibuFileRemover) {
    checkOpennessAndMigration();

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

          existingFiles.sort((firstIbuName, secondIbuName) -> {
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
          });

          BackupMetadata backupMetadata = null;

          while (!existingFiles.isEmpty()) {
            var ibuLastFile = existingFiles.removeLast();
            backupMetadata = validateFileAndFetchBackupMetadata(ibuLastFile, uuid,
                ibuInputStreamSupplier);

            if (backupMetadata == null) {
              LogManager.instance()
                  .error(this, "Backup unit file %s is broken and will be removed.", null,
                      ibuLastFile);
              ibuFileRemover.accept(ibuLastFile);
            } else {
              break;
            }
          }

          String ibuNextFile;
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
            var lastLsn = storeIncrementalBackupDataToStream(fileStream, fromLsn);

            writeBackupMetadata(xxHashStream, uuid, nextFileIndex, fromLsn, lastLsn);
          }

          LogManager.instance()
              .info(this, "Backup of database '%s' is completed. Backup unit file %s was created.",
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
  }

  private static void writeBackupMetadata(XXHashOutputStream xxHashStream, UUID uuid,
      int nextFileIndex, LogSequenceNumber fromLsn, LogSequenceNumber lastLsn) throws IOException {
    var dataOutputStream = new DataOutputStream(xxHashStream);
    dataOutputStream.writeShort(CURRENT_INCREMENTAL_BACKUP_FORMAT_VERSION);

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

    dataOutputStream.flush();

    var hashCode = xxHashStream.xxHash64.getValue();
    dataOutputStream.writeLong(hashCode);
    dataOutputStream.flush();
  }

  private String createIbuFileName(int backupNumber, String uuid) {
    var date = new Date();
    var strDate = new SimpleDateFormat(INCREMENTAL_BACKUP_DATEFORMAT, Locale.ROOT).format(date);
    return uuid + "-" + strDate + "-" + backupNumber + "-" + name + IBU_EXTENSION;
  }

  @Nullable
  private BackupMetadata validateFileAndFetchBackupMetadata(String ibuFileName, UUID dbUUID,
      Function<String, InputStream> ibuInputStreamSupplier) throws IOException {
    byte[] metaDataCandidate = null;

    try (var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(XX_HASH_SEED)) {
      try (var inputStream = ibuInputStreamSupplier.apply(ibuFileName)) {
        var buffer = new byte[(64 << 10)];

        while (true) {
          var read = inputStream.read(buffer);

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
                var r = inputStream.read(buffer, read, buffer.length);
                if (r == -1) {
                  LogManager.instance().warn(this,
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

            xxHash64.update(buffer, 0, read - IBU_METADATA_SIZE);
          } else {
            if (read >= IBU_METADATA_SIZE) {
              xxHash64.update(metaDataCandidate, 0, metaDataCandidate.length);
              xxHash64.update(buffer, 0, read - IBU_METADATA_SIZE);
              System.arraycopy(buffer, read - IBU_METADATA_SIZE,
                  metaDataCandidate, 0, IBU_METADATA_SIZE);
            } else {
              xxHash64.update(metaDataCandidate, 0, read);
              System.arraycopy(metaDataCandidate, read, metaDataCandidate, 0,
                  IBU_METADATA_SIZE - read);
              System.arraycopy(buffer, 0, metaDataCandidate, IBU_METADATA_SIZE - read, read);
            }
          }
        }
      }

      if (metaDataCandidate == null) {
        LogManager.instance().warn(this, "File %s does not contain backup metadata.", ibuFileName);
        return null;
      }

      xxHash64.update(metaDataCandidate, 0, metaDataCandidate.length - Long.BYTES);

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
      var metadataHashCode = LongSerializer.deserializeLiteral(metaDataCandidate,
          IBU_METADATA_HASH_CODE_OFFSET);

      if (metadataHashCode != xxHash64.getValue()) {
        LogManager.instance()
            .warn(this,
                "Hash code of the file %s is incorrect. Content of the file is invalid. File either broken or contains invalid data.",
                ibuFileName);
        return null;
      }

      if (dbUUID.getLeastSignificantBits() != metadataUUIDLowerBits
          || dbUUID.getMostSignificantBits() != metadataUUIDHigherBits) {
        var storedUUID = new UUID(metadataUUIDLowerBits, metadataUUIDHigherBits);
        LogManager.instance()
            .warn(this, "UUID of the file %s stored in metadata %s does not match DB UUID %s.",
                ibuFileName, storedUUID, dbUUID);
        return null;
      }

      var firstDashIndex = ibuFileName.indexOf('-');
      if (firstDashIndex == -1) {
        LogManager.instance().warn(this, "File name %s does not contain DB UUID.", ibuFileName);
        return null;
      }

      if (metadataVersion != CURRENT_INCREMENTAL_BACKUP_FORMAT_VERSION) {
        LogManager.instance()
            .warn(this,
                "Version of the file %s stored in metadata %d does not match supported version %d.",
                ibuFileName, metadataVersion, CURRENT_INCREMENTAL_BACKUP_FORMAT_VERSION);
        return null;
      }

      UUID fileNameUUID;
      try {
        fileNameUUID = UUID.fromString(ibuFileName.substring(firstDashIndex));
      } catch (IllegalArgumentException e) {
        LogManager.instance().warn(this, "UUID of the file %s is incorrect.", ibuFileName);
        return null;
      }

      if (fileNameUUID.getLeastSignificantBits() != metadataUUIDLowerBits
          || fileNameUUID.getMostSignificantBits() != metadataUUIDHigherBits) {
        LogManager.instance()
            .warn(this, "UUID of the file %s does not match DB UUID %s.", ibuFileName, dbUUID);
      }

      var secondDashIndex = ibuFileName.indexOf('-', firstDashIndex + 1);
      if (secondDashIndex == -1) {
        LogManager.instance()
            .warn(this, "File %s does not contain backup sequence number.", ibuFileName);
      }

      var thirdDashIndex = ibuFileName.indexOf('-', secondDashIndex + 1);
      if (thirdDashIndex == -1) {
        LogManager.instance()
            .warn(this, "File %s does not contain backup sequence number.", ibuFileName);
      }

      int sequenceNumber;
      try {
        sequenceNumber = Integer.parseInt(
            ibuFileName.substring(secondDashIndex + 1, thirdDashIndex));
      } catch (NumberFormatException e) {
        LogManager.instance()
            .warn(this, "Sequence number of the file %s is incorrect.", ibuFileName);
        return null;
      }

      if (metadataSequenceNumber != sequenceNumber) {
        LogManager.instance()
            .warn(this, "Sequence number of the file %s stored in metadata %s does not match DB "
                + "sequence number %s.", ibuFileName, sequenceNumber, metadataSequenceNumber);
        return null;
      }

      LogSequenceNumber startLsn = null;
      if (metadataStartLsnSegment != -1 && metadataStartLsnPosition != -1) {
        startLsn = new LogSequenceNumber(metadataStartLsnSegment, metadataStartLsnPosition);
      }
      if (metadataLastLsnSegment == -1 || metadataEndLsnPosition == -1) {
        LogManager.instance().warn(this, "Last LSN of the file %s stored in metadata is incorrect.",
            ibuFileName);
        return null;
      }

      var lastLsn = new LogSequenceNumber(metadataLastLsnSegment, metadataEndLsnPosition);

      return new BackupMetadata(metadataVersion, fileNameUUID, sequenceNumber, startLsn, lastLsn);
    }
  }


  private LogSequenceNumber storeIncrementalBackupDataToStream(OutputStream stream,
      LogSequenceNumber fromLsn)
      throws IOException {
    final var zipOutputStream = new ZipOutputStream(stream,
        Charset.forName(configuration.getCharset()));
    try {
      final long startSegment;
      final LogSequenceNumber freezeLsn;
      final var newSegmentFreezeId = atomicOperationsManager.freezeAtomicOperations(null);
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
        atomicOperationsManager.releaseAtomicOperations(newSegmentFreezeId);
      }

      try {
        backupIv(zipOutputStream);

        final var encryptionIv = new byte[16];
        final var secureRandom = new SecureRandom();
        secureRandom.nextBytes(encryptionIv);

        backupIBUEncryptionIv(zipOutputStream, encryptionIv);

        final var aesKeyEncoded =
            getConfiguration()
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
            copyWALToIncrementalBackup(zipOutputStream, startSegment);

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

  @Override
  public void fullIncrementalBackup(final OutputStream stream) {
    throw new UnsupportedOperationException("Full incremental backup is not supported yet.");
    //TODO: implement it next
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
            LongSerializer.INSTANCE.serializeNative(pageIndex, data, 0);
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
  public void restoreFromIncrementalBackup(DatabaseSessionInternal session,
      final String filePath) {
    restoreFromIncrementalBackup(Path.of(filePath));
  }

//  TODO: implement it next
//  @Override
//  public void restoreFullIncrementalBackup(DatabaseSessionInternal session,
//      final InputStream stream)
//      throws UnsupportedOperationException {
//    stateLock.writeLock().lock();
//    try {
//      final var aesKeyEncoded =
//          getConfiguration()
//              .getContextConfiguration()
//              .getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY);
//      final var aesKey =
//          aesKeyEncoded == null ? null : Base64.getDecoder().decode(aesKeyEncoded);
//
//      if (aesKey != null && aesKey.length != 16 && aesKey.length != 24 && aesKey.length != 32) {
//        throw new InvalidStorageEncryptionKeyException(name,
//            "Invalid length of the encryption key, provided size is " + aesKey.length);
//      }
//
//      var result = preprocessingIncrementalRestore();
//      restoreFromIncrementalBackup(
//          result.charset,
//          result.serverLocale,
//          result.locale,
//          result.contextConfiguration,
//          aesKey,
//          stream,
//          true);
//
//      postProcessIncrementalRestore(result.contextConfiguration);
//    } catch (IOException e) {
//      throw BaseException.wrapException(
//          new StorageException(name, "Error during restore from incremental backup"), e, name);
//    } finally {
//      stateLock.writeLock().unlock();
//    }
//  }

  private IncrementalRestorePreprocessingResult preprocessingIncrementalRestore()
      throws IOException {
    final var serverLocale = configuration.getLocaleInstance();
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

    return new IncrementalRestorePreprocessingResult(
        serverLocale, contextConfiguration, charset, locale);
  }

  private void restoreFromIncrementalBackup(final Path backupDirectory) {
    //TODO: implement it next
//    if (!Files.exists(backupDirectory)) {
//      throw new StorageException(name,
//          "Directory which should contain incremental backup files (files with extension '"
//              + IBU_EXTENSION_V3
//              + "') is absent. It should be located at '"
//              + backupDirectory.toAbsolutePath()
//              + "'");
//    }
//
//    try {
//      final var files = fetchIBUFiles(backupDirectory);
//      if (files.isEmpty()) {
//        throw new StorageException(name,
//            "Cannot find incremental backup files (files with extension '"
//                + IBU_EXTENSION_V3
//                + "') in directory '"
//                + backupDirectory.toAbsolutePath()
//                + "'");
//      }
//
//      stateLock.writeLock().lock();
//      try {
//
//        final var aesKeyEncoded =
//            getConfiguration()
//                .getContextConfiguration()
//                .getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY);
//        final var aesKey =
//            aesKeyEncoded == null ? null : Base64.getDecoder().decode(aesKeyEncoded);
//
//        if (aesKey != null && aesKey.length != 16 && aesKey.length != 24
//            && aesKey.length != 32) {
//          throw new InvalidStorageEncryptionKeyException(name,
//              "Invalid length of the encryption key, provided size is " + aesKey.length);
//        }
//
//        var result = preprocessingIncrementalRestore();
//        var restoreUUID = extractDbInstanceUUID(files.getFirst(), result.charset);
//
//        for (var file : files) {
//          var fileUUID = extractDbInstanceUUID(files.getFirst(), result.charset);
//          if ((restoreUUID == null && fileUUID == null)
//              || (restoreUUID != null && restoreUUID.equals(fileUUID))) {
//            final var ibuFile = backupDirectory.resolve(file);
//            try (final var ibuChannel = FileChannel.open(ibuFile, StandardOpenOption.READ)) {
//              final var versionBuffer = ByteBuffer.allocate(IntegerSerializer.INT_SIZE);
//              IOUtils.readByteBuffer(versionBuffer, ibuChannel);
//              versionBuffer.rewind();
//
//              final var backupVersion = versionBuffer.getInt();
//              if (backupVersion != CURRENT_INCREMENTAL_BACKUP_FORMAT_VERSION) {
//                throw new StorageException(name,
//                    "Invalid version of incremental backup version was provided. Expected "
//                        + CURRENT_INCREMENTAL_BACKUP_FORMAT_VERSION
//                        + " , provided "
//                        + backupVersion);
//              }
//
//              final var buffer = ByteBuffer.allocate(1);
//              IOUtils.readByteBuffer(buffer, ibuChannel,
//                  2 * IntegerSerializer.INT_SIZE + 2 * LongSerializer.LONG_SIZE,
//                  true);
//              buffer.rewind();
//
//              final var fullBackup = buffer.get() == 1;
//              try (final var inputStream = Channels.newInputStream(ibuChannel)) {
//                restoreFromIncrementalBackup(
//                    result.charset,
//                    result.serverLocale,
//                    result.locale,
//                    result.contextConfiguration,
//                    aesKey,
//                    inputStream,
//                    fullBackup);
//              }
//            }
//          } else {
//            LogManager.instance()
//                .warn(
//                    this,
//                    "Skipped file '"
//                        + file
//                        + "' is not a backup of the same database of previous backups");
//          }
//
//          postProcessIncrementalRestore(result.contextConfiguration);
//        }
//      } finally {
//        stateLock.writeLock().unlock();
//      }
//    } catch (IOException e) {
//      throw BaseException.wrapException(
//          new StorageException(name, "Error during restore from incremental backup"), e, name);
//    }
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
    linkCollectionsBTreeManager.load();

    openIndexes();
    flushAllData();

    atomicOperationsManager.executeInsideAtomicOperation(
        this::generateDatabaseInstanceId);
  }

  private void restoreFromIncrementalBackup(
      final String charset,
      final Locale serverLocale,
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
          .toLowerCase(serverLocale)
          .endsWith(CASDiskWriteAheadLog.WAL_SEGMENT_EXTENSION)) {
        final var walName = zipEntry.getName();
        final var segmentIndex =
            walName.lastIndexOf(
                '.',
                walName.length() - CASDiskWriteAheadLog.WAL_SEGMENT_EXTENSION.length() - 1);
        final var storageName = getName();

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

        final var pageIndex = LongSerializer.INSTANCE.deserializeNative(data, 0);

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
  public AbsoluteChange getLinkBagCounter(DatabaseSessionInternal session, RecordId identity,
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
      Locale serverLocale,
      ContextConfiguration contextConfiguration,
      String charset,
      Locale locale) {

  }

  private record BackupMetadata(int backupFormatVersion,
                                UUID databaseId,
                                int sequenceNumber,
                                LogSequenceNumber startLsn,
                                LogSequenceNumber endLsn) {

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
}
