package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.cas;

import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.AbstractWALRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        var walRecord = new TestRecord(random, wal.pageSize(), 1);
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.get(0);

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        wal.flush();

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        records = wal.read(lsn, 10);
        Assert.assertEquals(2, records.size());
        readRecord = (TestRecord) records.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        var walRecord = new TestRecord(random, wal.pageSize(), 1);
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.get(0);

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());

        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        wal.flush();

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        records = wal.read(lsn, 10);
        Assert.assertEquals(2, records.size());
        readRecord = (TestRecord) records.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        var walRecord = new TestRecord(random, wal.pageSize(), 1);
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.get(0);

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, walRecord.getLsn());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        wal.flush();

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        var walRecord = new TestRecord(random, wal.pageSize(), 1);
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        wal.flush();

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.get(0);

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, readRecord.getLsn());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        wal.flush();

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        records = wal.read(lsn, 10);
        Assert.assertEquals(2, records.size());
        Assert.assertEquals(lsn, records.get(0).getLsn());
        readRecord = (TestRecord) records.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
        final var lsn = wal.log(walRecord);

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), lsn);

        var records = wal.read(lsn, 10);
        Assert.assertEquals(1, records.size());
        var readRecord = (TestRecord) records.get(0);

        Assert.assertArrayEquals(walRecord.data, readRecord.data);
        Assert.assertEquals(lsn, walRecord.getLsn());
        wal.close();

        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        wal.flush();

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

        records = wal.read(lsn, 10);
        Assert.assertEquals(2, records.size());
        Assert.assertEquals(lsn, records.get(0).getLsn());
        readRecord = (TestRecord) records.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        for (var i = 0; i < 5; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        LogSequenceNumber lastLsn;
        for (var i = 0; i < numberOfSegmentsToAdd; i++) {
          wal.appendNewSegment();

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
          Assert.assertEquals(wal.end(), new LogSequenceNumber(i + 2, CASWALPage.RECORDS_OFFSET));

          final var recordsCount = random.nextInt(10_000) + 100;
          for (var k = 0; k < recordsCount; k++) {
            final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
            lastLsn = wal.log(walRecord);

            records.add(walRecord);

            Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        LogSequenceNumber lastLsn;
        for (var i = 0; i < numberOfSegmentsToAdd; i++) {
          wal.appendNewSegment();

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
          Assert.assertEquals(wal.end(), new LogSequenceNumber(i + 2, CASWALPage.RECORDS_OFFSET));

          final var recordsCount = random.nextInt(10_000) + 100;
          for (var k = 0; k < recordsCount; k++) {
            final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
            lastLsn = wal.log(walRecord);

            records.add(walRecord);

            Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 2 * wal.pageSize(), wal.pageSize());
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));

        List<TestRecord> records = new ArrayList<>();

        final var recordsCount = 10_000;

        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        Assert.assertEquals(wal.end(), new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET));

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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

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

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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
                record.data, ((TestRecord) (wal.read(record.getLsn(), 1).get(0))).data);
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
              record.data, ((TestRecord) (loadedWAL.read(record.getLsn(), 1).get(0))).data);
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

  @Test
  public void testAppendSegment() throws Exception {
    var iterations = 1;
    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      System.out.println("testAppendSegment seed : " + seed);
      final var random = new Random(seed);

      try {
        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        List<TestRecord> records = new ArrayList<>();

        var segmentWasAdded = false;
        System.out.println("Load data");
        final var recordsCount = 100_000;
        for (var i = 0; i < recordsCount; i++) {
          segmentWasAdded = false;
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
          Assert.assertEquals(wal.end(), lsn);

          if (random.nextDouble() < 0.05) {
            final var segments = random.nextInt(5) + 1;

            for (var k = 0; k < segments; k++) {
              wal.appendNewSegment();
              segmentWasAdded = true;
            }
          }
        }

        System.out.println("First check");
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

        System.out.println("Second check");
        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        if (segmentWasAdded) {
          Assert.assertEquals(
              new LogSequenceNumber(
                  records.get(records.size() - 1).getLsn().getSegment() + 4,
                  CASWALPage.RECORDS_OFFSET),
              wal.end());
        } else {
          Assert.assertEquals(
              new LogSequenceNumber(
                  records.get(records.size() - 1).getLsn().getSegment() + 1,
                  CASWALPage.RECORDS_OFFSET),
              wal.end());
        }

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

        Thread.sleep(2);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Error | Exception e) {
        System.out.println("testAppendSegment seed : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testAppendSegmentNext() throws Exception {
    final var iterations = 1;
    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      System.out.println("testAppendSegmentNext seed : " + seed);
      final var random = new Random(seed);

      try {
        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        List<TestRecord> records = new ArrayList<>();

        var segmentWasAdded = false;
        System.out.println("Load data");
        final var recordsCount = 100_000;
        for (var i = 0; i < recordsCount; i++) {
          segmentWasAdded = false;
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          records.add(walRecord);

          var lsn = wal.log(walRecord);
          Assert.assertEquals(walRecord.getLsn(), lsn);

          Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
          Assert.assertEquals(wal.end(), lsn);

          if (random.nextDouble() < 0.05) {
            final var segments = random.nextInt(5) + 1;

            for (var k = 0; k < segments; k++) {
              wal.appendNewSegment();
              segmentWasAdded = true;
            }
          }
        }

        System.out.println("First check");
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

        System.out.println("Second check");
        wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
        if (segmentWasAdded) {
          Assert.assertEquals(
              new LogSequenceNumber(
                  records.get(records.size() - 1).getLsn().getSegment() + 2,
                  CASWALPage.RECORDS_OFFSET),
              wal.end());
        } else {
          Assert.assertEquals(
              new LogSequenceNumber(
                  records.get(records.size() - 1).getLsn().getSegment() + 1,
                  CASWALPage.RECORDS_OFFSET),
              wal.end());
        }
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
        Assert.assertEquals(lastResult.size(), 1);
        var emptyRecord = lastResult.get(0);

        Assert.assertTrue(emptyRecord instanceof EmptyWALRecord);

        wal.close();

        Thread.sleep(2);
        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Error | Exception e) {
        System.out.println("testAppendSegmentNext seed : " + seed);
        throw e;
      }
    }
  }

  @Test
  public void testDelete() throws Exception {
    var wal =
        new CASDiskWriteAheadLog(
            "walTest",
            testDirectory,
            testDirectory,
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

      Assert.assertEquals(wal.begin(), new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET));
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

  @Test
  public void testWALCrash() throws Exception {
    final var iterations = 1;

    for (var n = 0; n < iterations; n++) {
      FileUtils.deleteRecursively(testDirectory.toFile());

      final var seed = System.nanoTime();
      final var random = new Random(seed);

      try {
        var wal =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        final List<TestRecord> records = new ArrayList<>();
        final var recordsCount = 100_000;
        for (var i = 0; i < recordsCount; i++) {
          final var walRecord = new TestRecord(random, 3 * wal.pageSize(), 1);
          wal.log(walRecord);
          records.add(walRecord);
        }

        wal.close();

        final var index = random.nextInt(records.size());
        final var lsn = records.get(index).getLsn();
        final var segment = lsn.getSegment();
        final long page = lsn.getPosition() / wal.pageSize();

        try (final var channel =
            FileChannel.open(
                testDirectory.resolve(getSegmentName(segment)),
                StandardOpenOption.WRITE,
                StandardOpenOption.READ)) {
          channel.position(page * wal.pageSize());

          final var buffer = ByteBuffer.allocate(wal.pageSize());
          channel.read(buffer);

          buffer.put(42, (byte) (buffer.get(42) + 1));
          buffer.position(0);

          channel.write(buffer);
        }

        var loadedWAL =
            new CASDiskWriteAheadLog(
                "walTest",
                testDirectory,
                testDirectory,
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

        var recordIterator = records.iterator();
        var walRecords = loadedWAL.read(records.get(0).getLsn(), 100);
        var walRecordIterator = walRecords.iterator();

        LogSequenceNumber lastLSN = null;
        var recordCounter = 0;

        if (segment == 1 && page == 0) {
          Assert.assertTrue(walRecords.isEmpty());
        } else {
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
              walRecords = loadedWAL.next(lastLSN, 100);

              if (walRecords.isEmpty()) {
                break;
              }

              walRecordIterator = walRecords.iterator();
            }
          }
        }

        final var nextRecordLSN = records.get(recordCounter).getLsn();
        Assert.assertEquals(segment, nextRecordLSN.getSegment());
        Assert.assertTrue(page >= nextRecordLSN.getPosition() / wal.pageSize());

        loadedWAL.close();

        System.out.printf("%d iterations out of %d were passed\n", n, iterations);
      } catch (Exception | Error e) {
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

  private void checkThatSegmentsBellowAreRemoved(CASDiskWriteAheadLog wal) {
    final var begin = wal.begin();

    for (var i = 0; i < begin.getSegment(); i++) {
      final var segmentPath = testDirectory.resolve(getSegmentName(i));
      Assert.assertFalse(Files.exists(segmentPath));
    }
  }

  private void checkThatAllNonActiveSegmentsAreRemoved(
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

  private String getSegmentName(long segment) {
    return "walTest." + segment + ".wal";
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
