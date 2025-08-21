package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.common.BasicYouTrackDB;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BTreeTestIT {

  private AtomicOperationsManager atomicOperationsManager;
  private BTree<String> singleValueTree;
  private BasicYouTrackDB youTrackDB;

  private String dbName;

  @Before
  public void before() throws Exception {
    final var buildDirectory =
        System.getProperty("buildDirectory", ".")
            + File.separator
            + BTreeTestIT.class.getSimpleName();

    dbName = "localSingleBTreeTest";
    final var dbDirectory = new File(buildDirectory, dbName);
    FileUtils.deleteRecursively(dbDirectory);

    final var config = YouTrackDBConfig.builder().build();
    youTrackDB = YourTracks.embedded(buildDirectory, config);
    youTrackDB.execute(
        "create database " + dbName + " disk users ( admin identified by 'admin' role admin)");

    AbstractStorage storage;
    try (var databaseDocumentTx = youTrackDB.open(dbName, "admin", "admin")) {
      storage =
          (AbstractStorage) ((DatabaseSessionInternal) databaseDocumentTx).getStorage();
    }
    singleValueTree = new BTree<>("singleBTree", ".sbt", ".nbt", storage);
    atomicOperationsManager = storage.getAtomicOperationsManager();
    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            singleValueTree.create(atomicOperation, UTF8Serializer.INSTANCE, null, 1
            ));
  }

  @After
  public void afterMethod() {
    youTrackDB.drop(dbName);
    youTrackDB.close();
  }

  @Test
  public void testKeyPut() throws Exception {
    final var keysCount = 1_000_000;

    final var rollbackInterval = 100;
    var lastKey = new String[1];
    for (var i = 0; i < keysCount / rollbackInterval; i++) {
      for (var n = 0; n < 2; n++) {
        final var iterationCounter = i;
        final var rollbackCounter = n;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                for (var j = 0; j < rollbackInterval; j++) {
                  final var key = Integer.toString(iterationCounter * rollbackInterval + j);
                  singleValueTree.put(
                      atomicOperation,
                      key, new RecordId(
                          (iterationCounter * rollbackInterval + j) % 32000,
                          iterationCounter * rollbackInterval + j));

                  if (rollbackCounter == 1) {
                    if ((iterationCounter * rollbackInterval + j) % 100_000 == 0) {
                      System.out.printf(
                          "%d items loaded out of %d%n",
                          iterationCounter * rollbackInterval + j, keysCount);
                    }

                    if (lastKey[0] == null) {
                      lastKey[0] = key;
                    } else if (key.compareTo(lastKey[0]) > 0) {
                      lastKey[0] = key;
                    }
                  }
                }
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }
      Assert.assertEquals("0", singleValueTree.firstKey());
      Assert.assertEquals(lastKey[0], singleValueTree.lastKey());
    }

    for (var i = 0; i < keysCount; i++) {
      Assert.assertEquals(
          i + " key is absent",
          new RecordId(i % 32000, i),
          singleValueTree.get(Integer.toString(i)));
      if (i % 100_000 == 0) {
        System.out.printf("%d items tested out of %d%n", i, keysCount);
      }
    }
    for (var i = keysCount; i < 2 * keysCount; i++) {
      Assert.assertNull(singleValueTree.get(Integer.toString(i)));
    }
  }

  @Test
  public void testKeyPutRandomUniform() throws Exception {
    final NavigableSet<String> keys = new TreeSet<>();
    final var random = new Random();
    final var keysCount = 1_000_000;

    final var rollbackRange = 100;
    while (keys.size() < keysCount) {
      for (var n = 0; n < 2; n++) {
        final var rollbackCounter = n;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                for (var i = 0; i < rollbackRange; i++) {
                  var val = random.nextInt(Integer.MAX_VALUE);
                  var key = Integer.toString(val);
                  singleValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));

                  if (rollbackCounter == 1) {
                    keys.add(key);
                  }
                  Assert.assertEquals(singleValueTree.get(key), new RecordId(val % 32000, val));
                }
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }
    }

    Assert.assertEquals(singleValueTree.firstKey(), keys.first());
    Assert.assertEquals(singleValueTree.lastKey(), keys.last());
    for (var key : keys) {
      final var val = Integer.parseInt(key);
      Assert.assertEquals(singleValueTree.get(key), new RecordId(val % 32000, val));
    }
  }

  @Test
  public void testKeyPutRandomGaussian() throws Exception {
    NavigableSet<String> keys = new TreeSet<>();
    var seed = System.currentTimeMillis();
    System.out.println("testKeyPutRandomGaussian seed : " + seed);

    var random = new Random(seed);
    final var keysCount = 1_000_000;
    final var rollbackRange = 100;

    while (keys.size() < keysCount) {
      for (var n = 0; n < 2; n++) {
        final var rollbackCounter = n;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                for (var i = 0; i < rollbackRange; i++) {
                  int val;
                  do {
                    val = (int) (random.nextGaussian() * Integer.MAX_VALUE / 2 + Integer.MAX_VALUE);
                  } while (val < 0);

                  var key = Integer.toString(val);
                  singleValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                  if (rollbackCounter == 1) {
                    keys.add(key);
                  }
                  Assert.assertEquals(singleValueTree.get(key), new RecordId(val % 32000, val));
                }
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }
    }
    Assert.assertEquals(singleValueTree.firstKey(), keys.first());
    Assert.assertEquals(singleValueTree.lastKey(), keys.last());

    for (var key : keys) {
      var val = Integer.parseInt(key);
      Assert.assertEquals(singleValueTree.get(key), new RecordId(val % 32000, val));
    }
  }

  @Test
  public void testKeyDeleteRandomUniform() throws Exception {
    final var keysCount = 1_000_000;

    NavigableSet<String> keys = new TreeSet<>();
    for (var i = 0; i < keysCount; i++) {
      var key = Integer.toString(i);
      final var k = i;
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              singleValueTree.put(atomicOperation, key, new RecordId(k % 32000, k)));
      keys.add(key);
    }

    final var rollbackInterval = 10;
    var keysIterator = keys.iterator();
    while (keysIterator.hasNext()) {
      var key = keysIterator.next();
      if (Integer.parseInt(key) % 3 == 0) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null, atomicOperation -> singleValueTree.remove(atomicOperation, key));
        keysIterator.remove();
      }

      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              var rollbackCounter = 0;
              final var keysDeletionIterator = keys.tailSet(key, false).iterator();
              while (keysDeletionIterator.hasNext() && rollbackCounter < rollbackInterval) {
                var keyToDelete = keysDeletionIterator.next();
                rollbackCounter++;
                singleValueTree.remove(atomicOperation, keyToDelete);
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }
    Assert.assertEquals(singleValueTree.firstKey(), keys.first());
    Assert.assertEquals(singleValueTree.lastKey(), keys.last());

    for (var key : keys) {
      var val = Integer.parseInt(key);
      if (val % 3 == 0) {
        Assert.assertNull(singleValueTree.get(key));
      } else {
        Assert.assertEquals(singleValueTree.get(key), new RecordId(val % 32000, val));
      }
    }
  }

  @Test
  public void testKeyDeleteRandomGaussian() throws Exception {
    NavigableSet<String> keys = new TreeSet<>();
    final var keysCount = 1_000_000;
    var seed = System.currentTimeMillis();
    System.out.println("testKeyDeleteRandomGaussian seed : " + seed);
    var random = new Random(seed);

    while (keys.size() < keysCount) {
      var val = (int) (random.nextGaussian() * Integer.MAX_VALUE / 2 + Integer.MAX_VALUE);
      if (val < 0) {
        continue;
      }
      var key = Integer.toString(val);
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              singleValueTree.put(atomicOperation, key, new RecordId(val % 32000, val)));
      keys.add(key);

      Assert.assertEquals(singleValueTree.get(key), new RecordId(val % 32000, val));
    }
    var keysIterator = keys.iterator();

    final var rollbackInterval = 10;
    while (keysIterator.hasNext()) {
      var key = keysIterator.next();

      if (Integer.parseInt(key) % 3 == 0) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null, atomicOperation -> singleValueTree.remove(atomicOperation, key));
        keysIterator.remove();
      }
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              var rollbackCounter = 0;
              final var keysDeletionIterator = keys.tailSet(key, false).iterator();
              while (keysDeletionIterator.hasNext() && rollbackCounter < rollbackInterval) {
                var keyToDelete = keysDeletionIterator.next();
                rollbackCounter++;
                singleValueTree.remove(atomicOperation, keyToDelete);
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }
    Assert.assertEquals(singleValueTree.firstKey(), keys.first());
    Assert.assertEquals(singleValueTree.lastKey(), keys.last());

    for (var key : keys) {
      var val = Integer.parseInt(key);
      if (val % 3 == 0) {
        Assert.assertNull(singleValueTree.get(key));
      } else {
        Assert.assertEquals(singleValueTree.get(key), new RecordId(val % 32000, val));
      }
    }
  }

  @Test
  public void testKeyDelete() throws Exception {
    final var keysCount = 1_000_000;

    for (var i = 0; i < keysCount; i++) {
      final var k = i;
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              singleValueTree.put(
                  atomicOperation, Integer.toString(k), new RecordId(k % 32000, k)));
    }
    final var rollbackInterval = 100;

    for (var i = 0; i < keysCount / rollbackInterval; i++) {
      for (var n = 0; n < 2; n++) {
        final var rollbackCounter = n;
        final var iterationsCounter = i;

        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                for (var j = 0; j < rollbackInterval; j++) {
                  final var key = iterationsCounter * rollbackInterval + j;
                  if (key % 3 == 0) {
                    Assert.assertEquals(
                        singleValueTree.remove(atomicOperation, Integer.toString(key)),
                        new RecordId(key % 32000, key));
                  }
                }
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }
    }
    for (var i = 0; i < keysCount; i++) {
      if (i % 3 == 0) {
        Assert.assertNull(singleValueTree.get(Integer.toString(i)));
      } else {
        Assert.assertEquals(singleValueTree.get(Integer.toString(i)), new RecordId(i % 32000, i));
      }
    }
  }

  @Test
  public void testKeyAddDelete() throws Exception {
    final var keysCount = 1_000_000;

    for (var i = 0; i < keysCount; i++) {
      final var key = i;
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              singleValueTree.put(
                  atomicOperation, Integer.toString(key), new RecordId(key % 32000, key)));

      Assert.assertEquals(singleValueTree.get(Integer.toString(i)), new RecordId(i % 32000, i));
    }
    final var rollbackInterval = 100;

    for (var i = 0; i < keysCount / rollbackInterval; i++) {
      for (var n = 0; n < 2; n++) {
        final var rollbackCounter = n;
        final var iterationsCounter = i;

        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                for (var j = 0; j < rollbackInterval; j++) {
                  final var key = iterationsCounter * rollbackInterval + j;

                  if (key % 3 == 0) {
                    Assert.assertEquals(
                        singleValueTree.remove(atomicOperation, Integer.toString(key)),
                        new RecordId(key % 32000, key));
                  }

                  if (key % 2 == 0) {
                    singleValueTree.put(
                        atomicOperation,
                        Integer.toString(keysCount + key),
                        new RecordId((keysCount + key) % 32000, keysCount + key));
                  }
                }
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }
    }

    for (var i = 0; i < keysCount; i++) {
      if (i % 3 == 0) {
        Assert.assertNull(singleValueTree.get(Integer.toString(i)));
      } else {
        Assert.assertEquals(singleValueTree.get(Integer.toString(i)), new RecordId(i % 32000, i));
      }

      if (i % 2 == 0) {
        Assert.assertEquals(
            singleValueTree.get(Integer.toString(keysCount + i)),
            new RecordId((keysCount + i) % 32000, keysCount + i));
      }
    }
  }

  @Test
  public void testKeyAddDeleteAll() throws Exception {
    for (var iterations = 0; iterations < 4; iterations++) {
      System.out.println("testKeyAddDeleteAll : iteration " + iterations);

      final var keysCount = 1_000_000;

      for (var i = 0; i < keysCount; i++) {
        final var key = i;
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                singleValueTree.put(
                    atomicOperation, Integer.toString(key), new RecordId(key % 32000, key)));

        Assert.assertEquals(singleValueTree.get(Integer.toString(i)), new RecordId(i % 32000, i));
      }

      for (var i = 0; i < keysCount; i++) {
        final var key = i;
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              Assert.assertEquals(
                  singleValueTree.remove(atomicOperation, Integer.toString(key)),
                  new RecordId(key % 32000, key));

              if (key > 0 && key % 100_000 == 0) {
                for (var keyToVerify = 0; keyToVerify < keysCount; keyToVerify++) {
                  if (keyToVerify > key) {
                    Assert.assertEquals(
                        new RecordId(keyToVerify % 32000, keyToVerify),
                        singleValueTree.get(Integer.toString(keyToVerify)));
                  } else {
                    Assert.assertNull(singleValueTree.get(Integer.toString(keyToVerify)));
                  }
                }
              }
            });
      }
      for (var i = 0; i < keysCount; i++) {
        Assert.assertNull(singleValueTree.get(Integer.toString(i)));
      }

      singleValueTree.assertFreePages(atomicOperationsManager.getCurrentOperation());
    }
  }

  @Test
  public void testKeyAddDeleteHalf() throws Exception {
    final var keysCount = 1_000_000;

    for (var i = 0; i < keysCount / 2; i++) {
      final var key = i;
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              singleValueTree.put(
                  atomicOperation, Integer.toString(key), new RecordId(key % 32000, key)));

      Assert.assertEquals(singleValueTree.get(Integer.toString(i)), new RecordId(i % 32000, i));
    }

    for (var iterations = 0; iterations < 4; iterations++) {
      System.out.println("testKeyAddDeleteHalf : iteration " + iterations);

      for (var i = 0; i < keysCount / 2; i++) {
        final var key = i + (iterations + 1) * keysCount / 2;
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                singleValueTree.put(
                    atomicOperation, Integer.toString(key), new RecordId(key % 32000, key)));

        Assert.assertEquals(
            singleValueTree.get(Integer.toString(key)), new RecordId(key % 32000, key));
      }

      final var offset = iterations * (keysCount / 2);

      for (var i = 0; i < keysCount / 2; i++) {
        final var key = i + offset;
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertEquals(
                    singleValueTree.remove(atomicOperation, Integer.toString(key)),
                    new RecordId(key % 32000, key)));
      }

      final var start = (iterations + 1) * (keysCount / 2);
      for (var i = 0; i < (iterations + 2) * keysCount / 2; i++) {
        if (i < start) {
          Assert.assertNull(singleValueTree.get(Integer.toString(i)));
        } else {
          Assert.assertEquals(
              new RecordId(i % 32000, i), singleValueTree.get(Integer.toString(i)));
        }
      }

      singleValueTree.assertFreePages(atomicOperationsManager.getCurrentOperation());
    }
  }

  @Test
  public void testKeyCursor() throws Exception {
    final var keysCount = 1_000_000;

    NavigableMap<String, RID> keyValues = new TreeMap<>();
    final var seed = System.nanoTime();

    System.out.println("testKeyCursor: " + seed);
    var random = new Random(seed);

    final var rollbackInterval = 100;

    var printCounter = 0;
    while (keyValues.size() < keysCount) {
      for (var n = 0; n < 2; n++) {
        final var rollbackCounter = n;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                for (var j = 0; j < rollbackInterval; j++) {
                  var val = random.nextInt(Integer.MAX_VALUE);
                  var key = Integer.toString(val);

                  singleValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                  if (rollbackCounter == 1) {
                    keyValues.put(key, new RecordId(val % 32000, val));
                  }
                }
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }
      if (keyValues.size() > printCounter * 100_000) {
        System.out.println(keyValues.size() + " entries were added.");
        printCounter++;
      }
    }
    Assert.assertEquals(singleValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(singleValueTree.lastKey(), keyValues.lastKey());

    final Iterator<String> indexIterator;
    try (var stream = singleValueTree.keyStream()) {
      indexIterator = stream.iterator();
      for (var entryKey : keyValues.keySet()) {
        final var indexKey = indexIterator.next();
        Assert.assertEquals(entryKey, indexKey);
      }
    }
  }

  @Test
  public void testIterateEntriesMajor() throws Exception {
    final var keysCount = 1_000_000;

    NavigableMap<String, RID> keyValues = new TreeMap<>();
    final var seed = System.nanoTime();

    System.out.println("testIterateEntriesMajor: " + seed);
    final var random = new Random(seed);

    final var rollbackInterval = 100;

    var printCounter = 0;

    while (keyValues.size() < keysCount) {
      for (var n = 0; n < 2; n++) {
        final var rollbackCounter = n;

        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                for (var j = 0; j < rollbackInterval; j++) {
                  var val = random.nextInt(Integer.MAX_VALUE);
                  var key = Integer.toString(val);

                  singleValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                  if (rollbackCounter == 1) {
                    keyValues.put(key, new RecordId(val % 32000, val));
                  }
                }
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }

      if (keyValues.size() > printCounter * 100_000) {
        System.out.println(keyValues.size() + " entries were added.");
        printCounter++;
      }
    }

    assertIterateMajorEntries(keyValues, random, true, true);
    assertIterateMajorEntries(keyValues, random, false, true);

    assertIterateMajorEntries(keyValues, random, true, false);
    assertIterateMajorEntries(keyValues, random, false, false);

    Assert.assertEquals(singleValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(singleValueTree.lastKey(), keyValues.lastKey());
  }

  @Test
  public void testIterateEntriesMinor() throws Exception {
    final var keysCount = 1_000_000;
    NavigableMap<String, RID> keyValues = new TreeMap<>();

    final var seed = System.nanoTime();

    System.out.println("testIterateEntriesMinor: " + seed);
    final var random = new Random(seed);

    final var rollbackInterval = 100;
    var printCounter = 0;

    while (keyValues.size() < keysCount) {
      for (var n = 0; n < 2; n++) {
        final var rollbackCounter = n;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                for (var j = 0; j < rollbackInterval; j++) {
                  var val = random.nextInt(Integer.MAX_VALUE);
                  var key = Integer.toString(val);

                  singleValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                  if (rollbackCounter == 1) {
                    keyValues.put(key, new RecordId(val % 32000, val));
                  }
                }
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }

      if (keyValues.size() > printCounter * 100_000) {
        System.out.println(keyValues.size() + " entries were added.");
        printCounter++;
      }
    }
    assertIterateMinorEntries(keyValues, random, true, true);
    assertIterateMinorEntries(keyValues, random, false, true);

    assertIterateMinorEntries(keyValues, random, true, false);
    assertIterateMinorEntries(keyValues, random, false, false);

    Assert.assertEquals(singleValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(singleValueTree.lastKey(), keyValues.lastKey());
  }

  @Test
  public void testIterateEntriesBetween() throws Exception {
    final var keysCount = 1_000_000;
    NavigableMap<String, RID> keyValues = new TreeMap<>();
    final var random = new Random();

    final var rollbackInterval = 100;

    var printCounter = 0;

    while (keyValues.size() < keysCount) {
      for (var n = 0; n < 2; n++) {
        final var rollbackCounter = n;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                for (var j = 0; j < rollbackInterval; j++) {
                  var val = random.nextInt(Integer.MAX_VALUE);
                  var key = Integer.toString(val);

                  singleValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                  if (rollbackCounter == 1) {
                    keyValues.put(key, new RecordId(val % 32000, val));
                  }
                }

                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }

      if (keyValues.size() > printCounter * 100_000) {
        System.out.println(keyValues.size() + " entries were added.");
        printCounter++;
      }
    }
    assertIterateBetweenEntries(keyValues, random, true, true, true);
    assertIterateBetweenEntries(keyValues, random, true, false, true);
    assertIterateBetweenEntries(keyValues, random, false, true, true);
    assertIterateBetweenEntries(keyValues, random, false, false, true);

    assertIterateBetweenEntries(keyValues, random, true, true, false);
    assertIterateBetweenEntries(keyValues, random, true, false, false);
    assertIterateBetweenEntries(keyValues, random, false, true, false);
    assertIterateBetweenEntries(keyValues, random, false, false, false);

    Assert.assertEquals(singleValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(singleValueTree.lastKey(), keyValues.lastKey());
  }

  @Test
  public void testIterateEntriesBetweenString() throws Exception {
    final var keysCount = 10;
    final NavigableMap<String, RID> keyValues = new TreeMap<>();
    final var random = new Random();
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            for (var j = 0; j < keysCount; j++) {
              final var key = "name" + j;
              final var val = random.nextInt(Integer.MAX_VALUE);
              final var collectionId = val % 32000;
              singleValueTree.put(atomicOperation, key, new RecordId(collectionId, val));
              System.out.println("Added key=" + key + ", value=" + val);

              keyValues.put(key, new RecordId(collectionId, val));
            }
          });
    } catch (final RollbackException ignore) {
      Assert.fail();
    }
    assertIterateBetweenEntriesNonRandom("name5", keyValues, true, true, true, 5);

    Assert.assertEquals(singleValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(singleValueTree.lastKey(), keyValues.lastKey());
  }

  private void assertIterateMajorEntries(
      NavigableMap<String, RID> keyValues,
      Random random,
      boolean keyInclusive,
      boolean ascSortOrder) {
    var keys = new String[keyValues.size()];
    var index = 0;

    for (var key : keyValues.keySet()) {
      keys[index] = key;
      index++;
    }

    for (var i = 0; i < 100; i++) {
      final var fromKeyIndex = random.nextInt(keys.length);
      var fromKey = keys[fromKeyIndex];

      if (random.nextBoolean()) {
        fromKey =
            fromKey.substring(0, fromKey.length() - 2) + (fromKey.charAt(fromKey.length() - 1) - 1);
      }

      final Iterator<RawPair<String, RID>> indexIterator;
      try (var stream =
          singleValueTree.iterateEntriesMajor(fromKey, keyInclusive, ascSortOrder)) {
        indexIterator = stream.iterator();

        Iterator<Map.Entry<String, RID>> iterator;
        if (ascSortOrder) {
          iterator = keyValues.tailMap(fromKey, keyInclusive).entrySet().iterator();
        } else {
          iterator =
              keyValues
                  .descendingMap()
                  .subMap(keyValues.lastKey(), true, fromKey, keyInclusive)
                  .entrySet()
                  .iterator();
        }

        while (iterator.hasNext()) {
          final var indexEntry = indexIterator.next();
          final var entry = iterator.next();

          Assert.assertEquals(indexEntry.first(), entry.getKey());
          Assert.assertEquals(indexEntry.second(), entry.getValue());
        }

        //noinspection ConstantConditions
        Assert.assertFalse(iterator.hasNext());
        Assert.assertFalse(indexIterator.hasNext());
      }
    }
  }

  private void assertIterateMinorEntries(
      NavigableMap<String, RID> keyValues,
      Random random,
      boolean keyInclusive,
      boolean ascSortOrder) {
    var keys = new String[keyValues.size()];
    var index = 0;

    for (var key : keyValues.keySet()) {
      keys[index] = key;
      index++;
    }

    for (var i = 0; i < 100; i++) {
      var toKeyIndex = random.nextInt(keys.length);
      var toKey = keys[toKeyIndex];
      if (random.nextBoolean()) {
        toKey = toKey.substring(0, toKey.length() - 2) + (toKey.charAt(toKey.length() - 1) + 1);
      }

      final Iterator<RawPair<String, RID>> indexIterator;
      try (var stream =
          singleValueTree.iterateEntriesMinor(toKey, keyInclusive, ascSortOrder)) {
        indexIterator = stream.iterator();

        Iterator<Map.Entry<String, RID>> iterator;
        if (ascSortOrder) {
          iterator = keyValues.headMap(toKey, keyInclusive).entrySet().iterator();
        } else {
          iterator = keyValues.headMap(toKey, keyInclusive).descendingMap().entrySet().iterator();
        }

        while (iterator.hasNext()) {
          var indexEntry = indexIterator.next();
          var entry = iterator.next();

          Assert.assertEquals(indexEntry.first(), entry.getKey());
          Assert.assertEquals(indexEntry.second(), entry.getValue());
        }

        //noinspection ConstantConditions
        Assert.assertFalse(iterator.hasNext());
        Assert.assertFalse(indexIterator.hasNext());
      }
    }
  }

  private void assertIterateBetweenEntries(
      NavigableMap<String, RID> keyValues,
      Random random,
      boolean fromInclusive,
      boolean toInclusive,
      boolean ascSortOrder) {
    var keys = new String[keyValues.size()];
    var index = 0;

    for (var key : keyValues.keySet()) {
      keys[index] = key;
      index++;
    }

    for (var i = 0; i < 100; i++) {
      var fromKeyIndex = random.nextInt(keys.length);
      var toKeyIndex = random.nextInt(keys.length);

      if (fromKeyIndex > toKeyIndex) {
        toKeyIndex = fromKeyIndex;
      }

      var fromKey = keys[fromKeyIndex];
      var toKey = keys[toKeyIndex];

      if (random.nextBoolean()) {
        fromKey =
            fromKey.substring(0, fromKey.length() - 2) + (fromKey.charAt(fromKey.length() - 1) - 1);
      }

      if (random.nextBoolean()) {
        toKey = toKey.substring(0, toKey.length() - 2) + (toKey.charAt(toKey.length() - 1) + 1);
      }

      if (fromKey.compareTo(toKey) > 0) {
        fromKey = toKey;
      }

      final Iterator<RawPair<String, RID>> indexIterator;
      try (var stream =
          singleValueTree.iterateEntriesBetween(
              fromKey, fromInclusive, toKey, toInclusive, ascSortOrder)) {
        indexIterator = stream.iterator();

        Iterator<Map.Entry<String, RID>> iterator;
        if (ascSortOrder) {
          iterator =
              keyValues.subMap(fromKey, fromInclusive, toKey, toInclusive).entrySet().iterator();
        } else {
          iterator =
              keyValues
                  .descendingMap()
                  .subMap(toKey, toInclusive, fromKey, fromInclusive)
                  .entrySet()
                  .iterator();
        }

        while (iterator.hasNext()) {
          var indexEntry = indexIterator.next();
          Assert.assertNotNull(indexEntry);

          var mapEntry = iterator.next();
          Assert.assertEquals(indexEntry.first(), mapEntry.getKey());
          Assert.assertEquals(indexEntry.second(), mapEntry.getValue());
        }
        //noinspection ConstantConditions
        Assert.assertFalse(iterator.hasNext());
        Assert.assertFalse(indexIterator.hasNext());
      }
    }
  }

  private void assertIterateBetweenEntriesNonRandom(
      final String fromKey,
      final NavigableMap<String, RID> keyValues,
      final boolean fromInclusive,
      final boolean toInclusive,
      final boolean ascSortOrder,
      final int startFrom) {
    var keys = new String[keyValues.size()];
    var index = 0;

    for (final var key : keyValues.keySet()) {
      keys[index] = key;
      index++;
    }

    for (var i = startFrom; i < keyValues.size(); i++) {
      final var toKey = keys[i];
      final Iterator<RawPair<String, RID>> indexIterator;
      try (final var stream =
          singleValueTree.iterateEntriesBetween(
              fromKey, fromInclusive, toKey, toInclusive, ascSortOrder)) {
        indexIterator = stream.iterator();
        Assert.assertTrue(indexIterator.hasNext());
      }
    }
  }

  static final class RollbackException extends BaseException implements HighLevelException {

    @SuppressWarnings("WeakerAccess")
    public RollbackException() {
      this("");
    }

    @SuppressWarnings("WeakerAccess")
    public RollbackException(String message) {
      super(message);
    }

    @SuppressWarnings("unused")
    public RollbackException(RollbackException exception) {
      super(exception);
    }
  }
}
