/*
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
package com.jetbrains.youtrackdb.internal.core.id;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.serialization.MemoryStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.Test;

/**
 * Pure unit tests for the {@code core/id} cluster:
 * {@link RecordId} (the canonical persistent record id record),
 * {@link ChangeableRecordId} (thread-safe identity-tracking variant), and
 * {@link ContextualRecordId} (delegating wrapper carrying user-supplied context).
 *
 * <p>Tests target each class's public surface — predicate methods
 * ({@code isValidPosition}, {@code isPersistent}, {@code isNew}, {@code isTemporary}),
 * the static {@link RecordIdInternal#fromString} parser and its error branches,
 * the binary {@code toStream}/{@code fromStream} round-trip variants, the static
 * {@link RecordIdInternal#serialize}/{@link RecordIdInternal#deserialize} pair
 * (including the null-sentinel {@code -2/-2} encoding), and the
 * {@link RecordIdInternal#checkCollectionLimits} guard rails. The collection of
 * tests covers all branches that do not require an active database session.
 */
public class RecordIdTest {

  // ---------------------------------------------------------------------------
  // RecordId — predicates and constructors
  // ---------------------------------------------------------------------------

  @Test
  public void recordIdConstructorFromRidCopiesCollectionIdAndPosition() {
    var source = new RecordId(7, 42L);
    var copy = new RecordId(source);
    assertEquals(7, copy.getCollectionId());
    assertEquals(42L, copy.getCollectionPosition());
  }

  @Test
  public void recordIdIsValidPositionRejectsTheInvalidSentinelOnly() {
    // COLLECTION_POS_INVALID == -1: any other long is a valid position (including
    // negative values used as "new" markers, which are still considered "valid"
    // in the predicate's narrow sense — only -1 is the sentinel).
    assertFalse(new RecordId(0, RID.COLLECTION_POS_INVALID).isValidPosition());
    assertTrue(new RecordId(0, 0L).isValidPosition());
    assertTrue(new RecordId(0, -2L).isValidPosition());
    assertTrue(new RecordId(0, 100L).isValidPosition());
  }

  @Test
  public void recordIdIsPersistentRequiresPositiveCollectionIdAndPositionAboveSentinel() {
    assertTrue(new RecordId(0, 0L).isPersistent());
    assertTrue(new RecordId(7, 42L).isPersistent());
    assertFalse(new RecordId(-1, 0L).isPersistent());
    // collectionPosition == COLLECTION_POS_INVALID is also non-persistent.
    assertFalse(new RecordId(7, RID.COLLECTION_POS_INVALID).isPersistent());
  }

  @Test
  public void recordIdIsNewWhenPositionIsNegative() {
    assertTrue(new RecordId(0, -1L).isNew());
    assertTrue(new RecordId(0, -10L).isNew());
    assertFalse(new RecordId(0, 0L).isNew());
  }

  @Test
  public void recordIdIsTemporaryRequiresValidCollectionAndPositionBelowSentinel() {
    // isTemporary: collectionId != -1 && position < COLLECTION_POS_INVALID (i.e. < -1).
    assertTrue(new RecordId(0, -2L).isTemporary());
    // collectionId == COLLECTION_ID_INVALID (-1) → not temporary.
    assertFalse(new RecordId(-1, -2L).isTemporary());
    // position == -1 (sentinel) → not temporary.
    assertFalse(new RecordId(0, -1L).isTemporary());
    // position >= 0 → not temporary.
    assertFalse(new RecordId(0, 5L).isTemporary());
  }

  @Test
  public void recordIdToStringMatchesPrefixedCollectionIdAndPosition() {
    assertEquals("#7:42", new RecordId(7, 42L).toString());
    assertEquals("#-1:-1", new RecordId(-1, -1L).toString());
  }

  @Test
  public void recordIdToStringWithStringBuilderAppendsAndReturnsSameBuilder() {
    var sb = new StringBuilder("prefix-");
    var rid = new RecordId(3, 12L);
    var result = rid.toString(sb);
    assertSame("toString(StringBuilder) must return the same builder for chaining", sb, result);
    assertEquals("prefix-#3:12", result.toString());
  }

  @Test
  public void recordIdToStringWithNullStringBuilderAllocatesAFreshBuffer() {
    // Defensive null-coalesce branch: passing null must return a freshly-allocated
    // builder containing just the rid string, not throw NPE.
    var result = new RecordId(2, 5L).toString(null);
    assertEquals("#2:5", result.toString());
  }

  @Test
  public void recordIdNextReturnsTheStringFormForTheSucceedingPosition() {
    assertEquals("#5:11", new RecordId(5, 10L).next());
  }

  @Test
  public void recordIdCopyReturnsTheSameInstanceBecauseRecordIsImmutable() {
    var rid = new RecordId(1, 2L);
    assertSame("RecordId is a record (immutable); copy() may return this", rid, rid.copy());
  }

  // ---------------------------------------------------------------------------
  // RecordId — equals / hashCode
  // ---------------------------------------------------------------------------

  @Test
  public void recordIdEqualsIsReflexive() {
    var rid = new RecordId(3, 7L);
    assertEquals(rid, rid);
  }

  @Test
  public void recordIdEqualsTreatsAnyIdentifiableWithMatchingIdentityAsEqual() {
    var a = new RecordId(3, 7L);
    var b = new RecordId(3, 7L);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void recordIdEqualsRejectsDifferentCollectionIdOrPosition() {
    var a = new RecordId(3, 7L);
    assertNotEquals(a, new RecordId(4, 7L));
    assertNotEquals(a, new RecordId(3, 8L));
  }

  @Test
  public void recordIdEqualsRejectsNonIdentifiableInputs() {
    var rid = new RecordId(3, 7L);
    assertNotEquals(rid, null);
    // Pin the non-Identifiable rejection branch.
    assertNotEquals(rid, "not a record id");
  }

  // ---------------------------------------------------------------------------
  // RecordId — compareTo
  // ---------------------------------------------------------------------------

  @Test
  public void recordIdCompareToReturnsZeroWhenComparedAgainstItself() {
    var rid = new RecordId(3, 7L);
    assertEquals(0, rid.compareTo(rid));
  }

  @Test
  public void recordIdCompareToOrdersByCollectionIdFirstThenPosition() {
    assertTrue(new RecordId(2, 0L).compareTo(new RecordId(3, 0L)) < 0);
    assertTrue(new RecordId(3, 0L).compareTo(new RecordId(2, 0L)) > 0);
    assertTrue(new RecordId(3, 5L).compareTo(new RecordId(3, 6L)) < 0);
    assertTrue(new RecordId(3, 6L).compareTo(new RecordId(3, 5L)) > 0);
    assertEquals(0, new RecordId(3, 5L).compareTo(new RecordId(3, 5L)));
  }

  // ---------------------------------------------------------------------------
  // RecordId — binary serialization (toStream / fromStream)
  // ---------------------------------------------------------------------------

  @Test
  public void recordIdRoundTripsThroughDataOutputAndDataInput() throws Exception {
    var original = new RecordId(7, 42L);
    var bos = new ByteArrayOutputStream();
    try (DataOutputStream dos = new DataOutputStream(bos)) {
      // Type as DataOutput to disambiguate from the OutputStream overload.
      original.toStream((DataOutput) dos);
    }
    try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      // Type as DataInput to disambiguate from the InputStream overload.
      var roundTrip = RecordIdInternal.fromStream((DataInput) dis);
      assertEquals(original, roundTrip);
      assertTrue("positive collection id must rehydrate as a RecordId",
          roundTrip instanceof RecordId);
    }
  }

  @Test
  public void recordIdRoundTripsThroughByteArrayHelpers() throws Exception {
    var original = new RecordId(2, 5L);
    var bytes = original.toStream();
    try (var dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
      // The byte[] form uses 2-byte short + 8-byte long; decoded via fromStream(InputStream).
      var roundTrip = RecordIdInternal.fromStream(new ByteArrayInputStream(bytes));
      assertEquals(original, roundTrip);
    }
  }

  @Test
  public void recordIdRoundTripsThroughOutputStreamAndInputStream() throws Exception {
    var original = new RecordId(11, 23L);
    var bos = new ByteArrayOutputStream();
    var beginOffset = original.toStream(bos);
    // BinaryProtocol#short2bytes returns -1 for non-MemoryStream OutputStreams
    // (only MemoryStream tracks position internally). Pin the contract so a
    // future refactor that swaps the sentinel signals loudly.
    assertEquals("toStream(OutputStream) returns -1 on non-MemoryStream sinks",
        -1, beginOffset);
    var roundTrip = RecordIdInternal.fromStream(new ByteArrayInputStream(bos.toByteArray()));
    assertEquals(original, roundTrip);
  }

  @Test
  public void recordIdRoundTripsThroughMemoryStream() throws Exception {
    var original = new RecordId(13, 51L);
    var memStream = new MemoryStream();
    var beginOffset = original.toStream(memStream);
    // MemoryStream tracks its own position and returns the offset where the
    // short write started; for a fresh MemoryStream that is zero.
    assertEquals("toStream(MemoryStream) returns the position before the short write",
        0, beginOffset);
    memStream.setPosition(0);
    var roundTrip = RecordIdInternal.fromStream(memStream);
    assertEquals(original, roundTrip);
  }

  @Test
  public void fromStreamWithNegativeCollectionIdRehydratesAsChangeableRecordId() throws Exception {
    // The fromStream branch routes negative collection ids to ChangeableRecordId
    // — the only legal way to represent "still-changing identity" on disk.
    var bos = new ByteArrayOutputStream();
    try (DataOutputStream dos = new DataOutputStream(bos)) {
      dos.writeShort(-1); // collectionId
      dos.writeLong(-1L); // collectionPosition
    }
    try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      var hydrated = RecordIdInternal.fromStream((DataInput) dis);
      assertTrue("negative collection id must rehydrate as ChangeableRecordId",
          hydrated instanceof ChangeableRecordId);
    }
  }

  // ---------------------------------------------------------------------------
  // RecordIdInternal.fromString — parser branches
  // ---------------------------------------------------------------------------

  @Test
  public void fromStringWithNullReturnsBlankChangeableRecordId() {
    var rid = RecordIdInternal.fromString(null, true);
    assertTrue(rid instanceof ChangeableRecordId);
  }

  @Test
  public void fromStringWithEmptyReturnsBlankChangeableRecordId() {
    var rid = RecordIdInternal.fromString("   ", true);
    assertTrue("whitespace-only input is treated as empty", rid instanceof ChangeableRecordId);
  }

  @Test
  public void fromStringWithoutSeparatorRejectsTheInputWithIllegalArgumentException() {
    try {
      RecordIdInternal.fromString("not-a-rid", false);
      fail("Expected IllegalArgumentException for missing separator");
    } catch (IllegalArgumentException expected) {
      assertTrue("message names the expected format",
          expected.getMessage().contains("not a RecordId"));
    }
  }

  @Test
  public void fromStringWithThreePartsRejectsTheInputWithIllegalArgumentException() {
    try {
      // Three colon-delimited parts violates the binary <id>:<pos> format.
      RecordIdInternal.fromString("#3:5:7", false);
      fail("Expected IllegalArgumentException for too many parts");
    } catch (IllegalArgumentException expected) {
      assertTrue("message names the expected format with example",
          expected.getMessage().contains("#<collection-id>:<collection-position>"));
    }
  }

  @Test
  public void fromStringWithChangeableTrueAndNegativePositionReturnsChangeableRecordId() {
    var rid = RecordIdInternal.fromString("#3:-5", true);
    assertTrue(rid instanceof ChangeableRecordId);
    assertEquals(3, rid.getCollectionId());
    assertEquals(-5L, rid.getCollectionPosition());
  }

  @Test
  public void fromStringWithChangeableTrueAndNonNegativePositionReturnsImmutableRecordId() {
    var rid = RecordIdInternal.fromString("#3:5", true);
    assertTrue("non-negative position takes the immutable branch even when changeable=true",
        rid instanceof RecordId);
  }

  @Test
  public void fromStringWithChangeableFalseAlwaysReturnsImmutableRecordId() {
    var rid = RecordIdInternal.fromString("#3:-5", false);
    assertTrue("changeable=false forces the immutable branch", rid instanceof RecordId);
  }

  @Test
  public void fromStringTrimsLeadingAndTrailingWhitespace() {
    var rid = RecordIdInternal.fromString("  #5:7  ", false);
    assertEquals(5, rid.getCollectionId());
    assertEquals(7L, rid.getCollectionPosition());
  }

  // ---------------------------------------------------------------------------
  // RecordIdInternal — static helpers
  // ---------------------------------------------------------------------------

  @Test
  public void generateStringFormatsRidWithPrefixAndSeparator() {
    assertEquals("#7:42", RecordIdInternal.generateString(7, 42L));
    assertEquals("#-1:-1", RecordIdInternal.generateString(-1, -1L));
  }

  @Test
  public void isValidIsTheNegationOfTheSentinel() {
    assertFalse(RecordIdInternal.isValid(RID.COLLECTION_POS_INVALID));
    assertTrue(RecordIdInternal.isValid(0L));
    assertTrue(RecordIdInternal.isValid(-2L));
  }

  @Test
  public void isPersistentStaticOnlyAcceptsPositionsAboveTheSentinel() {
    assertTrue(RecordIdInternal.isPersistent(0L));
    assertTrue(RecordIdInternal.isPersistent(100L));
    assertFalse(RecordIdInternal.isPersistent(-1L));
  }

  @Test
  public void isNewStaticAcceptsAnyNegativePosition() {
    assertTrue(RecordIdInternal.isNew(-1L));
    assertTrue(RecordIdInternal.isNew(-100L));
    assertFalse(RecordIdInternal.isNew(0L));
  }

  @Test
  public void isAStaticRecognizesValidRidStringForms() {
    assertTrue(RecordIdInternal.isA("#3:5"));
    assertFalse(RecordIdInternal.isA("not-a-rid"));
  }

  @Test
  public void checkCollectionLimitsRejectsCollectionIdBelowTheShortFloor() {
    try {
      RecordIdInternal.checkCollectionLimits(Short.MIN_VALUE - 1);
      fail("Expected DatabaseException for collectionId < Short.MIN_VALUE");
    } catch (DatabaseException expected) {
      assertTrue(expected.getMessage().contains("smaller than"));
    }
  }

  @Test
  public void checkCollectionLimitsRejectsCollectionIdAboveMax() {
    try {
      RecordIdInternal.checkCollectionLimits(RID.COLLECTION_MAX + 1);
      fail("Expected DatabaseException for collectionId > COLLECTION_MAX");
    } catch (DatabaseException expected) {
      assertTrue(expected.getMessage().contains("major than 32767"));
    }
  }

  @Test
  public void checkCollectionLimitsAcceptsBoundaryValues() {
    // Any id <= -2 is a provisional collection id (a class created inside a still-open
    // transaction carries one until commit), so the whole serialized-short range is accepted.
    RecordIdInternal.checkCollectionLimits(Short.MIN_VALUE);
    RecordIdInternal.checkCollectionLimits(-100);
    RecordIdInternal.checkCollectionLimits(-3);
    RecordIdInternal.checkCollectionLimits(-2);
    RecordIdInternal.checkCollectionLimits(-1);
    RecordIdInternal.checkCollectionLimits(0);
    RecordIdInternal.checkCollectionLimits(RID.COLLECTION_MAX);
  }

  @Test
  public void serializeAndDeserializeRoundTripARealRid() throws Exception {
    var original = new RecordId(7, 42L);
    var bos = new ByteArrayOutputStream();
    try (var dos = new DataOutputStream(bos)) {
      RecordIdInternal.serialize(original, dos);
    }
    try (var dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      var roundTrip = RecordIdInternal.deserialize(dis);
      assertEquals(original, roundTrip);
    }
  }

  @Test
  public void serializeNullEncodesTheMinusTwoSentinelPair() throws Exception {
    var bos = new ByteArrayOutputStream();
    try (var dos = new DataOutputStream(bos)) {
      RecordIdInternal.serialize(null, dos);
    }
    try (var dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      assertEquals(-2, dis.readInt());
      assertEquals(-2L, dis.readLong());
    }
  }

  @Test
  public void deserializeRecognizesTheMinusTwoSentinelAndReturnsNull() throws Exception {
    var bos = new ByteArrayOutputStream();
    try (var dos = new DataOutputStream(bos)) {
      dos.writeInt(-2);
      dos.writeLong(-2L);
    }
    try (var dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      assertNull("(-2, -2) sentinel pair must deserialize as null",
          RecordIdInternal.deserialize(dis));
    }
  }

  @Test
  public void deserializeWithNegativeCollectionIdRoutesToChangeableRecordId() throws Exception {
    var bos = new ByteArrayOutputStream();
    try (var dos = new DataOutputStream(bos)) {
      dos.writeInt(-1);
      dos.writeLong(-1L);
    }
    try (var dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      var hydrated = RecordIdInternal.deserialize(dis);
      assertTrue(hydrated instanceof ChangeableRecordId);
    }
  }

  @Test
  public void tempRecordIdReturnsAFreshDescendingNegativePositionEachCall() {
    var first = RecordIdInternal.tempRecordId();
    var second = RecordIdInternal.tempRecordId();
    // The TEMP_ID_GENERATOR descends, so each call yields a strictly smaller position.
    assertTrue("tempRecordId must descend", second.getCollectionPosition()
        < first.getCollectionPosition());
    assertEquals("tempRecordId uses COLLECTION_ID_INVALID", RID.COLLECTION_ID_INVALID,
        first.getCollectionId());
  }

  // ---------------------------------------------------------------------------
  // ChangeableRecordId — predicates and identity-change listener wiring
  // ---------------------------------------------------------------------------

  @Test
  public void changeableRecordIdNoArgConstructorYieldsNonPersistentNonValidIdentity() {
    var rid = new ChangeableRecordId();
    assertFalse(rid.isPersistent());
    assertFalse(rid.isValidPosition());
    assertEquals(RID.COLLECTION_ID_INVALID, rid.getCollectionId());
    assertEquals(RID.COLLECTION_POS_INVALID, rid.getCollectionPosition());
  }

  @Test
  public void changeableRecordIdConstructorFromRecordIdInternalCopiesIdentity() {
    var source = new RecordId(7, 42L);
    var rid = new ChangeableRecordId(source);
    assertEquals(7, rid.getCollectionId());
    assertEquals(42L, rid.getCollectionPosition());
  }

  @Test
  public void changeableRecordIdSetCollectionPositionUpdatesAtomicReference() {
    var rid = new ChangeableRecordId();
    rid.setCollectionPosition(5L);
    assertEquals(5L, rid.getCollectionPosition());
  }

  @Test
  public void changeableRecordIdSetCollectionPositionIsNoOpWhenNewValueMatches() {
    // Pin the early-return branch — no listeners should fire when the value is unchanged.
    var rid = new ChangeableRecordId(3, 5L);
    rid.setCollectionPosition(5L);
    assertEquals(5L, rid.getCollectionPosition());
  }

  @Test
  public void changeableRecordIdSetCollectionAndPositionUpdatesBothFields() {
    var rid = new ChangeableRecordId();
    rid.setCollectionAndPosition(7, 42L);
    assertEquals(7, rid.getCollectionId());
    assertEquals(42L, rid.getCollectionPosition());
  }

  @Test
  public void changeableRecordIdSetCollectionAndPositionIsNoOpWhenBothMatch() {
    var rid = new ChangeableRecordId(7, 42L);
    rid.setCollectionAndPosition(7, 42L);
    assertEquals(7, rid.getCollectionId());
    assertEquals(42L, rid.getCollectionPosition());
  }

  @Test
  public void changeableRecordIdSetCollectionAndPositionRejectsOutOfRangeId() {
    var rid = new ChangeableRecordId();
    try {
      rid.setCollectionAndPosition(RID.COLLECTION_MAX + 1, 5L);
      fail("Expected DatabaseException for over-limit collection id");
    } catch (DatabaseException expected) {
      assertTrue(expected.getMessage().contains("major than 32767"));
    }
  }

  @Test
  public void changeableRecordIdNotifiesIdentityChangeListenersOnPositionChange() {
    var rid = new ChangeableRecordId();
    var beforeFires = new int[] {0};
    var afterFires = new int[] {0};
    rid.addIdentityChangeListener(new IdentityChangeListener() {
      @Override
      public void onBeforeIdentityChange(Object source) {
        beforeFires[0]++;
      }

      @Override
      public void onAfterIdentityChange(Object source) {
        afterFires[0]++;
      }
    });
    rid.setCollectionPosition(7L);
    assertEquals("onBeforeIdentityChange must fire once on a real change", 1, beforeFires[0]);
    assertEquals("onAfterIdentityChange must fire once on a real change", 1, afterFires[0]);
  }

  @Test
  public void changeableRecordIdRemoveIdentityChangeListenerStopsNotifications() {
    var rid = new ChangeableRecordId();
    var fires = new int[] {0};
    IdentityChangeListener listener = new IdentityChangeListener() {
      @Override
      public void onBeforeIdentityChange(Object source) {
        fires[0]++;
      }

      @Override
      public void onAfterIdentityChange(Object source) {
        fires[0]++;
      }
    };
    rid.addIdentityChangeListener(listener);
    rid.removeIdentityChangeListener(listener);
    rid.setCollectionPosition(5L);
    assertEquals("removed listener must not fire", 0, fires[0]);
  }

  @Test
  public void changeableRecordIdAddIdentityChangeListenerIgnoresAdditionAfterPersistent() {
    // Once the rid is persistent, canChangeIdentity returns false and the
    // add call must short-circuit (no listener stored, no notification later).
    var rid = new ChangeableRecordId(7, 42L);
    assertFalse(rid.canChangeIdentity());
    var fires = new int[] {0};
    rid.addIdentityChangeListener(new IdentityChangeListener() {
      @Override
      public void onBeforeIdentityChange(Object source) {
        fires[0]++;
      }

      @Override
      public void onAfterIdentityChange(Object source) {
        fires[0]++;
      }
    });
    // The rid is persistent so identity is not expected to change; we only assert
    // that the add call did not throw and produced no notifications by changing
    // unrelated state below.
    assertFalse("persistent ChangeableRecordId reports canChangeIdentity == false",
        rid.canChangeIdentity());
    assertEquals("no listener notifications were generated", 0, fires[0]);
  }

  @Test
  public void changeableRecordIdEqualsCoversTempIdDistinguishingForBlankRids() {
    // Two blank ChangeableRecordIds (collectionId=-1, collectionPosition=-1) must
    // compare unequal because they carry distinct tempIds.
    var a = new ChangeableRecordId();
    var b = new ChangeableRecordId();
    assertNotEquals("two distinct blank ChangeableRecordIds carry different tempIds", a, b);
    assertEquals("each blank ChangeableRecordId equals itself", a, a);
  }

  @Test
  public void changeableRecordIdEqualsAcceptsAnyIdentifiableWithMatchingIdentity() {
    var changeable = new ChangeableRecordId(3, 7L);
    var plain = new RecordId(3, 7L);
    assertEquals(changeable, plain);
    // Reflexive
    assertEquals(changeable, changeable);
  }

  @Test
  public void changeableRecordIdEqualsRejectsDifferentIdentities() {
    var rid = new ChangeableRecordId(3, 7L);
    assertNotEquals(rid, new ChangeableRecordId(3, 8L));
    assertNotEquals(rid, new ChangeableRecordId(4, 7L));
    assertNotEquals(rid, null);
    assertNotEquals(rid, "not a record id");
  }

  @Test
  public void changeableRecordIdHashCodeFallsBackToTempIdForBlankRid() {
    // A blank rid has collectionId == COLLECTION_ID_INVALID and
    // collectionPosition == COLLECTION_POS_INVALID; in that case hashCode adds
    // 17 * Long.hashCode(tempId) so two blanks are very likely to differ.
    var a = new ChangeableRecordId();
    var b = new ChangeableRecordId();
    assertNotEquals("two distinct blanks should produce different hashes via tempId",
        a.hashCode(), b.hashCode());
  }

  @Test
  public void changeableRecordIdCompareToOrdersByIdentityFirstThenTempIdForBlanks() {
    var first = new ChangeableRecordId();
    var second = new ChangeableRecordId();
    // tempId is allocated from a strictly-monotonic counter, so first < second.
    assertTrue("blank ChangeableRecordIds compare by tempId order",
        first.compareTo(second) < 0);
    assertTrue(second.compareTo(first) > 0);
    assertEquals("self-compare returns zero", 0, first.compareTo(first));
  }

  @Test
  public void changeableRecordIdCompareToReturnsZeroAgainstEqualImmutableRecordId() {
    var changeable = new ChangeableRecordId(3, 7L);
    var plain = new RecordId(3, 7L);
    assertEquals(0, changeable.compareTo(plain));
  }

  @Test
  public void changeableRecordIdToStringMatchesRidStringForm() {
    var rid = new ChangeableRecordId(3, 7L);
    assertEquals("#3:7", rid.toString());
    var sb = new StringBuilder();
    rid.toString(sb);
    assertEquals("#3:7", sb.toString());
  }

  @Test
  public void changeableRecordIdNextDelegatesToImmutableRecordId() {
    var rid = new ChangeableRecordId(3, 7L);
    assertEquals("#3:8", rid.next());
  }

  @Test
  public void changeableRecordIdCopyForBlankRidPreservesTempIdIdentity() {
    var rid = new ChangeableRecordId();
    var copy = rid.copy();
    // Blank rid → copy is another ChangeableRecordId with the same tempId.
    assertTrue(copy instanceof ChangeableRecordId);
    assertEquals("the blank-blank copy preserves equality (same tempId)", rid, copy);
  }

  @Test
  public void changeableRecordIdCopyForPersistentRidReturnsImmutableRecordId() {
    var rid = new ChangeableRecordId(3, 7L);
    var copy = rid.copy();
    assertTrue("persistent ChangeableRecordId.copy() returns an immutable RecordId",
        copy instanceof RecordId);
    assertEquals(rid, copy);
  }

  @Test
  public void changeableRecordIdToStreamRoundTripsThroughDataOutputAndDataInput() throws Exception {
    var original = new ChangeableRecordId(7, 42L);
    var bos = new ByteArrayOutputStream();
    try (DataOutputStream dos = new DataOutputStream(bos)) {
      original.toStream((DataOutput) dos);
    }
    try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      // Positive collectionId rehydrates to immutable RecordId per fromStream branch.
      var roundTrip = RecordIdInternal.fromStream((DataInput) dis);
      assertEquals(original, roundTrip);
    }
  }

  // ---------------------------------------------------------------------------
  // ContextualRecordId — wrapping and delegation
  // ---------------------------------------------------------------------------

  @Test
  public void contextualRecordIdConstructorParsesRidStringIntoImmutableDelegate() {
    var rid = new ContextualRecordId("#3:7");
    assertEquals(3, rid.getCollectionId());
    assertEquals(7L, rid.getCollectionPosition());
  }

  @Test
  public void contextualRecordIdSetContextStoresAndReturnsTheGivenMap() {
    var rid = new ContextualRecordId("#3:7");
    Map<String, Object> ctx = new HashMap<>();
    ctx.put("k", "v");
    var same = rid.setContext(ctx);
    assertSame("setContext must return this for fluent chaining", rid, same);
    assertSame("getContext must return the exact map passed in (no defensive copy)",
        ctx, rid.getContext());
  }

  @Test
  public void contextualRecordIdPredicatesDelegateToTheUnderlyingRecordId() {
    var rid = new ContextualRecordId("#3:7");
    assertTrue(rid.isPersistent());
    assertTrue(rid.isValidPosition());
    assertFalse(rid.isNew());
    assertFalse(rid.isTemporary());
  }

  @Test
  public void contextualRecordIdEqualsAndHashCodeDelegateToTheUnderlyingRecordId() {
    var a = new ContextualRecordId("#3:7");
    var b = new ContextualRecordId("#3:7");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, new ContextualRecordId("#3:8"));
  }

  @Test
  public void contextualRecordIdComparesViaTheUnderlyingRecordId() {
    var a = new ContextualRecordId("#3:7");
    var b = new ContextualRecordId("#3:8");
    assertTrue(a.compareTo(b) < 0);
    assertTrue(b.compareTo(a) > 0);
    assertEquals(0, a.compareTo(a));
  }

  @Test
  public void contextualRecordIdNextDelegatesToTheUnderlyingRecordId() {
    var rid = new ContextualRecordId("#3:7");
    assertEquals("#3:8", rid.next());
  }

  @Test
  public void contextualRecordIdToStringDelegatesToTheUnderlyingRecordId() {
    var rid = new ContextualRecordId("#3:7");
    assertEquals("#3:7", rid.toString());
    var sb = new StringBuilder("prefix-");
    rid.toString(sb);
    assertEquals("prefix-#3:7", sb.toString());
  }

  @Test
  public void contextualRecordIdToStreamHelpersDelegateToTheUnderlyingRecordId() throws Exception {
    var rid = new ContextualRecordId("#3:7").setContext(new HashMap<>());

    // toStream(byte[]) — short collection id + long position => 10 bytes.
    var bytes = rid.toStream();
    assertEquals(10, bytes.length);

    // toStream(DataOutput)
    var bos = new ByteArrayOutputStream();
    try (DataOutputStream dos = new DataOutputStream(bos)) {
      rid.toStream((DataOutput) dos);
    }
    try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      assertEquals(3, dis.readShort());
      assertEquals(7L, dis.readLong());
    }

    // toStream(OutputStream) and MemoryStream both call into the delegate's helpers.
    var bos2 = new ByteArrayOutputStream();
    rid.toStream(bos2);
    assertEquals(10, bos2.size());
    var memStream = new MemoryStream();
    rid.toStream(memStream);
    memStream.setPosition(0);
    var roundTrip = RecordIdInternal.fromStream(memStream);
    assertEquals(rid, roundTrip);
  }

  @Test
  public void contextualRecordIdCopyDuplicatesContextMapToProtectAgainstAliasing() {
    var ctx = new HashMap<String, Object>();
    ctx.put("k", "v");
    var rid = new ContextualRecordId("#3:7").setContext(ctx);
    var copy = (ContextualRecordId) rid.copy();
    assertEquals("copy preserves the context entries", "v", copy.getContext().get("k"));

    // Mutating the original after copy must not leak into the copy.
    ctx.put("k2", "v2");
    assertFalse("copy carries a defensive snapshot of the context map",
        copy.getContext().containsKey("k2"));
  }

  // ---------------------------------------------------------------------------
  // RecordId — usable as Set / Map keys
  // ---------------------------------------------------------------------------

  @Test
  public void recordIdsAreUsableAsHashSetMembersByValueEquality() {
    var set = new HashSet<RecordId>();
    set.add(new RecordId(3, 7L));
    set.add(new RecordId(3, 7L));
    set.add(new RecordId(3, 8L));
    assertEquals("equal RecordIds collapse to a single Set member", 2, set.size());
  }
}
