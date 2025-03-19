package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.HighLevelException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CellBTreeMultiValueV2TestIT {

  private CellBTreeMultiValueV2<String> multiValueTree;
  private YouTrackDB youTrackDB;
  private AbstractPaginatedStorage storage;
  private AtomicOperationsManager atomicOperationsManager;

  private static final String DB_NAME = "localMultiBTreeTest";

  @Before
  public void before() throws IOException {
    final var buildDirectory =
        System.getProperty("buildDirectory", ".")
            + File.separator
            + CellBTreeMultiValueV2TestIT.class.getSimpleName();

    final var dbDirectory = new File(buildDirectory, DB_NAME);
    FileUtils.deleteRecursively(dbDirectory);

    final var config = YouTrackDBConfig.builder().build();
    youTrackDB = new YouTrackDBImpl("disk:" + buildDirectory, config);
    youTrackDB.execute(
        "create database " + DB_NAME + " disk users ( admin identified by 'admin' role admin)");

    try (var databaseDocumentTx = youTrackDB.open(DB_NAME, "admin", "admin")) {
      storage =
          (AbstractPaginatedStorage) ((DatabaseSessionInternal) databaseDocumentTx).getStorage();
    }

    atomicOperationsManager = storage.getAtomicOperationsManager();
    multiValueTree = new CellBTreeMultiValueV2<>("multiBTree", ".sbt", ".nbt", ".mdt", storage);

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            multiValueTree.create(UTF8Serializer.INSTANCE, null, 1, atomicOperation));
  }

  @After
  public void afterMethod() {
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();
  }

  @Test
  public void testPutNullKey() throws Exception {
    final var itemsCount = 64_000;

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32000, value)));

    final List<RID> result;
    try (var stream = multiValueTree.get(null)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new RecordId(i % 32000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveNullKey() throws IOException {
    final var itemsCount = 69_000;

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32000, value)));

    doInRollbackLoop(
        0,
        itemsCount / 3,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.remove(
                atomicOperation, null, new RecordId(3 * value % 32_000, 3L * value)));

    final List<RID> result;
    try (var stream = multiValueTree.get(null)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount / 3; i++) {
      final var val = i * 3;
      Assert.assertTrue(resultSet.contains(new RecordId((val + 1) % 32000, (val + 1))));
      Assert.assertTrue(resultSet.contains(new RecordId((val + 2) % 32000, (val + 2))));
    }
  }

  @Test
  public void testKeyPutRemoveSameTimeNullKey() throws IOException {
    final var itemsCount = 69_000;
    final var removed = new ModifiableInteger();

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        ((value, rollback, atomicOperation) -> {
          multiValueTree.put(atomicOperation, null, new RecordId(value % 32_000, value));

          if (value % 3 == 0) {
            multiValueTree.remove(atomicOperation, null, new RecordId(value % 32_000, value));
            if (!rollback) {
              removed.increment();
            }
          }
        }));

    final List<RID> result;
    try (var stream = multiValueTree.get(null)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount - removed.value, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount / 3; i++) {
      final var val = i * 3;
      Assert.assertTrue(resultSet.contains(new RecordId((val + 1) % 32000, (val + 1))));
      Assert.assertTrue(resultSet.contains(new RecordId((val + 2) % 32000, (val + 2))));
    }
  }

  @Test
  public void testKeyPutRemoveSameTimeBatchNullKey() throws IOException {
    final var itemsCount = 63_000;
    final var removed = new ModifiableInteger();

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        ((value, rollback, atomicOperation) -> {
          multiValueTree.put(atomicOperation, null, new RecordId(value % 32000, value));

          if (value > 0 && value % 9 == 0) {
            multiValueTree.remove(
                atomicOperation, null, new RecordId((value - 3) % 32_000, value - 3));
            multiValueTree.remove(
                atomicOperation, null, new RecordId((value - 6) % 32_000, value - 6));
            multiValueTree.remove(
                atomicOperation, null, new RecordId((value - 9) % 32_000, value - 9));

            if (!rollback) {
              removed.increment(3);
            }
          }
        }));

    final var roundedItems = ((itemsCount + 8) / 9) * 9;
    for (var n = 3; n < 10; n += 3) {
      final var counter = n;
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              multiValueTree.remove(
                  atomicOperation,
                  null,
                  new RecordId((roundedItems - counter) % 32_000, roundedItems - counter)));
      if (roundedItems - n < itemsCount) {
        removed.increment();
      }
    }

    final List<RID> result;
    try (var stream = multiValueTree.get(null)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount - removed.value, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount / 3; i++) {
      final var val = i * 3;
      Assert.assertTrue(resultSet.contains(new RecordId((val + 1) % 32000, (val + 1))));
      Assert.assertTrue(resultSet.contains(new RecordId((val + 2) % 32000, (val + 2))));
    }
  }

  @Test
  public void testKeyPutRemoveSliceNullKey() throws IOException {
    final var itemsCount = 64_000;

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32000, value)));

    final var start = itemsCount / 3;
    final var end = 2 * itemsCount / 3;

    doInRollbackLoop(
        start,
        end,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.remove(atomicOperation, null, new RecordId(value % 32_000, value)));

    final List<RID> result;
    try (var stream = multiValueTree.get(null)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount - (end - start), result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount; i++) {
      if (i >= start && i < end) {
        Assert.assertFalse(resultSet.contains(new RecordId(i % 32_000, i)));
      } else {
        Assert.assertTrue(resultSet.contains(new RecordId(i % 32_000, i)));
      }
    }
  }

  @Test
  public void testKeyPutRemoveSliceAndAddBackNullKey() throws IOException {
    final var itemsCount = 64_000;

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        ((value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32000, value))));

    final var start = itemsCount / 3;
    final var end = 2 * itemsCount / 3;

    doInRollbackLoop(
        start,
        end,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.remove(atomicOperation, null, new RecordId(value % 32_000, value)));
    doInRollbackLoop(
        start,
        end,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32_000, value)));

    final List<RID> result;
    try (var stream = multiValueTree.get(null)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new RecordId(i % 32_000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveSliceBeginEndNullKey() throws IOException {
    final var itemsCount = 64_000;

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32000, value)));

    final var rollbackSlice = 100;
    final var start = itemsCount / 3;
    final var end = 2 * itemsCount / 3;

    doInRollbackLoop(
        0,
        start,
        rollbackSlice,
        (value, rollback, atomicOperation) ->
            multiValueTree.remove(atomicOperation, null, new RecordId(value % 32_000, value)));
    doInRollbackLoop(
        end,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.remove(atomicOperation, null, new RecordId(value % 32_000, value)));

    final List<RID> result;
    try (var stream = multiValueTree.get(null)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount - (start + (itemsCount - end)), result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount; i++) {
      if (i < start || i >= end) {
        Assert.assertFalse(resultSet.contains(new RecordId(i % 32_000, i)));
      } else {
        Assert.assertTrue(resultSet.contains(new RecordId(i % 32_000, i)));
      }
    }
  }

  @Test
  public void testKeyPutRemoveSliceBeginEndAddBackOneNullKey() throws IOException {
    final var itemsCount = 64_000;

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32000, value)));

    final var start = itemsCount / 3;
    final var end = 2 * itemsCount / 3;

    doInRollbackLoop(
        0,
        start,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.remove(atomicOperation, null, new RecordId(value % 32_000, value)));
    doInRollbackLoop(
        end,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.remove(atomicOperation, null, new RecordId(value % 32_000, value)));
    doInRollbackLoop(
        0,
        start,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32_000, value)));
    doInRollbackLoop(
        end,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32_000, value)));

    final List<RID> result;
    try (var stream = multiValueTree.get(null)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new RecordId(i % 32_000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveSliceBeginEndAddBackTwoNullKey() throws IOException {
    final var itemsCount = 64_000;

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32000, value)));

    final var start = itemsCount / 3;
    final var end = 2 * itemsCount / 3;

    doInRollbackLoop(
        0,
        start,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.remove(atomicOperation, null, new RecordId(value % 32_000, value)));
    doInRollbackLoop(
        0,
        start,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32_000, value)));
    doInRollbackLoop(
        end,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.remove(atomicOperation, null, new RecordId(value % 32_000, value)));
    doInRollbackLoop(
        end,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, null, new RecordId(value % 32_000, value)));

    final List<RID> result;
    try (var stream = multiValueTree.get(null)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new RecordId(i % 32_000, i)));
    }
  }

  @Test
  public void testKeyPutSameKey() throws IOException {
    final var itemsCount = 1_000_000;
    final var key = "test_key";

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, key, new RecordId(value % 32000, value)));

    final List<RID> result;
    try (var stream = multiValueTree.get(key)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new RecordId(i % 32000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveSameKey() throws IOException {
    final var itemsCount = 256_000;
    final var key = "test_key";

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(atomicOperation, key, new RecordId(value % 32000, value)));

    doInRollbackLoop(
        0,
        itemsCount / 3,
        100,
        (value, rollback, atomicOperation) -> {
          final var val = 3 * value;
          multiValueTree.remove(atomicOperation, key, new RecordId(val % 32_000, val));
        });

    final List<RID> result;
    try (var stream = multiValueTree.get(key)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount / 3; i++) {
      final var val = i * 3;
      Assert.assertTrue(resultSet.contains(new RecordId((val + 1) % 32000, (val + 1))));
      Assert.assertTrue(resultSet.contains(new RecordId((val + 2) % 32000, (val + 2))));
    }
  }

  @Test
  public void testKeyPutTwoSameKeys() throws Exception {
    final var itemsCount = 1_000_000;
    final var keyOne = "test_key_one";
    final var keyTwo = "test_key_two";

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) -> {
          multiValueTree.put(atomicOperation, keyOne, new RecordId(value % 32000, value));
          multiValueTree.put(atomicOperation, keyTwo, new RecordId(value % 32000, value));
        });

    List<RID> result;
    try (var stream = multiValueTree.get(keyOne)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount, result.size());
    Set<RID> resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new RecordId(i % 32000, i)));
    }

    try (var stream = multiValueTree.get(keyTwo)) {
      result = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(itemsCount, result.size());
    resultSet = new HashSet<>(result);

    for (var i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new RecordId(i % 32000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveTwoSameKey() throws Exception {
    final var itemsCount = 1_000_000;
    final var keyOne = "test_key_1";
    final var keyTwo = "test_key_2";

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) -> {
          multiValueTree.put(atomicOperation, keyOne, new RecordId(value % 32000, value));
          multiValueTree.put(atomicOperation, keyTwo, new RecordId(value % 32000, value));
        });

    doInRollbackLoop(
        0,
        itemsCount / 3,
        100,
        (value, rollback, atomicOperation) -> {
          final var val = 3 * value;
          multiValueTree.remove(atomicOperation, keyOne, new RecordId(val % 32_000, val));
          multiValueTree.remove(atomicOperation, keyTwo, new RecordId(val % 32_000, val));
        });

    {
      final List<RID> result;
      try (var stream = multiValueTree.get(keyOne)) {
        result = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
      Set<RID> resultSet = new HashSet<>(result);

      for (var i = 0; i < itemsCount / 3; i++) {
        final var val = i * 3;
        Assert.assertTrue(resultSet.contains(new RecordId((val + 1) % 32000, (val + 1))));
        Assert.assertTrue(resultSet.contains(new RecordId((val + 2) % 32000, (val + 2))));
      }
    }

    {
      final List<RID> result;
      try (var stream = multiValueTree.get(keyTwo)) {
        result = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
      Set<RID> resultSet = new HashSet<>(result);

      for (var i = 0; i < itemsCount / 3; i++) {
        final var val = i * 3;
        Assert.assertTrue(resultSet.contains(new RecordId((val + 1) % 32000, (val + 1))));
        Assert.assertTrue(resultSet.contains(new RecordId((val + 2) % 32000, (val + 2))));
      }
    }
  }

  @Test
  public void testKeyPutTenSameKeys() throws Exception {
    final var itemsCount = 1_000_000;

    final var keys = new String[10];
    for (var i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + i;
    }

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) -> {
          for (var key : keys) {
            multiValueTree.put(atomicOperation, key, new RecordId(value % 32000, value));
          }
        });

    for (var key : keys) {
      List<RID> result;
      try (var stream = multiValueTree.get(key)) {
        result = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(itemsCount, result.size());
      Set<RID> resultSet = new HashSet<>(result);
      Assert.assertEquals(itemsCount, resultSet.size());

      for (var i = 0; i < itemsCount; i++) {
        Assert.assertTrue(resultSet.contains(new RecordId(i % 32000, i)));
      }
    }
  }

  @Test
  public void testKeyPutTenSameKeysReverse() throws Exception {
    final var itemsCount = 1_000_000;

    final var keys = new String[10];
    for (var i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + (9 - i);
    }

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) -> {
          for (var key : keys) {
            multiValueTree.put(atomicOperation, key, new RecordId(value % 32000, value));
          }
        });

    for (var key : keys) {
      List<RID> result;
      try (var stream = multiValueTree.get(key)) {
        result = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(itemsCount, result.size());
      Set<RID> resultSet = new HashSet<>(result);

      for (var i = 0; i < itemsCount; i++) {
        Assert.assertTrue(resultSet.contains(new RecordId(i % 32000, i)));
      }
    }
  }

  @Test
  public void testKeyPutRemoveTenSameKeys() throws Exception {
    final var itemsCount = 100_000;

    final var keys = new String[10];
    for (var i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + i;
    }

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) -> {
          for (var key : keys) {
            multiValueTree.put(atomicOperation, key, new RecordId(value % 32000, value));
          }
        });

    doInRollbackLoop(
        0,
        itemsCount / 3,
        100,
        (value, rollback, atomicOperation) -> {
          final var val = 3 * value;

          for (var key : keys) {
            multiValueTree.remove(atomicOperation, key, new RecordId(val % 32_000, val));
          }
        });

    for (var key : keys) {
      final List<RID> result;
      try (var stream = multiValueTree.get(key)) {
        result = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
      Set<RID> resultSet = new HashSet<>(result);

      for (var i = 0; i < itemsCount / 3; i++) {
        final var val = i * 3;
        Assert.assertTrue(resultSet.contains(new RecordId((val + 1) % 32000, (val + 1))));
        Assert.assertTrue(resultSet.contains(new RecordId((val + 2) % 32000, (val + 2))));
      }
    }
  }

  @Test
  public void testKeyPutThousandSameKeys() throws Exception {
    final var itemsCount = 20_000;

    final var keys = new String[1_000];
    for (var i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + i;
    }

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) -> {
          for (var key : keys) {
            multiValueTree.put(atomicOperation, key, new RecordId(value % 32000, value));
          }
          if (!rollback && value % 100 == 0) {
            System.out.printf("%d entries were inserted out of %d %n", value, itemsCount);
          }
        });

    for (var key : keys) {
      List<RID> result;
      try (var stream = multiValueTree.get(key)) {
        result = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(itemsCount, result.size());
      Set<RID> resultSet = new HashSet<>(result);

      for (var i = 0; i < itemsCount; i++) {
        Assert.assertTrue(resultSet.contains(new RecordId(i % 32000, i)));
      }
    }
  }

  @Test
  public void testKeyPutRemoveThousandSameKeys() throws Exception {
    final var itemsCount = 4_000;

    final var keys = new String[1000];
    for (var i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + i;
    }

    doInRollbackLoop(
        0,
        itemsCount,
        100,
        (value, rollback, atomicOperation) -> {
          for (var key : keys) {
            multiValueTree.put(atomicOperation, key, new RecordId(value % 32000, value));
          }
        });

    doInRollbackLoop(
        0,
        itemsCount / 3,
        100,
        (value, rollback, atomicOperation) -> {
          final var val = 3 * value;

          for (var key : keys) {
            multiValueTree.remove(atomicOperation, key, new RecordId(val % 32_000, val));
          }
        });

    for (var key : keys) {
      final List<RID> result;
      try (var stream = multiValueTree.get(key)) {
        result = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
      Set<RID> resultSet = new HashSet<>(result);

      for (var i = 0; i < itemsCount / 3; i++) {
        final var val = i * 3;
        Assert.assertTrue(resultSet.contains(new RecordId((val + 1) % 32000, (val + 1))));
        Assert.assertTrue(resultSet.contains(new RecordId((val + 2) % 32000, (val + 2))));
      }
    }
  }

  @Test
  public void testKeyPut() throws Exception {
    final var keysCount = 1_000_000;

    final var lastKey = new String[1];

    doInRollbackLoop(
        0,
        keysCount,
        100,
        (value, rollback, atomicOperation) -> {
          final var key = Integer.toString(value);
          multiValueTree.put(atomicOperation, key, new RecordId(value % 32000, value));

          if (!rollback) {
            if (value % 100_000 == 0) {
              System.out.printf("%d items loaded out of %d%n", value, keysCount);
            }

            if (lastKey[0] == null) {
              lastKey[0] = key;
            } else if (key.compareTo(lastKey[0]) > 0) {
              lastKey[0] = key;
            }

            Assert.assertEquals("0", multiValueTree.firstKey());
            Assert.assertEquals(lastKey[0], multiValueTree.lastKey());
          }
        });

    for (var i = 0; i < keysCount; i++) {
      final List<RID> result;
      try (var stream = multiValueTree.get(Integer.toString(i))) {
        result = stream.collect(Collectors.toList());
      }
      Assert.assertEquals(1, result.size());

      Assert.assertTrue(i + " key is absent", result.contains(new RecordId(i % 32000, i)));
      if (i % 100_000 == 0) {
        System.out.printf("%d items tested out of %d%n", i, keysCount);
      }
    }

    for (var i = keysCount; i < 2 * keysCount; i++) {
      try (var stream = multiValueTree.get(Integer.toString(i))) {
        Assert.assertFalse(stream.iterator().hasNext());
      }
    }
  }

  @Test
  public void testKeyPutRandomUniform() throws Exception {
    final NavigableMap<String, Integer> keys = new TreeMap<>();
    var seed = System.nanoTime();
    System.out.println("testKeyPutRandomUniform : " + seed);
    final var random = new Random(seed);
    final var keysCount = 1_000_000;

    while (keys.size() < keysCount) {
      var val = random.nextInt(Integer.MAX_VALUE);
      var key = Integer.toString(val);

      for (var k = 0; k < 2; k++) {
        final var rollbackCounter = k;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                multiValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }

      keys.compute(
          key,
          (k, v) -> {
            if (v == null) {
              return 1;
            }

            return v + 1;
          });

      final List<RID> result;
      try (var stream = multiValueTree.get(key)) {
        result = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(keys.get(key).longValue(), result.size());
      final RID expected = new RecordId(val % 32000, val);

      for (var rid : result) {
        Assert.assertEquals(expected, rid);
      }
    }

    Assert.assertEquals(multiValueTree.firstKey(), keys.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keys.lastKey());

    for (var entry : keys.entrySet()) {
      final var val = Integer.parseInt(entry.getKey());
      List<RID> result;
      try (var stream = multiValueTree.get(entry.getKey())) {
        result = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(entry.getValue().longValue(), result.size());
      final RID expected = new RecordId(val % 32000, val);

      for (var rid : result) {
        Assert.assertEquals(expected, rid);
      }
    }
  }

  @Test
  public void testKeyDelete() throws Exception {
    final var keysCount = 1_000_000;

    NavigableMap<String, Integer> keys = new TreeMap<>();
    doInRollbackLoop(
        0,
        keysCount,
        100,
        (value, rollback, atomicOperation) -> {
          var key = Integer.toString(value);

          multiValueTree.put(atomicOperation, key, new RecordId(value % 32000, value));

          if (!rollback) {
            keys.compute(
                key,
                (k, v) -> {
                  if (v == null) {
                    return 1;
                  }

                  return v + 1;
                });
          }
        });

    var iterator = keys.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var key = entry.getKey();
      var val = Integer.parseInt(key);

      if (val % 3 == 0) {
        for (var k = 0; k < 2; k++) {
          final var rollbackCounter = k;
          try {
            atomicOperationsManager.executeInsideAtomicOperation(
                null,
                atomicOperation -> {
                  multiValueTree.remove(atomicOperation, key, new RecordId(val % 32000, val));
                  if (rollbackCounter == 0) {
                    throw new RollbackException();
                  }
                });
          } catch (RollbackException ignore) {
          }
        }
        if (entry.getValue() == 1) {
          iterator.remove();
        } else {
          entry.setValue(entry.getValue() - 1);
        }
      }
    }

    Assert.assertEquals(multiValueTree.firstKey(), keys.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keys.lastKey());

    for (var i = 0; i < keysCount; i++) {
      final var key = String.valueOf(i);

      if (i % 3 == 0) {
        try (var stream = multiValueTree.get(key)) {
          Assert.assertFalse(stream.iterator().hasNext());
        }
      } else {
        List<RID> result;
        try (var stream = multiValueTree.get(key)) {
          result = stream.collect(Collectors.toList());
        }

        Assert.assertEquals(1, result.size());
        final RID expected = new RecordId(i % 32000, i);

        for (var rid : result) {
          Assert.assertEquals(expected, rid);
        }
      }
    }
  }

  @Test
  public void testKeyAddDelete() throws Exception {
    final var keysCount = 1_000_000;

    doInRollbackLoop(
        0,
        keysCount,
        100,
        (value, rollback, atomicOperation) ->
            multiValueTree.put(
                atomicOperation, Integer.toString(value), new RecordId(value % 32000, value)));

    doInRollbackLoop(
        0,
        keysCount,
        100,
        (value, rollback, atomicOperation) -> {
          if (value % 3 == 0) {
            Assert.assertTrue(
                multiValueTree.remove(
                    atomicOperation, Integer.toString(value),
                    new RecordId(value % 32000, value)));
          }

          if (value % 2 == 0) {
            multiValueTree.put(
                atomicOperation,
                Integer.toString(keysCount + value),
                new RecordId((keysCount + value) % 32000, keysCount + value));
          }
        });

    for (var i = 0; i < keysCount; i++) {
      if (i % 3 == 0) {
        try (var stream = multiValueTree.get(Integer.toString(i))) {
          Assert.assertFalse(stream.iterator().hasNext());
        }
      } else {
        List<RID> result;
        try (var stream = multiValueTree.get(Integer.toString(i))) {
          result = stream.collect(Collectors.toList());
        }

        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.contains(new RecordId(i % 32000, i)));
      }

      if (i % 2 == 0) {
        List<RID> result;
        try (var stream = multiValueTree.get(Integer.toString(keysCount + i))) {
          result = stream.collect(Collectors.toList());
        }

        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.contains(new RecordId((keysCount + i) % 32000, keysCount + i)));
      }
    }
  }

  @Test
  public void testKeyCursor() throws Exception {
    final var keysCount = 1_000_000;

    NavigableMap<String, RID> keyValues = new TreeMap<>();
    final var seed = System.nanoTime();

    System.out.println("testKeyCursor: " + seed);
    var random = new Random(seed);

    while (keyValues.size() < keysCount) {
      var val = random.nextInt(Integer.MAX_VALUE);
      var key = Integer.toString(val);

      for (var k = 0; k < 2; k++) {
        final var rollbackCounter = k;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                multiValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }

      keyValues.put(key, new RecordId(val % 32000, val));
    }

    Assert.assertEquals(multiValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keyValues.lastKey());

    try (var stream = multiValueTree.keyStream()) {
      final var indexIterator = stream.iterator();
      for (var entryKey : keyValues.keySet()) {
        final var indexKey = indexIterator.next();
        Assert.assertEquals(entryKey, indexKey);
      }
    }
  }

  @Test
  public void testIterateEntriesMajor() throws Exception {
    final var keysCount = 1_000_000;

    NavigableMap<String, Integer> keyValues = new TreeMap<>();
    final var seed = System.nanoTime();

    System.out.println("testIterateEntriesMajor: " + seed);
    var random = new Random(seed);

    while (keyValues.size() < keysCount) {
      var val = random.nextInt(Integer.MAX_VALUE);
      var key = Integer.toString(val);

      for (var k = 0; k < 2; k++) {
        final var rollbackCounter = k;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                multiValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }

      keyValues.compute(
          key,
          (k, v) -> {
            if (v == null) {
              return 1;
            }

            return v + 1;
          });
    }

    assertIterateMajorEntries(keyValues, random, true, true);
    assertIterateMajorEntries(keyValues, random, false, true);

    assertIterateMajorEntries(keyValues, random, true, false);
    assertIterateMajorEntries(keyValues, random, false, false);

    Assert.assertEquals(multiValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keyValues.lastKey());
  }

  @Test
  public void testIterateEntriesMinor() throws Exception {
    final var keysCount = 1_000_000;
    NavigableMap<String, Integer> keyValues = new TreeMap<>();

    final var seed = System.nanoTime();

    System.out.println("testIterateEntriesMinor: " + seed);
    var random = new Random(seed);

    while (keyValues.size() < keysCount) {
      var val = random.nextInt(Integer.MAX_VALUE);
      var key = Integer.toString(val);

      for (var k = 0; k < 2; k++) {
        final var rollbackCounter = k;

        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                multiValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }

      keyValues.compute(
          key,
          (k, v) -> {
            if (v == null) {
              return 1;
            }

            return v + 1;
          });
    }

    assertIterateMinorEntries(keyValues, random, true, true);
    assertIterateMinorEntries(keyValues, random, false, true);

    assertIterateMinorEntries(keyValues, random, true, false);
    assertIterateMinorEntries(keyValues, random, false, false);

    Assert.assertEquals(multiValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keyValues.lastKey());
  }

  @Test
  public void testIterateEntriesBetween() throws Exception {
    final var keysCount = 1_000_000;
    NavigableMap<String, Integer> keyValues = new TreeMap<>();
    var random = new Random();

    while (keyValues.size() < keysCount) {
      var val = random.nextInt(Integer.MAX_VALUE);
      var key = Integer.toString(val);

      for (var k = 0; k < 2; k++) {
        final var rollbackCounter = k;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                multiValueTree.put(atomicOperation, key, new RecordId(val % 32000, val));
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }

      keyValues.compute(
          key,
          (k, v) -> {
            if (v == null) {
              return 1;
            }

            return v + 1;
          });
    }

    assertIterateBetweenEntries(keyValues, random, true, true, true);
    assertIterateBetweenEntries(keyValues, random, true, false, true);
    assertIterateBetweenEntries(keyValues, random, false, true, true);
    assertIterateBetweenEntries(keyValues, random, false, false, true);

    assertIterateBetweenEntries(keyValues, random, true, true, false);
    assertIterateBetweenEntries(keyValues, random, true, false, false);
    assertIterateBetweenEntries(keyValues, random, false, true, false);
    assertIterateBetweenEntries(keyValues, random, false, false, false);

    Assert.assertEquals(multiValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keyValues.lastKey());
  }

  private void assertIterateMajorEntries(
      NavigableMap<String, Integer> keyValues,
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
            fromKey.substring(0, fromKey.length() - 1)
                + (char) (fromKey.charAt(fromKey.length() - 1) - 1);
      }

      final Iterator<RawPair<String, RID>> indexIterator;
      try (var stream =
          multiValueTree.iterateEntriesMajor(fromKey, keyInclusive, ascSortOrder)) {
        indexIterator = stream.iterator();

        Iterator<Map.Entry<String, Integer>> iterator;
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
          var indexEntry = indexIterator.next();
          final var entry = iterator.next();

          final int repetition = entry.getValue();
          final var value = Integer.parseInt(entry.getKey());
          final RID expected = new RecordId(value % 32_000, value);

          Assert.assertEquals(entry.getKey(), indexEntry.first);
          Assert.assertEquals(expected, indexEntry.second);

          for (var n = 1; n < repetition; n++) {
            indexEntry = indexIterator.next();

            Assert.assertEquals(entry.getKey(), indexEntry.first);
            Assert.assertEquals(expected, indexEntry.second);
          }
        }

        //noinspection ConstantConditions
        Assert.assertFalse(iterator.hasNext());
        Assert.assertFalse(indexIterator.hasNext());
      }
    }
  }

  private void assertIterateMinorEntries(
      NavigableMap<String, Integer> keyValues,
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
        toKey =
            toKey.substring(0, toKey.length() - 1) + (char) (toKey.charAt(toKey.length() - 1) + 1);
      }

      final Iterator<RawPair<String, RID>> indexIterator;
      try (var stream =
          multiValueTree.iterateEntriesMinor(toKey, keyInclusive, ascSortOrder)) {
        indexIterator = stream.iterator();
        Iterator<Map.Entry<String, Integer>> iterator;
        if (ascSortOrder) {
          iterator = keyValues.headMap(toKey, keyInclusive).entrySet().iterator();
        } else {
          iterator = keyValues.headMap(toKey, keyInclusive).descendingMap().entrySet().iterator();
        }

        while (iterator.hasNext()) {
          var indexEntry = indexIterator.next();
          var entry = iterator.next();

          final int repetition = entry.getValue();
          final var value = Integer.parseInt(entry.getKey());
          final RID expected = new RecordId(value % 32_000, value);

          Assert.assertEquals(entry.getKey(), indexEntry.first);
          Assert.assertEquals(expected, indexEntry.second);

          for (var n = 1; n < repetition; n++) {
            indexEntry = indexIterator.next();

            Assert.assertEquals(entry.getKey(), indexEntry.first);
            Assert.assertEquals(expected, indexEntry.second);
          }
        }

        //noinspection ConstantConditions
        Assert.assertFalse(iterator.hasNext());
        Assert.assertFalse(indexIterator.hasNext());
      }
    }
  }

  private void assertIterateBetweenEntries(
      NavigableMap<String, Integer> keyValues,
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
            fromKey.substring(0, fromKey.length() - 1)
                + (char) (fromKey.charAt(fromKey.length() - 1) - 1);
      }

      if (random.nextBoolean()) {
        toKey =
            toKey.substring(0, toKey.length() - 1) + (char) (toKey.charAt(toKey.length() - 1) + 1);
      }

      if (fromKey.compareTo(toKey) > 0) {
        fromKey = toKey;
      }

      final Iterator<RawPair<String, RID>> indexIterator;
      try (var stream =
          multiValueTree.iterateEntriesBetween(
              fromKey, fromInclusive, toKey, toInclusive, ascSortOrder)) {
        indexIterator = stream.iterator();

        Iterator<Map.Entry<String, Integer>> iterator;
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

          var entry = iterator.next();

          final int repetition = entry.getValue();
          final var value = Integer.parseInt(entry.getKey());
          final RID expected = new RecordId(value % 32_000, value);

          Assert.assertEquals(entry.getKey(), indexEntry.first);
          Assert.assertEquals(expected, indexEntry.second);

          for (var n = 1; n < repetition; n++) {
            indexEntry = indexIterator.next();

            Assert.assertEquals(entry.getKey(), indexEntry.first);
            Assert.assertEquals(expected, indexEntry.second);
          }
        }
        //noinspection ConstantConditions
        Assert.assertFalse(iterator.hasNext());
        Assert.assertFalse(indexIterator.hasNext());
      }
    }
  }

  private void doInRollbackLoop(
      final int start,
      final int end,
      @SuppressWarnings("SameParameterValue") final int rollbackSlice,
      final TxCode code)
      throws IOException {
    final var atomicOperationsManager = storage.getAtomicOperationsManager();

    for (var i = start; i < end; i += rollbackSlice) {
      final var iterationCounter = i;
      for (var k = 0; k < 2; k++) {
        final var rollbackCounter = k;
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                var counter = 0;
                while (counter < rollbackSlice && iterationCounter + counter < end) {
                  code.execute(iterationCounter + counter, rollbackCounter == 0, atomicOperation);

                  counter++;
                }
                if (rollbackCounter == 0) {
                  throw new RollbackException();
                }
              });
        } catch (RollbackException ignore) {
        }
      }
    }
  }

  private interface TxCode {

    void execute(int value, boolean rollback, AtomicOperation atomicOperation) throws IOException;
  }

  static final class RollbackException extends BaseException implements HighLevelException {

    public RollbackException() {
      this("");
    }

    public RollbackException(String message) {
      super(message);
    }

    @SuppressWarnings("unused")
    public RollbackException(RollbackException exception) {
      super(exception);
    }
  }
}
