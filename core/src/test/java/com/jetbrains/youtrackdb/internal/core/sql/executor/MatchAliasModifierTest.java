package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSuffixIdentifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class MatchAliasModifierTest {

  /** Count checked requests independently of a simulated physical cache miss on the first load. */
  @Test
  public void checkedRequestsFallEvenWhenPhysicalReadsAreAlreadyCached() {
    var session = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    when(session.assertIfNotActive()).thenReturn(true);
    var tx = session.getActiveTransaction();
    when(session.getActiveTransactionOrNull()).thenReturn(tx);
    var rid = new RecordId(9, 1);
    var entity = mock(Entity.class);
    when(entity.getIdentity()).thenReturn(rid);
    when(entity.getProperty("name")).thenReturn("value");
    var checkedRequests = new AtomicInteger();
    var physicalReads = new AtomicInteger();
    when(tx.loadEntity(any(Identifiable.class))).thenAnswer(invocation -> {
      if (checkedRequests.getAndIncrement() == 0) {
        physicalReads.incrementAndGet();
      }
      return entity;
    });
    var checked = ResultInternal.fromCheckedRecord(session, tx.loadEntity((Identifiable) rid));
    var row = new ResultInternal(session);
    row.setMatchProperty("a", checked);
    var ctx = new BasicCommandContext(session);
    var expression = ProjectionExpressionFactories.aliasProperty("a", "name").copy();
    for (int i = 0; i < 3; i++) {
      assertEquals("value", expression.execute(row, ctx));
    }
    assertEquals(1, checkedRequests.get());
    assertEquals(1, physicalReads.get());
    // Ordinary RID dereferences still issue a checked request even with a warm physical cache.
    var ordinary = new SQLSuffixIdentifier(new SQLIdentifier("name"));
    assertEquals("value", ordinary.execute((Identifiable) rid, ctx));
    assertEquals(2, checkedRequests.get());
    assertEquals(1, physicalReads.get());
    when(session.getSharedContext().getSecurity().getVersion(session)).thenReturn(1L);
    assertEquals("value", expression.execute(row, ctx));
    assertEquals(3, checkedRequests.get());
    assertEquals(1, physicalReads.get());
  }

  /** Identity reads never request record contents. Ordinary maps and missing values keep fallback. */
  @Test
  public void identityNeedsNoEvidenceAndFallbackKeepsTheEffectiveValue() {
    var row = new ResultInternal(null);
    var rid = new RecordId(9, 1);
    row.setProperty("a", rid);
    var ctx = new BasicCommandContext();
    var identity = ProjectionExpressionFactories.aliasRecordAttribute("a", "@rid").copy();
    assertEquals(rid, identity.execute(row, ctx));
    row.removeProperty("a");
    assertNull(identity.execute(row, ctx));
    var property = ProjectionExpressionFactories.aliasProperty("a", "name");
    row.setProperty("a", Map.of("name", "map-value"));
    assertEquals("map-value", property.execute(row, ctx));
    var external = mock(Result.class);
    when(external.getProperty("a")).thenReturn(Map.of("name", "external"));
    assertEquals("external", property.copy().execute(external, ctx));
  }
}
