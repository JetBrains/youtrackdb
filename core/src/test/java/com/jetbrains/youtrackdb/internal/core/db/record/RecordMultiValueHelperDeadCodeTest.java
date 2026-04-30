/*
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
package com.jetbrains.youtrackdb.internal.core.db.record;

import static com.jetbrains.youtrackdb.internal.core.db.record.RecordMultiValueHelper.MULTIVALUE_CONTENT_TYPE.ALL_RECORDS;
import static com.jetbrains.youtrackdb.internal.core.db.record.RecordMultiValueHelper.MULTIVALUE_CONTENT_TYPE.ALL_RIDS;
import static com.jetbrains.youtrackdb.internal.core.db.record.RecordMultiValueHelper.MULTIVALUE_CONTENT_TYPE.EMPTY;
import static com.jetbrains.youtrackdb.internal.core.db.record.RecordMultiValueHelper.MULTIVALUE_CONTENT_TYPE.HYBRID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.RecordMultiValueHelper.MULTIVALUE_CONTENT_TYPE;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Shape pin for {@link RecordMultiValueHelper}. PSI all-scope {@code ReferencesSearch}
 * confirms zero references across the full module graph (core / server / driver / embedded
 * / gremlin-annotations / tests / docker-tests). The class is a static helper that tracks
 * homogeneity of a multi-value (collection) field as elements are added — RID-only,
 * record-only, or hybrid — but no production code consults the resulting type after the
 * legacy multi-value tracking was rewritten in terms of typed wrappers (e.g.
 * {@code EntityLinkListImpl}, {@code EntityEmbeddedListImpl}).
 *
 * <p>Pin shape:
 * <ul>
 *   <li>The {@link MULTIVALUE_CONTENT_TYPE} enum has exactly four constants in declared order.
 *   <li>{@link RecordMultiValueHelper#updateContentType(MULTIVALUE_CONTENT_TYPE, Object)}
 *       is a state machine: every reachable transition is pinned with a falsifiable result
 *       so a future edit that swaps two arms would fail.
 *   <li>{@link RecordMultiValueHelper#toString(Object)} delegates to {@code MultiValue.toString}
 *       — pinning the delegation prevents a silent rewrite that loses formatting parity.
 * </ul>
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this class together with this test file.
 * The replacement homogeneity-tracking logic now lives inside the typed-collection wrappers
 * in {@code db/record/} and does not need a top-level helper.
 *
 * <p>Standalone — no database session needed; mocks suffice for the {@link DBRecord}
 * branch since only {@code instanceof DBRecord} is consulted.
 */
public class RecordMultiValueHelperDeadCodeTest {

  // ---------------------------------------------------------------------------
  // Enum shape pin
  // ---------------------------------------------------------------------------

  @Test
  public void enumHasExactlyFourConstantsInDeclaredOrder() {
    // Declared-order is load-bearing for any caller that ever switched on ordinal()
    // (none today, but pinning the full vector + arity catches a silent reorder).
    assertArrayEquals(
        new MULTIVALUE_CONTENT_TYPE[] {EMPTY, ALL_RECORDS, ALL_RIDS, HYBRID},
        MULTIVALUE_CONTENT_TYPE.values());
  }

  // ---------------------------------------------------------------------------
  // updateContentType state-machine pin
  // ---------------------------------------------------------------------------

  @Test
  public void updateContentType_fromHybridIsAlwaysSticky() {
    // Once HYBRID, always HYBRID — pin every input class because the production switch
    // returns the previous status as a fall-through.
    var rid = new RecordId(7, 1);
    var record = Mockito.mock(DBRecord.class);

    assertSame(HYBRID, RecordMultiValueHelper.updateContentType(HYBRID, rid));
    assertSame(HYBRID, RecordMultiValueHelper.updateContentType(HYBRID, record));
    assertSame(HYBRID, RecordMultiValueHelper.updateContentType(HYBRID, "string"));
    assertSame(HYBRID, RecordMultiValueHelper.updateContentType(HYBRID, null));
  }

  @Test
  public void updateContentType_fromEmptyClassifiesByValueKind() {
    // First-element classification — three distinct outcomes pinned independently to
    // prove no two arms collapse together after a refactor:
    //   RID    → ALL_RIDS
    //   DBRecord (non-RID) → ALL_RECORDS
    //   anything else → HYBRID  (the catch-all)
    assertSame(ALL_RIDS,
        RecordMultiValueHelper.updateContentType(EMPTY, new RecordId(3, 4)));
    assertSame(ALL_RECORDS,
        RecordMultiValueHelper.updateContentType(EMPTY, Mockito.mock(DBRecord.class)));
    assertSame(HYBRID, RecordMultiValueHelper.updateContentType(EMPTY, "string"));
    assertSame(HYBRID, RecordMultiValueHelper.updateContentType(EMPTY, 42));
    // Null is handled by the final else branch — pin its outcome explicitly so a future
    // null-guard insertion is caught.
    assertSame(HYBRID, RecordMultiValueHelper.updateContentType(EMPTY, null));
  }

  @Test
  public void updateContentType_fromAllRecordsPromotesToHybridOnRid() {
    // ALL_RECORDS → HYBRID only when an RID is added; non-RIDs (including more records)
    // keep the homogeneous tag. Pin both arms.
    var record = Mockito.mock(DBRecord.class);
    assertSame(HYBRID,
        RecordMultiValueHelper.updateContentType(ALL_RECORDS, new RecordId(1, 1)));
    assertSame(ALL_RECORDS,
        RecordMultiValueHelper.updateContentType(ALL_RECORDS, record));
    assertSame(ALL_RECORDS,
        RecordMultiValueHelper.updateContentType(ALL_RECORDS, "still-not-a-rid"));
  }

  @Test
  public void updateContentType_fromAllRidsPromotesToHybridOnNonRid() {
    // ALL_RIDS → HYBRID for everything that is not an RID (records, primitives, null).
    // RIDs themselves keep ALL_RIDS sticky.
    assertSame(ALL_RIDS,
        RecordMultiValueHelper.updateContentType(ALL_RIDS, new RecordId(2, 2)));
    assertSame(HYBRID,
        RecordMultiValueHelper.updateContentType(ALL_RIDS, Mockito.mock(DBRecord.class)));
    assertSame(HYBRID,
        RecordMultiValueHelper.updateContentType(ALL_RIDS, "string"));
    assertSame(HYBRID,
        RecordMultiValueHelper.updateContentType(ALL_RIDS, null));
  }

  // ---------------------------------------------------------------------------
  // toString delegation pin
  // ---------------------------------------------------------------------------

  @Test
  public void toStringDelegatesToMultiValueToString() {
    // Format is owned by MultiValue.toString — pin a representative case so a future
    // rewrite that inlines its own formatting (and silently changes the surface format)
    // is caught. Pin a non-collection scalar too: MultiValue's else-branch falls through
    // to iObject.toString(), so a refactor that loses that fall-through would fail here.
    var listFormatted = RecordMultiValueHelper.toString(List.of("a", "b"));
    assertNotNull("must return a non-null formatting result for collections", listFormatted);
    assertEquals("collection formatting must be MultiValue's bracketed comma-separated form",
        "[a, b]", listFormatted);
    assertEquals("scalar formatting must fall through to Object.toString",
        "abc", RecordMultiValueHelper.toString("abc"));
  }

  // ---------------------------------------------------------------------------
  // RID is intentionally an interface — pin the RecordId concrete-binding so the
  // EMPTY → ALL_RIDS branch above does not silently change meaning under a refactor
  // that introduces a sibling RID implementation.
  // ---------------------------------------------------------------------------

  @Test
  public void recordIdRemainsTheCanonicalRidImplementationForBranchPin() {
    var id = new RecordId(0, 0);
    assertTrue("RecordId must remain assignable to RID — the EMPTY→ALL_RIDS arm depends on it",
        id instanceof RID);
  }
}
