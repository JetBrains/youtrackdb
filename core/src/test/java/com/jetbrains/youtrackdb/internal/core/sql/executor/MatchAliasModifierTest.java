package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
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
    when(entity.getProperty("nested")).thenReturn(Map.of("name", "nested-value"));
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
    ctx.setSystemVariable(CommandContext.VAR_MATCHED, row);
    var chained = ProjectionExpressionFactories.matchedVariable("a", "nested", "name");
    assertEquals("nested-value", chained.execute(row, ctx));
    assertEquals("nested-value", chained.copy().execute(row, ctx));
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

  /** Missing aliases and ordinary maps retain their fallback on session-free and external rows. */
  @Test
  public void fallbackKeepsTheEffectiveValue() {
    var row = new ResultInternal(null);
    var ctx = new BasicCommandContext();
    var identity = ProjectionExpressionFactories.aliasRecordAttribute("a", "@rid").copy();
    assertNull(identity.execute(row, ctx));
    var property = ProjectionExpressionFactories.aliasProperty("a", "name");
    row.setProperty("a", Map.of("name", "map-value"));
    assertEquals("map-value", property.execute(row, ctx));
    var external = mock(Result.class);
    when(external.getProperty("a")).thenReturn(Map.of("name", "external"));
    assertEquals("external", property.copy().execute(external, ctx));
  }

  /** Both identity accessors skip checked requests only for current evidence of the same RID. */
  @Test
  public void directAndCrossAliasIdentitiesRequireReusableEvidence() {
    var session = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    when(session.assertIfNotActive()).thenReturn(true);
    var tx = session.getActiveTransaction();
    when(session.getActiveTransactionOrNull()).thenReturn(tx);
    var rid = new RecordId(9, 1);
    var entity = mock(Entity.class);
    when(entity.getIdentity()).thenReturn(rid);
    when(session.refreshRid(rid)).thenReturn(rid);
    var requests = new AtomicInteger();
    when(tx.load(any(Identifiable.class))).thenAnswer(invocation -> {
      requests.incrementAndGet();
      return entity;
    });
    var row = new ResultInternal(session);
    row.setMatchProperty("a", ResultInternal.fromCheckedRecord(session,
        tx.load((Identifiable) rid)));
    var outer = new ResultInternal(session);
    var ctx = new BasicCommandContext(session);
    ctx.setSystemVariable(CommandContext.VAR_MATCHED, row);
    var direct = ProjectionExpressionFactories.aliasRecordAttribute("a", "@rid");
    var cross = ProjectionExpressionFactories.matchedVariable("a", "@rid");
    for (var expression : new SQLExpression[] {direct, direct.copy()}) {
      assertEquals(rid, expression.execute(row, ctx));
    }
    ctx.setSystemVariable(CommandContext.VAR_CURRENT, null);
    for (var expression : new SQLExpression[] {cross, cross.copy()}) {
      assertEquals(rid, expression.execute(outer, ctx));
    }
    assertEquals(1, requests.get());
    assertSame(outer, ctx.getSystemVariable(CommandContext.VAR_CURRENT));

    // Invalidated security evidence and a plain RID both keep the ordinary checked read.
    when(session.getSharedContext().getSecurity().getVersion(session)).thenReturn(1L);
    assertEquals(rid, direct.execute(row, ctx));
    assertEquals(rid, cross.copy().execute(outer, ctx));
    assertEquals(3, requests.get());
    row.removeProperty("a");
    row.setProperty("a", rid);
    assertNull(row.getMatchedRecord("a"));
    assertEquals(rid, direct.copy().execute(row, ctx));
    assertEquals(rid, cross.execute(outer, ctx));
    assertEquals(5, requests.get());

    // A dangling RID must still return null through the old load-and-catch path.
    when(tx.load(any(Identifiable.class))).thenThrow(mock(RecordNotFoundException.class));
    assertNull(direct.execute(row, ctx));
    assertNull(cross.copy().execute(outer, ctx));
  }
}
