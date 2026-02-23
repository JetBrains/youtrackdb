package com.jetbrains.youtrackdb.internal.core.storage.index.hashindex.local.cache;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.AsyncFile;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AbstractWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


public class WOWCacheTestIT {

  private static final int pageSize = DurablePage.NEXT_FREE_POSITION + 8;

  private static String fileName;

  private static CASDiskWriteAheadLog writeAheadLog;
  private static final ByteBufferPool bufferPool = new ByteBufferPool(pageSize);
  private static Path storagePath;
  private static WOWCache wowCache;
  private static String storageName;

  private final ClosableLinkedContainer<Long, File> files = new ClosableLinkedContainer<>(1024);

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    var buildDirectory = System.getProperty("buildDirectory", ".");

    fileName = "wowCacheTest.tst";
    storageName = "WOWCacheTest";
    storagePath = Paths.get(buildDirectory).resolve(storageName);

    WALRecordsFactory.INSTANCE.registerNewRecord((byte) 128, TestRecord.class);
  }

  @Before
  public void beforeMethod() throws Exception {
    deleteCacheAndDeleteFile();

    initBuffer();
  }

  private static void deleteCacheAndDeleteFile() throws IOException {
    String nativeFileName = null;

    if (wowCache != null) {
      var fileId = wowCache.fileIdByName(fileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);

      wowCache.delete();
      wowCache = null;
    }

    if (writeAheadLog != null) {
      writeAheadLog.delete();
      writeAheadLog = null;
    }

    if (nativeFileName != null) {
      var testFile = storagePath.resolve(nativeFileName).toFile();

      if (testFile.exists()) {
        Assert.assertTrue(testFile.delete());
      }
    }

    var nameIdMapFile = storagePath.resolve("name_id_map.cm").toFile();
    if (nameIdMapFile.exists()) {
      Assert.assertTrue(nameIdMapFile.delete());
    }

    nameIdMapFile = storagePath.resolve("name_id_map_v2.cm").toFile();
    if (nameIdMapFile.exists()) {
      Assert.assertTrue(nameIdMapFile.delete());
    }
  }

  @AfterClass
  public static void afterClass() throws IOException {
    deleteCacheAndDeleteFile();

    Files.deleteIfExists(storagePath);
    bufferPool.clear();

    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(true);
    GlobalConfiguration.FILE_LOCK.setValue(true);
  }

  private void initBuffer() throws IOException, InterruptedException {
    Files.createDirectories(storagePath);

    writeAheadLog =
        new CASDiskWriteAheadLog(
            storageName,
            storagePath,
            storagePath,
            ContextConfiguration.WAL_DEFAULT_NAME,
            12_000,
            128,
            null,
            null,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            25,
            true,
            Locale.US,
            -1,
            1000,
            false,
            false,
            true,
            10);
    wowCache =
        new WOWCache(
            pageSize,
            false,
            bufferPool,
            writeAheadLog,
            new DoubleWriteLogNoOP(),
            10,
            10,
            100,
            storagePath,
            storageName,
            files,
            1,
            ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
            ChecksumMode.StoreAndVerify,
            null,
            null,
            false,
            Executors.newCachedThreadPool());

    wowCache.loadRegisteredFiles();
  }

  @Test
  public void testLoadStore() throws IOException {
    var random = new Random();

    var pageData = new byte[200][];
    var fileId = wowCache.addFile(fileName);
    final var nativeFileName = wowCache.nativeFileNameById(fileId);

    for (var i = 0; i < pageData.length; i++) {
      var data = new byte[8];
      random.nextBytes(data);

      pageData[i] = data;

      final var pageIndex = wowCache.allocateNewPage(fileId);
      Assert.assertEquals(i, pageIndex);
      final var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), false);
      cachePointer.acquireExclusiveLock();

      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      buffer.put(DurablePage.NEXT_FREE_POSITION, data);
      DurablePage.setLogSequenceNumberForPage(buffer, new LogSequenceNumber(0, 0));

      cachePointer.releaseExclusiveLock();

      wowCache.store(fileId, i, cachePointer);
      cachePointer.decrementReadersReferrer();
    }

    for (var i = 0; i < pageData.length; i++) {
      var dataOne = pageData[i];

      var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), true);
      var dataTwo = new byte[8];
      var buffer = cachePointer.getBuffer();
      assert buffer != null;
      buffer.get(DurablePage.NEXT_FREE_POSITION, dataTwo);
      cachePointer.decrementReadersReferrer();

      Assert.assertArrayEquals(dataTwo, dataOne);
    }

    wowCache.flush();

    for (var i = 0; i < pageData.length; i++) {
      var dataContent = pageData[i];
      assertFile(i, dataContent, new LogSequenceNumber(0, 0), nativeFileName);
    }
  }

  @Test
  public void testLoadStoreEncrypted() throws Exception {
    deleteCacheAndDeleteFile();

    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    Files.createDirectories(storagePath);

    writeAheadLog =
        new CASDiskWriteAheadLog(
            storageName,
            storagePath,
            storagePath,
            ContextConfiguration.WAL_DEFAULT_NAME,
            12_000,
            128,
            aesKey,
            iv,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            25,
            true,
            Locale.US,
            -1,
            1000,
            false,
            false,
            true,
            10);
    wowCache =
        new WOWCache(
            pageSize,
            false,
            bufferPool,
            writeAheadLog,
            new DoubleWriteLogNoOP(),
            10,
            10,
            100,
            storagePath,
            storageName,

            files,
            1,
            ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
            ChecksumMode.StoreAndVerify,
            iv,
            aesKey,
            false,
            Executors.newCachedThreadPool());

    wowCache.loadRegisteredFiles();

    var random = new Random();

    var pageData = new byte[200][];
    var fileId = wowCache.addFile(fileName);
    final var nativeFileName = wowCache.nativeFileNameById(fileId);

    for (var i = 0; i < pageData.length; i++) {
      var data = new byte[8];
      random.nextBytes(data);

      pageData[i] = data;

      final var pageIndex = wowCache.allocateNewPage(fileId);
      Assert.assertEquals(i, pageIndex);
      final var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), false);
      cachePointer.acquireExclusiveLock();

      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      buffer.put(DurablePage.NEXT_FREE_POSITION, data);
      cachePointer.releaseExclusiveLock();

      wowCache.store(fileId, i, cachePointer);
      cachePointer.decrementReadersReferrer();
    }

    for (var i = 0; i < pageData.length; i++) {
      var dataOne = pageData[i];

      var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), true);
      var dataTwo = new byte[8];
      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      buffer.get(DurablePage.NEXT_FREE_POSITION, dataTwo);
      cachePointer.decrementReadersReferrer();

      Assert.assertArrayEquals(dataTwo, dataOne);
    }

    wowCache.flush();

    for (var i = 0; i < pageData.length; i++) {
      var dataOne = pageData[i];

      var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), true);
      var dataTwo = new byte[8];
      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      buffer.get(DurablePage.NEXT_FREE_POSITION, dataTwo);
      cachePointer.decrementReadersReferrer();

      Assert.assertArrayEquals(dataTwo, dataOne);
    }

    for (var i = 0; i < pageData.length; i++) {
      var dataContent = pageData[i];
      assertFileEncrypted(
          wowCache.internalFileId(fileId),
          i,
          dataContent,
          nativeFileName,
          aesKey,
          iv);
    }
  }

  @Test
  public void testDataUpdate() throws Exception {
    final NavigableMap<Long, byte[]> pageIndexDataMap = new TreeMap<>();
    var fileId = wowCache.addFile(fileName);
    final var nativeFileName = wowCache.nativeFileNameById(fileId);

    var random = new Random();

    for (var i = 0; i < 2048; i++) {
      wowCache.allocateNewPage(fileId);

      final var pointer = bufferPool.acquireDirect(true, Intention.TEST);
      final var cachePointer = new CachePointer(pointer, bufferPool, fileId, i);

      cachePointer.incrementReadersReferrer();
      wowCache.store(fileId, i, cachePointer);
      cachePointer.decrementReadersReferrer();
    }

    for (var i = 0; i < 600; i++) {
      long pageIndex = random.nextInt(2048);

      var data = new byte[8];
      random.nextBytes(data);

      pageIndexDataMap.put(pageIndex, data);

      final var cachePointer =
          wowCache.load(fileId, pageIndex, new ModifiableBoolean(), false);
      cachePointer.acquireExclusiveLock();
      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      Assert.assertEquals(
          new LogSequenceNumber(0, 0), DurablePage.getLogSequenceNumberFromPage(buffer));

      buffer.put(DurablePage.NEXT_FREE_POSITION, data);
      cachePointer.releaseExclusiveLock();

      wowCache.store(fileId, pageIndex, cachePointer);
      cachePointer.decrementReadersReferrer();
    }

    for (var entry : pageIndexDataMap.entrySet()) {
      long pageIndex = entry.getKey();
      var dataOne = entry.getValue();

      var cachePointer = wowCache.load(fileId, pageIndex, new ModifiableBoolean(), true);
      var dataTwo = new byte[8];
      var buffer = cachePointer.getBuffer();
      assert buffer != null;
      buffer.get(DurablePage.NEXT_FREE_POSITION, dataTwo);

      cachePointer.decrementReadersReferrer();
      Assert.assertArrayEquals(dataTwo, dataOne);
    }

    for (var i = 0; i < 300; i++) {
      long desiredIndex = random.nextInt(2048);

      var pageIndex = pageIndexDataMap.ceilingKey(desiredIndex);
      if (pageIndex == null) {
        pageIndex = pageIndexDataMap.floorKey(desiredIndex);
      }

      var data = new byte[8];
      random.nextBytes(data);
      pageIndexDataMap.put(pageIndex, data);

      final var cachePointer =
          wowCache.load(fileId, pageIndex, new ModifiableBoolean(), true);

      cachePointer.acquireExclusiveLock();
      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      buffer.put(DurablePage.NEXT_FREE_POSITION, data);
      cachePointer.releaseExclusiveLock();

      wowCache.store(fileId, pageIndex, cachePointer);
      cachePointer.decrementReadersReferrer();
    }

    for (var entry : pageIndexDataMap.entrySet()) {
      long pageIndex = entry.getKey();
      var dataOne = entry.getValue();
      var cachePointer = wowCache.load(fileId, pageIndex, new ModifiableBoolean(), true);
      var dataTwo = new byte[8];
      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      buffer.get(DurablePage.NEXT_FREE_POSITION, dataTwo);
      cachePointer.decrementReadersReferrer();

      Assert.assertArrayEquals(dataTwo, dataOne);
    }

    wowCache.flush();

    for (var entry : pageIndexDataMap.entrySet()) {
      assertFile(entry.getKey(), entry.getValue(), new LogSequenceNumber(0, 0), nativeFileName);
    }
  }

  @Test
  public void testDataUpdateEncrypted() throws Exception {
    deleteCacheAndDeleteFile();

    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    Files.createDirectories(storagePath);

    writeAheadLog =
        new CASDiskWriteAheadLog(
            storageName,
            storagePath,
            storagePath,
            ContextConfiguration.WAL_DEFAULT_NAME,
            12_000,
            128,
            aesKey,
            iv,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            25,
            true,
            Locale.US,
            -1,
            1000,
            false,
            false,
            true,
            10);
    wowCache =
        new WOWCache(
            pageSize,
            false,
            bufferPool,
            writeAheadLog,
            new DoubleWriteLogNoOP(),
            10,
            10,
            100,
            storagePath,
            storageName,
            files,
            1,
            ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
            ChecksumMode.StoreAndVerify,
            iv,
            aesKey,
            false,
            Executors.newCachedThreadPool());

    wowCache.loadRegisteredFiles();

    final NavigableMap<Long, byte[]> pageIndexDataMap = new TreeMap<>();
    var fileId = wowCache.addFile(fileName);
    final var nativeFileName = wowCache.nativeFileNameById(fileId);

    final var seed = System.nanoTime();
    System.out.println("testDataUpdateEncrypted : seed " + seed);
    var random = new Random(seed);

    for (var i = 0; i < 2048; i++) {
      final long position = wowCache.allocateNewPage(fileId);
      Assert.assertEquals(i, position);
    }

    for (var i = 0; i < 600; i++) {
      long pageIndex = random.nextInt(2048);

      var data = new byte[8];
      random.nextBytes(data);

      var verifyChecksums = pageIndexDataMap.containsKey(pageIndex);
      pageIndexDataMap.put(pageIndex, data);

      final var cachePointer =
          wowCache.load(fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
      var bufferDuplicate = cachePointer.getBuffer();
      assert bufferDuplicate != null;

      if (verifyChecksums) {
        Assert.assertEquals(
            new LogSequenceNumber(0, 0), DurablePage.getLogSequenceNumberFromPage(bufferDuplicate));
      }

      cachePointer.acquireExclusiveLock();
      var buffer = cachePointer.getBuffer();

      buffer.put(DurablePage.NEXT_FREE_POSITION, data);
      DurablePage.setLogSequenceNumberForPage(buffer, new LogSequenceNumber(0, 0));

      cachePointer.releaseExclusiveLock();

      wowCache.store(fileId, pageIndex, cachePointer);
      cachePointer.decrementReadersReferrer();
    }

    for (var entry : pageIndexDataMap.entrySet()) {
      long pageIndex = entry.getKey();
      var dataOne = entry.getValue();

      var cachePointer = wowCache.load(fileId, pageIndex, new ModifiableBoolean(), true);
      var dataTwo = new byte[8];
      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      buffer.get(DurablePage.NEXT_FREE_POSITION, dataTwo);

      cachePointer.decrementReadersReferrer();
      Assert.assertArrayEquals(dataTwo, dataOne);
    }

    for (var i = 0; i < 300; i++) {
      long desiredIndex = random.nextInt(2048);

      var pageIndex = pageIndexDataMap.ceilingKey(desiredIndex);
      if (pageIndex == null) {
        pageIndex = pageIndexDataMap.floorKey(desiredIndex);
      }

      var data = new byte[8];
      random.nextBytes(data);
      pageIndexDataMap.put(pageIndex, data);

      final var cachePointer =
          wowCache.load(fileId, pageIndex, new ModifiableBoolean(), true);

      cachePointer.acquireExclusiveLock();
      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      buffer.put(DurablePage.NEXT_FREE_POSITION, data);

      cachePointer.releaseExclusiveLock();

      wowCache.store(fileId, pageIndex, cachePointer);
      cachePointer.decrementReadersReferrer();
    }

    for (var entry : pageIndexDataMap.entrySet()) {
      long pageIndex = entry.getKey();
      var dataOne = entry.getValue();
      var cachePointer = wowCache.load(fileId, pageIndex, new ModifiableBoolean(), true);
      var dataTwo = new byte[8];
      var buffer = cachePointer.getBuffer();
      assert buffer != null;

      buffer.get(DurablePage.NEXT_FREE_POSITION, dataTwo);
      cachePointer.decrementReadersReferrer();

      Assert.assertArrayEquals(dataTwo, dataOne);
    }

    wowCache.flush();

    for (var entry : pageIndexDataMap.entrySet()) {
      assertFileEncrypted(
          wowCache.internalFileId(fileId),
          (int) entry.getKey().longValue(),
          entry.getValue(),
          nativeFileName,
          aesKey,
          iv);
    }
  }

  @Test
  public void testFileRestore() throws IOException {
    final var nonDelFileId = wowCache.addFile(fileName);
    final var fileId = wowCache.addFile("removedFile.del");

    final var removedNativeFileName = wowCache.nativeFileNameById(fileId);
    assert removedNativeFileName != null;

    wowCache.deleteFile(fileId);
    var deletedFile = storagePath.resolve(removedNativeFileName).toFile();
    Assert.assertFalse(deletedFile.exists());

    var fileName = wowCache.restoreFileById(fileId);
    Assert.assertEquals("removedFile.del", fileName);

    fileName = wowCache.restoreFileById(nonDelFileId);
    Assert.assertNull(fileName);
    Assert.assertTrue(deletedFile.exists());

    fileName = wowCache.restoreFileById(1525454L);
    Assert.assertNull(fileName);

    wowCache.deleteFile(fileId);
    Assert.assertFalse(deletedFile.exists());
  }

  @Test
  public void testFileRestoreAfterClose() throws Exception {
    final var nonDelFileId = wowCache.addFile(fileName);
    final var fileId = wowCache.addFile("removedFile.del");

    final var removedNativeFileName = wowCache.nativeFileNameById(fileId);
    assert removedNativeFileName != null;

    wowCache.deleteFile(fileId);
    var deletedFile = storagePath.resolve(removedNativeFileName).toFile();

    Assert.assertFalse(deletedFile.exists());

    wowCache.close();
    writeAheadLog.close();

    initBuffer();

    var fileName = wowCache.restoreFileById(fileId);
    Assert.assertEquals("removedFile.del", fileName);

    fileName = wowCache.restoreFileById(nonDelFileId);
    Assert.assertNull(fileName);
    Assert.assertTrue(deletedFile.exists());

    fileName = wowCache.restoreFileById(1525454L);
    Assert.assertNull(fileName);

    wowCache.deleteFile(fileId);
    Assert.assertFalse(deletedFile.exists());
  }

  @Test
  public void testChecksumFailure() throws IOException {
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);

    final var fileId = wowCache.addFile(fileName);
    Assert.assertEquals(0, wowCache.allocateNewPage(fileId));
    final var cachePointer = wowCache.load(fileId, 0, new ModifiableBoolean(), false);

    cachePointer.acquireExclusiveLock();
    final var buffer = cachePointer.getBuffer();
    assert buffer != null;

    buffer.put(DurablePage.NEXT_FREE_POSITION,
        new byte[buffer.remaining() - DurablePage.NEXT_FREE_POSITION]);
    cachePointer.releaseExclusiveLock();

    wowCache.store(fileId, 0, cachePointer);
    cachePointer.decrementReadersReferrer();

    wowCache.flush();

    var fileName = wowCache.nativeFileNameById(fileId);
    assert fileName != null;

    final var path = storagePath.resolve(fileName);
    final File file = new AsyncFile(path, pageSize, false, Executors.newCachedThreadPool(),
        storageName);
    file.open();
    file.write(
        DurablePage.NEXT_FREE_POSITION,
        ByteBuffer.wrap(new byte[]{1}).order(ByteOrder.nativeOrder()));
    file.close();

    try {
      wowCache.load(fileId, 0, new ModifiableBoolean(), true);
      Assert.fail();
    } catch (StorageException e) {
      // ok
    }
  }

  @Test
  public void testMagicFailure() throws IOException {
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);

    final var fileId = wowCache.addFile(fileName);
    Assert.assertEquals(0, wowCache.allocateNewPage(fileId));
    final var cachePointer = wowCache.load(fileId, 0, new ModifiableBoolean(), false);

    cachePointer.acquireExclusiveLock();
    final var buffer = cachePointer.getBuffer();
    assert buffer != null;

    buffer.put(DurablePage.NEXT_FREE_POSITION,
        new byte[buffer.remaining() - DurablePage.NEXT_FREE_POSITION]);
    cachePointer.releaseExclusiveLock();

    wowCache.store(fileId, 0, cachePointer);
    cachePointer.decrementReadersReferrer();

    wowCache.flush();

    var fileName = wowCache.nativeFileNameById(fileId);
    assert fileName != null;

    final var path = storagePath.resolve(fileName);
    final File file = new AsyncFile(path, pageSize, false, Executors.newCachedThreadPool(),
        storageName);
    file.open();
    file.write(0, ByteBuffer.wrap(new byte[]{1}).order(ByteOrder.nativeOrder()));
    file.close();

    try {
      wowCache.load(fileId, 0, new ModifiableBoolean(), true);
      Assert.fail();
    } catch (StorageException e) {
      // ok
    }
  }

  @Test
  public void testNoChecksumVerificationIfNotRequested() throws IOException {
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);

    final var fileId = wowCache.addFile(fileName);
    Assert.assertEquals(0, wowCache.allocateNewPage(fileId));
    final var cachePointer = wowCache.load(fileId, 0, new ModifiableBoolean(), false);

    cachePointer.acquireExclusiveLock();
    final var buffer = cachePointer.getBuffer();
    assert buffer != null;

    buffer.put(DurablePage.NEXT_FREE_POSITION,
        new byte[buffer.remaining() - DurablePage.NEXT_FREE_POSITION]);
    cachePointer.releaseExclusiveLock();

    wowCache.store(fileId, 0, cachePointer);
    cachePointer.decrementReadersReferrer();

    wowCache.flush();

    var fileName = wowCache.nativeFileNameById(fileId);
    assert fileName != null;

    final var path = storagePath.resolve(fileName);
    final File file = new AsyncFile(path, pageSize, false, Executors.newCachedThreadPool(),
        storageName);
    file.open();
    file.write(
        DurablePage.NEXT_FREE_POSITION,
        ByteBuffer.wrap(new byte[]{1}).order(ByteOrder.nativeOrder()));
    file.close();

    wowCache.load(fileId, 0, new ModifiableBoolean(), false).decrementReadersReferrer();
  }

  @Test
  public void testNoChecksumFailureIfVerificationTurnedOff() throws IOException {
    wowCache.setChecksumMode(ChecksumMode.Off);

    final var fileId = wowCache.addFile(fileName);
    Assert.assertEquals(0, wowCache.allocateNewPage(fileId));
    final var cachePointer = wowCache.load(fileId, 0, new ModifiableBoolean(), true);

    cachePointer.acquireExclusiveLock();
    final var buffer = cachePointer.getBuffer();
    assert buffer != null;

    buffer.put(DurablePage.NEXT_FREE_POSITION,
        new byte[buffer.remaining() - DurablePage.NEXT_FREE_POSITION]);
    cachePointer.releaseExclusiveLock();

    wowCache.store(fileId, 0, cachePointer);
    cachePointer.decrementReadersReferrer();

    wowCache.flush();

    var fileName = wowCache.nativeFileNameById(fileId);
    assert fileName != null;

    final var path = storagePath.resolve(fileName);
    final File file = new AsyncFile(path, pageSize, false, Executors.newCachedThreadPool(),
        storageName);
    file.open();
    file.write(
        DurablePage.NEXT_FREE_POSITION,
        ByteBuffer.wrap(new byte[]{1}).order(ByteOrder.nativeOrder()));
    file.close();

    wowCache.load(fileId, 0, new ModifiableBoolean(), true).decrementReadersReferrer();
  }

  @Test
  public void testNoChecksumFailureIfVerificationTurnedOffOnLoad() throws IOException {
    wowCache.setChecksumMode(ChecksumMode.Store);

    final var fileId = wowCache.addFile(fileName);
    Assert.assertEquals(0, wowCache.allocateNewPage(fileId));
    final var cachePointer = wowCache.load(fileId, 0, new ModifiableBoolean(), true);

    cachePointer.acquireExclusiveLock();
    final var buffer = cachePointer.getBuffer();
    assert buffer != null;

    buffer.put(DurablePage.NEXT_FREE_POSITION,
        new byte[buffer.remaining() - DurablePage.NEXT_FREE_POSITION]);
    cachePointer.releaseExclusiveLock();

    wowCache.store(fileId, 0, cachePointer);
    cachePointer.decrementReadersReferrer();

    wowCache.flush();

    var fileName = wowCache.nativeFileNameById(fileId);
    assert fileName != null;

    final var path = storagePath.resolve(fileName);
    final File file = new AsyncFile(path, pageSize, false, Executors.newCachedThreadPool(),
        storageName);
    file.open();
    file.write(
        DurablePage.NEXT_FREE_POSITION,
        ByteBuffer.wrap(new byte[]{1}).order(ByteOrder.nativeOrder()));
    file.close();

    wowCache.load(fileId, 0, new ModifiableBoolean(), true).decrementReadersReferrer();
  }

  @Test
  public void testNoChecksumFailureIfNoChecksumProvided() throws IOException {
    wowCache.setChecksumMode(ChecksumMode.Off);

    final var fileId = wowCache.addFile(fileName);
    Assert.assertEquals(0, wowCache.allocateNewPage(fileId));
    final var cachePointer = wowCache.load(fileId, 0, new ModifiableBoolean(), true);

    cachePointer.acquireExclusiveLock();
    final var buffer = cachePointer.getBuffer();
    assert buffer != null;

    buffer.put(DurablePage.NEXT_FREE_POSITION,
        new byte[buffer.remaining() - DurablePage.NEXT_FREE_POSITION]);
    cachePointer.releaseExclusiveLock();

    wowCache.store(fileId, 0, cachePointer);
    cachePointer.decrementReadersReferrer();

    wowCache.flush();

    var fileName = wowCache.nativeFileNameById(fileId);
    assert fileName != null;

    final var path = storagePath.resolve(fileName);
    final File file = new AsyncFile(path, pageSize, false, Executors.newCachedThreadPool(),
        storageName);
    file.open();
    file.write(
        DurablePage.NEXT_FREE_POSITION,
        ByteBuffer.wrap(new byte[]{1}).order(ByteOrder.nativeOrder()));
    file.close();

    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);
    wowCache.load(fileId, 0, new ModifiableBoolean(), true).decrementReadersReferrer();
  }

  private static void assertFile(
      long pageIndex, byte[] value, LogSequenceNumber lsn, String fileName) throws IOException {
    File fileClassic =
        new AsyncFile(
            storagePath.resolve(fileName), pageSize, false, Executors.newCachedThreadPool(),
            storageName);
    fileClassic.open();
    var content = new byte[8 + DurablePage.NEXT_FREE_POSITION];
    fileClassic.read(
        pageIndex * (8 + DurablePage.NEXT_FREE_POSITION),
        ByteBuffer.wrap(content).order(ByteOrder.nativeOrder()),
        true);

    Assert.assertArrayEquals(
        Arrays.copyOfRange(
            content, DurablePage.NEXT_FREE_POSITION, 8 + DurablePage.NEXT_FREE_POSITION),
        value);

    var magicNumber = LongSerializer.deserializeNative(content, 0);
    Assert.assertEquals(WOWCache.MAGIC_NUMBER_WITH_CHECKSUM, magicNumber);

    var readLsn =
        DurablePage.getLogSequenceNumberFromPage(
            ByteBuffer.wrap(content).order(ByteOrder.nativeOrder()));

    Assert.assertEquals(lsn, readLsn);

    fileClassic.close();
  }

  private static void assertFileEncrypted(
      int fileId,
      int pageIndex,
      byte[] value,
      String fileName,
      final byte[] aesKey,
      final byte[] iv)
      throws Exception {
    File fileClassic =
        new AsyncFile(
            storagePath.resolve(fileName), pageSize, false, Executors.newCachedThreadPool(),
            storageName);
    fileClassic.open();
    var content = new byte[8 + DurablePage.NEXT_FREE_POSITION];
    fileClassic.read(
        (long) pageIndex * (8 + DurablePage.NEXT_FREE_POSITION),
        ByteBuffer.wrap(content).order(ByteOrder.nativeOrder()),
        true);

    final var cipher = Cipher.getInstance("AES/CTR/NoPadding");

    final SecretKey secretKey = new SecretKeySpec(aesKey, "AES");

    final var magicNumber = LongSerializer.deserializeNative(content, 0);
    final var updateCounter = magicNumber >>> 8;

    final var updatedIv = new byte[iv.length];

    for (var i = 0; i < IntegerSerializer.INT_SIZE; i++) {
      updatedIv[i] = (byte) (iv[i] ^ ((pageIndex >>> i) & 0xFF));
    }

    for (var i = 0; i < IntegerSerializer.INT_SIZE; i++) {
      updatedIv[i + IntegerSerializer.INT_SIZE] =
          (byte) (iv[i + IntegerSerializer.INT_SIZE] ^ ((fileId >>> i) & 0xFF));
    }

    for (var i = 0; i < LongSerializer.LONG_SIZE - 1; i++) {
      updatedIv[i + 2 * IntegerSerializer.INT_SIZE] =
          (byte) (iv[i + 2 * IntegerSerializer.INT_SIZE] ^ ((updateCounter >>> i) & 0xFF));
    }

    updatedIv[updatedIv.length - 1] = iv[iv.length - 1];

    cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(updatedIv));
    System.arraycopy(
        cipher.doFinal(
            content, WOWCache.CHECKSUM_OFFSET, content.length - WOWCache.CHECKSUM_OFFSET),
        0,
        content,
        WOWCache.CHECKSUM_OFFSET,
        content.length - WOWCache.CHECKSUM_OFFSET);

    Assert.assertArrayEquals(
        Arrays.copyOfRange(
            content, DurablePage.NEXT_FREE_POSITION, 8 + DurablePage.NEXT_FREE_POSITION),
        value);

    Assert.assertEquals(WOWCache.MAGIC_NUMBER_WITH_CHECKSUM_ENCRYPTED, magicNumber & 0xFF);
    fileClassic.close();
  }

  public static final class TestRecord extends AbstractWALRecord {

    private byte[] data;

    @SuppressWarnings("unused")
    public TestRecord() {
    }

    @SuppressWarnings("unused")
    public TestRecord(byte[] data) {
      this.data = data;
    }

    @Override
    public int toStream(byte[] content, int offset) {
      IntegerSerializer.serializeNative(data.length, content, offset);
      offset += IntegerSerializer.INT_SIZE;

      System.arraycopy(data, 0, content, offset, data.length);
      offset += data.length;

      return offset;
    }

    @Override
    public void toStream(ByteBuffer buffer) {
      buffer.putInt(data.length);
      buffer.put(data);
    }

    @Override
    public int fromStream(byte[] content, int offset) {
      var len = IntegerSerializer.deserializeNative(content, offset);
      offset += IntegerSerializer.INT_SIZE;

      data = new byte[len];
      System.arraycopy(content, offset, data, 0, len);
      offset += len;

      return offset;
    }

    @Override
    public int serializedSize() {
      return data.length + IntegerSerializer.INT_SIZE;
    }

    @Override
    public int getId() {
      return (byte) 128;
    }
  }
}
