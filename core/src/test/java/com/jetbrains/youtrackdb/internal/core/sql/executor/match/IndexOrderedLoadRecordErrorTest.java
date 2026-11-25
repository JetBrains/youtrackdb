package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import org.junit.Test;

/**
 * Index-ordered MATCH must skip only missing records on load, and must surface every other load
 * failure instead of silently dropping the index entry.
 */
public class IndexOrderedLoadRecordErrorTest {

  /**
   * A storage or transaction failure other than RecordNotFoundException must propagate out of
   * loadRecord so the MATCH query fails rather than returning an incomplete result set.
   */
  @Test
  public void nonMissingLoadFailurePropagates() {
    var rid = new RecordId(1, 1);
    var session = mock(DatabaseSessionEmbedded.class);
    var tx = mock(FrontendTransactionImpl.class);
    when(session.getActiveTransaction()).thenReturn(tx);
    when(tx.load(rid)).thenThrow(new IllegalStateException("injected storage failure"));

    var thrown = assertThrows(
        IllegalStateException.class,
        () -> IndexOrderedEdgeStep.loadRecord(rid, session));
    assertEquals("injected storage failure", thrown.getMessage());
  }

  /**
   * A RecordNotFoundException still means a stale index entry: skip the entry and return null so
   * the scan continues with the next key.
   */
  @Test
  public void recordNotFoundIsSkipped() {
    var rid = new RecordId(1, 2);
    var session = mock(DatabaseSessionEmbedded.class);
    var tx = mock(FrontendTransactionImpl.class);
    when(session.getActiveTransaction()).thenReturn(tx);
    when(tx.load(rid)).thenThrow(new RecordNotFoundException("testdb", rid));

    assertNull(IndexOrderedEdgeStep.loadRecord(rid, session));
  }
}
