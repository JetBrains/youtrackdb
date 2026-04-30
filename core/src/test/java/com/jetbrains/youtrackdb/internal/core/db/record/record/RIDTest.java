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
package com.jetbrains.youtrackdb.internal.core.db.record.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Standalone unit tests for the public {@link RID} surface (constants, {@link RID#of(String)} and
 * {@link RID#of(int, long)} factories) and the {@code RecordIdInternal} parsing path it
 * delegates to. The {@code core/id/} package has tests for marker-RID subclasses
 * ({@code SnapshotMarkerRIDTest}, {@code TombstoneRIDTest}) but no test pinning the parsing edge
 * cases reachable through {@code RID.of(String)} — these tests close that gap. All branches that
 * convert a malformed input into an {@link IllegalArgumentException} or a {@link DatabaseException}
 * are pinned so a regression that swallows the error or returns a default {@code RecordId} fails
 * loudly.
 */
public class RIDTest {

  // ---------- RID.of(int, long) ----------

  /**
   * The primitive factory routes to a {@link RecordId} record with the supplied components and a
   * default-constructed {@link com.jetbrains.youtrackdb.internal.core.db.record.record.RID}.
   */
  @Test
  public void ofIntLongReturnsRecordId() {
    var rid = RID.of(5, 17);
    assertEquals(RecordId.class, rid.getClass());
    assertEquals(5, rid.getCollectionId());
    assertEquals(17L, rid.getCollectionPosition());
  }

  /** A new RID is its own identity (the default {@code RID#getIdentity} contract). */
  @Test
  public void ofIntLongIdentityIsSelf() {
    var rid = RID.of(3, 0);
    assertSame(rid, rid.getIdentity());
  }

  /** isNew is the {@code position < 0} predicate — pin both sides of the boundary. */
  @Test
  public void isNewPositiveSide() {
    assertFalse(RID.of(0, 0).isNew());
  }

  @Test
  public void isNewNegativeSide() {
    assertTrue(RID.of(0, -1).isNew());
  }

  /** isPersistent requires {@code collectionId > -1} AND {@code collectionPosition > -1}. */
  @Test
  public void isPersistentRequiresPositiveCollectionAndPosition() {
    assertTrue(RID.of(0, 0).isPersistent());
    assertFalse(RID.of(-1, 0).isPersistent());
    assertFalse(RID.of(0, -1).isPersistent());
  }

  // ---------- RID.of(String) — happy path ----------

  /** A valid {@code #cid:pos} string parses to a {@link RecordId}. */
  @Test
  public void ofStringHappyPath() {
    var rid = RID.of("#5:17");
    assertEquals(RecordId.class, rid.getClass());
    assertEquals(5, rid.getCollectionId());
    assertEquals(17L, rid.getCollectionPosition());
  }

  /** Without a {@code #} prefix the string still parses (the prefix is optional in fromString). */
  @Test
  public void ofStringAcceptsNoPrefix() {
    var rid = RID.of("5:17");
    assertEquals(RecordId.class, rid.getClass());
    assertEquals(5, rid.getCollectionId());
    assertEquals(17L, rid.getCollectionPosition());
  }

  /** Surrounding whitespace is trimmed before parsing. */
  @Test
  public void ofStringTrimsWhitespace() {
    var rid = RID.of("   #5:17   ");
    assertEquals(5, rid.getCollectionId());
    assertEquals(17L, rid.getCollectionPosition());
  }

  /** A negative position is preserved; with the {@code changeable=false} default a {@link RecordId} record is returned (not a {@link ChangeableRecordId}). */
  @Test
  public void ofStringWithNegativePositionStillReturnsRecordIdWhenNotChangeable() {
    var rid = RID.of("#5:-1");
    assertEquals(RecordId.class, rid.getClass());
    assertEquals(5, rid.getCollectionId());
    assertEquals(-1L, rid.getCollectionPosition());
  }

  /** Boundary: collectionId at the upper limit of {@code COLLECTION_MAX} (32767) is accepted. */
  @Test
  public void ofStringAcceptsMaxCollectionId() {
    var rid = RID.of("#32767:0");
    assertEquals(RID.COLLECTION_MAX, rid.getCollectionId());
  }

  /** Boundary: collectionId at the lower bound {@code -2} is accepted. */
  @Test
  public void ofStringAcceptsMinCollectionId() {
    var rid = RID.of("#-2:0");
    assertEquals(-2, rid.getCollectionId());
  }

  // ---------- RID.of(String) — error paths ----------

  /** {@code null} → {@link ChangeableRecordId} default sentinel (no exception). */
  @Test
  public void ofStringNullReturnsChangeableSentinel() {
    var rid = RID.of(null);
    assertEquals(ChangeableRecordId.class, rid.getClass());
    assertNotNull(rid);
  }

  /** Empty string → {@link ChangeableRecordId} default sentinel. */
  @Test
  public void ofStringEmptyReturnsChangeableSentinel() {
    var rid = RID.of("");
    assertEquals(ChangeableRecordId.class, rid.getClass());
  }

  /** A whitespace-only string is trimmed to empty and routes to the default sentinel. */
  @Test
  public void ofStringWhitespaceOnlyReturnsChangeableSentinel() {
    var rid = RID.of("   \t  \n ");
    assertEquals(ChangeableRecordId.class, rid.getClass());
  }

  /** Missing separator is rejected with a precise message. */
  @Test
  public void ofStringMissingSeparatorRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> RID.of("#517"));
    assertTrue(
        "actual: " + ex.getMessage(),
        ex.getMessage().contains("not a RecordId")
            && ex.getMessage().contains("<collection-id>:<collection-position>"));
  }

  /** Too many separators are rejected. */
  @Test
  public void ofStringExtraSeparatorRejected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> RID.of("#5:17:99"));
    assertTrue(
        "actual: " + ex.getMessage(),
        ex.getMessage().contains("not a RecordId")
            && ex.getMessage().contains("Example: #3:12"));
  }

  /** A non-numeric collectionId raises {@link NumberFormatException}. */
  @Test(expected = NumberFormatException.class)
  public void ofStringNonNumericCollectionIdRejected() {
    RID.of("#abc:0");
  }

  /** A non-numeric position raises {@link NumberFormatException}. */
  @Test(expected = NumberFormatException.class)
  public void ofStringNonNumericPositionRejected() {
    RID.of("#0:abc");
  }

  /** collectionId below {@code -2} is rejected by {@code checkCollectionLimits}. */
  @Test
  public void ofStringCollectionIdTooLowRejected() {
    var ex = assertThrows(DatabaseException.class, () -> RID.of("#-3:0"));
    assertTrue(
        "actual: " + ex.getMessage(),
        ex.getMessage().contains("negative collection id") && ex.getMessage().contains("-3"));
  }

  /** collectionId above {@link RID#COLLECTION_MAX} is rejected. */
  @Test
  public void ofStringCollectionIdTooHighRejected() {
    var ex = assertThrows(DatabaseException.class, () -> RID.of("#32768:0"));
    assertTrue(
        "actual: " + ex.getMessage(),
        ex.getMessage().contains("major than 32767") && ex.getMessage().contains("32768"));
  }

  // ---------- equals / compareTo across String + (int,long) construction ----------

  /** {@code RID.of(String)} and {@code RID.of(int,long)} produce equal records for equal inputs. */
  @Test
  public void ofStringAndOfIntLongAreEqualForSameComponents() {
    assertEquals(RID.of("#5:17"), RID.of(5, 17));
  }

  /** Identical RIDs compare to zero. */
  @Test
  public void compareToEqualIsZero() {
    assertEquals(0, RID.of(5, 17).compareTo(RID.of(5, 17)));
  }

  /** Different collectionIds order by collectionId (lower < higher). */
  @Test
  public void compareToOrdersByCollectionIdFirst() {
    assertTrue(RID.of(5, 0).compareTo(RID.of(6, 0)) < 0);
    assertTrue(RID.of(6, 0).compareTo(RID.of(5, 0)) > 0);
  }

  /** With equal collectionId, position breaks the tie. */
  @Test
  public void compareToBreaksTiesByPosition() {
    assertTrue(RID.of(5, 17).compareTo(RID.of(5, 99)) < 0);
    assertTrue(RID.of(5, 99).compareTo(RID.of(5, 17)) > 0);
  }

  /** {@link RID#PREFIX} and {@link RID#SEPARATOR} are pinned at compile-time constants {@code '#'}/{@code ':'}. */
  @Test
  public void constantsArePinned() {
    assertEquals('#', RID.PREFIX);
    assertEquals(':', RID.SEPARATOR);
    assertEquals(32767, RID.COLLECTION_MAX);
    assertEquals(-1, RID.COLLECTION_ID_INVALID);
    assertEquals(-1L, RID.COLLECTION_POS_INVALID);
  }

  /** {@code toString()} round-trips with {@code RID.of(String)}. */
  @Test
  public void toStringRoundTrip() {
    var rid = RID.of(5, 17);
    assertEquals("#5:17", rid.toString());
    assertEquals(rid, RID.of(rid.toString()));
  }
}
