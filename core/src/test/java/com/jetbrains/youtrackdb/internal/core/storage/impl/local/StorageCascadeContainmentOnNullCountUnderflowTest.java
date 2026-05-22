package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDelta;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeEngineTestFixtures;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end cascade-containment test exercising the engine-mutator clamp+error
 * path on the {@link BTreeSingleValueIndexEngine} side of the in-memory
 * approximate-count surface. Combines all three containment layers introduced
 * earlier in this track:
 *
 * <ul>
 *   <li>the broadened pre-{@code endTxCommit} catch at
 *       {@link AbstractStorage#commit} (covers persisted-side asserts before
 *       they would escape as bare {@link Error}),</li>
 *   <li>the broadened {@code AtomicOperationsManager} wrapper catches (covers
 *       lambda-body asserts inside {@code executeInside*} /
 *       {@code calculateInside*} wrappers),</li>
 *   <li>the {@link AbstractStorage#setInError(Throwable)} guard that keeps
 *       {@code isInError()} {@code false} for any {@link AssertionError}
 *       survivor that still reaches a top-level {@code catch (Error)} clause.</li>
 * </ul>
 *
 * <p>The synthetic fixture targets the single-value engine because its
 * {@code persistCountDelta} drops {@code nullDelta} (non-null and null keys
 * share one B-tree; the persisted null count is derived at {@code load()} time
 * from {@code countNulls(atomicOperation)}). Injecting an oversized negative
 * {@code nullDelta} on the SV side therefore exercises exactly the in-memory
 * underflow path inside {@code applyIndexCountDeltas} — calling
 * {@code BTreeSingleValueIndexEngine.addToApproximateNullCount(Long.MIN_VALUE + 1)}
 * — without disturbing the persisted side. After the clamp+error rewrite, the
 * mutator does not throw; the surrounding {@code db.commit()} must return
 * normally, the storage's {@code isInError()} must remain {@code false}, and a
 * subsequent commit on the same storage instance must succeed.
 *
 * <p>Same-package placement is deliberate: it lets the test read the
 * {@code protected} accessor {@link AbstractStorage#isInError()} directly,
 * without needing a test-only assert hook on the production class. The
 * synthetic injection uses the same {@code IndexCountDeltaHolder} pattern as
 * {@link PersistedSideAssertionRoutedToRollbackTest}, including the external
 * engine-id bit mask ({@code & 0x7FFFFFF}) that strips the
 * {@code engineAPIVersion} high bits.
 */
public class StorageCascadeContainmentOnNullCountUnderflowTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name)
  // collisions when this test runs in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;
  private AbstractStorage storage;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(dbName, DatabaseType.MEMORY, getClass());
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) db.getStorage();
    // Sanity: a freshly-opened storage must not already be in error state, or
    // every assertion below is degenerate.
    assertThat(storage.isInError())
        .as("freshly-opened storage starts clean")
        .isFalse();
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  /**
   * Drives an in-memory {@code approximateNullCount} underflow through the
   * full storage commit path by overwriting the per-transaction
   * {@code IndexCountDelta.nullDelta} with {@code Long.MIN_VALUE + 1} just
   * before {@code db.commit()}. {@code applyIndexCountDeltas} at the success
   * branch of the commit then invokes
   * {@code BTreeSingleValueIndexEngine.addToApproximateNullCount(Long.MIN_VALUE + 1)}
   * on the live engine, the clamp+error helper logs at {@code SEVERE} level
   * with the engine identity and clamps the counter back to {@code 0}, and the
   * commit returns normally. The storage stays usable for a subsequent
   * begin/put/commit cycle.
   *
   * <p>Verification points:
   * <ol>
   *   <li>{@code db.commit()} returns without throwing.</li>
   *   <li>{@code storage.isInError()} is still {@code false} (no layer's
   *       {@code catch (Error)} flipped the flag because no {@link Error} was
   *       thrown by the mutator at all).</li>
   *   <li>A second {@code begin / put / commit} on the same storage instance
   *       succeeds, proving the cascade is fully contained and not merely
   *       deferred to the next commit.</li>
   *   <li>Exactly one {@code SEVERE} log record was emitted by the engine's
   *       {@code reportAndClampUnderflow} path, naming the engine and the
   *       observed updated/delta values. The capture happens through the
   *       shared {@link BTreeEngineTestFixtures#captureSevereOn} helper so
   *       the JUL handler ritual lives in one place across the codebase.</li>
   * </ol>
   */
  @Test
  public void inMemoryNullCountUnderflowIsClampedAndStorageStaysUsable()
      throws Exception {
    // Set up a UNIQUE index so the BTreeSingleValueIndexEngine path is used.
    SchemaClass cls = db.createVertexClass("PersonSCC");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonSCC_name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Seed one record so the engine is fully instantiated and loaded. Without
    // a seed the engine's load() path may not have run, leaving the in-memory
    // approximateNullCount counter in an unobservable state.
    db.begin();
    var seed = db.newVertex("PersonSCC");
    seed.setProperty("name", "Alice");
    db.commit();

    // IndexAbstract.getIndexId() returns the external id (engineAPIVersion in
    // the high 5 bits, internal engine id in the low 27 bits — see
    // AbstractStorage.generateIndexId / extractInternalId). The
    // IndexCountDeltaHolder is keyed by the internal engine id, so we mask to
    // 27 bits.
    int externalIndexId = ((IndexAbstract) db.getSharedContext().getIndexManager()
        .getIndex("PersonSCC_name")).getIndexId();
    int engineId = externalIndexId & 0x7_FF_FF_FF;

    // Capture the engine's SEVERE log emissions across the whole commit-attempt
    // window so the clamp+error path's evidence (the engine's reportAndClampUnderflow
    // log line) is visible to the assertions below. The capture wraps both
    // the commit() that triggers the underflow and the follow-up second commit;
    // the second commit's path is normal and should not add a SEVERE record.
    List<LogRecord> captured = BTreeEngineTestFixtures.captureSevereOn(
        BTreeSingleValueIndexEngine.class,
        () -> {
          try {
            runUnderflowCommit(engineId);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }

          // Second commit on the same storage instance must succeed. This is
          // the load-bearing usability check: if Layer 2's setInError guard
          // had not kept isInError() false, or if the engine mutator had
          // thrown, this second commit would fail with StorageException.
          db.begin();
          var charlie = db.newVertex("PersonSCC");
          charlie.setProperty("name", "Charlie");
          db.commit();
        });

    // Layer 2's contract: no permanent error state. If the engine mutator
    // had thrown, the AssertionError would have escaped applyIndexCountDeltas,
    // reached a top-level catch (Error), called setInError -> error.set(e),
    // and isInError() would now report true. The clamp+error rewrite stops
    // the throw at its source, so this assertion pins the cascade-containment
    // contract end-to-end.
    assertThat(storage.isInError())
        .as("storage must stay usable after the in-memory null-count underflow"
            + " is clamped at the engine mutator")
        .isFalse();

    // Exactly one SEVERE log record from the engine: the first-underflow
    // stack-trace variant. The second commit does not underflow (it adds a
    // normal +1 totalDelta and 0 nullDelta), so no additional SEVERE records
    // are expected from the capture window.
    var severeRecords = captured.stream()
        .filter(r -> r.getLevel() == Level.SEVERE)
        .toList();
    assertThat(severeRecords)
        .as("the clamp+error path must emit exactly one SEVERE record for the"
            + " underflow (captured=%s)", captured)
        .hasSize(1);
    var first = severeRecords.get(0);
    assertThat(first.getMessage())
        .as("the SEVERE record must name the null-count surface and the engine"
            + " identity so a future regression is pin-pointable")
        .contains("approximateNullCount")
        .contains("PersonSCC_name")
        .contains("delta=" + (Long.MIN_VALUE + 1));
    assertThat(first.getThrown())
        .as("first-occurrence record must carry a stack trace via the latch"
            + " set by reportAndClampUnderflow")
        .isNotNull();
  }

  /**
   * Runs a single transaction that puts one vertex (driving a legitimate
   * {@code totalDelta = +1} accumulation through the index's commit
   * machinery), reflectively overwrites the per-transaction
   * {@code IndexCountDelta.nullDelta} with {@code Long.MIN_VALUE + 1}, and
   * then calls {@code db.commit()}. The commit must return normally because
   * the engine-mutator clamp+error path swallows the underflow rather than
   * throwing.
   */
  private void runUnderflowCommit(int engineId) throws Exception {
    db.begin();
    var bob = db.newVertex("PersonSCC");
    bob.setProperty("name", "Bob");

    // db.newVertex above will, during commit, accumulate +1 on totalDelta and
    // 0 on nullDelta via IndexCountDelta.accumulate. We overwrite nullDelta
    // here to Long.MIN_VALUE + 1 so applyIndexCountDeltas at the commit's
    // success branch ends up calling
    // BTreeSingleValueIndexEngine.addToApproximateNullCount(Long.MIN_VALUE + 1).
    // The accumulate runs inside commitIndexes which runs BEFORE the success
    // branch of the commit (and BEFORE applyIndexCountDeltas), so we cannot
    // overwrite nullDelta yet — getOrCreateIndexCountDeltas during a
    // commitIndexes-driven accumulate would otherwise blow our written value
    // away. The pattern below uses the same call as
    // PersistedSideAssertionRoutedToRollbackTest: ask for the holder/entry
    // first, then commit. The accumulator add-assigns onto the existing
    // long, so an overwritten -Long.MIN_VALUE-1 leaves the final value at
    // Long.MIN_VALUE + 1 + 0 (no null keys are inserted by the seed or this
    // tx). The totalDelta is left alone so its +1 still applies normally.
    var tx = db.getActiveTransaction();
    var atomicOp = tx.getAtomicOperation();
    var holder = atomicOp.getOrCreateIndexCountDeltas();
    var delta = holder.getOrCreate(engineId);
    Field nullDeltaField = IndexCountDelta.class.getDeclaredField("nullDelta");
    nullDeltaField.setAccessible(true);
    nullDeltaField.setLong(delta, Long.MIN_VALUE + 1L);

    // After this commit, applyIndexCountDeltas calls
    // engine.addToApproximateNullCount(Long.MIN_VALUE + 1). The
    // approximateNullCount started at 0 (no null inserts on the seed
    // transaction); addAndGet produces a deeply negative value, the
    // if (updated < 0) branch fires, reportAndClampUnderflow logs SEVERE
    // and clamps via compareAndSet. No exception escapes the mutator, so
    // commit() returns normally.
    db.commit();
  }
}
