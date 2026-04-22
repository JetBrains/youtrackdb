package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

/**
 * Direct coverage for {@link LoaderExecutionStream}. The loader pulls {@link Identifiable}
 * entries from an upstream iterator and materializes them into {@link ResultInternal}s via
 * {@code session.load}. It recognizes three shapes:
 *
 * <ul>
 *   <li>full {@code DBRecord} — wrapped directly, no lookup;</li>
 *   <li>{@link ContextualRecordId} — loaded, then {@code res.addMetadata(ctx)} attaches the
 *       contextual metadata map;</li>
 *   <li>plain {@link com.jetbrains.youtrackdb.internal.core.db.record.record.RID} — loaded
 *       via {@code session.load(nextRid.getIdentity())}.</li>
 * </ul>
 *
 * <p>It also skips null iterator values and swallows {@code RecordNotFoundException} by
 * stopping the scan (breaks out of the current hasNext with an unset nextResult, which the
 * next hasNext call re-examines).
 */
public class LoaderExecutionStreamTest extends DbTestBase {

  private BasicCommandContext newContext() {
    return new BasicCommandContext(session);
  }

  private EntityImpl createAndSave() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    session.commit();
    return entity;
  }

  /**
   * When the iterator yields a fully-loaded {@link EntityImpl}, the loader wraps it directly
   * in a {@link ResultInternal} without performing any lookup.
   */
  @Test
  public void loaderWrapsDBRecordDirectly() {
    var ctx = newContext();
    var entity = createAndSave();
    var stream = new LoaderExecutionStream(List.<Identifiable>of(entity).iterator());

    assertThat(stream.hasNext(ctx)).isTrue();
    var result = stream.next(ctx);
    assertThat(result.getIdentity()).isEqualTo(entity.getIdentity());
    // $current is set so downstream steps can reference the active row.
    assertThat(ctx.<Object>getSystemVariable(CommandContext.VAR_CURRENT)).isSameAs(result);
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  /**
   * A plain {@link com.jetbrains.youtrackdb.internal.core.db.record.record.RID} is resolved
   * via {@code session.load(identity)}. The load requires an active transaction.
   */
  @Test
  public void loaderResolvesBareRidByLoading() {
    var ctx = newContext();
    var entity = createAndSave();
    var rid = entity.getIdentity();

    session.begin();
    try {
      var stream = new LoaderExecutionStream(List.<Identifiable>of(rid).iterator());
      assertThat(stream.hasNext(ctx)).isTrue();
      var result = stream.next(ctx);
      assertThat(result.getIdentity()).isEqualTo(rid);
    } finally {
      session.rollback();
    }
  }

  /**
   * When the iterator element is a {@link ContextualRecordId}, the loader attaches the
   * context map as metadata on the resulting ResultInternal. The load requires an active
   * transaction.
   */
  @Test
  public void loaderAttachesMetadataForContextualRecordId() {
    var ctx = newContext();
    var entity = createAndSave();

    var contextual = new ContextualRecordId(entity.getIdentity().toString());
    var metadata = new HashMap<String, Object>();
    metadata.put("__origin__", "test-fixture");
    contextual.setContext(metadata);

    session.begin();
    try {
      var stream = new LoaderExecutionStream(List.<Identifiable>of(contextual).iterator());
      assertThat(stream.hasNext(ctx)).isTrue();
      var result = (ResultInternal) stream.next(ctx);
      assertThat(result.getMetadata("__origin__")).isEqualTo("test-fixture");
    } finally {
      session.rollback();
    }
  }

  /**
   * The loader silently skips null iterator entries and proceeds to the next non-null one
   * (the null-entry branch in {@code fetchNext} falls through into the next iteration).
   */
  @Test
  public void loaderSkipsNullIteratorEntries() {
    var ctx = newContext();
    var entity = createAndSave();

    var list = new java.util.ArrayList<Identifiable>();
    list.add(null);
    list.add(entity);
    var stream = new LoaderExecutionStream(list.iterator());

    assertThat(stream.hasNext(ctx)).isTrue();
    assertThat(stream.next(ctx).getIdentity()).isEqualTo(entity.getIdentity());
    assertThat(stream.hasNext(ctx)).isFalse();
  }

  /**
   * A RID that does not resolve to an existing record causes the loader to stop the scan
   * without throwing: {@code RecordNotFoundException} is swallowed and {@code nextResult}
   * stays null, so the next hasNext returns false. Uses a cluster id of 1 (admin/default)
   * with an impossible position so {@code session.load} reaches the not-found path.
   */
  @Test
  public void loaderStopsOnRecordNotFoundException() {
    var ctx = newContext();
    // cluster 0 (internal) position 999_999_999 is guaranteed not to exist.
    var missingRid = new RecordId(0, 999_999_999L);
    session.begin();
    try {
      var stream = new LoaderExecutionStream(List.<Identifiable>of(missingRid).iterator());
      assertThat(stream.hasNext(ctx)).isFalse();
    } finally {
      session.rollback();
    }
  }

  /**
   * Calling {@code next()} on an exhausted LoaderExecutionStream throws
   * {@link IllegalStateException}.
   */
  @Test
  public void loaderNextOnExhaustedStreamThrows() {
    var ctx = newContext();
    var stream = new LoaderExecutionStream(Collections.<Identifiable>emptyIterator());

    assertThat(stream.hasNext(ctx)).isFalse();
    assertThatThrownBy(() -> stream.next(ctx)).isInstanceOf(IllegalStateException.class);
  }

  /** {@code close} is a no-op and idempotent. */
  @Test
  public void loaderCloseIsNoOp() {
    var ctx = newContext();
    var stream = new LoaderExecutionStream(Collections.<Identifiable>emptyIterator());

    stream.close(ctx);
    stream.close(ctx);
  }
}
