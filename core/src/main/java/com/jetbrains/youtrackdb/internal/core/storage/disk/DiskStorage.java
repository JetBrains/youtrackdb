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

import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.MASTER_RECORD_EXTENSION;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.WAL_SEGMENT_EXTENSION;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.exception.ErrorCode;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.util.CallableFunction;
import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.compression.impl.ZIPCompressionUtil;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.engine.local.EngineLocalPaginated;
import com.jetbrains.youtrackdb.internal.core.exception.BackupInProgressException;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidInstanceIdException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidStorageEncryptionKeyException;
import com.jetbrains.youtrackdb.internal.core.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
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
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.EnterpriseStorageOperationListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageStartupMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MetaDataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbsoluteChange;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import net.jpountz.xxhash.XXHashFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @since 28.03.13
 */
public class DiskStorage extends AbstractStorage {

  private static final Logger logger = LoggerFactory.getLogger(DiskStorage.class);

  private static final String INCREMENTAL_BACKUP_LOCK = "backup.ibl";

  private static final String ALGORITHM_NAME = "AES";
  private static final String TRANSFORMATION = "AES/CTR/NoPadding";

  private static final ThreadLocal<Cipher> CIPHER =
      ThreadLocal.withInitial(DiskStorage::getCipherInstance);

  private static final String IBU_EXTENSION_V3 = ".ibu3";
  private static final int INCREMENTAL_BACKUP_VERSION = 423;
  private static final String CONF_ENTRY_NAME = "database.ocf";
  private static final String INCREMENTAL_BACKUP_DATEFORMAT = "yyyy-MM-dd-HH-mm-ss";
  private static final String CONF_UTF_8_ENTRY_NAME = "database_utf8.ocf";

  private static final String ENCRYPTION_IV = "encryption.iv";

  private final List<EnterpriseStorageOperationListener> listeners = new CopyOnWriteArrayList<>();

  @SuppressWarnings("WeakerAccess")
  protected static final long IV_SEED = 234120934;

  private static final String IV_EXT = ".iv";

  @SuppressWarnings("WeakerAccess")
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

        startupMetadata.setTxMetadata(getLastMetadata().orElse(null));
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
                SystemVariableResolver.resolveSystemVariables(url));
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
      atomicOperationsManager.executeInsideAtomicOperation(null, this::generateDatabaseInstanceId);
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

    return new StartupMetadata(startupMetadata.getLastTxId(), startupMetadata.getTxMetadata());
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
        startupMetadata.setTxMetadata(getLastMetadata().orElse(null));

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
  protected boolean isWriteAllowedDuringIncrementalBackup() {
    return true;
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
  public String incrementalBackup(DatabaseSessionInternal session, final String backupDirectory,
      CallableFunction<Void, Void> started) {
    return incrementalBackup(new java.io.File(backupDirectory), started);
  }

  @Override
  public void fullIncrementalBackup(final OutputStream stream) {
    try {
      incrementalBackup(stream, null, false);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during incremental backup"), e, name);
    }
  }

  @SuppressWarnings("unused")
  public boolean isLastBackupCompatibleWithUUID(final java.io.File backupDirectory)
      throws IOException {
    if (!backupDirectory.exists()) {
      return true;
    }

    final var fileLockPath = backupDirectory.toPath().resolve(INCREMENTAL_BACKUP_LOCK);
    try (var lockChannel =
        FileChannel.open(fileLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      try (final var ignored = lockChannel.lock()) {
        final var files = fetchIBUFiles(backupDirectory);
        if (files.length > 0) {
          var backupUUID =
              extractDbInstanceUUID(backupDirectory, files[0], configuration.getCharset());
          try {
            checkDatabaseInstanceId(backupUUID);
          } catch (InvalidInstanceIdException ex) {
            return false;
          }
        }
      } catch (final OverlappingFileLockException e) {
        LogManager.instance()
            .error(
                this,
                "Another incremental backup process is in progress, please wait till it will be"
                    + " finished",
                null);
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name, "Error during incremental backup"),
            e, name);
      }

      try {
        Files.deleteIfExists(fileLockPath);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new StorageException(name, "Error during incremental backup"),
            e, name);
      }
    }
    return true;
  }

  private String incrementalBackup(
      final java.io.File backupDirectory, final CallableFunction<Void, Void> started) {
    var fileName = "";

    if (!backupDirectory.exists()) {
      if (!backupDirectory.mkdirs()) {
        throw new StorageException(name,
            "Backup directory "
                + backupDirectory.getAbsolutePath()
                + " does not exist and can not be created");
      }
    }
    checkNoBackupInStorageDir(backupDirectory);

    final var fileLockPath = backupDirectory.toPath().resolve(INCREMENTAL_BACKUP_LOCK);
    try (final var lockChannel =
        FileChannel.open(fileLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      try (@SuppressWarnings("unused") final var fileLock = lockChannel.lock()) {
        RandomAccessFile rndIBUFile = null;
        try {
          final var files = fetchIBUFiles(backupDirectory);

          final LogSequenceNumber lastLsn;
          long nextIndex;
          final UUID backupUUID;

          if (files.length == 0) {
            lastLsn = null;
            nextIndex = 0;
          } else {
            lastLsn = extractIBULsn(backupDirectory, files[files.length - 1]);
            nextIndex = extractIndexFromIBUFile(backupDirectory, files[files.length - 1]) + 1;
            backupUUID =
                extractDbInstanceUUID(backupDirectory, files[0], configuration.getCharset());
            checkDatabaseInstanceId(backupUUID);
          }

          final var dateFormat = new SimpleDateFormat(INCREMENTAL_BACKUP_DATEFORMAT);
          if (lastLsn != null) {
            fileName =
                getName()
                    + "_"
                    + dateFormat.format(new Date())
                    + "_"
                    + nextIndex
                    + IBU_EXTENSION_V3;
          } else {
            fileName =
                getName()
                    + "_"
                    + dateFormat.format(new Date())
                    + "_"
                    + nextIndex
                    + "_full"
                    + IBU_EXTENSION_V3;
          }

          final var ibuFile = new java.io.File(backupDirectory, fileName);

          if (started != null) {
            started.call(null);
          }
          rndIBUFile = new RandomAccessFile(ibuFile, "rw");
          try {
            final var ibuChannel = rndIBUFile.getChannel();

            final var versionBuffer = ByteBuffer.allocate(IntegerSerializer.INT_SIZE);
            versionBuffer.putInt(INCREMENTAL_BACKUP_VERSION);
            versionBuffer.rewind();

            IOUtils.writeByteBuffer(versionBuffer, ibuChannel, 0);

            ibuChannel.position(
                2 * IntegerSerializer.INT_SIZE
                    + 2 * LongSerializer.LONG_SIZE
                    + ByteSerializer.BYTE_SIZE);

            LogSequenceNumber maxLsn;
            try (var stream = Channels.newOutputStream(ibuChannel)) {
              maxLsn = incrementalBackup(stream, lastLsn, true);
              final var dataBuffer =
                  ByteBuffer.allocate(
                      IntegerSerializer.INT_SIZE
                          + 2 * LongSerializer.LONG_SIZE
                          + ByteSerializer.BYTE_SIZE);

              dataBuffer.putLong(nextIndex);
              dataBuffer.putLong(maxLsn.getSegment());
              dataBuffer.putInt(maxLsn.getPosition());

              if (lastLsn == null) {
                dataBuffer.put((byte) 1);
              } else {
                dataBuffer.put((byte) 0);
              }

              dataBuffer.rewind();

              ibuChannel.write(dataBuffer);
              IOUtils.writeByteBuffer(dataBuffer, ibuChannel, IntegerSerializer.INT_SIZE);
            }
          } catch (RuntimeException e) {
            rndIBUFile.close();

            if (!ibuFile.delete()) {
              LogManager.instance()
                  .error(
                      this, ibuFile.getAbsolutePath() + " is closed but can not be deleted", null);
            }

            throw e;
          }
        } catch (IOException e) {
          throw BaseException.wrapException(
              new StorageException(name, "Error during incremental backup"), e, name);
        } finally {
          try {
            if (rndIBUFile != null) {
              rndIBUFile.close();
            }
          } catch (IOException e) {
            LogManager.instance().error(this, "Can not close %s file", e, fileName);
          }
        }
      }
    } catch (final OverlappingFileLockException e) {
      LogManager.instance()
          .error(
              this,
              "Another incremental backup process is in progress, please wait till it will be"
                  + " finished",
              null);
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during incremental backup"),
          e, name);
    }

    try {
      Files.deleteIfExists(fileLockPath);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during incremental backup"),
          e, name);
    }

    return fileName;
  }

  @Nullable
  private UUID extractDbInstanceUUID(java.io.File backupDirectory, String file, String charset)
      throws IOException {
    final var ibuFile = new java.io.File(backupDirectory, file);
    final RandomAccessFile rndIBUFile;
    try {
      rndIBUFile = new RandomAccessFile(ibuFile, "r");
    } catch (FileNotFoundException e) {
      throw BaseException.wrapException(new StorageException(name, "Backup file was not found"), e,
          name);
    }

    try {
      final var ibuChannel = rndIBUFile.getChannel();
      ibuChannel.position(3 * LongSerializer.LONG_SIZE + 1);

      final var inputStream = Channels.newInputStream(ibuChannel);
      final var bufferedInputStream = new BufferedInputStream(inputStream);
      final var zipInputStream =
          new ZipInputStream(bufferedInputStream, Charset.forName(charset));

      ZipEntry zipEntry;
      while ((zipEntry = zipInputStream.getNextEntry()) != null) {
        if (zipEntry.getName().equals("database_instance.uuid")) {
          var dis = new DataInputStream(zipInputStream);
          return UUID.fromString(dis.readUTF());
        }
      }
    } finally {
      rndIBUFile.close();
    }
    return null;
  }

  private void checkNoBackupInStorageDir(final java.io.File backupDirectory) {
    if (storagePath == null || backupDirectory == null) {
      return;
    }

    var invalid = false;
    final var storageDir = storagePath.toFile();
    if (backupDirectory.equals(storageDir)) {
      invalid = true;
    }

    if (invalid) {
      throw new StorageException(name, "Backup cannot be performed in the storage path");
    }
  }

  @SuppressWarnings("unused")
  public void registerStorageListener(EnterpriseStorageOperationListener listener) {
    this.listeners.add(listener);
  }

  @SuppressWarnings("unused")
  public void unRegisterStorageListener(EnterpriseStorageOperationListener listener) {
    this.listeners.remove(listener);
  }

  private String[] fetchIBUFiles(final java.io.File backupDirectory) throws IOException {
    final var files =
        backupDirectory.list(
            (dir, name) ->
                new java.io.File(dir, name).length() > 0 && name.toLowerCase()
                    .endsWith(IBU_EXTENSION_V3));

    if (files == null) {
      throw new StorageException(name,
          "Can not read list of backup files from directory " + backupDirectory.getAbsolutePath());
    }

    final List<Pair<Long, String>> indexedFiles = new ArrayList<>(files.length);

    for (var file : files) {
      final var fileIndex = extractIndexFromIBUFile(backupDirectory, file);
      indexedFiles.add(new Pair<>(fileIndex, file));
    }

    Collections.sort(indexedFiles);

    final var sortedFiles = new String[files.length];

    var index = 0;
    for (var indexedFile : indexedFiles) {
      sortedFiles[index] = indexedFile.getValue();
      index++;
    }

    return sortedFiles;
  }

  private LogSequenceNumber extractIBULsn(java.io.File backupDirectory, String file) {
    final var ibuFile = new java.io.File(backupDirectory, file);
    final RandomAccessFile rndIBUFile;
    try {
      rndIBUFile = new RandomAccessFile(ibuFile, "r");
    } catch (FileNotFoundException e) {
      throw BaseException.wrapException(new StorageException(name, "Backup file was not found"), e,
          name);
    }

    try {
      try {
        final var ibuChannel = rndIBUFile.getChannel();
        ibuChannel.position(IntegerSerializer.INT_SIZE + LongSerializer.LONG_SIZE);

        var lsnData =
            ByteBuffer.allocate(IntegerSerializer.INT_SIZE + LongSerializer.LONG_SIZE);
        ibuChannel.read(lsnData);
        lsnData.rewind();

        final var segment = lsnData.getLong();
        final var position = lsnData.getInt();

        return new LogSequenceNumber(segment, position);
      } finally {
        rndIBUFile.close();
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during read of backup file"),
          e, name);
    } finally {
      try {
        rndIBUFile.close();
      } catch (IOException e) {
        LogManager.instance().error(this, "Error during read of backup file", e);
      }
    }
  }

  private static long extractIndexFromIBUFile(final java.io.File backupDirectory,
      final String fileName)
      throws IOException {
    final var file = new java.io.File(backupDirectory, fileName);

    try (final var rndFile = new RandomAccessFile(file, "r")) {
      rndFile.seek(IntegerSerializer.INT_SIZE);
      return validateLongIndex(rndFile.readLong());
    }
  }

  private static long validateLongIndex(final long index) {
    return index < 0 ? 0 : Math.abs(index);
  }

  private LogSequenceNumber incrementalBackup(
      final OutputStream stream, final LogSequenceNumber fromLsn, final boolean singleThread)
      throws IOException {
    LogSequenceNumber lastLsn;

    checkOpennessAndMigration();

    if (singleThread && isIncrementalBackupRunning()) {
      throw new BackupInProgressException(name,
          "You are trying to start incremental backup but it is in progress now, please wait till"
              + " it will be finished",
          getName(), ErrorCode.BACKUP_IN_PROGRESS);
    }
    startBackup();
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();
      final long freezeId;

      if (!isWriteAllowedDuringIncrementalBackup()) {
        freezeId =
            atomicOperationsManager.freezeAtomicOperations(
                () -> new ModificationOperationProhibitedException(
                    name, "Incremental backup in progress")
            );
      } else {
        freezeId = -1;
      }

      try {
        final var zipOutputStream =
            new ZipOutputStream(
                new BufferedOutputStream(stream), Charset.forName(configuration.getCharset()));
        try {
          final long startSegment;
          final LogSequenceNumber freezeLsn;

          if (fromLsn == null) {
            var databaseInstanceUUID = super.readDatabaseInstanceId();
            if (databaseInstanceUUID == null) {
              atomicOperationsManager.executeInsideAtomicOperation(
                  null, this::generateDatabaseInstanceId);
              databaseInstanceUUID = super.readDatabaseInstanceId();
            }
            final var zipEntry = new ZipEntry("database_instance.uuid");

            zipOutputStream.putNextEntry(zipEntry);
            var dos = new DataOutputStream(zipOutputStream);
            dos.writeUTF(databaseInstanceUUID.toString());
            dos.flush();
          }

          final var newSegmentFreezeId =
              atomicOperationsManager.freezeAtomicOperations(null);
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

            getLastMetadata()
                .ifPresent(
                    metadata -> {
                      try {
                        writeAheadLog.log(new MetaDataRecord(metadata));
                      } catch (final IOException e) {
                        throw new IllegalStateException("Error during write of metadata", e);
                      }
                    });
          } finally {
            atomicOperationsManager.releaseAtomicOperations(newSegmentFreezeId);
          }

          try {
            backupIv(zipOutputStream);

            final var encryptionIv = new byte[16];
            final var secureRandom = new SecureRandom();
            secureRandom.nextBytes(encryptionIv);

            backupEncryptedIv(zipOutputStream, encryptionIv);

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

            lastLsn = backupPagesWithChanges(fromLsn, zipOutputStream, encryptionIv, aesKey);
            final var lastWALLsn =
                copyWALToIncrementalBackup(zipOutputStream, startSegment);

            if (lastWALLsn != null && (lastLsn == null || lastWALLsn.compareTo(lastLsn) > 0)) {
              lastLsn = lastWALLsn;
            }
          } finally {
            writeAheadLog.removeCutTillLimit(freezeLsn);
          }
        } finally {
          try {
            zipOutputStream.finish();
            zipOutputStream.flush();
          } catch (IOException e) {
            LogManager.instance().warn(this, "Failed to flush resource " + zipOutputStream);
          }
        }
      } finally {
        if (!isWriteAllowedDuringIncrementalBackup()) {
          atomicOperationsManager.releaseAtomicOperations(freezeId);
        }
      }
    } finally {
      stateLock.readLock().unlock();
      endBackup();
    }

    return lastLsn;
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
              backUpPage, LongSerializer.LONG_SIZE, backUpPage.length - LongSerializer.LONG_SIZE);
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

  private static void backupEncryptedIv(final ZipOutputStream zipOutputStream,
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

      var fileHandler = entry.getValue();
      final var filledUpTo = writeCache.getFilledUpTo(fileHandler.fileId());
      final var zipEntry = new ZipEntry(fileName);

      stream.putNextEntry(zipEntry);

      final var binaryFileId = new byte[LongSerializer.LONG_SIZE];
      final var fileId = writeCache.externalFileId(writeCache.internalFileId(fileHandler.fileId()));
      LongSerializer.serializeLiteral(fileId, binaryFileId, 0);
      stream.write(binaryFileId, 0, binaryFileId.length);

      for (var pageIndex = 0; pageIndex < filledUpTo; pageIndex++) {
        final var fileHandler = readCache.loadFileHandler(fileId);
        final var cacheEntry =
            readCache.silentLoadForRead(fileHandler, pageIndex, writeCache, true);
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
            DurablePage.getPageData(cachePointerBuffer, data, LongSerializer.LONG_SIZE, pageSize);

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
    restoreFromIncrementalBackup(new java.io.File(filePath));
  }

  @Override
  public void restoreFullIncrementalBackup(DatabaseSessionInternal session,
      final InputStream stream)
      throws UnsupportedOperationException {
    stateLock.writeLock().lock();
    try {
      final var aesKeyEncoded =
          getConfiguration()
              .getContextConfiguration()
              .getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY);
      final var aesKey =
          aesKeyEncoded == null ? null : Base64.getDecoder().decode(aesKeyEncoded);

      if (aesKey != null && aesKey.length != 16 && aesKey.length != 24 && aesKey.length != 32) {
        throw new InvalidStorageEncryptionKeyException(name,
            "Invalid length of the encryption key, provided size is " + aesKey.length);
      }

      var result = preprocessingIncrementalRestore();
      restoreFromIncrementalBackup(
          result.charset,
          result.serverLocale,
          result.locale,
          result.contextConfiguration,
          aesKey,
          stream,
          true);

      postProcessIncrementalRestore(result.contextConfiguration);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during restore from incremental backup"), e, name);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private IncrementalRestorePreprocessingResult preprocessingIncrementalRestore()
      throws IOException {
    final var serverLocale = configuration.getLocaleInstance();
    final var contextConfiguration = configuration.getContextConfiguration();
    final var charset = configuration.getCharset();
    final var locale = configuration.getLocaleInstance();

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation -> {
          closeCollections();
          closeIndexes(atomicOperation);
          ((CollectionBasedStorageConfiguration) configuration).close(atomicOperation);
        });

    configuration = null;

    return new IncrementalRestorePreprocessingResult(
        serverLocale, contextConfiguration, charset, locale);
  }

  private void restoreFromIncrementalBackup(final java.io.File backupDirectory) {
    if (!backupDirectory.exists()) {
      throw new StorageException(name,
          "Directory which should contain incremental backup files (files with extension '"
              + IBU_EXTENSION_V3
              + "') is absent. It should be located at '"
              + backupDirectory.getAbsolutePath()
              + "'");
    }

    try {
      final var files = fetchIBUFiles(backupDirectory);
      if (files.length == 0) {
        throw new StorageException(name,
            "Cannot find incremental backup files (files with extension '"
                + IBU_EXTENSION_V3
                + "') in directory '"
                + backupDirectory.getAbsolutePath()
                + "'");
      }

      stateLock.writeLock().lock();
      try {

        final var aesKeyEncoded =
            getConfiguration()
                .getContextConfiguration()
                .getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY);
        final var aesKey =
            aesKeyEncoded == null ? null : Base64.getDecoder().decode(aesKeyEncoded);

        if (aesKey != null && aesKey.length != 16 && aesKey.length != 24 && aesKey.length != 32) {
          throw new InvalidStorageEncryptionKeyException(name,
              "Invalid length of the encryption key, provided size is " + aesKey.length);
        }

        var result = preprocessingIncrementalRestore();
        var restoreUUID = extractDbInstanceUUID(backupDirectory, files[0], result.charset);

        for (var file : files) {
          var fileUUID = extractDbInstanceUUID(backupDirectory, files[0], result.charset);
          if ((restoreUUID == null && fileUUID == null)
              || (restoreUUID != null && restoreUUID.equals(fileUUID))) {
            final var ibuFile = new java.io.File(backupDirectory, file);

            var rndIBUFile = new RandomAccessFile(ibuFile, "rw");
            try {
              final var ibuChannel = rndIBUFile.getChannel();
              final var versionBuffer = ByteBuffer.allocate(IntegerSerializer.INT_SIZE);
              IOUtils.readByteBuffer(versionBuffer, ibuChannel);
              versionBuffer.rewind();

              final var backupVersion = versionBuffer.getInt();
              if (backupVersion != INCREMENTAL_BACKUP_VERSION) {
                throw new StorageException(name,
                    "Invalid version of incremental backup version was provided. Expected "
                        + INCREMENTAL_BACKUP_VERSION
                        + " , provided "
                        + backupVersion);
              }

              ibuChannel.position(2 * IntegerSerializer.INT_SIZE + 2 * LongSerializer.LONG_SIZE);
              final var buffer = ByteBuffer.allocate(1);
              ibuChannel.read(buffer);
              buffer.rewind();

              final var fullBackup = buffer.get() == 1;

              try (final var inputStream = Channels.newInputStream(ibuChannel)) {
                restoreFromIncrementalBackup(
                    result.charset,
                    result.serverLocale,
                    result.locale,
                    result.contextConfiguration,
                    aesKey,
                    inputStream,
                    fullBackup);
              }
            } finally {
              try {
                rndIBUFile.close();
              } catch (IOException e) {
                LogManager.instance().warn(this, "Failed to close resource " + rndIBUFile);
              }
            }
          } else {
            LogManager.instance()
                .warn(
                    this,
                    "Skipped file '"
                        + file
                        + "' is not a backup of the same database of previous backups");
          }

          postProcessIncrementalRestore(result.contextConfiguration);
        }
      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during restore from incremental backup"), e, name);
    }
  }

  private void postProcessIncrementalRestore(ContextConfiguration contextConfiguration)
      throws IOException {
    if (CollectionBasedStorageConfiguration.exists(writeCache)) {
      configuration = new CollectionBasedStorageConfiguration(this);
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              ((CollectionBasedStorageConfiguration) configuration)
                  .load(contextConfiguration, atomicOperation));
    } else {
      configuration = new CollectionBasedStorageConfiguration(this);
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              ((CollectionBasedStorageConfiguration) configuration)
                  .load(contextConfiguration, atomicOperation));
    }

    atomicOperationsManager.executeInsideAtomicOperation(null, this::openCollections);
    linkCollectionsBTreeManager.close();
    linkCollectionsBTreeManager.load();
    openIndexes();

    flushAllData();

    atomicOperationsManager.executeInsideAtomicOperation(null, this::generateDatabaseInstanceId);
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
        final var fileId = writeCache.fileHandlerByName(entry.getKey()).fileId();

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
                '.', walName.length() - CASDiskWriteAheadLog.WAL_SEGMENT_EXTENSION.length() - 1);
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
      final FileHandler fileHandler;
      if (!writeCache.exists(fileName)) {
        fileHandler = readCache.addFile(fileName, expectedFileId, writeCache);
      } else {
        fileHandler = writeCache.fileHandlerByName(fileName);
      }

      if (!writeCache.fileIdsAreEqual(expectedFileId, fileHandler.fileId())) {
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

        var cacheEntry = readCache.loadForWrite(fileHandler, pageIndex, writeCache, true, null);

        if (cacheEntry == null) {
          do {
            if (cacheEntry != null) {
              readCache.releaseFromWrite(cacheEntry, writeCache, true);
            }

            cacheEntry = readCache.allocateNewPage(fileHandler, writeCache, null);
          } while (cacheEntry.getPageIndex() != pageIndex);
        }

        try {
          final var buffer = cacheEntry.getCachePointer().getBuffer();
          assert buffer != null;
          final var backedUpPageLsn =
              DurablePage.getLogSequenceNumber(LongSerializer.LONG_SIZE, data);
          if (isFull) {
            buffer.put(0, data, LongSerializer.LONG_SIZE, data.length - LongSerializer.LONG_SIZE);

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
        final var fileHandler = writeCache.fileHandlerByName(file);
        readCache.deleteFile(fileHandler.fileId(), writeCache);
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
  public @Nonnull RawBuffer readRecord(RecordIdInternal iRid) {
    try {
      return super.readRecord(iRid);
    } finally {
      listeners.forEach(EnterpriseStorageOperationListener::onRead);
    }
  }

  @Override
  public int getAbsoluteLinkBagCounter(RID ownerId, String fieldName, RID key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public AbsoluteChange getLinkBagCounter(DatabaseSessionInternal session,
      RecordIdInternal identity,
      String fieldName, RID rid) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<RecordOperation> commit(FrontendTransactionImpl frontendTransaction,
      boolean allocated) {
    var operations = super.commit(frontendTransaction, allocated);
    listeners.forEach((l) -> l.onCommit(operations));
    return operations;
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

  private void waitBackup() {
    backupLock.lock();
    try {
      while (isIncrementalBackupRunning()) {
        try {
          backupIsDone.await();
        } catch (java.lang.InterruptedException e) {
          throw BaseException.wrapException(
              new ThreadInterruptedException("Interrupted wait for backup to finish"), e, name);
        }
      }
    } finally {
      backupLock.unlock();
    }
  }

  @Override
  protected void checkBackupRunning() {
    waitBackup();
  }

  private record IncrementalRestorePreprocessingResult(
      Locale serverLocale,
      ContextConfiguration contextConfiguration,
      String charset,
      Locale locale) {

  }
}
