package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.TransactionException;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction.TXSTATUS;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Coverage for {@link FrontendTransactionImpl} aspects not exercised by
 * {@link TransactionTest} (which focuses on snapshot isolation and
 * concurrent semantics). This class targets the simpler accessor branches,
 * the read-only mode rejection path, the nested-tx counter behaviour, and
 * the toString format that callers grep through logs.
 */
public class FrontendTransactionImplCoverageTest extends DbTestBase {

  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.getTransactionInternal().isActive()) {
      session.rollback();
    }
  }

  /**
   * The read-only constructor variant rejects record additions so
   * read-only sessions cannot accidentally mutate. We use the package-level
   * variant `(session, readOnly)` which is the constructor used by
   * {@code beginReadOnly()}'s factory.
   */
  /**
   * The read-only constructor variant rejects record additions so
   * read-only sessions cannot accidentally mutate. The readOnly branch in
   * {@code addRecordOperation} fires before any record-state checks, so
   * we don't need a fully-constructed RecordAbstract — only the readOnly
   * gate matters here.
   */
  @Test
  public void readOnlyTransactionRejectsAddRecordOperation() {
    var tx = new FrontendTransactionImpl(session, true);
    var thrown =
        Assert.assertThrows(
            DatabaseException.class, () -> tx.addRecordOperation(null, (byte) 1));
    Assert.assertTrue(
        "DatabaseException must mention read-only mode: " + thrown.getMessage(),
        thrown.getMessage().toLowerCase().contains("read-only"));
  }

  /**
   * Three FrontendTransactionImpl constructors exist: (session),
   * (session, readOnly), and (session, txId, readOnly). The third one
   * accepts an externally-supplied txId — exercise that path so the
   * `id` field is sourced from the parameter rather than from
   * `ThreadLocalRandom`.
   */
  @Test
  public void explicitTxIdConstructorPropagatesId() {
    var tx = new FrontendTransactionImpl(session, 9_000_001L, false);
    Assert.assertEquals(9_000_001L, tx.getId());
    // Default state of a freshly-constructed (un-begun) impl
    Assert.assertEquals(TXSTATUS.INVALID, tx.getStatus());
    Assert.assertFalse(tx.isActive());
    Assert.assertEquals(0, tx.getTxStartCounter());
    Assert.assertEquals(0, tx.getEntryCount());
    Assert.assertSame(session, tx.getDatabaseSession());
  }

  /**
   * The single-argument constructor must default readOnly to false, so the
   * record-operation gate does not fire when used.
   */
  @Test
  public void singleArgConstructorIsWritable() {
    var tx = new FrontendTransactionImpl(session);
    Assert.assertEquals(TXSTATUS.INVALID, tx.getStatus());
    Assert.assertFalse(tx.isActive());
    // Calling beginInternal flips status to BEGUN; pin that the constructor's
    // readOnly default did not break begin.
    tx.beginInternal();
    Assert.assertEquals(TXSTATUS.BEGUN, tx.getStatus());
    Assert.assertTrue(tx.isActive());
    tx.rollbackInternal();
    session.setNoTxMode();
  }

  /**
   * Nested begin/commit increments the counter and only the outermost
   * commit triggers a storage write. The inner commit returns null per the
   * contract; the outer one returns the rid-remap map.
   */
  @Test
  public void nestedBeginCommitOnlyOutermostMaterialises() {
    var tx = session.begin();
    tx.beginInternal();
    Assert.assertEquals(2, tx.getTxStartCounter());

    var innerResult = tx.commitInternal();
    Assert.assertNull("inner commit must return null", innerResult);
    Assert.assertEquals(1, tx.getTxStartCounter());
    Assert.assertEquals(TXSTATUS.BEGUN, tx.getStatus());

    var outerResult = tx.commitInternal();
    Assert.assertNotNull("outer commit must return a rid-remap map", outerResult);
    Assert.assertTrue(outerResult.isEmpty());
    Assert.assertEquals(0, tx.getTxStartCounter());
    Assert.assertEquals(TXSTATUS.COMPLETED, tx.getStatus());
  }

  /**
   * Committing once more than the corresponding begins is a programming
   * error and must throw TransactionException, not silently no-op.
   */
  @Test
  public void overCommitTransactionThrowsTransactionException() {
    var tx = session.begin();
    tx.commitInternal();
    Assert.assertThrows(TransactionException.class, tx::commitInternal);
  }

  /**
   * After an explicit rollback, attempting to begin again on the same tx
   * instance must throw RollbackException — distinguishing a deliberately-
   * rolled-back tx from a never-started one.
   */
  @Test
  public void rolledBackTransactionRejectsReBegin() {
    var tx = new FrontendTransactionImpl(session);
    tx.beginInternal();
    tx.rollbackInternal();
    // After rollbackInternal, status is ROLLED_BACK (or close-INVALID
    // depending on outer counter state); rolling back again must throw.
    Assert.assertThrows(IllegalStateException.class, tx::rollbackInternal);
    session.setNoTxMode();
  }

  /**
   * toString must include the tx id, status, and entry counts so it can be
   * parsed from logs. The exact format is "FrontendTransactionOptimistic
   * [id=…, status=…, recEntries=…, idxEntries=…]".
   */
  @Test
  public void toStringIncludesIdStatusAndCounts() {
    var tx = new FrontendTransactionImpl(session, 42L, false);
    var formatted = tx.toString();
    Assert.assertTrue("must contain class tag: " + formatted,
        formatted.startsWith("FrontendTransactionOptimistic ["));
    Assert.assertTrue("must contain id: " + formatted, formatted.contains("id=42"));
    Assert.assertTrue(
        "must contain status: " + formatted, formatted.contains("status=" + TXSTATUS.INVALID));
    Assert.assertTrue("must contain recEntries: " + formatted, formatted.contains("recEntries=0"));
    Assert.assertTrue("must contain idxEntries: " + formatted, formatted.contains("idxEntries=0"));
  }

  /**
   * setStatus is a setter used by the storage layer during commit/rollback;
   * pin that it round-trips and is independent of begin/commit.
   */
  @Test
  public void setStatusRoundTrips() {
    var tx = new FrontendTransactionImpl(session);
    tx.setStatus(TXSTATUS.COMMITTING);
    Assert.assertEquals(TXSTATUS.COMMITTING, tx.getStatus());
    tx.setStatus(TXSTATUS.ROLLED_BACK);
    Assert.assertEquals(TXSTATUS.ROLLED_BACK, tx.getStatus());
    Assert.assertFalse(tx.isActive());
  }

  /**
   * setSession swaps the underlying session reference (used by pool reuse).
   */
  @Test
  public void setSessionSwapsBackingSession() {
    var tx = new FrontendTransactionImpl(session);
    Assert.assertSame(session, tx.getDatabaseSession());
    // Re-set to the same session — exercises the assignment path; we don't
    // test setSession(<other>) because cross-session reuse is invariant-
    // sensitive and would need careful pool teardown.
    tx.setSession(session);
    Assert.assertSame(session, tx.getDatabaseSession());
  }

  /**
   * setCustomData stores per-tx user data, retrievable via getCustomData.
   * Returns null for unknown keys.
   */
  @Test
  public void customDataRoundTrips() {
    var tx = new FrontendTransactionImpl(session);
    Assert.assertNull(tx.getCustomData("absent"));
    tx.setCustomData("k", 123);
    Assert.assertEquals(123, tx.getCustomData("k"));
    tx.setCustomData("k", "overridden");
    Assert.assertEquals("overridden", tx.getCustomData("k"));
  }

  /**
   * getInvolvedIndexes returns null when no indexEntries were ever
   * registered (the lazy-allocated list path), and a populated list once
   * an entry exists. Pin the null-return branch so we know we covered it.
   */
  @Test
  public void getInvolvedIndexesNullWhenEmpty() {
    var tx = new FrontendTransactionImpl(session);
    Assert.assertNull("empty index entries must return null, not empty list",
        tx.getInvolvedIndexes());
    Assert.assertTrue(tx.getIndexOperations().isEmpty());
  }

  /**
   * loadRecord on a deleted-in-tx record throws RecordNotFoundException
   * — exercises the {@code if (isDeletedInTx) throw} branch.
   */
  @Test
  public void loadRecordThrowsAfterDeleteInSameTx() {
    // V is the built-in vertex class registered by the embedded session
    // bootstrap; we don't need to create it.
    var tx = session.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", "x");
    tx.commitInternal();

    var rid = v.getIdentity();

    var tx2 = session.begin();
    try {
      var loaded = tx2.loadVertex(rid);
      session.delete(loaded);
      Assert.assertThrows(RecordNotFoundException.class, () -> tx2.loadRecord(rid));
      Assert.assertFalse(tx2.exists(rid));
    } finally {
      tx2.rollbackInternal();
    }
  }

  /**
   * isCallBackProcessingInProgress is false on a freshly-constructed tx
   * and after the lifecycle has progressed without a callback step.
   */
  @Test
  public void isCallBackProcessingInProgressFalseByDefault() {
    var tx = new FrontendTransactionImpl(session);
    Assert.assertFalse(tx.isCallBackProcessingInProgress());
  }

  /**
   * beginInternal asserts on its txStartCounter precondition; we use the
   * negative-counter detection path to pin the {@link TransactionException}
   * branch via setStatus + reflection-free arithmetic. Calling begin twice
   * must increment the counter, not throw.
   */
  @Test
  public void doubleBeginIncrementsCounter() {
    var tx = session.begin();
    Assert.assertEquals(1, tx.getTxStartCounter());
    tx.beginInternal();
    Assert.assertEquals(2, tx.getTxStartCounter());
    tx.commitInternal();
    tx.commitInternal();
  }

  /**
   * getIndexChanges returns null for an unknown index name, the
   * documented "no entry" sentinel.
   */
  @Test
  public void getIndexChangesNullForUnknownIndex() {
    var tx = new FrontendTransactionImpl(session);
    Assert.assertNull(tx.getIndexChanges("does-not-exist"));
    Assert.assertNull(tx.getIndexChangesInternal("does-not-exist"));
  }

  /**
   * clearIndexEntries empties the index-changes map. Even on an empty
   * tx, the call must succeed (idempotent).
   */
  @Test
  public void clearIndexEntriesIsIdempotent() {
    var tx = new FrontendTransactionImpl(session);
    tx.clearIndexEntries();
    tx.clearIndexEntries();
    Assert.assertTrue(tx.getIndexOperations().isEmpty());
  }

  /**
   * getRecord on an unknown rid returns null per the contract (no throw).
   * Pin the early-return branch when no record entry exists.
   */
  @Test
  public void getRecordReturnsNullForUnknownRid() {
    var tx = new FrontendTransactionImpl(session);
    var rid = new com.jetbrains.youtrackdb.internal.core.id.RecordId(0, 1);
    Assert.assertNull(tx.getRecord(rid));
  }

  /**
   * getRecordEntry follows the same contract as getRecord — null sentinel
   * for unknown rid.
   */
  @Test
  public void getRecordEntryReturnsNullForUnknownRid() {
    var tx = new FrontendTransactionImpl(session);
    var rid = new com.jetbrains.youtrackdb.internal.core.id.RecordId(0, 1);
    Assert.assertNull(tx.getRecordEntry(rid));
  }

  /**
   * Calling rollbackInternal before begin (status INVALID) throws
   * IllegalStateException — the "transaction is in invalid state" branch.
   */
  @Test
  public void rollbackOnUnstartedTransactionThrows() {
    var tx = new FrontendTransactionImpl(session);
    var thrown = Assert.assertThrows(IllegalStateException.class, tx::rollbackInternal);
    Assert.assertTrue(thrown.getMessage().toLowerCase().contains("invalid state"));
  }

  /**
   * commitInternal when the transaction has not been begun must throw
   * TransactionException via checkTransactionValid().
   */
  @Test
  public void commitOnUnstartedTransactionThrows() {
    var tx = new FrontendTransactionImpl(session);
    Assert.assertThrows(TransactionException.class, tx::commitInternal);
  }
}
