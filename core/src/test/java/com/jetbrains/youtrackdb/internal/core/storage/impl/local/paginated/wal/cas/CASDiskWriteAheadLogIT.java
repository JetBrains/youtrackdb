package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AbstractWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class CASDiskWriteAheadLogIT {

  private static Path testDirectory;

  @BeforeClass
  public static void beforeClass() {
    testDirectory =
        Paths.get(
            System.getProperty(
                "buildDirectory" + File.separator + "casWALTest",
                "." + File.separator + "target" + File.separator + "casWALTest"));

    WALRecordsFactory.INSTANCE.registerNewRecord(1024, TestRecord.class);
  }

  @Before
  public void before() {
    FileUtils.deleteRecursively(testDirectory.toFile());
  }

  @Test
  @Ignore
  public void testAddSingleOnePageRecord() throws Exception {
    final var iterations = 10;

    for (var i = 0; i < iterations; i++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        var walRecord = new TestRecord(random, wal.pageSize(), 1);
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        wal.flush();

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        records = wal.read(lsn, 10);
        Assert.assertEquals(2, records.size());
        readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());

        Assert.assertTrue(records.get(1) instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(1);

        //noinspection ConstantConditions
        if (i > 0 && i % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", i, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddSingleOnePageRecord : " + seed);
        throw e;
      }
    }
  }

  @Test
  @Ignore
  public void testAddSingleOnePageRecordEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 10;

    for (var i = 0; i < iterations; i++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        var walRecord = new TestRecord(random, wal.pageSize(), 1);
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        wal.flush();

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        records = wal.read(lsn, 10);
        Assert.assertEquals(2, records.size());
        readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());

        Assert.assertTrue(records.get(1) instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(1);

        // noinspection ConstantConditions
        if (i > 0 && i % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", i, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddSingleOnePageRecord : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddSingleOnePageRecordNonEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 10;

    for (var i = 0; i < iterations; i++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        var walRecord = new TestRecord(random, wal.pageSize(), 1);
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, walRecord.getLsn());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        wal.flush();

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        try {
          wal.read(lsn, 10);
          Assert.fail();
        } catch (Exception e) {
          // ignore
        }

        wal.close();

        Thread.sleep(1);

        //noinspection ConstantConditions
        if (i > 0 && i % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", i, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddSingleOnePageRecord : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddSingleOnePageRecordWrongEncryption() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 10;

    for (var i = 0; i < iterations; i++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        var walRecord = new TestRecord(random, wal.pageSize(), 1);
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, walRecord.getLsn());
        wal.close();

        final var otherAesKeyEncoded = "DD0ViGecppQOx4ijWL4XGBwun9NAfbqFaDnVpn9+lj8=";
        final var otherAesKey = Base64.getDecoder().decode(otherAesKeyEncoded);

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                otherAesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        wal.flush();

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        records = wal.read(lsn, 10);
        Assert.assertTrue(records.isEmpty());

        wal.close();

        Thread.sleep(1);

        //noinspection ConstantConditions
        if (i > 0 && i % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", i, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddSingleOnePageRecord : " + seed);
        throw e;
      }
    }
  }

  @Test
  @Ignore
  public void testAddSingleRecordSeveralPages() throws Exception {
    final var iterations = 10;
    for (var i = 0; i < iterations; i++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        wal.flush();

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        records = wal.read(lsn, 10);
        Assert.assertEquals(2, records.size());
        Assert.assertEquals(lsn, records.getFirst().getLsn());
        readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());

        Assert.assertTrue(records.get(1) instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(1);

        //noinspection ConstantConditions
        if (i > 0 && i % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", i, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddSingleRecordSeveralPages : " + seed);
        throw e;
      }
    }
  }

  @Test
  @Ignore
  public void testAddSingleRecordSeveralPagesEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 10;
    for (var i = 0; i < iterations; i++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, walRecord.getLsn());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        wal.flush();

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        records = wal.read(lsn, 10);
        Assert.assertEquals(2, records.size());
        Assert.assertEquals(lsn, records.getFirst().getLsn());
        readRecord = (TestRecord) records.getFirst();

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());

        Assert.assertTrue(records.get(1) instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(1);

        //noinspection ConstantConditions
        if (i > 0 && i % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", i, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddSingleRecordSeveralPages : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddFewSmallRecords() throws Exception {
    final var iterations = 10;
    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < 5; i++) {
          final var result = wal.read(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, 5).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < 5; i++) {
          final var result = wal.read(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, 5).iterator();

          while (resultIterator.hasNext()) {
            final WALRecord resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }

            var testResultRecord = (TestRecord) resultRecord;
            var record = recordIterator.next();

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        Thread.sleep(1);

        //noinspection ConstantConditions
        if (n > 0 && n % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", n, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddFewSmallRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddFewSmallRecordsEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 10;
    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < 5; i++) {
          final var result = wal.read(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, 5).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < 5; i++) {
          final var result = wal.read(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, 5).iterator();

          while (resultIterator.hasNext()) {
            final WALRecord resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }

            var testResultRecord = (TestRecord) resultRecord;
            var record = recordIterator.next();

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        Thread.sleep(1);

        //noinspection ConstantConditions
        if (n > 0 && n % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", n, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddFewSmallRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddFewSmallRecords() throws Exception {
    final var iterations = 10;

    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
          Assert.assertEquals(walRecord.getLsn(), lsn);
        }

        for (var i = 0; i < 4; i++) {
          final var result = wal.next(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i + 1, 5).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(4).getLsn(), 10).isEmpty());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < 4; i++) {
          final var result = wal.next(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i + 1, 5).iterator();

          while (resultIterator.hasNext()) {
            var resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }

            var testResultRecord = (TestRecord) resultRecord;
            var record = recordIterator.next();

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(4).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);

        //noinspection ConstantConditions
        if (n > 0 && n % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", n, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testNextAddFewSmallRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddFewSmallRecordsEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 10;

    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
          Assert.assertEquals(walRecord.getLsn(), lsn);
        }

        for (var i = 0; i < 4; i++) {
          final var result = wal.next(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i + 1, 5).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(4).getLsn(), 10).isEmpty());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < 4; i++) {
          final var result = wal.next(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i + 1, 5).iterator();

          while (resultIterator.hasNext()) {
            var resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }

            var testResultRecord = (TestRecord) resultRecord;
            var record = recordIterator.next();

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(4).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);

        //noinspection ConstantConditions
        if (n > 0 && n % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", n, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testNextAddFewSmallRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddFewBigRecords() throws Exception {
    final var iterations = 10;

    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < 5; i++) {
          final var result = wal.read(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, 5).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < 5; i++) {
          final var result = wal.read(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, 5).iterator();

          while (resultIterator.hasNext()) {
            var resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }
            var record = recordIterator.next();

            final var testResultRecord = (TestRecord) resultRecord;
            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        Thread.sleep(1);

        //noinspection ConstantConditions
        if (n > 0 && n % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", n, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddFewBigRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddFewBigRecordsEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 10;

    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < 5; i++) {
          final var result = wal.read(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, 5).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < 5; i++) {
          final var result = wal.read(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, 5).iterator();

          while (resultIterator.hasNext()) {
            var resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }
            var record = recordIterator.next();

            final var testResultRecord = (TestRecord) resultRecord;
            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        Thread.sleep(1);

        //noinspection ConstantConditions
        if (n > 0 && n % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", n, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testAddFewBigRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddFewBigRecords() throws Exception {
    final var iterations = 10;

    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < 4; i++) {
          final var result = wal.next(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i + 1, 5).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(4).getLsn(), 10).isEmpty());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < 4; i++) {
          final var result = wal.next(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i + 1, 5).iterator();

          while (resultIterator.hasNext()) {
            WALRecord resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var testResultRecord = (TestRecord) resultRecord;

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(4).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);

        //noinspection ConstantConditions
        if (n > 0 && n % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", n, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testNextAddFewBigRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddFewBigRecordsEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 10;

    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < 4; i++) {
          final var result = wal.next(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i + 1, 5).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(4).getLsn(), 10).isEmpty());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < 4; i++) {
          final var result = wal.next(records.get(i).getLsn(), 10);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i + 1, 5).iterator();

          while (resultIterator.hasNext()) {
            WALRecord resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var testResultRecord = (TestRecord) resultRecord;

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(4).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);

        //noinspection ConstantConditions
        if (n > 0 && n % 1000 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", n, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testNextAddFewBigRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddNSmallRecords() throws Exception {
    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var testResultRecord = (TestRecord) resultRecord;

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }
            var record = recordIterator.next();

            var testResultRecord = (TestRecord) resultRecord;

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();
        Thread.sleep(1);

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);

      } catch (Exception | Error e) {
        System.out.println("testAddNSmallRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddNSmallRecordsEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var testResultRecord = (TestRecord) resultRecord;

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var resultRecord = resultIterator.next();
            if (resultRecord instanceof EmptyWALRecord) {
              continue;
            }
            var record = recordIterator.next();

            var testResultRecord = (TestRecord) resultRecord;

            Assert.assertArrayEquals(record.data, testResultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();
        Thread.sleep(1);

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);

      } catch (Exception | Error e) {
        System.out.println("testAddNSmallRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddNSmallRecords() throws Exception {
    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(recordsCount - 1).getLsn(), 500).isEmpty());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(recordsCount - 1).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();
        Thread.sleep(2);

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);

      } catch (Exception | Error e) {
        System.out.println("testNextAddNSmallRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddNSmallRecordsEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());
      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(recordsCount - 1).getLsn(), 500).isEmpty());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var record = recordIterator.next();
            var resultRecord = (TestRecord) resultIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(recordsCount - 1).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();
        Thread.sleep(2);

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);

      } catch (Exception | Error e) {
        System.out.println("testNextAddNSmallRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  @Ignore
  public void testAddNSegments() throws Exception {
    var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();
      final var random = new Random(seed);

      FileUtils.deleteRecursively(testDirectory.toFile());
      try {
        final var numberOfSegmentsToAdd = random.nextInt(4) + 3;

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        LogSequenceNumber lastLsn;
        for (var i = 0; i < numberOfSegmentsToAdd; i++) {
          wal.appendNewSegment();

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), new LogSequenceNumber(i + 2, CASWALPage.RECORDS_OFFSET));

          final var recordsCount = random.nextInt(10_000) + 100;
          for (var k = 0; k < recordsCount; k++) {
            final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
            lastLsn = wal.log(walRecord);

            records.add(walRecord);

            Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
            Assert.assertEquals(wal.end(), lastLsn);
          }
        }

        Assert.assertEquals(numberOfSegmentsToAdd + 1, wal.activeSegment());

        for (var i = 0; i < records.size(); i++) {
          final var testRecord = records.get(i);
          final var result = wal.read(testRecord.getLsn(), 10);

          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, records.size()).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            while (writeableWALRecord instanceof EmptyWALRecord) {
              if (resultIterator.hasNext()) {
                writeableWALRecord = resultIterator.next();
              } else {
                writeableWALRecord = null;
              }
            }

            if (writeableWALRecord == null) {
              break;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        long walSize;
        try (var walk = Files.walk(testDirectory)) {
          walSize =
              walk.filter(p -> p.toFile().isFile() && p.getFileName().toString().endsWith(".wal"))
                  .mapToLong(p -> p.toFile().length())
                  .sum();
        }

        var calculatedWalSize =
            ((wal.size() + wal.pageSize() - 1) / wal.pageSize()) * wal.pageSize();

        Assert.assertEquals(calculatedWalSize, walSize);

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(
            wal.end(),
            new LogSequenceNumber(numberOfSegmentsToAdd + 2, CASWALPage.RECORDS_OFFSET));

        Assert.assertEquals(numberOfSegmentsToAdd + 2, wal.activeSegment());

        for (var i = 0; i < records.size(); i++) {
          final var testRecord = records.get(i);
          final var result = wal.read(testRecord.getLsn(), 10);

          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, records.size()).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var resultRecord = (TestRecord) writeableWALRecord;
            var record = recordIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        final var recordsCount = random.nextInt(10_000) + 100;
        for (var k = 0; k < recordsCount; k++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          wal.log(walRecord);

          records.add(walRecord);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), walRecord.getLsn());
        }

        for (var i = 0; i < records.size(); i++) {
          final var testRecord = records.get(i);
          final var result = wal.read(testRecord.getLsn(), 10);

          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, records.size()).iterator();

          while (resultIterator.hasNext()) {
            var writeableRecord = resultIterator.next();
            if (writeableRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        try (var walk = Files.walk(testDirectory)) {
          walSize =
              walk.filter(p -> p.toFile().isFile() && p.getFileName().toString().endsWith(".wal"))
                  .mapToLong(p -> p.toFile().length())
                  .sum();
        }

        calculatedWalSize = ((wal.size() + wal.pageSize() - 1) / wal.pageSize()) * wal.pageSize();

        Assert.assertEquals(calculatedWalSize, walSize);

        Thread.sleep(2);

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testAddNSegments seed : " + seed);
        throw e;
      }
    }
  }

  @Test
  @Ignore
  public void testAddNSegmentsEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();
      final var random = new Random(seed);

      FileUtils.deleteRecursively(testDirectory.toFile());
      try {
        final var numberOfSegmentsToAdd = random.nextInt(4) + 3;

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        LogSequenceNumber lastLsn;
        for (var i = 0; i < numberOfSegmentsToAdd; i++) {
          wal.appendNewSegment();

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), new LogSequenceNumber(i + 2, CASWALPage.RECORDS_OFFSET));

          final var recordsCount = random.nextInt(10_000) + 100;
          for (var k = 0; k < recordsCount; k++) {
            final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
            lastLsn = wal.log(walRecord);

            records.add(walRecord);

            Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
            Assert.assertEquals(wal.end(), lastLsn);
          }
        }

        Assert.assertEquals(numberOfSegmentsToAdd + 1, wal.activeSegment());

        for (var i = 0; i < records.size(); i++) {
          final var testRecord = records.get(i);
          final var result = wal.read(testRecord.getLsn(), 10);

          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, records.size()).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();

            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        long walSize;
        try (var walk = Files.walk(testDirectory)) {
          walSize =
              walk.filter(p -> p.toFile().isFile() && p.getFileName().toString().endsWith(".wal"))
                  .mapToLong(p -> p.toFile().length())
                  .sum();
        }

        var calculatedWalSize =
            ((wal.size() + wal.pageSize() - 1) / wal.pageSize()) * wal.pageSize();

        Assert.assertEquals(calculatedWalSize, walSize);

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(
            wal.end(),
            new LogSequenceNumber(numberOfSegmentsToAdd + 2, CASWALPage.RECORDS_OFFSET));

        Assert.assertEquals(numberOfSegmentsToAdd + 2, wal.activeSegment());

        for (var i = 0; i < records.size(); i++) {
          final var testRecord = records.get(i);
          final var result = wal.read(testRecord.getLsn(), 10);

          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, records.size()).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        final var recordsCount = random.nextInt(10_000) + 100;
        for (var k = 0; k < recordsCount; k++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          wal.log(walRecord);

          records.add(walRecord);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), walRecord.getLsn());
        }

        for (var i = 0; i < records.size(); i++) {
          final var testRecord = records.get(i);
          final var result = wal.read(testRecord.getLsn(), 10);

          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, records.size()).iterator();

          while (resultIterator.hasNext()) {
            var writeableRecord = resultIterator.next();
            if (writeableRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        try (var walk = Files.walk(testDirectory)) {
          walSize =
              walk.filter(p -> p.toFile().isFile() && p.getFileName().toString().endsWith(".wal"))
                  .mapToLong(p -> p.toFile().length())
                  .sum();
        }

        calculatedWalSize = ((wal.size() + wal.pageSize() - 1) / wal.pageSize()) * wal.pageSize();

        Assert.assertEquals(calculatedWalSize, walSize);

        Thread.sleep(2);

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testAddNSegments seed : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddNBigRecords() throws Exception {
    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());

      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var resultRecord = (TestRecord) writeableWALRecord;
            var record = recordIterator.next();

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();

            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        Thread.sleep(1);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testAddNBigRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddNBigRecordsEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());

      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();

            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        Thread.sleep(1);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testAddNBigRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddNBigRecords() throws Exception {
    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());

      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(recordsCount - 1).getLsn(), 500).isEmpty());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }
            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(recordsCount - 1).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testNextAddNBigRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddNBigRecordsEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());

      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(recordsCount - 1).getLsn(), 500).isEmpty());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(recordsCount - 1).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testNextAddNBigRecords : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddRecordsMix() throws Exception {
    final var iterations = 1;
    for (var n = 0; n < iterations; n++) {
      final var seed = 26866978951787L; // System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());

      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testAddRecordsMix : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAddRecordsMixEncrypted() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 1;
    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());

      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testAddRecordsMix : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddRecordsMix() throws Exception {
    final var iterations = 1;
    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());

      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(recordsCount - 1).getLsn(), 500).isEmpty());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(recordsCount - 1).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testNextAddRecordsMix : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testNextAddRecordsMixEncryption() throws Exception {
    final var aesKeyEncoded = "T1JJRU5UREJfSVNfQ09PTA==";
    final var aesKey = Base64.getDecoder().decode(aesKeyEncoded);
    final var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    final var iterations = 1;
    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();

      FileUtils.deleteRecursively(testDirectory.toFile());

      try {
        final var random = new Random(seed);

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.end());

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
        }

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        Assert.assertTrue(wal.next(records.get(recordsCount - 1).getLsn(), 500).isEmpty());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                aesKey,
                iv,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
        Assert.assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), wal.end());

        for (var i = 0; i < recordsCount - 1; i++) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        var lastResult = wal.next(records.get(recordsCount - 1).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        System.out.println("testNextAddRecordsMix : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testSegSize() throws Exception {
    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      final var seed = System.nanoTime();
      FileUtils.deleteRecursively(testDirectory.toFile());

      try {
        final var random = new Random(seed);
        final var recordsCount = random.nextInt(10_000) + 100;

        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);
        for (var i = 0; i < recordsCount; i++) {

          final var testRecord = new TestRecord(random, 4 * wal.pageSize(), 1);
          wal.log(testRecord);
        }

        wal.close();

        final long segSize;
        try (var walk = Files.walk(testDirectory)) {
          segSize =
              walk.filter(p -> p.toFile().isFile() && p.getFileName().toString().endsWith(".wal"))
                  .mapToLong(p -> p.toFile().length())
                  .sum();
        }

        final var calculatedSegSize =
            ((wal.segSize() + wal.pageSize() - 1) / wal.pageSize()) * wal.pageSize();
        Assert.assertEquals(segSize, calculatedSegSize);

        Thread.sleep(2);

        //noinspection ConstantConditions
        if (n > 0 && n % 10 == 0) {
          System.out.printf("%d iterations out of %d were passed\n", n, iterations);
        }
      } catch (Exception | Error e) {
        System.out.println("testLogSize : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testFlush() throws Exception {
    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      var wal =
          new CASDiskWriteAheadLog(
              "walTest",
              testDirectory,
              testDirectory,
              ContextConfiguration.WAL_DEFAULT_NAME,
              48_000,
              64,
              null,
              null,
              Integer.MAX_VALUE,
              256 * 1024 * 1024,
              20,
              true,
              Locale.US,
              10 * 1024 * 1024 * 1024L,
              1000,
              false,
              false,
              false,
              10);

      var seed = System.nanoTime();
      var random = new Random(seed);

      LogSequenceNumber lastLSN = null;
      for (var k = 0; k < 10000; k++) {
        var recordsCount = 20;
        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), 1);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          Assert.assertEquals(wal.end(), lsn);
          lastLSN = lsn;
        }

        wal.flush();

        Assert.assertEquals(lastLSN, wal.getFlushedLsn());
      }

      wal.close();

      var loadedWAL =
          new CASDiskWriteAheadLog(
              "walTest",
              testDirectory,
              testDirectory,
              ContextConfiguration.WAL_DEFAULT_NAME,
              48_000,
              64,
              null,
              null,
              Integer.MAX_VALUE,
              256 * 1024 * 1024,
              20,
              true,
              Locale.US,
              10 * 1024 * 1024 * 1024L,
              1000,
              false,
              false,
              false,
              10);

      Assert.assertNotNull(loadedWAL.getFlushedLsn());
      Assert.assertEquals(loadedWAL.end(), loadedWAL.getFlushedLsn());

      loadedWAL.close();

      System.out.printf("%d iterations out of %d is passed \n", n, iterations);
    }
  }

  @Test
  public void cutTillTest() throws Exception {
    var iterations = 1;
    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      final var random = new Random(seed);

      var segments = new TreeSet<Long>();
      var records = new TreeMap<LogSequenceNumber, TestRecord>();

      var wal =
          new CASDiskWriteAheadLog(
              "walTest",
              testDirectory,
              testDirectory,
              ContextConfiguration.WAL_DEFAULT_NAME,
              48_000,
              64,
              null,
              null,
              Integer.MAX_VALUE,
              10 * 1024 * 1024,
              20,
              true,
              Locale.US,
              10 * 1024 * 1024L,
              1000,
              false,
              false,
              false,
              10);

      LogSequenceNumber begin = null;
      LogSequenceNumber end = null;

      for (var k = 0; k < 10; k++) {
        for (var i = 0; i < 30_000; i++) {
          final var walRecord =
              new TestRecord(random, 4 * wal.pageSize(), 2 * wal.pageSize());
          var lsn = wal.log(walRecord);
          records.put(lsn, walRecord);

          segments.add(lsn.getSegment());
        }

        long minSegment = segments.first();
        long maxSegment = segments.last();

        var segment = random.nextInt((int) (maxSegment - minSegment)) + minSegment;
        final var notActive = wal.nonActiveSegments();

        wal.cutAllSegmentsSmallerThan(segment);

        begin = wal.begin();
        final var cutSegmentIndex = Arrays.binarySearch(notActive, segment);

        if (cutSegmentIndex >= 0) {
          Assert.assertTrue(begin.getSegment() >= notActive[cutSegmentIndex]);
        } else {
          Assert.assertTrue(begin.getSegment() > notActive[notActive.length - 1]);
        }

        begin = wal.begin();
        end = wal.end();

        segments.headSet(segment, false).clear();
        for (var record : records.values()) {
          if (record.getLsn().getSegment() < begin.getSegment()) {
            Assert.assertTrue(wal.read(record.getLsn(), 1).isEmpty());
          } else {
            Assert.assertArrayEquals(
                record.data, ((TestRecord) (wal.read(record.getLsn(), 1).getFirst())).data);
          }
        }

        records.headMap(begin, false).clear();

        for (var i = 0; i < begin.getSegment(); i++) {
          final var segmentPath = testDirectory.resolve(getSegmentName(i));
          Assert.assertFalse(Files.exists(segmentPath));
        }

        {
          final var segmentPath = testDirectory.resolve(getSegmentName(end.getSegment() + 1));
          Assert.assertFalse(Files.exists(segmentPath));
        }
      }

      wal.close();

      var loadedWAL =
          new CASDiskWriteAheadLog(
              "walTest",
              testDirectory,
              testDirectory,
              ContextConfiguration.WAL_DEFAULT_NAME,
              48_000,
              64,
              null,
              null,
              Integer.MAX_VALUE,
              10 * 1024 * 1024,
              20,
              true,
              Locale.US,
              10 * 1024 * 1024L,
              1000,
              false,
              false,
              false,
              10);

      var minSegment = begin.getSegment();
      var maxSegment = end.getSegment();

      var segment = random.nextInt((int) (maxSegment - minSegment)) + minSegment;
      loadedWAL.cutAllSegmentsSmallerThan(segment);

      Assert.assertEquals(
          new LogSequenceNumber(segment, CASWALPage.RECORDS_OFFSET), loadedWAL.begin());
      Assert.assertEquals(
          new LogSequenceNumber(end.getSegment() + 1, CASWALPage.RECORDS_OFFSET), loadedWAL.end());

      for (var record : records.values()) {
        if (record.getLsn().getSegment() < segment) {
          Assert.assertTrue(loadedWAL.read(record.getLsn(), 1).isEmpty());
        } else {
          Assert.assertArrayEquals(
              record.data, ((TestRecord) (loadedWAL.read(record.getLsn(), 1).getFirst())).data);
        }
      }

      begin = loadedWAL.begin();
      end = loadedWAL.end();

      for (var i = 0; i < begin.getSegment(); i++) {
        final var segmentPath = testDirectory.resolve(getSegmentName(i));
        Assert.assertFalse(Files.exists(segmentPath));
      }

      {
        final var segmentPath = testDirectory.resolve(getSegmentName(end.getSegment() + 1));
        Assert.assertFalse(Files.exists(segmentPath));
      }

      loadedWAL.close();

      System.out.printf("%d iterations out of %d are passed \n", n, iterations);
    }
  }

  @Test
  public void testCutTillLimit() throws Exception {
    FileUtils.deleteRecursively(testDirectory.toFile());

    final var seed = System.nanoTime();
    final var random = new Random(seed);

    final var records = new TreeMap<LogSequenceNumber, TestRecord>();

    var wal =
        new CASDiskWriteAheadLog(
            "walTest",
            testDirectory,
            testDirectory,
            ContextConfiguration.WAL_DEFAULT_NAME,
            48_000,
            64,
            null,
            null,
            Integer.MAX_VALUE,
            10 * 1024 * 1024,
            20,
            true,
            Locale.US,
            10 * 1024 * 1024L,
            1000,
            false,
            false,
            false,
            10);

    for (var k = 0; k < 10; k++) {
      for (var i = 0; i < 30_000; i++) {
        final var walRecord = new TestRecord(random, 4 * wal.pageSize(), 2 * wal.pageSize());
        var lsn = wal.log(walRecord);
        records.put(lsn, walRecord);
      }

      final var limits = new TreeMap<LogSequenceNumber, Integer>();

      var lsn = chooseRandomRecord(random, records);
      addLimit(limits, lsn);
      wal.addCutTillLimit(lsn);

      lsn = chooseRandomRecord(random, records);
      addLimit(limits, lsn);
      wal.addCutTillLimit(lsn);

      lsn = chooseRandomRecord(random, records);
      addLimit(limits, lsn);
      wal.addCutTillLimit(lsn);

      var nonActive = wal.nonActiveSegments();
      wal.cutTill(limits.lastKey());

      var segment = limits.firstKey().getSegment();
      var begin = wal.begin();
      checkThatAllNonActiveSegmentsAreRemoved(nonActive, segment, wal);

      Assert.assertTrue(begin.getSegment() <= segment);

      lsn = limits.firstKey();
      removeLimit(limits, lsn);
      wal.removeCutTillLimit(lsn);

      nonActive = wal.nonActiveSegments();
      wal.cutTill(limits.lastKey());

      segment = limits.firstKey().getSegment();
      begin = wal.begin();

      checkThatAllNonActiveSegmentsAreRemoved(nonActive, segment, wal);
      checkThatSegmentsBellowAreRemoved(wal);

      Assert.assertTrue(begin.getSegment() <= segment);

      lsn = limits.lastKey();
      removeLimit(limits, lsn);
      wal.removeCutTillLimit(lsn);

      nonActive = wal.nonActiveSegments();
      wal.cutTill(lsn);

      segment = limits.firstKey().getSegment();
      begin = wal.begin();

      checkThatAllNonActiveSegmentsAreRemoved(nonActive, segment, wal);
      checkThatSegmentsBellowAreRemoved(wal);
      Assert.assertTrue(begin.getSegment() <= segment);

      lsn = limits.lastKey();
      removeLimit(limits, lsn);
      wal.removeCutTillLimit(lsn);

      nonActive = wal.nonActiveSegments();
      wal.cutTill(lsn);
      checkThatAllNonActiveSegmentsAreRemoved(nonActive, lsn.getSegment(), wal);
      checkThatSegmentsBellowAreRemoved(wal);

      records.headMap(wal.begin(), false).clear();
    }

    wal.close();
  }

  /// Tests that the `read()` method of [CASDiskWriteAheadLog] correctly reads WAL records across
  /// multiple segments, both before and after WAL close/reopen (simulating crash recovery).
  ///
  /// The test exercises the following scenario:
  /// <ol>
  ///     - **Write phase**: 100,000 randomly-sized records (1 byte to 3x page size) are
  ///     written to the WAL. After each record, with ~5% probability, 1-5 new segments are
  ///     appended via `appendNewSegment()`, forcing subsequent records into new WAL
  ///     segment files. This creates a multi-segment WAL with records scattered across many
  ///     segment boundaries.
  ///     - **First verification (in-memory)**: While the WAL is still open, the test reads
  ///     all records using `read(lsn, 500)`, starting from each record's LSN to fetch a
  ///     batch of up to 500 records. It verifies that every record's data payload and LSN
  ///     match the original written records. [EmptyWALRecord] entries (inserted at segment
  ///     boundaries) are skipped.
  ///     - **Close and reopen**: The WAL is closed and reopened from disk, simulating a
  ///     database restart or crash recovery. The reopened WAL uses different configuration
  ///     parameters (smaller initial segment count of 100, unlimited max segment size set to
  ///     `Integer.MAX_VALUE`, and free-space limit of -1) to verify that recovery
  ///     works regardless of current configuration.
  ///     - **Post-recovery begin/end verification**: After reopening, the test checks that:
  ///
  ///   - `wal.begin()` still points to the very first record in segment 1 at
  ///     offset [CASWALPage#RECORDS_OFFSET].
  ///   - `wal.end()` points to the correct position. On recovery, the WAL scans
  ///     existing segment files, takes `segments.last() + 1` as the new segment ID, and
  ///     opens a fresh segment there. If K segments were appended after the last record
  ///     (creating segment files up to N+K), recovery opens segment N+K+1.
  ///     If no segments were appended, recovery opens N+1. So the expected end is
  ///     always `(lastRecordSegment + segmentsAppendedAfterLastRecord + 1,
  ///     RECORDS_OFFSET)`.
  ///
  ///
  ///     - **Second verification (from disk)**: The same record-by-record read via
  ///     `read()` is repeated on the reopened WAL. All records are verified to have
  ///     identical data and LSNs as the originals. The inner loop also checks
  ///     `recordIterator.hasNext()` to handle trailing [EmptyWALRecord] entries
  ///     that may appear after the last user record due to recovery.
  /// </ol>
  ///
  /// **Key invariants verified**:
  ///
  ///     - `read()` correctly returns records spanning segment boundaries.
  ///     - Random segment splits at arbitrary points do not corrupt record ordering or data.
  ///     - All records survive WAL close and reopen (durability).
  ///     - The WAL's begin/end LSN bookkeeping remains consistent after recovery,
  ///     accounting for the exact number of empty segments appended after the last record.
  ///     - EmptyWALRecords at segment boundaries are correctly handled (skipped during
  ///     iteration).
  ///
  ///
  /// The test uses a random seed (printed to stdout) so failures can be reproduced
  /// deterministically by reusing the same seed value.
  @Test
  public void testAppendSegment() throws Exception {
    var iterations = 1;
    for (var n = 0; n < iterations; n++) {
      // Clean the test directory to ensure a fresh WAL for each iteration.
      FileUtils.deleteRecursively(testDirectory.toFile());

      // Use a time-based random seed so that failures can be reproduced by reusing the seed.
      final var seed = System.nanoTime();
      System.out.println("testAppendSegment seed : " + seed);
      final var random = new Random(seed);

      try {
        // --- WAL Initialization ---
        // Create a new WAL with a 48KB max segment size and 10MB free-space limit.
        // The relatively small segment size (48KB) ensures that records will span across
        // many segments, thoroughly testing cross-segment reads.
        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                48_000,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                10 * 1024 * 1024,
                20,
                true,
                Locale.US,
                10 * 1024 * 1024 * 1024L,
                1000,
                false,
                false,
                false,
                10);

        // Stores all written records in order for later verification.
        List<TestRecord> records = new ArrayList<>();

        // Tracks how many segments were appended after the very last record.
        // This affects the expected WAL end position after recovery: the WAL constructor
        // sets currentSegment = segments.last() + 1, so end = N + K + 1 where N is the
        // last record's segment and K is the number of appended segments after it.
        var segmentsAppendedAfterLastRecord = 0;
        System.out.println("Load data");

        // --- Write Phase ---
        // Write 100,000 records with random sizes between 1 byte and 3x the WAL page size.
        // Randomly append new segments (~5% chance per record) to create a complex
        // multi-segment WAL layout.
        final var recordsCount = 100_000;
        for (var i = 0; i < recordsCount; i++) {
          // Reset per-record: only the state after the LAST record matters for the
          // end-position assertion after recovery.
          segmentsAppendedAfterLastRecord = 0;

          // Create a record with random data payload (size: 1 to 3*pageSize bytes).
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          // Log the record and verify the returned LSN matches the one assigned to the record.
          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          // The WAL begin should always point to the very first record position in segment 1.
          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          // The WAL end should always be the LSN of the most recently logged record.
          Assert.assertEquals(wal.end(), lsn);

          // With ~5% probability, force 1-5 new segment boundaries.
          // This simulates real-world scenarios where segments rotate due to size limits
          // or explicit segment switches (e.g., after checkpoint).
          if (random.nextDouble() < 0.05) {
            final var segments = random.nextInt(5) + 1;

            for (var k = 0; k < segments; k++) {
              wal.appendNewSegment();
              segmentsAppendedAfterLastRecord++;
            }
          }
        }

        // --- First Verification: In-Memory (WAL still open) ---
        // Walk through all records using read(), verifying data integrity and LSN correctness.
        // read(lsn, limit) returns up to `limit` records starting FROM the given LSN.
        System.out.println("First check");
        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          // Expected records start from i (read() returns records starting FROM the given LSN).
          final var recordIterator = records.subList(i, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            // EmptyWALRecords are internal markers placed at segment boundaries;
            // they carry no user data and should be skipped during verification.
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            // Verify the record's data payload matches exactly.
            Assert.assertArrayEquals(record.data, resultRecord.data);
            // Verify the record's LSN matches the one assigned during logging.
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        // --- Close WAL ---
        // Flush all in-memory buffers to disk and close the WAL cleanly.
        wal.close();

        // --- Reopen WAL (simulates restart/recovery) ---
        // Reopen the WAL from disk with different configuration parameters:
        //   - Smaller initial segment count (100 vs 48,000) — tests that recovery does
        //     not depend on matching the original segment configuration.
        //   - Max segment size set to Integer.MAX_VALUE — no size-based segment rotation.
        //   - Free-space limit set to -1 — no free-space-based cleanup during recovery.
        System.out.println("Second check");
        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        // --- Post-Recovery Begin/End Assertions ---
        // After recovery, begin() must still point to the first record in segment 1.
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());

        // The expected end position depends on how many segments were appended after the
        // last logged record. On recovery, the WAL constructor scans all segment files,
        // computes nextSegmentId = segments.last() + 1, and opens a new segment there.
        //   - If the last record is in segment N and K segments were appended after it,
        //     segment files exist up to N+K, so recovery opens segment N+K+1.
        //   - If no segments were appended (K=0), recovery opens segment N+1.
        // In both cases: end = (N + K + 1, RECORDS_OFFSET).
        Assert.assertEquals(
            new LogSequenceNumber(
                records.getLast().getLsn().getSegment()
                    + segmentsAppendedAfterLastRecord + 1,
                CASWALPage.RECORDS_OFFSET),
            wal.end());

        // --- Second Verification: From Disk (after recovery) ---
        // Repeat the same record-by-record read as the first check, but now reading
        // from the recovered WAL. This verifies durability — all records survive close/reopen.
        for (var i = 0; i < recordsCount; i++) {
          final var result = wal.read(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator = records.subList(i, recordsCount).iterator();

          // Note: this loop also checks recordIterator.hasNext() to handle the case where
          // the result batch extends beyond the expected records (e.g., includes trailing
          // EmptyWALRecords from recovery).
          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        wal.close();

        Thread.sleep(2);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Error | Exception e) {
        // Re-print the seed on failure so the exact sequence can be reproduced for debugging.
        System.out.println("testAppendSegment seed : " + seed);
        throw e;
      }
    }
  }

  /// Tests that the `next()` method of [CASDiskWriteAheadLog] correctly iterates through WAL
  /// records across multiple segments, both before and after WAL close/reopen (simulating crash
  /// recovery).
  ///
  /// The test exercises the following scenario:
  /// <ol>
  ///     - **Write phase**: 100,000 randomly-sized records (1 byte to 3x page size) are
  ///     written to the WAL. After each record, with ~5% probability, 1-5 new segments are
  ///     appended via `appendNewSegment()`, forcing subsequent records into new WAL
  ///     segment files. This creates a multi-segment WAL with records scattered across many
  ///     segment boundaries.
  ///     - **First verification (in-memory)**: While the WAL is still open, the test walks
  ///     through all records using `next(lsn, 500)`, starting from each record's LSN
  ///     to fetch the next batch of up to 500 records. It verifies that every record's data
  ///     payload and LSN match the original written records. [EmptyWALRecord] entries
  ///     (inserted at segment boundaries) are skipped. The test also confirms that calling
  ///     `next()` on the very last record returns an empty list, indicating no more
  ///     records follow.
  ///     - **Close and reopen**: The WAL is closed and reopened from disk, simulating a
  ///     database restart or crash recovery. The reopened WAL uses different configuration
  ///     parameters (smaller initial segment count of 100, unlimited max segment size set to
  ///     `Integer.MAX_VALUE`, and free-space limit of -1) to verify that recovery
  ///     works regardless of current configuration.
  ///     - **Post-recovery begin/end verification**: After reopening, the test checks that:
  ///
  ///   - `wal.begin()` still points to the very first record in segment 1 at
  ///     offset [CASWALPage#RECORDS_OFFSET].
  ///       - `wal.end()` points to the correct position. On recovery, the WAL scans
  ///     existing segment files, takes `segments.last() + 1` as the new segment ID, and
  ///     opens a fresh segment there. If K segments were appended after the last record
  ///     (creating segment files up to N+K), recovery opens segment N+K+1.
  ///     If no segments were appended, recovery opens N+1. So the expected end is
  ///     always `(lastRecordSegment + segmentsAppendedAfterLastRecord + 1,
  ///     RECORDS_OFFSET)`.
  ///
  ///
  ///     - **Second verification (from disk)**: The same record-by-record iteration via
  ///     `next()` is repeated on the reopened WAL. All records are verified to have
  ///     identical data and LSNs as the originals. Unlike the first check, after the last
  ///     record, `next()` now returns a single [EmptyWALRecord] rather than an
  ///     empty list — this is because recovery appends an empty record as a sentinel marking
  ///     the end of the recovered WAL.
  /// </ol>
  ///
  /// **Key invariants verified**:
  ///
  ///     - `next()` correctly traverses segment boundaries, returning records from
  ///     subsequent segments transparently.
  ///     - Random segment splits at arbitrary points do not corrupt record ordering or data.
  ///     - All records survive WAL close and reopen (durability).
  ///     - The WAL's begin/end LSN bookkeeping remains consistent after recovery.
  ///     - EmptyWALRecords at segment boundaries are correctly handled (skipped during
  ///     iteration).
  ///
  ///
  /// The test uses a random seed (printed to stdout) so failures can be reproduced
  /// deterministically by reusing the same seed value.
  @Test
  public void testAppendSegmentNext() throws Exception {
    final var iterations = 1;
    for (var n = 0; n < iterations; n++) {
      // Clean the test directory to ensure a fresh WAL for each iteration.
      FileUtils.deleteRecursively(testDirectory.toFile());

      // Use a time-based random seed so that failures can be reproduced by reusing the seed.
      final var seed = System.nanoTime();
      System.out.println("testAppendSegmentNext seed : " + seed);
      final var random = new Random(seed);

      try {
        // --- WAL Initialization ---
        // Create a new WAL with a 48KB max segment size and 10MB free-space limit.
        // The relatively small segment size (48KB) ensures that records will span across
        // many segments, thoroughly testing cross-segment iteration.
        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                48_000,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                10 * 1024 * 1024,
                20,
                true,
                Locale.US,
                10 * 1024 * 1024 * 1024L,
                1000,
                false,
                false,
                false,
                10);

        // Stores all written records in order for later verification.
        List<TestRecord> records = new ArrayList<>();

        // Tracks how many segments were appended after the very last record.
        // This affects the expected WAL end position after recovery: the WAL constructor
        // sets currentSegment = segments.last() + 1, so end = N + K + 1 where N is the
        // last record's segment and K is the number of appended segments after it.
        var segmentsAppendedAfterLastRecord = 0;
        System.out.println("Load data");

        // --- Write Phase ---
        // Write 100,000 records with random sizes between 1 byte and 3x the WAL page size.
        // Randomly append new segments (~5% chance per record) to create a complex
        // multi-segment WAL layout.
        final var recordsCount = 100_000;
        for (var i = 0; i < recordsCount; i++) {
          // Reset per-record: only the state after the LAST record matters for the
          // end-position assertion after recovery.
          segmentsAppendedAfterLastRecord = 0;

          // Create a record with random data payload (size: 1 to 3*pageSize bytes).
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          // Log the record and verify the returned LSN matches the one assigned to the record.
          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          // The WAL begin should always point to the very first record position in segment 1.
          Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
          // The WAL end should always be the LSN of the most recently logged record.
          Assert.assertEquals(wal.end(), lsn);

          // With ~5% probability, force 1-5 new segment boundaries.
          // This simulates real-world scenarios where segments rotate due to size limits
          // or explicit segment switches (e.g., after checkpoint).
          if (random.nextDouble() < 0.05) {
            final var segments = random.nextInt(5) + 1;

            for (var k = 0; k < segments; k++) {
              wal.appendNewSegment();
              segmentsAppendedAfterLastRecord++;
            }
          }
        }

        // --- First Verification: In-Memory (WAL still open) ---
        // Walk through all records using next(), verifying data integrity and LSN correctness.
        // next(lsn, limit) returns up to `limit` records starting AFTER the given LSN.
        System.out.println("First check");
        for (var i = 0; i < recordsCount - 1; ) {
          // Fetch the next batch of up to 500 records after records[i]'s LSN.
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          // Expected records start from i+1 (next() returns records AFTER the given LSN).
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          while (resultIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            // EmptyWALRecords are internal markers placed at segment boundaries;
            // they carry no user data and should be skipped during verification.
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }

            i++;
            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            // Verify the record's data payload matches exactly.
            Assert.assertArrayEquals(record.data, resultRecord.data);
            // Verify the record's LSN matches the one assigned during logging.
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        // After the last record, next() should return an empty list — there are no more
        // records to read while the WAL is still open and no recovery sentinel exists.
        Assert.assertTrue(wal.next(records.get(recordsCount - 1).getLsn(), 500).isEmpty());

        // --- Close WAL ---
        // Flush all in-memory buffers to disk and close the WAL cleanly.
        wal.close();

        // --- Reopen WAL (simulates restart/recovery) ---
        // Reopen the WAL from disk with different configuration parameters:
        //   - Smaller initial segment count (100 vs 48,000) — tests that recovery does
        //     not depend on matching the original segment configuration.
        //   - Max segment size set to Integer.MAX_VALUE — no size-based segment rotation.
        //   - Free-space limit set to -1 — no free-space-based cleanup during recovery.
        System.out.println("Second check");
        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                100,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                20,
                true,
                Locale.US,
                -1,
                1000,
                false,
                false,
                false,
                10);

        // --- Post-Recovery Begin/End Assertions ---
        // After recovery, begin() must still point to the first record in segment 1.
        Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());

        // The expected end position depends on how many segments were appended after the
        // last logged record. On recovery, the WAL constructor scans all segment files,
        // computes nextSegmentId = segments.last() + 1, and opens a new segment there.
        //   - If the last record is in segment N and K segments were appended after it,
        //     segment files exist up to N+K, so recovery opens segment N+K+1.
        //   - If no segments were appended (K=0), recovery opens segment N+1.
        // In both cases: end = (N + K + 1, RECORDS_OFFSET).
        Assert.assertEquals(
            new LogSequenceNumber(
                records.getLast().getLsn().getSegment()
                    + segmentsAppendedAfterLastRecord + 1,
                CASWALPage.RECORDS_OFFSET),
            wal.end());

        // --- Second Verification: From Disk (after recovery) ---
        // Repeat the same record-by-record iteration as the first check, but now reading
        // from the recovered WAL. This verifies durability — all records survive close/reopen.
        for (var i = 0; i < recordsCount - 1; ) {
          final var result = wal.next(records.get(i).getLsn(), 500);
          Assert.assertFalse(result.isEmpty());

          final var resultIterator = result.iterator();
          final var recordIterator =
              records.subList(i + 1, recordsCount).iterator();

          // Note: this loop also checks recordIterator.hasNext() to handle the case where
          // the result batch extends beyond the expected records (e.g., includes trailing
          // EmptyWALRecords from recovery).
          while (resultIterator.hasNext() && recordIterator.hasNext()) {
            var writeableWALRecord = resultIterator.next();
            if (writeableWALRecord instanceof EmptyWALRecord) {
              continue;
            }
            i++;
            var record = recordIterator.next();
            var resultRecord = (TestRecord) writeableWALRecord;

            Assert.assertArrayEquals(record.data, resultRecord.data);
            Assert.assertEquals(record.getLsn(), resultRecord.getLsn());
          }
        }

        // After recovery, next() on the last record should return a single EmptyWALRecord.
        // This is different from the first check (which returned an empty list) because
        // the recovery process writes an EmptyWALRecord sentinel at the end of the WAL
        // to mark the boundary of recovered data.
        var lastResult = wal.next(records.get(recordsCount - 1).getLsn(), 10);
        Assert.assertEquals(1, lastResult.size());
        var emptyRecord = lastResult.getFirst();

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Error | Exception e) {
        // Re-print the seed on failure so the exact sequence can be reproduced for debugging.
        System.out.println("testAppendSegmentNext seed : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testDelete() throws Exception {
    @SuppressWarnings("resource") var wal =
        new CASDiskWriteAheadLog(
            "walTest",
            testDirectory,
            testDirectory,
            ContextConfiguration.WAL_DEFAULT_NAME,
            48_000,
            64,
            null,
            null,
            Integer.MAX_VALUE,
            10 * 1024 * 1024,
            20,
            true,
            Locale.US,
            10 * 1024 * 1024 * 1024L,
            1000,
            false,
            false,
            false,
            10);

    final var seed = System.nanoTime();
    final var random = new Random(seed);
    System.out.println("testDelete seed : " + seed);

    final var recordsCount = 30_000;
    for (var i = 0; i < recordsCount; i++) {
      final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);

      var lsn = wal.log(walRecord);
      Assert.assertEquals(walRecord.getLsn(), lsn);

      Assert.assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), wal.begin());
      Assert.assertEquals(wal.end(), lsn);

      if (random.nextDouble() < 0.05) {
        final var segments = random.nextInt(5) + 1;

        for (var k = 0; k < segments; k++) {
          wal.appendNewSegment();
        }
      }
    }

    wal.delete();

    Assert.assertTrue(Files.exists(testDirectory));
    var files = testDirectory.toFile().listFiles();
    Assert.assertTrue(files == null || files.length == 0);
  }

  /// Tests that [CASDiskWriteAheadLog] correctly detects page-level corruption and truncates the
  /// WAL at the corrupted page during recovery.
  ///
  /// The test exercises the following scenario:
  /// <ol>
  ///     - **Write phase**: 100,000 randomly-sized records (1 byte to 3x page size) are
  ///     written to the WAL without any explicit segment appends (segments rotate naturally
  ///     based on the 10MB max segment size). The WAL is then closed, flushing all data to
  ///     disk.
  ///     - **Corruption phase**: A random record is selected and the WAL page containing
  ///     that record is identified by its segment file and page offset. The page is read
  ///     from disk, a single byte at offset 42 (within the records data area, past the
  ///     22-byte page header) is incremented, and the corrupted page is written back to
  ///     the same position. This simulates a disk-level bit flip or partial write failure.
  ///     The XX_HASH checksum stored in the page header will no longer match the modified
  ///     content, so the WAL will detect the corruption on the next read.
  ///     - **Recovery phase**: The WAL is reopened from disk. The constructor scans
  ///     existing segment files and opens a new segment for writing, but does not validate
  ///     old pages eagerly — corruption is detected lazily when pages are read.
  ///     - **Verification phase**: The test reads records sequentially from the beginning,
  ///     comparing each against the original. Reading proceeds in batches of 100 using
  ///     `read()` for the first batch and `next()` for subsequent batches. When the
  ///     corrupted page is encountered, the XX_HASH check fails and the WAL returns no
  ///     more records, breaking the read loop.
  ///     - **Corruption boundary assertion**: After the loop, the test verifies that the
  ///     first unreadable record (`records.get(recordCounter)`) is located in the same
  ///     segment as the corrupted page, and that the corrupted page index is >= the page
  ///     where that record starts. This accounts for the fact that a record may start
  ///     on a page before the corrupted one but span into it, causing the earlier record
  ///     to be the first one that fails to read.
  /// </ol>
  ///
  /// **Special case**: If the corrupted page is page 0 of segment 1 (the very first page
  /// of the WAL), no records can be read at all, and the test asserts that the initial
  /// `read()` returns an empty list.
  ///
  /// **Key invariants verified**:
  ///
  ///     - Single-byte corruption is detected via the per-page XX_HASH checksum.
  ///     - Records before the corrupted page are fully intact and readable.
  ///     - The WAL does not return partially corrupted records — it stops at the page
  ///     boundary.
  ///     - The first unreadable record is in the same segment and at or after the
  ///     corrupted page.
  ///
  ///
  /// The test uses a random seed (printed to stdout) so failures can be reproduced
  /// deterministically by reusing the same seed value.
  @Test
  public void testWALCrash() throws Exception {
    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      // Clean the test directory to ensure a fresh WAL for each iteration.
      FileUtils.deleteRecursively(testDirectory.toFile());

      // Use a time-based random seed so that failures can be reproduced by reusing the seed.
      final var seed = System.nanoTime();
      final var random = new Random(seed);

      try {
        // --- WAL Initialization ---
        // Create a new WAL with 48KB max page cache and 10MB max segment size.
        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                48_000,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                10 * 1024 * 1024,
                20,
                true,
                Locale.US,
                10 * 1024 * 1024 * 1024L,
                1000,
                false,
                false,
                false,
                10);

        // --- Write Phase ---
        // Write 100,000 records with random sizes (1 to 3*pageSize bytes) and close the WAL.
        final List<TestRecord> records = new ArrayList<>();
        final var recordsCount = 100_000;
        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          wal.log(walRecord);
          records.add(walRecord);
        }

        wal.close();

        // --- Corruption Phase ---
        // Pick a random record and identify the WAL page that contains it.
        final var index = random.nextInt(records.size());
        final var lsn = records.get(index).getLsn();
        final var segment = lsn.getSegment();
        final long page = lsn.getPosition() / wal.pageSize();

        // Read the target page, flip byte 42 (in the records data area, past the 22-byte
        // page header: 8 bytes magic + 8 bytes XX_HASH + 4 bytes operation ID + 2 bytes
        // page size = 22 bytes), and write the corrupted page back to its original position.
        // This invalidates the page's XX_HASH checksum.
        try (final var channel =
            FileChannel.open(
                testDirectory.resolve(getSegmentName(segment)),
                StandardOpenOption.WRITE,
                StandardOpenOption.READ)) {
          channel.position(page * wal.pageSize());

          final var buffer = ByteBuffer.allocate(wal.pageSize());
          channel.read(buffer);

          buffer.put(42, (byte) (buffer.get(42) + 1));
          IOUtils.writeByteBuffer(buffer, channel, page * wal.pageSize());
        }

        // --- Recovery Phase ---
        // Reopen the WAL. The constructor does not validate old segments eagerly;
        // corruption will be detected lazily during read operations.
        var loadedWAL =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
                ContextConfiguration.WAL_DEFAULT_NAME,
                48_000,
                64,
                null,
                null,
                Integer.MAX_VALUE,
                10 * 1024 * 1024,
                20,
                true,
                Locale.US,
                10 * 1024 * 1024 * 1024L,
                1000,
                false,
                false,
                false,
                10);

        // --- Verification Phase ---
        // Read records from the beginning and compare with originals.
        // The first batch is fetched using read(), subsequent batches using next().
        var recordIterator = records.iterator();
        var walRecords = loadedWAL.read(records.getFirst().getLsn(), 100);
        var walRecordIterator = walRecords.iterator();

        LogSequenceNumber lastLSN = null;
        var recordCounter = 0;

        if (segment == 1 && page == 0) {
          // Special case: page 0 of segment 1 is corrupted, meaning the very first page
          // of the WAL is unreadable. No records can be read at all.
          Assert.assertTrue(walRecords.isEmpty());
        } else {
          // Read records sequentially, comparing each with the original.
          // When the corrupted page is reached, the XX_HASH check will fail and
          // next() will return an empty list, breaking the loop.
          while (recordIterator.hasNext()) {
            if (walRecordIterator.hasNext()) {
              final var walRecord = walRecordIterator.next();

              final var walTestRecord = (TestRecord) walRecord;
              final var record = recordIterator.next();

              Assert.assertEquals(record.getLsn(), walTestRecord.getLsn());
              Assert.assertArrayEquals(record.data, walTestRecord.data);

              lastLSN = record.getLsn();

              recordCounter++;
            } else {
              // Current batch exhausted — fetch the next batch of 100 records
              // starting after the last successfully read record.
              walRecords = loadedWAL.next(lastLSN, 100);

              if (walRecords.isEmpty()) {
                // No more readable records — corruption boundary reached.
                break;
              }

              walRecordIterator = walRecords.iterator();
            }
          }
        }

        // --- Corruption Boundary Assertion ---
        // The first record that could NOT be read (records[recordCounter]) must be in the
        // same segment as the corrupted page. Its starting page must be <= the corrupted
        // page index, because a record that starts before the corrupted page might span
        // into it, making it the first record to fail the checksum.
        final var nextRecordLSN = records.get(recordCounter).getLsn();
        Assert.assertEquals(segment, nextRecordLSN.getSegment());
        Assert.assertTrue(page >= nextRecordLSN.getPosition() / wal.pageSize());

        loadedWAL.close();

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
        // Re-print the seed on failure so the exact sequence can be reproduced for debugging.
        System.out.println("testWALCrash seed : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testIntegerOverflowNoException() throws Exception {
    final var wal =
        new CASDiskWriteAheadLog(
            "walTest",
            testDirectory,
            testDirectory,
            ContextConfiguration.WAL_DEFAULT_NAME,
            Integer.MAX_VALUE,
            64,
            null,
            null,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            20,
            true,
            Locale.US,
            -1,
            1000,
            false,
            false,
            false,
            10);
    wal.close();
    Assert.assertEquals(
        "Integer.MAX overflow must be reset to Integer.MAX.",
        CASDiskWriteAheadLog.DEFAULT_MAX_CACHE_SIZE,
        wal.maxCacheSize());
  }

  @Test
  public void testIntegerNegativeNoException() throws Exception {
    final var wal =
        new CASDiskWriteAheadLog(
            "walTest",
            testDirectory,
            testDirectory,
            ContextConfiguration.WAL_DEFAULT_NAME,
            -27,
            64,
            null,
            null,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            20,
            true,
            Locale.US,
            -1,
            1000,
            false,
            false,
            false,
            10);
    wal.close();
    Assert.assertTrue(
        "Negative int must not produce exception in `doFlush`", 0 > wal.maxCacheSize());
  }

  @Test
  public void testIntegerNegativeOverflowNoException() throws Exception {
    final var wal =
        new CASDiskWriteAheadLog(
            "walTest",
            testDirectory,
            testDirectory,
            ContextConfiguration.WAL_DEFAULT_NAME,
            Integer.MIN_VALUE,
            64,
            null,
            null,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            20,
            true,
            Locale.US,
            -1,
            1000,
            false,
            false,
            false,
            10);
    wal.close();
    Assert.assertEquals(
        "Integer.MIN overflow must be reset to Integer.MAX.",
        CASDiskWriteAheadLog.DEFAULT_MAX_CACHE_SIZE,
        wal.maxCacheSize());
  }

  private static void checkThatSegmentsBellowAreRemoved(CASDiskWriteAheadLog wal) {
    final var begin = wal.begin();

    for (var i = 0; i < begin.getSegment(); i++) {
      final var segmentPath = testDirectory.resolve(getSegmentName(i));
      Assert.assertFalse(Files.exists(segmentPath));
    }
  }

  private static void checkThatAllNonActiveSegmentsAreRemoved(
      long[] nonActive, long segment, CASDiskWriteAheadLog wal) {
    if (nonActive.length == 0) {
      return;
    }

    final var index = Arrays.binarySearch(nonActive, segment);
    final var begin = wal.begin();

    if (index < 0) {
      Assert.assertTrue(begin.getSegment() > nonActive[nonActive.length - 1]);
    } else {
      Assert.assertTrue(begin.getSegment() >= nonActive[index]);
    }
  }

  private static void addLimit(
      TreeMap<LogSequenceNumber, Integer> limits, LogSequenceNumber lsn) {
    limits.merge(lsn, 1, Integer::sum);
  }

  private static void removeLimit(
      TreeMap<LogSequenceNumber, Integer> limits, LogSequenceNumber lsn) {
    var counter = limits.get(lsn);
    if (counter == 1) {
      limits.remove(lsn);
    } else {
      limits.put(lsn, counter - 1);
    }
  }

  private static LogSequenceNumber chooseRandomRecord(
      Random random, NavigableMap<LogSequenceNumber, ? extends WriteableWALRecord> records) {
    if (records.isEmpty()) {
      return null;
    }
    var first = records.firstKey();
    var last = records.lastKey();

    final var firstSegment = (int) first.getSegment();
    final var lastSegment = (int) last.getSegment();

    final int segment;
    if (lastSegment > firstSegment) {
      segment = random.nextInt(lastSegment - firstSegment) + firstSegment;
    } else {
      segment = lastSegment;
    }

    final var lastLSN =
        records.floorKey(new LogSequenceNumber(segment, Integer.MAX_VALUE));
    final var position = random.nextInt(lastLSN.getPosition());

    var lsn = records.ceilingKey(new LogSequenceNumber(segment, position));
    Assert.assertNotNull(lsn);

    return lsn;
  }

  private static String getSegmentName(long segment) {
    return ContextConfiguration.WAL_DEFAULT_NAME + "." + segment + ".wal";
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

    TestRecord(Random random, int maxSize, int minSize) {
      var len = random.nextInt(maxSize - minSize + 1) + 1;
      data = new byte[len];
      random.nextBytes(data);
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
      return 1024;
    }
  }
}
