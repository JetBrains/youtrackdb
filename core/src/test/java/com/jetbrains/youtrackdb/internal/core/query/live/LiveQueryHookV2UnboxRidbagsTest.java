/*
 *
 *
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
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.query.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

/**
 * Coverage for the single live static in {@link LiveQueryHookV2} — {@code unboxRidbags}.
 *
 * <p>{@code unboxRidbags} is the only {@code LiveQueryHookV2} entry point with a production
 * caller: {@code CopyRecordContentBeforeUpdateStep} uses it to replace {@link LinkBag} values on
 * a snapshotted document with a flat {@code List<Identifiable>} before the update commits. The
 * method has two branches:
 *
 * <ul>
 *   <li><strong>{@link LinkBag} input</strong> — iterate the bag, extract {@code primaryRid} from
 *       each pair, return a new {@code ArrayList<Identifiable>} sized to the bag.
 *   <li><strong>Any other input</strong> — identity-return the value unchanged.
 * </ul>
 *
 * <p>The sibling {@link LiveQueryDeadCodeTest} covers the rest of the file as dead code; this
 * class is <em>not</em> a dead-code pin. Track 22 will delete the dead surface around
 * {@code unboxRidbags} but {@code unboxRidbags} itself must survive (possibly relocated to a
 * helper, per the TODO comment in the source).
 *
 * <p>Extends {@link TestUtilsFixture} because constructing a real {@link LinkBag} requires a
 * session. The passthrough branch does not, but we keep both branches co-located for clarity and
 * to use the same fixture's {@code @After rollbackIfLeftOpen} safety net.
 */
public class LiveQueryHookV2UnboxRidbagsTest extends TestUtilsFixture {

  // -------------------------------------------------------------------------
  // Non-LinkBag passthrough branch — identity return across representative value types
  // -------------------------------------------------------------------------

  /** Non-LinkBag {@link String} input must return the same instance (identity passthrough). */
  @Test
  public void passthroughReturnsStringUnchanged() {
    var value = "not-a-linkbag";
    var result = LiveQueryHookV2.unboxRidbags(value);
    assertSame("String passthrough must return the exact same instance", value, result);
  }

  /** {@link Integer} input exercises the non-LinkBag branch on a boxed primitive. */
  @Test
  public void passthroughReturnsIntegerUnchanged() {
    Integer value = 42;
    var result = LiveQueryHookV2.unboxRidbags(value);
    assertSame(value, result);
  }

  /**
   * A {@code null} input flows through the {@code instanceof} check (which is false for null) and
   * out the identity-return — observable value is {@code null}.
   */
  @Test
  public void passthroughReturnsNullUnchanged() {
    var result = LiveQueryHookV2.unboxRidbags(null);
    assertNull("null input must be returned unchanged", result);
  }

  /**
   * A non-LinkBag {@link java.util.Collection} (e.g. {@link ArrayList}) must passthrough with no
   * iteration. Guards against accidental broadening of the LinkBag branch to all iterables.
   */
  @Test
  public void passthroughReturnsPlainListUnchanged() {
    var value = new ArrayList<>(List.of("a", "b"));
    var result = LiveQueryHookV2.unboxRidbags(value);
    assertSame("Non-LinkBag List must pass through without re-wrapping", value, result);
  }

  /**
   * A {@link java.util.Map} passes through unchanged. Ensures the {@code instanceof LinkBag}
   * dispatch does not accidentally fire for arbitrary collection-like inputs.
   */
  @Test
  public void passthroughReturnsMapUnchanged() {
    var value = new HashMap<String, Object>();
    value.put("k", "v");
    var result = LiveQueryHookV2.unboxRidbags(value);
    assertSame(value, result);
  }

  // -------------------------------------------------------------------------
  // LinkBag branch — the method's sole reason to exist
  // -------------------------------------------------------------------------

  /**
   * An empty {@link LinkBag} must be unboxed into a non-null, empty {@code List<Identifiable>}.
   * Verifies the {@code new ArrayList<>(linkBag.size())} allocation and the terminating branch
   * of the iterator loop.
   */
  @Test
  public void linkBagEmptyProducesEmptyList() {
    session.begin();
    try {
      var bag = new LinkBag(session);
      var result = LiveQueryHookV2.unboxRidbags(bag);
      assertTrue(
          "empty LinkBag must produce an empty List<Identifiable>",
          result instanceof List<?> && ((List<?>) result).isEmpty());
    } finally {
      session.rollback();
    }
  }

  /**
   * A {@link LinkBag} with one entry must yield a single-element list containing the entry's
   * {@code primaryRid}. Verifies the happy-path extraction and preserves the primary-vs-secondary
   * distinction (only primaries are returned).
   */
  @Test
  public void linkBagSingleEntryExtractsPrimaryRid() {
    session.begin();
    try {
      var bag = new LinkBag(session);
      var primary = RecordIdInternal.fromString("#1:12", false);
      var secondary = RecordIdInternal.fromString("#1:50", false);
      bag.add(primary, secondary);

      var result = LiveQueryHookV2.unboxRidbags(bag);

      assertTrue(result instanceof List<?>);
      @SuppressWarnings("unchecked")
      var list = (List<Identifiable>) result;
      assertEquals(1, list.size());
      assertEquals(
          "unboxRidbags must extract primaryRid, not secondaryRid",
          primary,
          list.get(0).getIdentity());
    } finally {
      session.rollback();
    }
  }

  /**
   * Multiple {@link LinkBag} entries must all appear in the extracted list. Preserves the
   * contract callers rely on for downstream snapshotting.
   */
  @Test
  public void linkBagMultipleEntriesExtractsAllPrimaryRids() {
    session.begin();
    try {
      var bag = new LinkBag(session);
      var p1 = RecordIdInternal.fromString("#1:12", false);
      var p2 = RecordIdInternal.fromString("#1:13", false);
      var p3 = RecordIdInternal.fromString("#1:14", false);
      bag.add(p1, RecordIdInternal.fromString("#1:112", false));
      bag.add(p2, RecordIdInternal.fromString("#1:113", false));
      bag.add(p3, RecordIdInternal.fromString("#1:114", false));

      var result = LiveQueryHookV2.unboxRidbags(bag);

      assertTrue(result instanceof List<?>);
      @SuppressWarnings("unchecked")
      var list = (List<Identifiable>) result;
      assertEquals("all primaryRids must be extracted", 3, list.size());
      for (RID expected : List.of(p1, p2, p3)) {
        assertTrue(
            "extracted list must contain primaryRid " + expected,
            list.stream().anyMatch(i -> expected.equals(i.getIdentity())));
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * The returned list is a fresh {@code ArrayList} — mutating it must not touch the underlying
   * {@link LinkBag}. Pins the "defensive copy" observable even though the implementation does not
   * explicitly advertise it; callers rely on it to avoid aliasing bugs in the snapshot step.
   */
  @Test
  public void linkBagResultIsFreshCopy() {
    session.begin();
    try {
      var bag = new LinkBag(session);
      bag.add(
          RecordIdInternal.fromString("#1:20", false),
          RecordIdInternal.fromString("#1:100", false));

      var result = LiveQueryHookV2.unboxRidbags(bag);
      assertTrue(result instanceof List<?>);
      @SuppressWarnings("unchecked")
      var list = (List<Identifiable>) result;

      list.clear();

      assertEquals(
          "clearing the returned list must not affect the underlying LinkBag", 1, bag.size());
    } finally {
      session.rollback();
    }
  }
}
