package com.jetbrains.youtrackdb.internal.core.storage.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises the stored relation independently of runtime and notification-cache ownership. */
public class CollectionNameIndexTest {
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /** The fixed key covers every supported owner id and does not grow with Unicode names. */
  @Test
  public void fixedKeysEncodeIdsAndNormalizeExpandedLongNames() {
    var hash = CollectionNameKey.hash("name");
    // SHA-256 of UTF-8 "name", truncated to 15 bytes and encoded independently.
    assertEquals("gqNTf_Dbzn7sNdae3DoY", hash);
    assertEquals("cnh_" + hash + "AAAAA_", CollectionNameKey.key(hash, 63));
    assertEquals("cnh_" + hash + "AAAABA", CollectionNameKey.key(hash, 64));
    assertEquals("cnh_" + hash + "AAAH__", CollectionNameKey.key(hash, 32767));
    assertEquals("cnh_" + hash + "B_____", CollectionNameKey.key(hash, Integer.MAX_VALUE));
    assertEquals(20, hash.length());
    assertEquals("cnh_" + hash + "AAAAAA", CollectionNameKey.key(hash, 0));
    for (var id : new int[] {0, 1, 63, 64, 32767, Integer.MAX_VALUE}) {
      var key = CollectionNameKey.key(hash, id);
      assertEquals(30, key.length());
      assertEquals(64, 4 + 2 * key.length());
      assertEquals(id, CollectionNameKey.owner(key));
      assertTrue(key.compareTo(CollectionNameKey.prefix(hash)) > 0);
      assertTrue(key.compareTo(CollectionNameKey.prefix(hash) + "{") < 0);
    }
    assertThrows(IllegalArgumentException.class, () -> CollectionNameKey.key(hash, -1));
    var expanded = CollectionNameKey.normalize("\u0130".repeat(2000));
    assertEquals(4000, expanded.length());
    assertEquals(30, CollectionNameKey.key(CollectionNameKey.hash(expanded), 32767).length());
    assertEquals(CollectionNameKey.hash("name"),
        CollectionNameKey.hash(CollectionNameKey.normalize("NAME")));
  }

  /** Complete operations use one destination range, with previous-name reads counted separately. */
  @Test
  public void completeCreateAndRenameHaveBoundedOwnershipReads() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        var config = spy(db.config());
        setField(db.storage(), "configuration", config);
        var tree = spy(db.tree());
        var records = spy((PaginatedCollectionV2) field(config, "collection"));
        setField(config, "btree", tree);
        setField(config, "collection", records);
        var reads = new ArrayList<String>();
        doAnswer(call -> {
          reads.add(StackWalker.getInstance().walk(frames -> frames
              .anyMatch(frame -> frame.getMethodName().equals("findNameOwner")))
                  ? "candidate" : "maintenance");
          return call.callRealMethod();
        }).when(records).readCurrentRecord(org.mockito.ArgumentMatchers.anyLong(), any());
        for (var i = 0; i < 20; i++) {
          clearInvocations(config, tree, records);
          reads.clear();
          db.session.addCollection("unrelated" + i);
          assertCounts(config, tree, records, reads, 0, 0);
        }
        clearInvocations(config, tree, records);
        reads.clear();
        var id = db.session.addCollection("target");
        assertCounts(config, tree, records, reads, 0, 0);
        clearInvocations(config, tree, records);
        reads.clear();
        db.storage().setCollectionAttribute(id, StorageCollection.ATTRIBUTES.NAME, "destination");
        assertCounts(config, tree, records, reads, 0, 1);
        clearInvocations(config, tree, records);
        reads.clear();
        db.storage().setCollectionAttribute(id, StorageCollection.ATTRIBUTES.NAME, "DESTINATION");
        assertCounts(config, tree, records, reads, 1, 0);
        clearInvocations(config, tree, records);
        reads.clear();
        db.storage().setCollectionAttribute(id, StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY,
            "version");
        assertCounts(config, tree, records, reads, 1, 0);
        clearInvocations(config, tree, records);
        reads.clear();
        // Bypass the runtime duplicate fast path so the stored owner supplies the rejection.
        @SuppressWarnings("unchecked")
        var names = (Map<String, StorageCollection>) field(db.storage(), "collectionMap");
        var runtime = names.remove("destination");
        try {
          assertThrows(ConfigurationException.class,
              () -> db.session.addCollection("destination"));
          assertCounts(config, tree, records, reads, 1, 0);
        } finally {
          names.put("destination", runtime);
        }
        db.agreement();
      }
    }
  }

  /** The B-tree prefetch cache contains only the bounded hash group, not neighboring owners. */
  @Test
  public void boundedRangeExcludesOtherHashesInsidePrefetchCache() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        var hash = "zzzzzzzzzzzzzzzzzzzy";
        db.config().nameHashForTest = name -> name.startsWith("collision") ? hash
            : name.startsWith("other") ? "zzzzzzzzzzzzzzzzzzzz" : CollectionNameKey.hash(name);
        for (var name : List.of("othera", "otherb", "collisiona", "collisionb", "collisionc")) {
          db.session.addCollection(name);
        }
        var size = BTree.class.getDeclaredField("SPLITERATOR_CACHE_SIZE");
        size.setAccessible(true);
        // All five fixture name keys fit in one prefetch. No global setting needs changing.
        assertTrue(size.getInt(null) > 5);
        db.operation(op -> {
          try (var tail = db.tree().iterateEntriesBetween(CollectionNameKey.prefix(hash), true,
              "cnh`", false, true, op)) {
            assertEquals("the whole name-key tail must fit in one prefetch", 5, tail.count());
          }
        });
        var original = db.tree();
        var tree = spy(original);
        var observed = new ArrayList<Integer>();
        doAnswer(call -> {
          @SuppressWarnings("unchecked")
          var stream = (Stream<RawPair<String, RID>>) call.callRealMethod();
          var iterator = stream.spliterator();
          return StreamSupport.stream(iterator, false).peek(pair -> {
            try {
              var cache = (List<?>) field(iterator, "dataCache");
              observed.add(cache.size());
              assertEquals(3, cache.size());
              for (var item : cache) {
                assertTrue(((RawPair<?, ?>) item).first().toString()
                    .startsWith(CollectionNameKey.prefix(hash)));
              }
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          }).onClose(stream::close);
        }).when(tree).iterateEntriesBetween(any(), anyBoolean(), any(), anyBoolean(),
            anyBoolean(), any());
        setField(db.config(), "btree", tree);
        try {
          // Production must visit every collision candidate but no neighboring hash group.
          db.operation(op -> assertNull(db.config().readCollectionByName("collisionmissing", op)));
          assertEquals(List.of(3, 3, 3), observed);
        } finally {
          setField(db.config(), "btree", original);
        }
        db.agreement();
      }
    }
  }

  /** Collisions permit distinct full names and preserve every reference through rollback. */
  @Test
  public void collidingCreatesRenameDropAndRollbackKeepExactOwners() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        collisions(db);
        var a = db.session.addCollection("collisiona");
        var b = db.session.addCollection("collisionb");
        var c = db.session.addCollection("collisionc");
        db.storage().setCollectionAttribute(a, StorageCollection.ATTRIBUTES.NAME, "collisiond");
        assertThrows(ConfigurationException.class,
            () -> db.storage().setCollectionAttribute(b, StorageCollection.ATTRIBUTES.NAME,
                "COLLISIOND"));
        assertEquals(b, db.storage().getCollectionIdByName("collisionb"));
        db.agreement();
        assertInterrupted(() -> db.operation(op -> {
          db.config().dropCollection(op, c);
          var owner = db.config().readCollection(b, op);
          owner.name = "collisione";
          db.config().updateCollection(op, owner);
          db.agreement(op);
          throw new CommandInterruptedException("names", "rollback");
        }));
        db.operation(op -> {
          assertNotNull(db.config().readCollectionByName("collisionb", op));
          assertNotNull(db.config().readCollectionByName("collisionc", op));
          assertNull(db.config().readCollectionByName("collisione", op));
        });
        db.agreement();
        db.storage().dropCollection(db.session, c);
        db.agreement();
        db.session.addCollection("collisionc");
        db.agreement();
      }
    }
  }

  /** A direct conflict returns within the bound and leaves both cache and stored bytes untouched. */
  @Test(timeout = 60000)
  public void directConfigurationRejectsBeforeCacheMutationWithoutLockReentry() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        var a = db.session.addCollection("first");
        var b = db.session.addCollection("second");
        var cached = db.config().getCollections().get(b);
        db.operation(op -> {
          var replacement = db.config().readCollection(b, op);
          replacement.name = "FIRST";
          assertThrows(ConfigurationException.class, () -> db.config().updateCollection(op,
              replacement));
          assertTrue(cached == db.config().getCollections().get(b));
          assertEquals("second", db.config().readCollection(b, op).name());
          assertEquals(a, db.config().readCollectionByName("first", op).id());
        });
        db.agreement();
      }
    }
  }

  /** A stale runtime name cannot overwrite the new stored owner during a non-name update. */
  @Test
  public void staleRuntimeNameCannotStealAnotherOwnersName() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        var a = db.session.addCollection("original");
        var runtime = db.storage().getCollectionInstances().stream()
            .filter(c -> c.getId() == a).findFirst().orElseThrow();
        var originalTree = db.tree();
        var failedTree = spy(originalTree);
        setField(db.config(), "btree", failedTree);
        org.mockito.Mockito.doThrow(new CommandInterruptedException("names", "rollback rename"))
            .when(failedTree).put(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(CollectionNameKey.key(
                    CollectionNameKey.hash("stale"), a)),
                org.mockito.ArgumentMatchers.any());
        try {
          assertInterrupted(
              () -> db.storage().setCollectionAttribute(a, StorageCollection.ATTRIBUTES.NAME,
                  "stale"));
        } finally {
          setField(db.config(), "btree", originalTree);
        }
        // Failed rename repair is separate work. Free only its stale runtime registration.
        ((Map<?, ?>) field(db.storage(), "collectionMap")).remove("stale");
        var b = db.session.addCollection("stale");
        assertEquals("stale", runtime.getName());
        runtime.setRecordConflictStrategy("content");
        var strategy = runtime.getRecordConflictStrategy();
        assertEquals("content", strategy.getName());
        var cached = db.config().getCollections().get(a);
        var stored = new AtomicReference<byte[]>();
        db.operation(op -> stored.set(db.storedBytes(a, op)));
        assertThrows(ConfigurationException.class,
            () -> db.storage().setCollectionAttribute(a,
                StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY, "version"));
        assertSame(strategy, runtime.getRecordConflictStrategy());
        assertEquals("stale", runtime.getName());
        assertSame(cached, db.config().getCollections().get(a));
        db.operation(op -> {
          var after = db.config().readCollection(a, op);
          assertArrayEquals(stored.get(), db.storedBytes(a, op));
          assertEquals("original", after.name());
          assertEquals(b, db.config().readCollectionByName("stale", op).id());
        });
        db.agreement();
      }
    }
  }

  /** A non-name update serializes its runtime name and moves the stored key with that payload. */
  @Test
  public void nonNameUpdateMovesKeyWhenRuntimeNameDiffersFromStoredName() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        var id = db.session.addCollection("storedname");
        var runtime = db.storage().getCollectionInstances().stream()
            .filter(c -> c.getId() == id).findFirst().orElseThrow();
        runtime.setCollectionName("runtimename");
        db.storage().setCollectionAttribute(id, StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY,
            "version");
        db.operation(op -> {
          assertNull(db.config().readCollectionByName("storedname", op));
          assertEquals(id, db.config().readCollectionByName("runtimename", op).id());
        });
        db.agreement();
      }
    }
  }

  /** A non-name update rolls back both its serialized runtime name and its completed key move. */
  @Test(timeout = 60000)
  public void nonNameUpdateRollbackRestoresStoredNameAndExactKey() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        var id = db.session.addCollection("storedname");
        var runtime = db.storage().getCollectionInstances().stream()
            .filter(c -> c.getId() == id).findFirst().orElseThrow();
        runtime.setCollectionName("runtimename");
        var original = db.tree();
        var tree = spy(original);
        var failed = new AtomicReference<AtomicOperation>();
        doAnswer(call -> {
          var result = call.callRealMethod();
          AtomicOperation op = call.getArgument(0);
          failed.set(op);
          // Fail only after the payload and both name-key mutations are visible.
          // The configuration write lock is non-reentrant. Inspect raw references here.
          assertEquals("runtimename", db.storedEntry(id, op).name());
          assertNull(tree.get(CollectionNameKey.key(CollectionNameKey.hash("storedname"), id), op));
          assertEquals(tree.get("collection_" + id, op),
              tree.get(CollectionNameKey.key(CollectionNameKey.hash("runtimename"), id), op));
          db.agreement(op);
          throw new CommandInterruptedException("names", "rollback non-name update");
        }).when(tree).put(any(), org.mockito.ArgumentMatchers.eq(
            CollectionNameKey.key(CollectionNameKey.hash("runtimename"), id)), any());
        setField(db.config(), "btree", tree);
        try {
          assertInterrupted(() -> db.storage().setCollectionAttribute(id,
              StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY, "version"));
        } finally {
          setField(db.config(), "btree", original);
        }
        assertNotNull(failed.get());
        assertTrue("finalization must confirm rollback", failed.get().isRollbackInProgress());
        db.operation(op -> {
          assertEquals("storedname", db.config().readCollection(id, op).name());
          assertEquals(id, db.config().readCollectionByName("storedname", op).id());
          assertNull(db.config().readCollectionByName("runtimename", op));
        });
        db.agreement();
      }
    }
  }

  /** Failed schema creates remove their keys, including undo of a replacement in a reused slot. */
  @Test(timeout = 60000)
  public void failedSchemaCreateAndSlotReuseUndoKeepNameIndexAgreement() throws Exception {
    for (var type : DatabaseType.values()) {
      for (var reuse : new boolean[] {false, true}) {
        try (var db = database(type, StorageFormat.LAYOUT_28)) {
          int originalId;
          if (reuse) {
            db.session.executeInTx(
                tx -> db.session.getMetadata().getSchema().createClass("OriginalOwner", 1));
            originalId = db.session.getMetadata().getSchema().getClass("OriginalOwner")
                .getCollectionIds()[0];
          } else {
            originalId = -1;
          }
          var originalName = reuse ? db.storage().getCollectionNameById(originalId) : null;
          var original = db.tree();
          var tree = spy(original);
          var created = new AtomicReference<StoragePaginatedCollectionConfiguration>();
          var latest = new AtomicReference<AtomicOperation>();
          var failed = new AtomicReference<AtomicOperation>();
          doAnswer(call -> {
            var result = call.callRealMethod();
            String key = call.getArgument(1);
            if (key.startsWith("cnh_")) {
              AtomicOperation op = call.getArgument(0);
              latest.set(op);
              created.set(db.storedEntry(CollectionNameKey.owner(key), op));
            }
            return result;
          }).when(tree).put(any(), any(), any());
          setField(db.config(), "btree", tree);
          db.storage().setCommitWindowTestHook(() -> {
            var op = latest.get();
            failed.set(op);
            try {
              assertNotNull(created.get());
              var replacement = db.config().readCollectionByName(created.get().name(), op);
              assertNotNull(replacement);
              if (reuse) {
                assertEquals(originalId, replacement.id());
                assertNull(db.config().readCollectionByName(originalName, op));
              }
              db.agreement(op);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
            throw new CommandInterruptedException("names", "rollback schema create");
          });
          try {
            db.session.begin();
            if (reuse) {
              db.session.getMetadata().getSchema().dropClass("OriginalOwner");
            }
            db.session.getMetadata().getSchema().createClass("FailedOwner", 1);
            assertThrows(CommandInterruptedException.class, db.session::commit);
          } finally {
            db.storage().setCommitWindowTestHook(null);
            setField(db.config(), "btree", original);
          }
          assertNotNull(failed.get());
          assertTrue("finalization must confirm rollback", failed.get().isRollbackInProgress());
          db.operation(op -> {
            assertNull(db.config().readCollectionByName(created.get().name(), op));
            if (reuse) {
              assertEquals(originalId, db.config().readCollectionByName(originalName, op).id());
            }
          });
          db.agreement();
        }
      }
    }
  }

  /** Each completed primitive agrees inside one operation, including reuse of a dropped owner. */
  @Test
  public void sameOperationCreatesAndDropRecreateAgreeBeforeCommitAndAfterRollback()
      throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        collisions(db);
        assertInterrupted(() -> db.operation(op -> {
          var a = 30000;
          db.config().updateCollection(op, entry(a, "collisiona"));
          db.agreement(op);
          db.config().updateCollection(op, entry(a + 1, "collisionb"));
          db.agreement(op);
          db.config().dropCollection(op, a);
          db.agreement(op);
          var restored = new StoragePaginatedCollectionConfiguration(a, "collisionc", null,
              true, 0, 0, "none", null, null, "version", 0, 100000);
          db.config().updateCollection(op, restored);
          db.agreement(op);
          throw new CommandInterruptedException("names", "rollback several primitives");
        }));
        db.agreement();
      }
    }
  }

  /** Old snapshots must see current owners, including colliding names created after begin. */
  @Test
  public void olderOperationReadsCurrentOwnerAndOwnChanges() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        collisions(db);
        var id = db.session.addCollection("collisiona");
        var old = db.storage().getAtomicOperationsManager().startAtomicOperation();
        db.storage().setCollectionAttribute(id, StorageCollection.ATTRIBUTES.NAME, "collisionb");
        db.session.addCollection("collisionc");
        db.storage().getAtomicOperationsManager().startToApplyOperations(old);
        try {
          assertNull(db.config().readCollectionByName("collisiona", old));
          assertEquals(id, db.config().readCollectionByName("collisionb", old).id());
          assertThrows(ConfigurationException.class,
              () -> db.config().checkCollectionName(old, 32000, "collisionc"));
          assertNotNull(db.config().checkCollectionName(old, 32000, "collisionz"));
          db.config().dropCollection(old, id);
          db.agreement(old);
        } finally {
          db.storage().getAtomicOperationsManager().endAtomicOperation(old,
              new CommandInterruptedException("names", "rollback old operation"));
        }
        db.agreement();
      }
    }
  }

  /** Schema reconciliation removes the current name after a later rename, even on failed commit. */
  @Test
  public void oldSchemaDropUsesCurrentStoredNameAfterRenameAndReopen() throws Exception {
    for (var type : DatabaseType.values()) {
      for (var fail : new boolean[] {false, true}) {
        var root = folder.newFolder().toPath();
        try (var db = new Fixture(root, type, StorageFormat.LAYOUT_28, true)) {
          db.session
              .executeInTx(tx -> db.session.getMetadata().getSchema().createClass("Owner", 1));
          var id = db.session.getMetadata().getSchema().getClass("Owner").getCollectionIds()[0];
          db.session.begin();
          try (var later = (DatabaseSessionEmbedded) db.manager.open(
              "names", "admin", DbTestBase.ADMIN_PASSWORD)) {
            later.getStorage().setCollectionAttribute(id, StorageCollection.ATTRIBUTES.NAME,
                "currentowner");
          }
          db.session.activateOnCurrentThread();
          db.session.getMetadata().getSchema().dropClass("Owner");
          if (fail) {
            db.storage().setCommitWindowTestHook(() -> {
              throw new CommandInterruptedException("names", "rollback schema drop");
            });
            try {
              assertThrows(CommandInterruptedException.class, db.session::commit);
            } finally {
              db.storage().setCommitWindowTestHook(null);
            }
          } else {
            db.session.commit();
          }
          db.operation(op -> assertEquals(fail,
              db.config().readCollectionByName("currentowner", op) != null));
          db.agreement();
        }
        if (type == DatabaseType.DISK) {
          try (var reopened = new Fixture(root, type, StorageFormat.LAYOUT_28, false)) {
            reopened.agreement();
            reopened.operation(op -> assertEquals(fail,
                reopened.config().readCollectionByName("currentowner", op) != null));
          }
        }
      }
    }
  }

  /** Layout 27 keeps its collection lifecycle and stores no name keys. */
  @Test
  public void legacyLifecycleDoesNotWriteNameKeys() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_27)) {
        var id = db.session.addCollection("legacyname");
        db.storage().setCollectionAttribute(id, StorageCollection.ATTRIBUTES.NAME, "legacyrenamed");
        db.operation(op -> assertNotNull(db.config().readCollectionByName("legacyrenamed", op)));
        db.storage().dropCollection(db.session, id);
        db.operation(op -> {
          try (var keys = db.tree().iterateEntriesBetween("cnh_", true, "cnh`", false, true, op)) {
            assertEquals(0, keys.count());
          }
        });
      }
    }
  }

  private static void assertInterrupted(org.junit.function.ThrowingRunnable action) {
    var failure = assertThrows(
        com.jetbrains.youtrackdb.internal.core.exception.StorageException.class, action);
    assertEquals(CommandInterruptedException.class, failure.getCause().getClass());
  }

  /** A boundary result authorizes only the same operation, owner and normalized destination. */
  @Test
  public void mismatchedCheckArgumentsRequireFreshOwnershipChecks() throws Exception {
    for (var type : DatabaseType.values()) {
      try (var db = database(type, StorageFormat.LAYOUT_28)) {
        var a = db.session.addCollection("owner");
        var b = db.session.addCollection("otherowner");
        var retained = new java.util.concurrent.atomic.AtomicReference<
            CollectionBasedStorageConfiguration.CollectionNameCheck>();
        db.operation(op -> {
          retained.set(db.config().checkCollectionName(op, b, "free"));
          var replacement = db.config().readCollection(b, op);
          replacement.name = "owner";
          assertThrows(ConfigurationException.class,
              () -> db.config().updateCollection(op, replacement, retained.get()));
          var own = db.config().checkCollectionName(op, a, "owner");
          assertThrows(ConfigurationException.class,
              () -> db.config().updateCollection(op, replacement, own));
        });
        db.storage().setCollectionAttribute(a, StorageCollection.ATTRIBUTES.NAME, "free");
        db.operation(op -> {
          var replacement = db.config().readCollection(b, op);
          replacement.name = "free";
          assertThrows(ConfigurationException.class,
              () -> db.config().updateCollection(op, replacement, retained.get()));
        });
        db.agreement();
      }
    }
  }

  /** Fresh databases accept long expanded names while their configuration key limit is 66 bytes. */
  @Test
  @org.junit.experimental.categories.Category(com.jetbrains.youtrackdb.internal.SequentialTest.class)
  public void longNamesFitExistingConfigurationKeyCapacity() throws Exception {
    var setting = com.jetbrains.youtrackdb.api.config.GlobalConfiguration.BTREE_MAX_KEY_SIZE;
    var previous = setting.getValue();
    try {
      setting.setValue(66);
      for (var type : DatabaseType.values()) {
        try (var db = database(type, StorageFormat.LAYOUT_28)) {
          var name = "\u0130".repeat(2000);
          var id = db.session.addCollection(name);
          db.operation(op -> assertEquals(id, db.config().readCollectionByName(name, op).id()));
          db.agreement();
        }
      }
    } finally {
      setting.setValue(previous);
    }
  }

  private static StoragePaginatedCollectionConfiguration entry(int id, String name) {
    return new StoragePaginatedCollectionConfiguration(id, name, null,
        true, 0, 0, "none", null, null, "version", 0, 100000 + id);
  }

  private Fixture database(DatabaseType type, StorageFormat format) throws Exception {
    return new Fixture(folder.newFolder().toPath(), type, format, true);
  }

  private static void collisions(Fixture db) throws Exception {
    db.config().nameHashForTest = name -> CollectionNameKey.hash(
        name.startsWith("collision") ? "collision" : name);
  }

  private static void assertCounts(CollectionBasedStorageConfiguration config, BTree<String> tree,
      PaginatedCollectionV2 records, List<String> reads, long candidates, long maintenance) {
    assertEquals(1, calls(tree, "iterateEntriesBetween"));
    assertEquals(0, calls(tree, "iterateEntriesMajor"));
    assertEquals(0, calls(tree, "iterateEntriesMinor"));
    assertEquals(0, calls(tree, "allEntries"));
    assertEquals(0, calls(tree, "keyStream"));
    assertEquals(0, calls(config, "readCollections"));
    // These constant budgets include slot guards, component counters and payload maintenance.
    // Counting both APIs also detects a point-read scan that avoids the range methods.
    assertTrue("point reads: " + calls(tree, "get"), calls(tree, "get") <= 12);
    assertTrue("snapshot reads: " + calls(records, "readRecord"),
        calls(records, "readRecord") <= 3);
    assertEquals(candidates, reads.stream().filter("candidate"::equals).count());
    assertEquals(maintenance, reads.stream().filter("maintenance"::equals).count());
    assertEquals(candidates + maintenance, calls(records, "readCurrentRecord"));
  }

  private static long calls(Object spy, String method) {
    return mockingDetails(spy).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals(method)).count();
  }

  private static Field declared(Object target, String name) throws Exception {
    var type = target instanceof AbstractStorage ? AbstractStorage.class : target.getClass();
    var result = type.getDeclaredField(name);
    result.setAccessible(true);
    return result;
  }

  private static Object field(Object target, String name) throws Exception {
    return declared(target, name).get(target);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    declared(target, name).set(target, value);
  }

  @FunctionalInterface
  private interface Action {
    void run(AtomicOperation operation) throws Exception;
  }

  private static final class Fixture implements AutoCloseable {
    final YouTrackDBImpl manager;
    final DatabaseSessionEmbedded session;

    Fixture(Path root, DatabaseType type, StorageFormat format, boolean create) throws Exception {
      manager = (YouTrackDBImpl) YourTracks.instance(root);
      if (create) {
        try (var selection = StorageBirthTestSupport.selectBirthFormat(manager, "names", format)) {
          manager.create("names", type, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
        }
      }
      session = (DatabaseSessionEmbedded) manager.open("names", "admin", DbTestBase.ADMIN_PASSWORD);
    }

    AbstractStorage storage() {
      return session.getStorage();
    }

    CollectionBasedStorageConfiguration config() throws Exception {
      var f = AbstractStorage.class.getDeclaredField("configuration");
      f.setAccessible(true);
      return (CollectionBasedStorageConfiguration) f.get(storage());
    }

    @SuppressWarnings("unchecked")
    BTree<String> tree() throws Exception {
      return (BTree<String>) field(config(), "btree");
    }

    void operation(Action action) throws Exception {
      storage().getAtomicOperationsManager().executeInsideAtomicOperation(action::run);
    }

    StoragePaginatedCollectionConfiguration storedEntry(int id, AtomicOperation op)
        throws Exception {
      var reference = tree().get("collection_" + id, op);
      var decode = CollectionBasedStorageConfiguration.class.getDeclaredMethod(
          "deserializeStorageCollectionConfig", int.class, byte[].class, int.class);
      decode.setAccessible(true);
      return (StoragePaginatedCollectionConfiguration) decode.invoke(
          config(), id, storedBytes(id, op), reference.getCollectionId());
    }

    byte[] storedBytes(int id, AtomicOperation op) throws Exception {
      var reference = tree().get("collection_" + id, op);
      return ((PaginatedCollectionV2) field(config(), "collection"))
          .readCurrentRecord(reference.getCollectionPosition(), op).buffer().clone();
    }

    void agreement() throws Exception {
      operation(this::agreement);
    }

    void agreement(AtomicOperation op) throws Exception {
      Map<String, RID> expected = new HashMap<>();
      // Read records from iterator references, independently of the ownership lookup under test.
      try (var entries = tree().iterateEntriesBetween(
          "collection_", true, "collection`", false, true, op)) {
        for (var entry : entries.toList()) {
          int id = Integer.parseInt(entry.first().substring("collection_".length()));
          var bytes = ((PaginatedCollectionV2) field(config(), "collection"))
              .readCurrentRecord(entry.second().getCollectionPosition(), op).buffer();
          var decode = CollectionBasedStorageConfiguration.class.getDeclaredMethod(
              "deserializeStorageCollectionConfig", int.class, byte[].class, int.class);
          decode.setAccessible(true);
          var owner = (StoragePaginatedCollectionConfiguration) decode.invoke(
              config(), id, bytes, entry.second().getCollectionId());
          var normalized = CollectionNameKey.normalize(owner.name());
          var hash = config().nameHashForTest == null ? CollectionNameKey.hash(normalized)
              : config().nameHashForTest.apply(normalized);
          expected.put(CollectionNameKey.key(hash, id), entry.second());
        }
      }
      Map<String, RID> actual = new HashMap<>();
      try (var entries = tree().iterateEntriesBetween("cnh_", true, "cnh`", false, true, op)) {
        entries.forEach(entry -> actual.put(entry.first(), entry.second()));
      }
      assertEquals(expected, actual);
    }

    @Override
    public void close() {
      session.close();
      manager.close();
    }
  }
}
