/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.fetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Dead-code pin tests for the static entry points on {@link FetchHelper} that survive after the
 * dead-method trim of {@code core/fetch/}. The package itself is preserved; this file pins the
 * surviving public surface ({@code buildFetchPlan}, {@code isEmbedded}, {@code processRecordRidMap},
 * {@code removeParsedFromMap}, plus the {@code DEFAULT} / {@code DEFAULT_FETCHPLAN} constants),
 * since the only in-repo driver outside this file is {@code DepthFetchPlanTest} in core's own test
 * source — it covers the live {@link FetchHelper#fetch} entry path but does not cover the static
 * helpers below without a database session.
 *
 * <p>Every test pins a falsifiable observable (return value, thrown exception, or identity of a
 * cached singleton) so that a mutation to the underlying branch is detectable.
 */
public class FetchHelperDeadCodeTest {

  // ---------------------------------------------------------------------------
  // buildFetchPlan — null / default / custom branches
  // ---------------------------------------------------------------------------

  @Test
  public void buildFetchPlanReturnsNullForNullInput() {
    assertNull(FetchHelper.buildFetchPlan(null));
  }

  @Test
  public void buildFetchPlanReturnsDefaultSingletonForExactDefaultString() {
    // "*:0" is the canonical default. Production returns the cached DEFAULT_FETCHPLAN
    // singleton — identity matters because downstream code checks reference equality
    // (e.g., processRecordRidMap early-returns on iFetchPlan == DEFAULT_FETCHPLAN).
    var fp = FetchHelper.buildFetchPlan(FetchHelper.DEFAULT);
    assertSame("must be the cached singleton", FetchHelper.DEFAULT_FETCHPLAN, fp);
  }

  @Test
  public void buildFetchPlanReturnsFreshFetchPlanForCustomString() {
    var fp = FetchHelper.buildFetchPlan("ref:3");
    assertNotNull(fp);
    // Distinct from the default singleton — only "*:0" produces the cached instance.
    assertNotSame("custom plan is not the default singleton",
        FetchHelper.DEFAULT_FETCHPLAN, fp);
    // And parses as a normal FetchPlan — ref@level 0 resolves to 3.
    assertEquals(3, fp.getDepthLevel("ref", 0));
  }

  @Test
  public void buildFetchPlanReturnsFreshInstanceForEmptyStringInput() {
    // Empty string does not match the DEFAULT literal "*:0" so it takes the new-plan branch
    // and constructs a no-op FetchPlan with only the wildcard default.
    var fp = FetchHelper.buildFetchPlan("");
    assertNotNull(fp);
    assertNotSame(FetchHelper.DEFAULT_FETCHPLAN, fp);
    assertEquals(0, fp.getDepthLevel("any", 0));
  }

  // ---------------------------------------------------------------------------
  // isEmbedded — null / non-entity / empty collection / collection-of-non-entities
  // ---------------------------------------------------------------------------

  @Test
  public void isEmbeddedReturnsFalseForNull() {
    // null is not an EntityImpl, not a LinkBag; MultiValue.getFirstValue(null) returns null;
    // isEmbedded stays false.
    assertFalse(FetchHelper.isEmbedded(null));
  }

  @Test
  public void isEmbeddedReturnsFalseForNonEntityScalar() {
    // A String is neither an EntityImpl nor a multi-value — the first-value probe falls
    // through and the method returns false.
    assertFalse(FetchHelper.isEmbedded("hello"));
    assertFalse(FetchHelper.isEmbedded(42));
  }

  @Test
  public void isEmbeddedReturnsFalseForEmptyList() {
    // Empty list is a multi-value but isEmpty → getFirstValue returns null → isEmbedded stays
    // false.
    assertFalse(FetchHelper.isEmbedded(Collections.emptyList()));
  }

  @Test
  public void isEmbeddedReturnsFalseForListOfNonIdentifiables() {
    // First element is a String — not an EntityImpl — so isEmbedded stays false.
    var list = new ArrayList<Object>();
    list.add("item1");
    list.add("item2");
    assertFalse(FetchHelper.isEmbedded(list));
  }

  @Test
  public void isEmbeddedReturnsFalseForEmptyMap() {
    assertFalse(FetchHelper.isEmbedded(new HashMap<String, Object>()));
  }

  // ---------------------------------------------------------------------------
  // processRecordRidMap — null-plan and default-plan early returns
  // ---------------------------------------------------------------------------

  @Test
  public void processRecordRidMapEarlyReturnsForNullFetchPlan() {
    // The null-plan guard early-returns before the record is dereferenced. Passing nulls for
    // db/record/context exercises the guard without requiring a session — absent the guard,
    // the method would NPE on record.getPropertyNamesInternal.
    var parsed = new Object2IntOpenHashMap<RID>();
    parsed.defaultReturnValue(-1);
    FetchHelper.processRecordRidMap(null, null, null, 0, 0, -1, parsed, "", null);
    assertTrue("null plan must not touch the parsed-records map", parsed.isEmpty());
  }

  @Test
  public void processRecordRidMapEarlyReturnsForDefaultFetchPlanSingleton() {
    // The DEFAULT_FETCHPLAN guard early-returns when the caller passes the cached singleton.
    // This test pins that calling with DEFAULT_FETCHPLAN and a null record does not NPE —
    // i.e., the guard fires before the record walk. (Note: the guard uses reference equality
    // but FetchPlan inherits Object.equals, so swapping `==` for `.equals` would produce an
    // identical observable here — that specific mutation is not pinnable without introducing
    // a FetchPlan instance that .equals DEFAULT_FETCHPLAN, which does not happen in
    // production.)
    var parsed = new Object2IntOpenHashMap<RID>();
    parsed.defaultReturnValue(-1);
    FetchHelper.processRecordRidMap(
        null, null, FetchHelper.DEFAULT_FETCHPLAN, 0, 0, -1, parsed, "", null);
    assertTrue(parsed.isEmpty());
  }

  // ---------------------------------------------------------------------------
  // removeParsedFromMap — protected method, accessible from same package
  // ---------------------------------------------------------------------------

  @Test
  public void removeParsedFromMapEvictsEntryByIdentity() {
    // Direct pin of the protected helper. The map is keyed by RID; removeParsedFromMap reads
    // the Identifiable's identity and calls removeInt. Verified by a before/after snapshot of
    // map size and a targeted get.
    var parsed = new Object2IntOpenHashMap<RID>();
    parsed.defaultReturnValue(-1);
    RID rid = new RecordId(5, 7);
    parsed.put(rid, 3);
    assertEquals("entry present before removal", 3, parsed.getInt(rid));

    FetchHelper.removeParsedFromMap(parsed, new FixedIdentifiable(rid));

    assertEquals("entry removed → default-return-value sentinel", -1, parsed.getInt(rid));
    assertTrue(parsed.isEmpty());
  }

  @Test
  public void removeParsedFromMapIsANoOpForAbsentEntry() {
    // Removing a key that was never put must not throw and must not alter the map's
    // observable content.
    var parsed = new Object2IntOpenHashMap<RID>();
    parsed.defaultReturnValue(-1);
    parsed.put(new RecordId(1, 1), 0);
    assertEquals(1, parsed.size());

    FetchHelper.removeParsedFromMap(parsed, new FixedIdentifiable(new RecordId(9, 9)));

    assertEquals("unrelated entry untouched", 1, parsed.size());
  }

  @Test
  public void removeParsedFromMapOnlyRemovesMatchingIdentity() {
    // Multi-entry map: removing one identity must leave the others intact. Protects against a
    // mutation that accidentally iterates the full map.
    var parsed = new Object2IntOpenHashMap<RID>();
    parsed.defaultReturnValue(-1);
    RID keep1 = new RecordId(1, 1);
    RID remove = new RecordId(2, 2);
    RID keep2 = new RecordId(3, 3);
    parsed.put(keep1, 10);
    parsed.put(remove, 20);
    parsed.put(keep2, 30);

    FetchHelper.removeParsedFromMap(parsed, new FixedIdentifiable(remove));

    assertEquals(2, parsed.size());
    assertEquals(10, parsed.getInt(keep1));
    assertEquals(-1, parsed.getInt(remove));
    assertEquals(30, parsed.getInt(keep2));
  }

  // ---------------------------------------------------------------------------
  // DEFAULT constant and DEFAULT_FETCHPLAN singleton
  // ---------------------------------------------------------------------------

  @Test
  public void defaultPlanConstantIsStarColonZero() {
    // Pin the public constants' observable values so a typo or constant rename fails the test
    // before it fails downstream production code that reads the singleton by reference.
    assertEquals("*:0", FetchHelper.DEFAULT);
    assertNotNull(FetchHelper.DEFAULT_FETCHPLAN);
    // The singleton's wildcard answer is 0 at level 0.
    assertEquals(0, FetchHelper.DEFAULT_FETCHPLAN.getDepthLevel("anyField", 0));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link Identifiable} stub that returns a constant RID — sufficient for pinning the
   * {@link FetchHelper#removeParsedFromMap} contract without a database session. Intentionally
   * package-private and declared inside the test class so it does not leak into test-commons.
   */
  private static final class FixedIdentifiable implements Identifiable {

    private final RID rid;

    FixedIdentifiable(RID rid) {
      this.rid = rid;
    }

    @Override
    public RID getIdentity() {
      return rid;
    }

    @Override
    public int compareTo(Identifiable o) {
      return rid.compareTo(o.getIdentity());
    }
  }

  /**
   * Integration-light pin: every non-null, non-"*:0" string that {@link FetchHelper#buildFetchPlan}
   * accepts must also yield a working {@link FetchPlan} where the semantic probe sees the
   * declared depth. Pins the parser surface against silent regressions that return non-null but
   * with broken depth maps.
   */
  @Test
  public void buildFetchPlanProducesSemanticallyConsistentPlanForEveryAcceptedString() {
    Map<String, SemanticProbe> samples = new LinkedHashMap<>();
    samples.put("ref:1", p -> assertEquals(1, p.getDepthLevel("ref", 0)));
    samples.put("ref:-1", p -> assertEquals(-1, p.getDepthLevel("ref", 0)));
    samples.put("ref:1 other:2", p -> {
      assertEquals(1, p.getDepthLevel("ref", 0));
      assertEquals(2, p.getDepthLevel("other", 0));
    });
    samples.put("*:3", p -> assertEquals(3, p.getDepthLevel("anyField", 0)));
    samples.put("[2-4]ref:1", p -> {
      assertEquals(1, p.getDepthLevel("ref", 2));
      assertEquals(1, p.getDepthLevel("ref", 4));
    });
    samples.put("[*]ref:-1", p -> {
      assertEquals(-1, p.getDepthLevel("ref", 0));
      assertEquals(-1, p.getDepthLevel("ref", 100));
    });
    samples.put("field*:2", p -> assertEquals(2, p.getDepthLevel("fieldX", 0)));

    for (var entry : samples.entrySet()) {
      var s = entry.getKey();
      var fp = FetchHelper.buildFetchPlan(s);
      assertNotNull("buildFetchPlan(" + s + ")", fp);
      entry.getValue().probe(fp);
    }
  }

  @FunctionalInterface
  private interface SemanticProbe {
    void probe(com.jetbrains.youtrackdb.internal.core.fetch.FetchPlan fp);
  }
}
