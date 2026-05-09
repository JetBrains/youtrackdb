package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.NoTxRecordReadException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction.TXSTATUS;
import java.util.Collections;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Coverage for {@link FrontendTransactionNoTx}, the no-op transaction used
 * by the embedded session before any explicit {@code begin()} call. The
 * class enforces the contract "writes/loads are forbidden in no-tx mode" by
 * rejecting almost every operation; this test pins that contract on every
 * branch — the read methods throw {@link NoTxRecordReadException}, the
 * mutating methods throw {@link UnsupportedOperationException} or
 * {@link com.jetbrains.youtrackdb.internal.core.exception.DatabaseException},
 * and the few side-effect-free getters return the documented defaults.
 */
public class FrontendTransactionNoTxTest extends DbTestBase {

  private FrontendTransactionNoTx noTx;

  @Before
  public void initNoTx() {
    // The session itself is created by DbTestBase#beforeTest(); we instantiate
    // a fresh no-tx wrapper instead of reusing the session's currentTx so the
    // test does not depend on whether a previous interaction reset it.
    noTx = new FrontendTransactionNoTx(session);
  }

  @After
  public void rollbackIfLeftOpen() {
    // FrontendTransactionNoTx.close() is a no-op, but if a misbehaving test
    // started a real tx on the underlying session, ensure it's released.
    if (session != null && !session.isClosed() && session.getTransactionInternal().isActive()) {
      session.rollback();
    }
  }

  @Test
  public void beginInternalThrowsUnsupported() {
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.beginInternal());
  }

  @Test
  public void commitInternalThrowsUnsupported() {
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.commitInternal());
  }

  @Test
  public void rollbackInternalThrowsUnsupported() {
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.rollbackInternal());
  }

  @Test
  public void publicCommitAndRollbackThrowUnsupported() {
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.commit());
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.rollback());
  }

  @Test
  public void getStatusIsInvalidAndIsActiveFalse() {
    // Before any begin, the no-tx wrapper exposes INVALID/inactive so the
    // session's check-if-active gate skips no-tx flows.
    Assert.assertEquals(TXSTATUS.INVALID, noTx.getStatus());
    Assert.assertFalse(noTx.isActive());
  }

  @Test
  public void counterMethodsReturnZero() {
    Assert.assertEquals(0, noTx.getEntryCount());
    Assert.assertEquals(0, noTx.getRecordOperationsCount());
    Assert.assertEquals(0, noTx.activeTxCount());
    Assert.assertEquals(0, noTx.amountOfNestedTxs());
    Assert.assertEquals(0L, noTx.getId());
  }

  @Test
  public void streamAndCollectionAccessorsAreEmpty() {
    Assert.assertEquals(0L, noTx.getRecordOperations().count());
    Assert.assertTrue(noTx.getCurrentRecordEntries().isEmpty());
    Assert.assertTrue(noTx.getRecordOperationsInternal().isEmpty());
    Assert.assertEquals(Collections.emptyList(), noTx.getInvolvedIndexes());
  }

  @Test
  public void getIndexChangesReturnsNull() {
    Assert.assertNull(noTx.getIndexChanges("any-index"));
    Assert.assertNull(noTx.getIndexChangesInternal("any-index"));
  }

  @Test
  public void getIndexOperationsThrowsUnsupported() {
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.getIndexOperations());
  }

  @Test
  public void readMethodsThrowNoTxRecordReadException() {
    var rid = new RecordId(0, 1);
    Assert.assertThrows(NoTxRecordReadException.class, () -> noTx.loadRecord(rid));
    Assert.assertThrows(NoTxRecordReadException.class, () -> noTx.exists(rid));
    Assert.assertThrows(NoTxRecordReadException.class, () -> noTx.getRecord(rid));
  }

  @Test
  public void rangeRidQueriesThrowNoTxRecordReadException() {
    var rid = new RecordId(0, 1);
    Assert.assertThrows(
        NoTxRecordReadException.class, () -> noTx.getNextRidInCollection(rid, Long.MAX_VALUE));
    Assert.assertThrows(
        NoTxRecordReadException.class, () -> noTx.getPreviousRidInCollection(rid, 0L));
  }

  /**
   * The richer {@code load*} family relies on the session in tx mode and
   * is forbidden in no-tx mode; covers every overload pair (RID +
   * Identifiable, OrNull variants).
   */
  @Test
  public void loadEntityFamilyIsUnsupported() {
    var rid = new RecordId(0, 1);
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.loadEntity(rid));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.loadEntityOrNull(rid));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.loadVertex(rid));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.loadVertexOrNull(rid));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.loadEdge(rid));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.loadEdgeOrNull(rid));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.loadBlob(rid));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.loadBlobOrNull(rid));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.load(rid));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.loadOrNull(rid));
  }

  @Test
  public void factoryMethodsAreUnsupported() {
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.newBlob());
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.newBlob(new byte[] {1}));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.newEntity());
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.newEntity("V"));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.newEmbeddedEntity());
    Assert.assertThrows(
        UnsupportedOperationException.class, () -> noTx.newEmbeddedEntity("Embedded"));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.newVertex("V"));
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> noTx.createOrLoadEntityFromJson("{\"@type\":\"d\"}"));
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> noTx.createOrLoadRecordFromJson("{\"@type\":\"d\"}"));
  }

  @Test
  public void queryAndCommandFamilyIsUnsupported() {
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.query("SELECT 1"));
    Assert.assertThrows(
        UnsupportedOperationException.class, () -> noTx.query("SELECT 1", Collections.emptyMap()));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.execute("SELECT 1"));
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> noTx.execute("SELECT 1", Collections.emptyMap()));
    Assert.assertThrows(
        UnsupportedOperationException.class, () -> noTx.command("INSERT INTO V SET name='x'"));
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> noTx.command("INSERT INTO V", Collections.emptyMap()));
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> noTx.computeScript("sql", "RETURN 1"));
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> noTx.computeScript("sql", "RETURN 1", Collections.<String, Object>emptyMap()));
  }

  @Test
  public void mutationsAreUnsupportedOrDatabaseException() {
    var rid = new RecordId(0, 1);
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.setStatus(TXSTATUS.BEGUN));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.clearRecordEntries());
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.clearIndexEntries());
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.setCustomData("k", "v"));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.getCustomData("k"));
    Assert.assertThrows(UnsupportedOperationException.class, () -> noTx.getRecordEntry(rid));
    Assert.assertThrows(
        UnsupportedOperationException.class, () -> noTx.addRecordOperation(null, (byte) 0));
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> noTx.addIndexEntry(null, "ix", null, "key", null));
    // delete/deleteRecord throw DatabaseException, not UnsupportedOperationException, by design.
    Assert.assertThrows(
        com.jetbrains.youtrackdb.internal.core.exception.DatabaseException.class,
        () -> noTx.deleteRecord(null));
  }

  @Test
  public void boundaryFlagsHaveDocumentedDefaults() {
    var rid = new RecordId(0, 5);
    Assert.assertFalse(noTx.isDeletedInTx(rid));
    Assert.assertFalse(noTx.isScheduledForCallbackProcessing(rid));
    Assert.assertFalse(noTx.isCallBackProcessingInProgress());
  }

  @Test
  public void getRecordSerializationContextIsUnsupported() {
    Assert.assertThrows(
        UnsupportedOperationException.class, () -> noTx.getRecordSerializationContext());
  }

  @Test
  public void assertIdentityChangedAfterCommitIsUnsupported() {
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> noTx.assertIdentityChangedAfterCommit(null, null));
  }

  @Test
  public void getDatabaseSessionAndSetSession() {
    Assert.assertSame(session, noTx.getDatabaseSession());
    // setSession swaps the session reference without touching transaction state.
    noTx.setSession(session);
    Assert.assertSame(session, noTx.getDatabaseSession());
  }

  @Test
  public void closeIsNoOp() {
    // close() must be safely callable; it has no observable side-effect.
    noTx.close();
    noTx.close();
    Assert.assertEquals(TXSTATUS.INVALID, noTx.getStatus());
  }

  @Test
  public void recordNotFoundAndRecordReadDistinguished() {
    // RecordNotFoundException is the exception read methods may declare on
    // the FrontendTransaction interface; ensure the no-tx variant prefers
    // the more specific NoTxRecordReadException so callers can distinguish
    // "no-tx misuse" from "record genuinely missing".
    Assert.assertNotEquals(RecordNotFoundException.class, NoTxRecordReadException.class);
    var rid = new RecordId(0, 1);
    var thrown = Assert.assertThrows(NoTxRecordReadException.class, () -> noTx.loadRecord(rid));
    Assert.assertNotNull(thrown.getMessage());
  }
}
