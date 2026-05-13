/*
 *
 *
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
package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for the {@link AbstractCollectionConverter#convertSingleValue} dispatch
 * arms. The base class is exercised through a test-only {@code Probe} subclass whose only job
 * is to expose {@code convertSingleValue} so the test can drive each arm directly:
 *
 * <ul>
 *   <li>{@code item == null} — callback receives null, {@code updated} returned unchanged.</li>
 *   <li>{@code item instanceof Identifiable} (rid) — dispatched to {@link LinkConverter}; if
 *       the rewrite produces {@code BROKEN_LINK} the callback is skipped; otherwise the
 *       converted rid is added.</li>
 *   <li>{@code item} is a non-Identifiable, non-collection scalar — the factory returns null,
 *       the input is added unchanged, {@code updated} stays as input.</li>
 * </ul>
 *
 * <p>The dispatch on collection types (lists, sets, maps, link bag) is exercised separately
 * by the per-collection converter tests; this test pins the lower-level base-class contract.
 */
public class AbstractCollectionConverterTest extends DbTestBase {

  /**
   * Defensive {@code @After} (rollback safety net).
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session == null || session.isClosed()) {
      return;
    }
    var tx = session.getActiveTransactionOrNull();
    if (tx != null && tx.isActive()) {
      tx.rollback();
    }
  }

  /**
   * Test-only subclass that exposes the protected {@link
   * AbstractCollectionConverter#convertSingleValue} method to the surrounding tests. The
   * generic parameter is irrelevant here — {@code convertSingleValue} doesn't touch it; it
   * dispatches on the runtime type of {@code item}.
   */
  private static final class Probe extends AbstractCollectionConverter<Object> {

    Probe(ConverterData converterData) {
      super(converterData);
    }

    @Override
    public Object convert(DatabaseSessionEmbedded session, Object value) {
      throw new UnsupportedOperationException("Probe is for convertSingleValue exposure only");
    }

    boolean exposeConvertSingleValue(DatabaseSessionEmbedded db, Object item,
        ResultCallback callback, boolean updated) {
      return convertSingleValue(db, item, callback, updated);
    }
  }

  /**
   * Captures a sequence of items added by a {@link AbstractCollectionConverter.ResultCallback}
   * for assertion. Provides a simple read-only view of the call sequence.
   */
  private static final class CapturingCallback
      implements AbstractCollectionConverter.ResultCallback {

    final List<Object> items = new ArrayList<>();

    @Override
    public void add(Object item) {
      items.add(item);
    }
  }

  /**
   * The null-item arm: callback receives null, the {@code updated} flag is returned as
   * {@code false} (the method's early-return arm).
   */
  @Test
  public void testNullItemAddsNullAndReportsNotUpdated() {
    var probe = new Probe(new ConverterData(session, new HashSet<>()));
    var callback = new CapturingCallback();

    var updated = probe.exposeConvertSingleValue(session, null, callback, false);

    assertFalse("null-item path returns updated=false unconditionally", updated);
    assertEquals(1, callback.items.size());
    assertNull("callback received null", callback.items.get(0));
  }

  /**
   * Identifiable item with no rewrite: the LinkConverter looks up the rid and returns it
   * unchanged (no mapping found, not in broken-rids set). Callback receives the rid; the
   * {@code updated} flag stays as the input value.
   */
  @Test
  public void testIdentifiableItemNoRewriteAddsRidAndPreservesUpdatedFlag() {
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));

    var probe = new Probe(new ConverterData(session, new HashSet<>()));
    var callback = new CapturingCallback();

    var rid = new RecordId(20, 1);
    var updated = probe.exposeConvertSingleValue(session, rid, callback, false);

    assertFalse("no rewrite happened — updated must remain false", updated);
    assertEquals(1, callback.items.size());
    assertEquals(rid, callback.items.get(0));
  }

  /**
   * Identifiable item that is mapped: LinkConverter returns the mapped target; callback
   * receives the new rid; the {@code updated} flag flips to true.
   */
  @Test
  public void testIdentifiableItemMappedFlipsUpdatedFlag() {
    var fromRid = new RecordId(10, 4);
    var toRid = new RecordId(10, 3);
    ImporterTestFixtures.setupRidMapping(session, fromRid, toRid);

    var probe = new Probe(new ConverterData(session, new HashSet<>()));
    var callback = new CapturingCallback();

    var updated = probe.exposeConvertSingleValue(session, fromRid, callback, false);

    assertTrue("mapped rewrite must flip updated flag to true", updated);
    assertEquals(1, callback.items.size());
    assertEquals(toRid, callback.items.get(0));
  }

  /**
   * Identifiable item that is broken: LinkConverter returns the {@code BROKEN_LINK} sentinel,
   * which the dispatch detects via {@code ==} and skips the callback.add call. The {@code
   * updated} flag is still flipped to true because the rewrite happened — the entry just
   * happens to be elided.
   */
  @Test
  public void testIdentifiableItemBrokenSkipsCallbackButFlipsUpdated() {
    var brokenRid = new RecordId(7, 1);
    Set<RID> brokenRids = new HashSet<>();
    brokenRids.add(brokenRid);
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));

    var probe = new Probe(new ConverterData(session, brokenRids));
    var callback = new CapturingCallback();

    var updated = probe.exposeConvertSingleValue(session, brokenRid, callback, false);

    assertTrue("broken-link path still flips updated flag (rewrite happened)", updated);
    assertTrue("callback must NOT be invoked for broken links", callback.items.isEmpty());
  }

  /**
   * Non-Identifiable, non-collection scalar (string here): the factory returns null, the
   * dispatch falls into the {@code else} branch and adds the input as-is. The {@code updated}
   * flag is preserved as input (no rewrite occurred).
   */
  @Test
  public void testScalarItemAddsByReferenceAndPreservesUpdatedFlag() {
    var probe = new Probe(new ConverterData(session, new HashSet<>()));
    var callback = new CapturingCallback();

    var item = "scalar";
    var updated = probe.exposeConvertSingleValue(session, item, callback, false);

    assertFalse("no converter, no rewrite — updated stays false", updated);
    assertEquals(1, callback.items.size());
    assertSame("scalar must be added by reference", item, callback.items.get(0));
  }

  /**
   * If {@code updated=true} is passed in, the no-rewrite scalar arm preserves it (the flag is
   * cumulative across iterations, never cleared by a no-op element).
   */
  @Test
  public void testScalarItemPreservesUpdatedTrueInput() {
    var probe = new Probe(new ConverterData(session, new HashSet<>()));
    var callback = new CapturingCallback();

    var updated = probe.exposeConvertSingleValue(session, "x", callback, true);

    assertTrue("incoming updated=true must survive a no-op element", updated);
    assertEquals(1, callback.items.size());
  }

  /**
   * The {@code ResultCallback} interface is callable as a lambda — pin that the interface
   * stays usable for the {@code LinkBagConverter}'s lambda call site (which passes a
   * lambda, not an anonymous class).
   */
  @Test
  public void testResultCallbackCallableAsLambda() {
    AbstractCollectionConverter.ResultCallback callback = item -> {
      /* no-op */ };
    callback.add("anything");
    // Lambda compiles and is invocable — pin holds.
  }
}
