package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHook;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Test;

/** Checks the record provenance and filtered property seam used by direct counting. */
public class PropertyGroupCountRecordTest extends DbTestBase {

  /** Checked records are reused for each occurrence. Identifier-only rows keep checked loading. */
  @Test
  public void checkedRecordsAreReusedAndIdentifiersStillLoad() {
    session.begin();
    var vertex = session.newVertex("V");
    vertex.setProperty("p", "same");
    session.commit();
    session.begin();
    var checked = session.loadEntity(vertex.getIdentity());
    var row = new ResultInternal(session);
    row.setMatchProperty("v", ResultInternal.fromCheckedRecord(session, checked));
    assertThat(row.getMatchedRecord("v")).isNotNull();
    var reads = new AtomicInteger();
    RecordHook hook = new RecordHook() {
      @Override
      public void onUnregister() {
      }

      @Override
      public void onTrigger(@Nonnull TYPE type, @Nonnull DBRecord record) {
        if (type == TYPE.READ) {
          reads.incrementAndGet();
        }
      }
    };
    session.registerHook(hook);
    try {
      var counting = new PropertyGroupCount("v", "p", false);
      var ctx = new BasicCommandContext(session);
      var graph = mock(YTDBGraphInternal.class);
      assertThat(
          counting.drain(ctx, ExecutionStream.resultIterator(List.of(row, row, row).iterator()),
              graph))
          .isEqualTo(Map.of("same", 3L));
      assertThat(reads).hasValue(0);
      var identifiers = new ResultInternal(session);
      identifiers.setMatchProperty("v", vertex.getIdentity());
      assertThat(identifiers.getMatchedRecord("v")).isNull();
      assertThat(counting.drain(ctx,
          ExecutionStream.resultIterator(List.of(identifiers).iterator()), graph))
          .isEqualTo(Map.of("same", 1L));
      assertThat(reads).hasValue(1);
    } finally {
      session.unregisterHook(hook);
      session.rollback();
    }
  }

  /** A denied identifier keeps the existing not-found failure rather than exposing its property. */
  @Test
  public void deniedIdentifierKeepsExistingVisibilityBehavior() {
    session.createVertexClass("Hidden");
    session.begin();
    var vertex = session.newVertex("Hidden");
    vertex.setProperty("p", "classified");
    var security = session.getSharedContext().getSecurity();
    var policy = security.createSecurityPolicy(session, "denyRecord");
    policy.setActive(true);
    policy.setReadRule("false");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Hidden", policy);
    session.commit();
    var rid = vertex.getIdentity();
    reOpen(readerUser, readerPassword);
    session.begin();
    var row = new ResultInternal(session);
    row.setMatchProperty("v", rid);
    var ctx = new BasicCommandContext(session);
    var counting = new PropertyGroupCount("v", "p", true);
    assertThatThrownBy(() -> counting.drain(ctx,
        ExecutionStream.resultIterator(List.of(row).iterator()), mock(YTDBGraphInternal.class)))
        .isInstanceOf(com.jetbrains.youtrackdb.api.exception.RecordNotFoundException.class);
  }

  /** Null/empty/internal names and edge endpoints are absent before productive null inclusion. */
  @Test
  public void providerExcludedNamesAndAbsentAliases() {
    session.begin();
    var vertex = session.newVertex("V");
    var edge = vertex.addEdge(vertex, "E");
    var row = new ResultInternal(session);
    row.setMatchProperty("v", ResultInternal.fromCheckedRecord(session, edge));
    var empty = new ResultInternal(session);
    var ctx = new BasicCommandContext(session);
    for (var key : new String[] {null, "", "@rid", "in", "out"}) {
      for (boolean productive : List.of(false, true)) {
        var map = new PropertyGroupCount("v", key, productive).drain(ctx,
            ExecutionStream.resultIterator(List.of(row, empty).iterator()),
            mock(YTDBGraphInternal.class));
        assertThat(map.size()).isEqualTo(productive ? 1 : 0);
        if (productive) {
          assertThat(map).containsEntry(null, 2L);
        }
      }
    }
  }
}
