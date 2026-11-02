package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.StaleIndexEngineException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineReference;
import com.jetbrains.youtrackdb.internal.core.index.engine.MultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.SingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.V1IndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexLifecycleCell;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/** Deterministic writer interleavings at the post-record, pre-index apply boundary. */
public class IndexCommitValidationTest {

  private final String dbName = "writer-validation-" + UUID.randomUUID();
  private YouTrackDBImpl manager;
  private DatabaseSessionEmbedded db;
  private AbstractStorage storage;
  private IndexAbstract a;
  private IndexAbstract b;

  @Before
  public void before() {
    var testEnv = System.getProperty("youtrackdb.test.env", "memory");
    var dbType =
        List.of("ci", "release").contains(testEnv) ? DatabaseType.DISK : DatabaseType.MEMORY;
    manager = DbTestBase.createYTDBManagerAndDb(dbName, dbType, getClass());
    db = manager.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) db.getStorage();
  }

  @After
  public void after() {
    db.close();
    manager.drop(dbName);
    manager.close();
  }

  private void indexes(SchemaClass.INDEX_TYPE type) {
    var cls = db.createClass("Writer");
    cls.createProperty("a", PropertyType.STRING);
    cls.createProperty("b", PropertyType.STRING);
    cls.createIndex("A", type, "a");
    cls.createIndex("B", type, "b");
    a = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex("A");
    b = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex("B");
  }

  /** All constructor shapes and copy construction retain retry/high-level type, context and cause. */
  @Test
  public void staleExceptionPreservesRetryContractContextAndCause() {
    var messageOnly = new StaleIndexEngineException("stale");
    var named = new StaleIndexEngineException(dbName, "stale");
    var session = new StaleIndexEngineException(db, "stale");
    var nullSession = new StaleIndexEngineException((DatabaseSessionEmbedded) null, "stale");
    assertThat(messageOnly.getDbName()).isNull();
    assertThat(nullSession.getDbName()).isNull();
    assertThat(session.getDbName()).isEqualTo(dbName);
    named.setComponentName("index-owner");
    var cause = new IllegalStateException("generation changed");
    named.initCause(cause);
    var copy = new StaleIndexEngineException(named);
    assertThat(copy).isInstanceOf(NeedRetryException.class).isInstanceOf(HighLevelException.class);
    assertThat(copy.getDbName()).isEqualTo(dbName);
    assertThat(copy.getMessage()).contains("stale", "index-owner", dbName);
    assertThat(copy.getCause()).isSameAs(cause);
    storage.moveToErrorStateIfNeeded(copy);
    assertThat(storage.getStatus().name()).isEqualTo("OPEN");
  }

  /** Packed CLEAR applies to a nonzero slot/API, then writes null/non-null keys on that binding. */
  @Test
  public void uniqueClearAndLaterKeysLeaveNeighborUnchanged() throws Exception {
    successfulClear(SchemaClass.INDEX_TYPE.UNIQUE);
  }

  /** Multi-value bound dispatch retains duplicate-key values and RID-specific remove semantics. */
  @Test
  public void multiValueClearAndLaterKeysLeaveNeighborUnchanged() throws Exception {
    successfulClear(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var first = new RecordId(7, 10);
    var second = new RecordId(7, 11);
    db.begin();
    stage(a, OPERATION.PUT, "shared", first);
    stage(a, OPERATION.PUT, "shared", second);
    stage(a, OPERATION.REMOVE, "new", first);
    stage(a, OPERATION.REMOVE, null, first);
    db.commit();
    assertThat(rids(a, "shared")).containsExactlyInAnyOrder(first, second);
    assertThat(rids(a, "new")).isEmpty();
    assertThat(rids(a, null)).isEmpty();
    db.begin();
    stage(a, OPERATION.REMOVE, "shared", first);
    db.commit();
    assertThat(rids(a, "shared")).containsExactly(second);
  }

  private void successfulClear(SchemaClass.INDEX_TYPE type) throws Exception {
    indexes(type);
    var first = new RecordId(7, 10);
    db.begin();
    stage(a, OPERATION.PUT, "old", first);
    stage(a, OPERATION.PUT, null, first);
    stage(b, OPERATION.PUT, "neighbor", first);
    stage(b, OPERATION.PUT, null, first);
    db.commit();
    var reference = a.engineSnapshot().engineReference();
    assertThat(reference.slot()).isGreaterThan(0);
    assertThat(reference.apiVersion()).isEqualTo(1);
    db.begin();
    stage(a, OPERATION.CLEAR, null, null);
    stage(a, OPERATION.PUT, "new", first);
    stage(a, OPERATION.PUT, null, first);
    db.commit();
    assertThat(rids(a, "old")).isEmpty();
    assertThat(rids(a, "new")).containsExactly(first);
    assertThat(rids(a, null)).containsExactly(first);
    assertThat(rids(b, "neighbor")).containsExactly(first);
    assertThat(rids(b, null)).containsExactly(first);
    db.begin();
    stage(a, OPERATION.REMOVE, "new", null);
    if (type == SchemaClass.INDEX_TYPE.NOTUNIQUE) {
      // Key-only removal is not a multi-value operation. Use the RID-specific path instead.
      db.rollback();
      db.begin();
      stage(a, OPERATION.REMOVE, "new", first);
    }
    db.commit();
    assertThat(rids(a, "new")).isEmpty();
  }

  /** After lock-time recovery, B becomes stale again. Admission preserves A and reader history. */
  @Test
  public void staleSecondIndexRejectsBeforeFirstClearAndRollsBackRecords() throws Exception {
    indexes(SchemaClass.INDEX_TYPE.UNIQUE);
    var rid = db.computeInTx(tx -> {
      var row = tx.newEntity("Writer");
      row.setProperty("a", "old");
      row.setProperty("b", "replacement-entry");
      return row.getIdentity();
    });
    try (var reader = manager.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD)) {
      reader.begin();
      db.activateOnCurrentThread();
      db.executeInTx(tx -> tx.loadEntity(rid).setProperty("a", "current"));
      var history = snapshotMap("sharedIndexesSnapshot");
      assertThat(history).isNotEmpty();
      var visibility = snapshotMap("indexesSnapshotVisibilityIndex");
      var engines = engines();
      var slot = b.engineSnapshot().engineReference().slot();
      var original = engines.get(slot);
      var replacement = spy(original);
      var originalReference = original.getEngineReference();
      var reference = new IndexEngineReference(slot, originalReference.apiVersion(),
          originalReference.generation() + 1);
      reference.bindOwner(b.getIdentity());
      Mockito.doReturn(reference).when(replacement).getEngineReference();
      db.begin();
      stage(a, OPERATION.CLEAR, null, null);
      var row = db.getActiveTransaction().loadEntity(rid);
      row.setProperty("b", "rejected-write");
      // Selection must recover this carrier before the boundary makes its new binding stale.
      publish(b, Integer.MAX_VALUE, null, b.getIdentity());
      txBoundary(() -> {
        assertThat(b.engineSnapshot().engineReference()).isSameAs(originalReference);
        engines.set(slot, replacement);
      });
      assertThatThrownBy(db::commit).isExactlyInstanceOf(StaleIndexEngineException.class)
          .isInstanceOf(NeedRetryException.class);
      try {
        assertThat(snapshotMap("sharedIndexesSnapshot")).containsAllEntriesOf(history);
        assertThat(snapshotMap("indexesSnapshotVisibilityIndex")).containsAllEntriesOf(visibility);
        assertThat(db.getTransactionInternal().isActive()).isFalse();
        assertThat(rids(a, "current")).containsExactly(rid);
        // The replacement shares durable pages with the old engine but carries a new generation.
        db.begin();
        try (var entries =
            ((V1IndexEngine) replacement)
                .get("replacement-entry", db.getActiveTransaction().getAtomicOperation())) {
          assertThat(entries.toList()).containsExactly(rid);
        }
        db.rollback();
        Mockito.verify(replacement, Mockito.never()).clear(any(), any());
      } finally {
        engines.set(slot, original);
      }
      assertThat(rids(b, "replacement-entry")).containsExactly(rid);
      assertThat(rids(b, "rejected-write")).isEmpty();
      db.executeInTx(tx -> assertThat(tx.loadEntity(rid).<String>getProperty("b"))
          .isEqualTo("replacement-entry"));
      db.executeInTx(tx -> tx.loadEntity(rid).setProperty("b", "later-valid"));
      assertThat(rids(b, "later-valid")).containsExactly(rid);
      reader.activateOnCurrentThread();
      assertThat(a.getRids(reader, "old").toList()).containsExactly(rid);
      reader.rollback();
      db.activateOnCurrentThread();
    }
    assertThat(storage.getStatus().name()).isEqualTo("OPEN");
  }

  /** A missing engine fails retryably after owner lookup, without publication or CLEAR. */
  @Test
  public void missingEngineAtLockSelectionFailsRetryablyAfterOwnerLookup() throws Exception {
    indexes(SchemaClass.INDEX_TYPE.UNIQUE);
    var engines = engines();
    var oldCarrier = carrier(a).get();
    var slot = a.engineSnapshot().engineReference().slot();
    var original = engines.get(slot);
    var recovering = spy(a);
    db.begin();
    stage(recovering, OPERATION.CLEAR, null, null);
    engines.set(slot, null);
    try {
      assertThatThrownBy(db::commit).isExactlyInstanceOf(StaleIndexEngineException.class)
          .isInstanceOf(NeedRetryException.class)
          .hasCauseInstanceOf(StaleIndexEngineException.class);
      assertThat(carrier(a).get()).isSameAs(oldCarrier);
      assertThat(Mockito.mockingDetails(recovering).getInvocations()).anyMatch(
          invocation -> invocation.getMethod().getName().equals("resolveOwnedEngineWithStateLock"));
    } finally {
      engines.set(slot, original);
    }
    assertThat(storage.getStatus().name()).isEqualTo("OPEN");
    db.executeInTx(tx -> tx.newEntity("Writer").setProperty("a", "later"));
    assertThat(rids(a, "later")).hasSize(1);
  }

  /** A different registered object with the same reference cannot apply work locked on the old one. */
  @Test
  public void applyEngineMustBeIdenticalToLockedEngine() throws Exception {
    indexes(SchemaClass.INDEX_TYPE.UNIQUE);
    var engines = engines();
    var slot = a.engineSnapshot().engineReference().slot();
    var locked = engines.get(slot);
    var other = spy(locked);
    db.begin();
    stage(a, OPERATION.CLEAR, null, null);
    txBoundary(() -> engines.set(slot, other));
    try {
      assertThatThrownBy(db::commit).isExactlyInstanceOf(StaleIndexEngineException.class)
          .hasMessageContaining("differs from locked engine");
      Mockito.verify(other, Mockito.never()).clear(any(), any());
      assertThat(a.engineSnapshot().engineReference()).isSameAs(locked.getEngineReference());
    } finally {
      engines.set(slot, locked);
    }
  }

  /** Every incomplete/foreign carrier and missing/reused registration fails before CLEAR. */
  @Test
  public void applyRejectsMissingForeignAndIncompleteOwnershipWithoutRecovery() throws Exception {
    indexes(SchemaClass.INDEX_TYPE.UNIQUE);
    var engines = engines();
    var snapshot = a.engineSnapshot();
    var engine = engines.get(snapshot.engineReference().slot());
    var oldCarrier = carrier(a).get();
    var guarded = withoutOwnerRecovery(a);
    var rid = new RecordId(7, 21);
    db.begin();
    stage(a, OPERATION.PUT, "preserved", rid);
    stage(a, OPERATION.PUT, null, rid);
    db.commit();
    var sameOwner = new IndexEngineReference(engine.getId(), 1,
        snapshot.engineReference().generation() + 1);
    sameOwner.bindOwner(a.getIdentity());
    var reusedSlot = spy(engine);
    var foreignOwner = new IndexEngineReference(engine.getId(), 1,
        snapshot.engineReference().generation() + 2);
    foreignOwner.bindOwner(b.getIdentity());
    Mockito.doReturn(foreignOwner).when(reusedSlot).getEngineReference();
    var mutations = List.<Runnable>of(
        () -> engines.set(engine.getId(), null),
        () -> engines.set(engine.getId(), reusedSlot),
        () -> publish(a, snapshot.engineIdentifier(), null, a.getIdentity()),
        () -> publish(a, snapshot.engineIdentifier(), snapshot.engineReference(), null),
        () -> publish(a, snapshot.engineIdentifier(), snapshot.engineReference(),
            new RecordId(9, 99)),
        () -> publish(a, -1, null, a.getIdentity()),
        () -> publish(a, snapshot.engineIdentifier(), sameOwner, a.getIdentity()));
    for (var mutation : mutations) {
      db.begin();
      stage(guarded, OPERATION.CLEAR, null, null);
      txBoundary(mutation);
      try {
        assertThatThrownBy(db::commit).isExactlyInstanceOf(StaleIndexEngineException.class);
        assertThat(db.getTransactionInternal().isActive()).isFalse();
        // A recovering dispatch would republish the valid carrier and execute CLEAR.
        if (engines.get(engine.getId()) == engine) {
          assertThat(carrier(a).get()).isNotSameAs(oldCarrier);
        }
      } finally {
        engines.set(engine.getId(), engine);
        carrier(a).set(oldCarrier);
      }
      assertThat(rids(a, "preserved")).containsExactly(rid);
      assertThat(rids(a, null)).containsExactly(rid);
    }
    assertNoOwnerRecovery(guarded);
    assertThat(storage.getStatus().name()).isEqualTo("OPEN");
  }

  /** Publication during CLEAR cannot redirect later key/null writes or either remove path. */
  @Test
  public void publicationAfterValidationDoesNotRedirectBoundDispatch() throws Exception {
    indexes(SchemaClass.INDEX_TYPE.UNIQUE);
    var engines = engines();
    var slot = a.engineSnapshot().engineReference().slot();
    var original = engines.get(slot);
    var observed = spy(original);
    var oldCarrier = carrier(a).get();
    var destination = b.engineSnapshot();
    var guarded = withoutOwnerRecovery(a);
    doAnswer(invocation -> {
      publish(a, destination.engineIdentifier(), destination.engineReference(), b.getIdentity());
      return invocation.callRealMethod();
    }).when(observed).clear(any(), any());
    engines.set(slot, observed);
    var rid = new RecordId(7, 10);
    try {
      db.begin();
      stage(guarded, OPERATION.CLEAR, null, null);
      stage(guarded, OPERATION.PUT, "kept", rid);
      stage(guarded, OPERATION.PUT, null, rid);
      db.commit();
      assertThat(a.getIndexId()).isEqualTo(destination.engineIdentifier());
    } finally {
      carrier(a).set(oldCarrier);
      engines.set(slot, original);
    }
    assertThat(rids(a, "kept")).containsExactly(rid);
    assertThat(rids(a, null)).containsExactly(rid);
    assertThat(rids(b, "kept")).isEmpty();
    assertThat(rids(b, null)).isEmpty();
    // Publish again during the first sorted key write. Both remove shapes must stay on A too.
    doAnswer(invocation -> {
      publish(a, destination.engineIdentifier(), destination.engineReference(), b.getIdentity());
      return invocation.callRealMethod();
    }).when((SingleValueIndexEngine) observed).validatedPut(any(), any(), any(), any());
    engines.set(slot, observed);
    try {
      db.begin();
      stage(guarded, OPERATION.PUT, "0-trigger", rid);
      stage(guarded, OPERATION.REMOVE, "kept", rid);
      stage(guarded, OPERATION.REMOVE, null, null);
      db.commit();
    } finally {
      carrier(a).set(oldCarrier);
      engines.set(slot, original);
    }
    assertThat(rids(a, "0-trigger")).containsExactly(rid);
    assertThat(rids(a, "kept")).isEmpty();
    assertThat(rids(a, null)).isEmpty();
    assertThat(rids(b, "0-trigger")).isEmpty();
    assertNoOwnerRecovery(guarded);
  }

  /** Publication during the first PUT cannot redirect a later multi-value non-null PUT to B. */
  @Test
  public void publicationAfterValidationDoesNotRedirectMultiValueNonNullPut() throws Exception {
    multiValuePublication(OPERATION.PUT, "later");
  }

  /** Publication during the first PUT cannot redirect a later multi-value null-key PUT to B. */
  @Test
  public void publicationAfterValidationDoesNotRedirectMultiValueNullPut() throws Exception {
    multiValuePublication(OPERATION.PUT, null);
  }

  /** A bound RID-specific REMOVE deletes only A's selected RID and preserves both of B's RIDs. */
  @Test
  public void publicationAfterValidationDoesNotRedirectMultiValueRidRemove() throws Exception {
    multiValuePublication(OPERATION.REMOVE, "later");
  }

  /** A bound null-key RID-specific REMOVE preserves A's other RID and both of B's null-key RIDs. */
  @Test
  public void publicationAfterValidationDoesNotRedirectMultiValueNullRidRemove() throws Exception {
    multiValuePublication(OPERATION.REMOVE, null);
  }

  private void multiValuePublication(OPERATION operation, Object key) throws Exception {
    indexes(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var first = new RecordId(7, 10);
    var second = new RecordId(7, 11);
    db.begin();
    stage(a, OPERATION.PUT, key, second);
    stage(b, OPERATION.PUT, key, second);
    if (operation == OPERATION.REMOVE) {
      // Both indexes contain the selected RID, so a redirected remove mutates B observably.
      stage(a, OPERATION.PUT, key, first);
      stage(b, OPERATION.PUT, key, first);
    }
    db.commit();
    var engines = engines();
    var slot = a.engineSnapshot().engineReference().slot();
    var original = engines.get(slot);
    var observed = spy(original);
    var oldCarrier = carrier(a).get();
    var destination = b.engineSnapshot();
    var guarded = withoutOwnerRecovery(a);
    var publications = new AtomicInteger();
    doAnswer(invocation -> {
      if ("0-trigger".equals(invocation.getArgument(1))) {
        publish(a, destination.engineIdentifier(), destination.engineReference(), b.getIdentity());
        publications.incrementAndGet();
      }
      return invocation.callRealMethod();
    }).when((MultiValueIndexEngine) observed).put(any(), any(), any());
    engines.set(slot, observed);
    try {
      db.begin();
      // Non-null keys are sorted and null keys run last, so publication precedes the tested path.
      stage(guarded, OPERATION.PUT, "0-trigger", first);
      stage(guarded, operation, key, first);
      db.commit();
      assertThat(publications.get()).isEqualTo(1);
      assertThat(a.getIndexId()).isEqualTo(destination.engineIdentifier());
    } finally {
      carrier(a).set(oldCarrier);
      engines.set(slot, original);
    }
    assertThat(rids(a, "0-trigger")).containsExactly(first);
    assertThat(rids(b, "0-trigger")).isEmpty();
    if (operation == OPERATION.PUT) {
      assertThat(rids(a, key)).containsExactlyInAnyOrder(first, second);
      assertThat(rids(b, key)).containsExactly(second);
    } else {
      assertThat(rids(a, key)).containsExactly(second);
      assertThat(rids(b, key)).containsExactlyInAnyOrder(first, second);
    }
    assertNoOwnerRecovery(guarded);
  }

  /** With a writer already waiting, validation and dispatch must retain the caller's reader bit. */
  @Test(timeout = 30_000)
  public void waitingWriterCannotEraseReaderProtectionDuringValidationAndDispatch()
      throws Exception {
    db.activateOnCurrentThread();
    indexes(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var engines = engines();
    var slot = a.engineSnapshot().engineReference().slot();
    var original = engines.get(slot);
    var observed = spy(original);
    var checks = new AtomicInteger();
    var writer = new Thread(() -> {
      storage.stateLock.writeLock().lock();
      storage.stateLock.writeLock().unlock();
    });
    writer.setDaemon(true);
    doAnswer(invocation -> {
      assertThat(storage.stateLock.isReadLockedByCurrentThread()).isTrue();
      if (writer.isAlive()) {
        checks.incrementAndGet();
      }
      return invocation.callRealMethod();
    }).when(observed).getEngineReference();
    doAnswer(invocation -> {
      assertThat(storage.stateLock.isReadLockedByCurrentThread()).isTrue();
      checks.incrementAndGet();
      return invocation.callRealMethod();
    }).when(observed).clear(any(), any());
    doAnswer(invocation -> {
      assertThat(storage.stateLock.isReadLockedByCurrentThread()).isTrue();
      checks.incrementAndGet();
      return invocation.callRealMethod();
    }).when((MultiValueIndexEngine) observed).put(any(), any(), any());
    engines.set(slot, observed);
    try {
      db.begin();
      stage(a, OPERATION.CLEAR, null, null);
      stage(a, OPERATION.PUT, "new", new RecordId(7, 10));
      stage(a, OPERATION.PUT, null, new RecordId(7, 11));
      txBoundary(() -> {
        writer.start();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!storage.stateLock.isWriteLocked() && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertThat(storage.stateLock.isWriteLocked()).isTrue();
        assertThat(storage.stateLock.isReadLockedByCurrentThread()).isTrue();
      });
      db.commit();
      assertThat(checks.get()).isGreaterThanOrEqualTo(4);
    } finally {
      engines.set(slot, original);
      writer.join(10_000);
    }
    assertThat(writer.isAlive()).isFalse();
    assertThat(rids(a, "new")).containsExactly(new RecordId(7, 10));
    assertThat(rids(a, null)).containsExactly(new RecordId(7, 11));
  }

  /** Schema reconciliation and record apply roll back on stale admission, including eager membership. */
  @Test
  public void schemaCarryApplyRejectionRestoresRegistriesMembershipLifecycleAndProvisionalState()
      throws Exception {
    indexes(SchemaClass.INDEX_TYPE.UNIQUE);
    var member = db.createClass("Member");
    var memberName = db.getCollectionNameById(member.getCollectionIds()[0]);
    var indexManager = db.getSharedContext().getIndexManager();
    indexManager.addCollectionToIndex(db, memberName, "A", false);
    var namesBefore = new HashSet<>(db.getCollectionNames());
    var membership = new HashSet<>(a.getCollections());
    var cell = storage.getIndexLifecycle(a.getIdentity());
    var lifecycle = cell.snapshot();
    var oldCarrier = carrier(a).get();
    var reached = new AtomicInteger();
    db.begin();
    var schema = db.getMetadata().getSchema();
    indexManager.removeCollectionFromIndex(db, memberName, "A");
    schema.dropClass("Member");
    var created = schema.createClass("Provisional");
    created.createProperty("name", PropertyType.STRING);
    created.createIndex("Provisional.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var provisionalId = created.getCollectionIds()[0];
    assertThat(provisionalId).isLessThan(-1);
    var deferred = (IndexAbstract) indexManager.getIndex(db, "Provisional.name");
    assertThat(deferred).isNotNull();
    var row = db.newEntity("Provisional");
    row.setProperty("name", "undurable");
    var state = db.getTxSchemaState();
    stage(a, OPERATION.CLEAR, null, null);
    txBoundary(() -> {
      assertThat(storage.stateLock.isWriteLockedByCurrentThread()).isTrue();
      assertThat(a.getCollections()).doesNotContain(memberName);
      assertThat(state.getResolvedCollectionId(provisionalId)).isGreaterThanOrEqualTo(0);
      assertThat(row.getIdentity().isPersistent()).isTrue();
      assertThat(storage.readRecord(
          (com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal) row.getIdentity(),
          db.getActiveTransaction().getAtomicOperation())).isInstanceOf(RawBuffer.class);
      reached.incrementAndGet();
      publish(a, -1, null, a.getIdentity());
    });
    try {
      assertThatThrownBy(db::commit).isExactlyInstanceOf(StaleIndexEngineException.class);
    } finally {
      carrier(a).set(oldCarrier);
    }
    assertThat(reached.get()).isEqualTo(1);
    assertThat(new HashSet<>(db.getCollectionNames())).isEqualTo(namesBefore);
    assertThat(a.getCollections()).containsExactlyInAnyOrderElementsOf(membership);
    assertThat(indexManager.getIndex("A")).isSameAs(a);
    assertThat(indexManager.getIndex("Provisional.name")).isNull();
    assertThat(storage.loadIndexEngine("Provisional.name")).isEqualTo(-1);
    assertThat(storage.getIndexLifecycle(a.getIdentity())).isSameAs(cell);
    assertThat(cell.snapshot()).isEqualTo(lifecycle);
    assertThat(storage.getIndexLifecycle(deferred.getIdentity())).isNull();
    assertThat(db.getTxSchemaState()).isNull();
    assertThat(db.getTransactionInternal().isActive()).isFalse();
    assertThat(storage.getCollectionIdByName(state.getProvisionalCollectionName(provisionalId)))
        .isEqualTo(-1);
    assertThat(db.getMetadata().getSchema().getClass("Member")).isNotNull();
    assertThat(db.getMetadata().getSchema().getClass("Provisional")).isNull();
    db.begin();
    var later = db.getMetadata().getSchema().createClass("AfterRejection");
    assertThat(later.getCollectionIds()[0]).isEqualTo(provisionalId);
    db.commit();
    for (var id : db.getMetadata().getSchema().getClass("AfterRejection").getCollectionIds()) {
      assertThat(id).isGreaterThanOrEqualTo(0);
    }
    assertThat(storage.getStatus().name()).isEqualTo("OPEN");
  }

  private static IndexAbstract withoutOwnerRecovery(IndexAbstract index) {
    return Mockito.mock(index.getClass(), Mockito.withSettings().spiedInstance(index)
        .defaultAnswer(invocation -> {
          if (invocation.getMethod().getName().startsWith("resolveOwnedEngine")) {
            throw new AssertionError("Apply must not recover an owner");
          }
          return invocation.callRealMethod();
        }));
  }

  private static void assertNoOwnerRecovery(IndexAbstract index) {
    assertThat(Mockito.mockingDetails(index).getInvocations()).noneMatch(
        invocation -> invocation.getMethod().getName().startsWith("resolveOwnedEngine"));
  }

  private void stage(IndexAbstract index, OPERATION operation, Object key, RID rid) {
    db.getActiveTransaction().addIndexEntry(index, index.getName(), operation, key, rid);
  }

  private List<RID> rids(IndexAbstract index, Object key) {
    return db.computeInTx(tx -> index.getRids(db, key).toList());
  }

  private void txBoundary(Runnable mutation) {
    // Serialization operations run after all record apply and engine selection, before index apply.
    db.getActiveTransaction().getRecordSerializationContext()
        .push((operation, ignored) -> mutation.run());
  }

  @SuppressWarnings("unchecked")
  private List<BaseIndexEngine> engines() throws Exception {
    var field = AbstractStorage.class.getDeclaredField("indexEngines");
    field.setAccessible(true);
    return (List<BaseIndexEngine>) field.get(storage);
  }

  @SuppressWarnings("unchecked")
  private Map<Object, Object> snapshotMap(String name) throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    return new HashMap<>((Map<Object, Object>) field.get(storage));
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<Object> carrier(IndexAbstract index) {
    try {
      var field = IndexAbstract.class.getDeclaredField("handleState");
      field.setAccessible(true);
      return (AtomicReference<Object>) field.get(index);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void publish(IndexAbstract index, int id, IndexEngineReference reference,
      RID owner) {
    try {
      var state = carrier(index);
      var constructor = state.get().getClass().getDeclaredConstructor(int.class,
          IndexEngineReference.class, RID.class, IndexLifecycleCell.class);
      constructor.setAccessible(true);
      state.set(constructor.newInstance(id, reference, owner, null));
    } catch (InvocationTargetException exception) {
      throw new AssertionError(exception.getCause());
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }
}
