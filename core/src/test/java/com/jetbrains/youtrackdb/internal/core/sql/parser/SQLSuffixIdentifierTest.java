/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.List;
import org.junit.Test;

/**
 * Direct-dispatch tests for {@link SQLSuffixIdentifier#execute(
 * com.jetbrains.youtrackdb.internal.core.query.Result,
 * com.jetbrains.youtrackdb.internal.core.command.CommandContext)}, the projection resolver that
 * maps a bare identifier to a value on the current record/result.
 *
 * <p>The resolver splits identifiers into three dispatch families that this class exercises
 * one-by-one:
 *
 * <ul>
 *   <li><b>{@code $}-prefixed names</b> — resolved from the context variables first, then (when no
 *       context variable exists) from the result's own projection columns, then from the {@link
 *       ResultInternal} metadata and temporary properties, otherwise null. A record can never store
 *       such a name, so the projection probe is gated on {@code isProjection()} and no dispatch to
 *       the record ever happens.
 *   <li><b>{@code out_}/{@code in_} edge accessors and other names that base
 *       {@code validatePropertyName} would reject</b> — routed through hasProperty-first, which
 *       never validates and therefore never throws (hasProperty-first guard).
 *   <li><b>Regular property names</b> — resolved through the getProperty-first hot path, with a
 *       hasProperty fall-back that disambiguates "present-but-null" from "absent".
 * </ul>
 *
 * <p>Most tests use a content-backed {@link ResultInternal} so present/absent/present-but-null can
 * be controlled exactly. One test uses an entity-backed result to prove the hasProperty-first
 * guard actually
 * prevents the throw that {@code EntityImpl.getProperty} would raise on an invalid name.
 */
public class SQLSuffixIdentifierTest extends DbTestBase {

  private static Object resolve(String name, ResultInternal record, BasicCommandContext ctx) {
    return new SQLSuffixIdentifier(new SQLIdentifier(name)).execute(record, ctx);
  }

  // =========================================================================
  // $-prefixed names
  // =========================================================================

  /**
   * A {@code $}-prefixed name that matches a context variable resolves to that variable's value.
   * Pins the early ctx-variable branch (before the per-record dispatch).
   */
  @Test
  public void dollarNameResolvesFromContextVariable() {
    var ctx = new BasicCommandContext(session);
    ctx.setVariable("$myVar", "ctx-value");
    var record = new ResultInternal(session);

    assertThat(resolve("$myVar", record, ctx)).isEqualTo("ctx-value");
  }

  /**
   * When no context variable matches, a {@code $}-prefixed name is resolved from the result's
   * metadata map. Pins the {@code $}-branch metadata lookup inside the per-record block.
   */
  @Test
  public void dollarNameResolvesFromResultMetadataWhenNoContextVariable() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setMetadata("$flag", 42);

    assertThat(resolve("$flag", record, ctx)).isEqualTo(42);
  }

  /**
   * When neither a context variable nor metadata matches, a {@code $}-prefixed name falls back to
   * the result's temporary properties. Pins the {@code $}-branch temporary-property lookup.
   */
  @Test
  public void dollarNameResolvesFromTemporaryPropertyWhenNoMetadata() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setTemporaryProperty("$tmp", "temp-value");

    assertThat(resolve("$tmp", record, ctx)).isEqualTo("temp-value");
  }

  /**
   * A {@code $}-prefixed name that is a column of the result's own projection resolves to that
   * column's value. MATCH addresses its pattern nodes through {@code $}-prefixed alias namespaces —
   * the planner's auto-generated aliases and the Gremlin-to-MATCH translator's reserved
   * {@code $g2m_} pattern-node aliases — and the RETURN projection reads the matched element back
   * out by that name. A resolver that treats every {@code $} name as a context/metadata-only lookup
   * returns null for each row here, which surfaces downstream as a result set of the right size
   * whose every element is null.
   */
  @Test
  public void dollarNameResolvesFromProjectionColumn() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setProperty("$g2m_v0", "matched-element");

    assertThat(resolve("$g2m_v0", record, ctx)).isEqualTo("matched-element");
  }

  /**
   * A {@code $}-prefixed projection column shadows same-named metadata and temporary properties:
   * the projection is probed before either fall-back, so the column value wins. Pins the ordering
   * inside the {@code $} branch, which the two single-source tests above cannot distinguish.
   */
  @Test
  public void dollarNameProjectionColumnWinsOverMetadataAndTemporaryProperty() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setProperty("$g2m_v0", "from-projection");
    record.setMetadata("$g2m_v0", "from-metadata");
    record.setTemporaryProperty("$g2m_v0", "from-temporary");

    assertThat(resolve("$g2m_v0", record, ctx)).isEqualTo("from-projection");
  }

  /**
   * On an entity-backed (non-projection) result, a {@code $}-prefixed name resolves to null without
   * throwing and without touching the record. No entity can store such a name — base
   * {@code validatePropertyName} rejects a leading {@code $} on write — so the projection probe is
   * gated on {@code isProjection()} and the record is never asked, which on a lazily loaded
   * RID-only result would otherwise cost a storage read for a guaranteed miss.
   *
   * <p>The no-dispatch half of that claim is what the spy pins. Asserting only "returns null without
   * throwing" cannot witness it: an ungated probe reaches {@code hasProperty}, which misses silently
   * on any result shape and lets the {@code $} branch fall through to metadata exactly as before, so
   * the observable outcome is identical with and without the gate. Verifying the dispatch never
   * happens is the only assertion here that fails when the gate is deleted.
   */
  @Test
  public void dollarNameOnEntityBackedResultResolvesToNullWithoutTouchingTheRecord() {
    session.createClass("SuffixDollarEntity");
    session.begin();
    try {
      var entity = session.newEntity("SuffixDollarEntity");
      entity.setProperty("name", "Alice");
      var record = spy(new ResultInternal(session, entity));
      var ctx = new BasicCommandContext(session);

      assertThat(record.isProjection())
          .as("an entity-backed result is not a projection, so the $ probe must be skipped")
          .isFalse();
      clearInvocations(record);
      var suffix = new SQLSuffixIdentifier(new SQLIdentifier("$g2m_v0"));
      assertThatCode(() -> suffix.execute(record, ctx)).doesNotThrowAnyException();
      assertThat(suffix.execute(record, ctx)).isNull();
      verify(record, never()).hasProperty(anyString());
    } finally {
      session.rollback();
    }
  }

  /**
   * The shape the {@code isProjection()} gate exists for: a result backed by a bare RID rather than a
   * materialized entity. Here an ungated probe would not merely miss — {@code hasProperty} takes the
   * lazy-load branch and issues a record load, a storage read for a name no record can hold. The
   * entity-backed sibling above cannot reach that branch, because its identifiable is already an
   * {@code Entity} and resolves in memory, so this is the fixture that makes the cost claim in
   * {@code SQLSuffixIdentifier}'s comment checkable.
   *
   * <p>Resolution is still null and the dispatch still never happens; deleting
   * {@code isProjection() &&} from the guard makes the verification below fail.
   */
  @Test
  public void dollarNameOnRidBackedResultResolvesToNullWithoutLoadingTheRecord() {
    session.createClass("SuffixDollarLazy");
    session.begin();
    try {
      var entity = session.newEntity("SuffixDollarLazy");
      entity.setProperty("name", "Bob");
      session.commit();
      session.begin();
      // A RID is an Identifiable but not an Entity, so ResultInternal keeps it unresolved and
      // hasProperty would have to load it — the read the gate is there to avoid.
      var record = spy(new ResultInternal(session, entity.getIdentity()));
      var ctx = new BasicCommandContext(session);

      assertThat(record.isProjection())
          .as("a RID-backed result carries no projection map, so the $ probe must be skipped")
          .isFalse();
      clearInvocations(record);
      var suffix = new SQLSuffixIdentifier(new SQLIdentifier("$g2m_v0"));
      assertThat(suffix.execute(record, ctx)).isNull();
      verify(record, never()).hasProperty(anyString());
    } finally {
      session.rollback();
    }
  }

  /**
   * A {@code $}-prefixed name that matches nothing (no context variable, no projection column, no
   * metadata, no temporary property) resolves to null and never dispatches to getProperty. Pins the
   * {@code $}-branch terminal {@code return null}.
   */
  @Test
  public void dollarNameResolvesToNullWhenNothingMatches() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);

    assertThat(resolve("$missing", record, ctx)).isNull();
  }

  // =========================================================================
  // out_/in_ edge-accessor names (hasProperty-first, never validates)
  // =========================================================================

  /**
   * An {@code out_}-prefixed edge accessor that is present on the record returns its value via the
   * hasProperty-first path. Pins the {@code startsWith(DIRECTION_OUT_PREFIX)} true branch with a
   * present entry.
   */
  @Test
  public void outPrefixNamePresentReturnsValue() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setProperty(Vertex.DIRECTION_OUT_PREFIX + "Knows", "edge-out");

    assertThat(resolve(Vertex.DIRECTION_OUT_PREFIX + "Knows", record, ctx)).isEqualTo("edge-out");
  }

  /**
   * An {@code in_}-prefixed edge accessor that is present on the record returns its value. Pins the
   * {@code startsWith(DIRECTION_IN_PREFIX)} sub-condition of the guard.
   */
  @Test
  public void inPrefixNamePresentReturnsValue() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setProperty(Vertex.DIRECTION_IN_PREFIX + "Knows", "edge-in");

    assertThat(resolve(Vertex.DIRECTION_IN_PREFIX + "Knows", record, ctx)).isEqualTo("edge-in");
  }

  /**
   * An {@code out_}-prefixed edge accessor that is absent resolves to null (hasProperty returns
   * false, so the branch falls through to the terminal null) without throwing. Pins the guard's
   * hasProperty-first false branch.
   */
  @Test
  public void outPrefixNameAbsentResolvesToNull() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);

    assertThat(resolve(Vertex.DIRECTION_OUT_PREFIX + "Missing", record, ctx)).isNull();
  }

  // =========================================================================
  // Regular property names (getProperty-first hot path)
  // =========================================================================

  /**
   * A regular property that exists with a non-null value is returned directly by the
   * getProperty-first hot path. Pins the {@code propValue != null} short-circuit.
   */
  @Test
  public void regularNamePresentNonNullReturnsValue() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setProperty("name", "Alice");

    assertThat(resolve("name", record, ctx)).isEqualTo("Alice");
  }

  /**
   * A regular property that EXISTS but holds a null value must be returned as null and treated as
   * "present": the hot path calls hasProperty (which is true) and returns the null value instead of
   * falling through to metadata/temporary lookup. This pins the present-but-null disambiguation:
   * even though a temporary property of the same name exists, it must NOT win because the record
   * property is present.
   */
  @Test
  public void regularNamePresentButNullReturnsNullAndDoesNotFallThroughToTemporary() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setProperty("nickname", null);
    // A same-named temporary property that must be shadowed by the present-but-null record value.
    record.setTemporaryProperty("nickname", "should-not-win");

    assertThat(resolve("nickname", record, ctx)).isNull();
  }

  /**
   * A regular property that is absent (no value, no metadata, no temporary property) resolves to
   * null. Pins the hot path's {@code hasProperty == false} fall-through to the terminal null.
   */
  @Test
  public void regularNameAbsentResolvesToNull() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);

    assertThat(resolve("ghost", record, ctx)).isNull();
  }

  /**
   * A regular name that is absent as a property but present in metadata resolves from metadata.
   * Pins the post-hot-path metadata fall-through branch.
   */
  @Test
  public void regularNameAbsentFallsThroughToMetadata() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setMetadata("metaKey", 99);

    assertThat(resolve("metaKey", record, ctx)).isEqualTo(99);
  }

  /**
   * A regular name that is absent as a property and absent in metadata resolves from the temporary
   * properties. Pins the post-hot-path temporary-property fall-through branch.
   */
  @Test
  public void regularNameAbsentFallsThroughToTemporaryProperty() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setTemporaryProperty("tmpKey", "tmpValue");

    assertThat(resolve("tmpKey", record, ctx)).isEqualTo("tmpValue");
  }

  // =========================================================================
  // hasProperty-first guard on a real entity: invalid names must not throw
  // =========================================================================

  /**
   * On an entity-backed result, resolving a name that base
   * {@code EntityImpl.validatePropertyName} would reject (digit-first, or containing ':', ' ',
   * '=') must NOT throw and must resolve to null when the property is absent. Without the guard the
   * getProperty-first hot path would call {@code EntityImpl.getProperty}, which throws
   * {@code IllegalArgumentException}/{@code DatabaseException} for these names. A regular valid
   * name is asserted alongside as a control to confirm the hot path still resolves normally on
   * the same entity.
   */
  @Test
  public void invalidNamesOnEntityBackedResultResolveToNullWithoutThrowing() {
    session.createClass("SuffixGuardEntity");
    session.begin();
    try {
      var entity = session.newEntity("SuffixGuardEntity");
      entity.setProperty("name", "Alice");
      var record = new ResultInternal(session, entity);
      var ctx = new BasicCommandContext(session);

      var invalidAbsentNames = List.of("123prop", "prop:value", "my property", "key=value");
      for (var invalid : invalidAbsentNames) {
        var suffix = new SQLSuffixIdentifier(new SQLIdentifier(invalid));
        assertThatCode(() -> suffix.execute(record, ctx))
            .as("resolving invalid absent name [%s] must not throw", invalid)
            .doesNotThrowAnyException();
        assertThat(suffix.execute(record, ctx))
            .as("resolving invalid absent name [%s] must be null", invalid)
            .isNull();
      }

      // Control: a valid, present regular name still resolves via the hot path.
      assertThat(resolve("name", record, ctx)).isEqualTo("Alice");
      // Control: a valid, absent regular name resolves to null (no throw).
      assertThat(resolve("ghost", record, ctx)).isNull();
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // star projection
  // =========================================================================

  /**
   * A star ({@code *}) suffix returns the current record unchanged. Pins the {@code star} short
   * branch of the resolver.
   */
  @Test
  public void starReturnsCurrentRecord() {
    var ctx = new BasicCommandContext(session);
    var record = new ResultInternal(session);
    record.setProperty("a", 1);
    var suffix = new SQLSuffixIdentifier(-1);
    suffix.star = true;

    assertThat(suffix.execute(record, ctx)).isSameAs(record);
  }
}
