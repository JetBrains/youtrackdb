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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import org.junit.Test;

/**
 * Standalone POJO unit tests for {@link MultiValueChangeEvent}: pins the {@link ChangeType} enum
 * values, the two constructor shapes (with and without {@code oldValue}), the four getters, and
 * the {@code equals}/{@code hashCode}/{@code toString} contracts. The class is the unit of
 * recorded change carried inside {@link MultiValueChangeTimeLine} for every tracked collection
 * (lists, sets, maps, link bags), so silent regressions here would compound into wrong WAL records
 * and replay-time inconsistencies.
 */
public class MultiValueChangeEventTest {

  // ---------- ChangeType enum pin ----------

  @Test
  public void changeTypeValuesArePinned() {
    var values = ChangeType.values();
    assertEquals(3, values.length);
    assertSame(ChangeType.ADD, values[0]);
    assertSame(ChangeType.UPDATE, values[1]);
    assertSame(ChangeType.REMOVE, values[2]);
  }

  // ---------- 3-arg constructor + getters ----------

  /** The 3-arg ctor leaves {@code oldValue} unset (i.e. {@code null}). */
  @Test
  public void threeArgConstructorLeavesOldValueNull() {
    var event = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v");

    assertSame(ChangeType.ADD, event.getChangeType());
    assertEquals(Integer.valueOf(0), event.getKey());
    assertEquals("v", event.getValue());
    assertNull(event.getOldValue());
  }

  /** The 4-arg ctor sets all four fields. */
  @Test
  public void fourArgConstructorSetsAllFields() {
    var event = new MultiValueChangeEvent<>(ChangeType.UPDATE, 7, "newV", "oldV");

    assertSame(ChangeType.UPDATE, event.getChangeType());
    assertEquals(Integer.valueOf(7), event.getKey());
    assertEquals("newV", event.getValue());
    assertEquals("oldV", event.getOldValue());
  }

  /** The class accepts null keys and null values — pin both branches in equals/hashCode. */
  @Test
  public void supportsNullKeyAndNullValue() {
    var event = new MultiValueChangeEvent<String, String>(ChangeType.REMOVE, null, null, null);

    assertNull(event.getKey());
    assertNull(event.getValue());
    assertNull(event.getOldValue());
  }

  // ---------- equals / hashCode ----------

  @Test
  public void equalsReflexive() {
    var event = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v");
    assertEquals(event, event);
  }

  @Test
  @SuppressWarnings("ConstantConditions")
  public void equalsRejectsNull() {
    var event = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v");
    assertNotEquals(event, null);
  }

  @Test
  public void equalsRejectsForeignType() {
    var event = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v");
    assertNotEquals(event, "not an event");
  }

  /** Identical components → equal events with identical hashCode. */
  @Test
  public void equalsAndHashCodeAgreeForIdenticalComponents() {
    var a = new MultiValueChangeEvent<>(ChangeType.UPDATE, 1, "new", "old");
    var b = new MultiValueChangeEvent<>(ChangeType.UPDATE, 1, "new", "old");

    assertEquals(a, b);
    assertEquals(b, a);
    assertEquals(a.hashCode(), b.hashCode());
  }

  /** Differing changeType breaks equality. */
  @Test
  public void inequalityFromChangeType() {
    var a = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v");
    var b = new MultiValueChangeEvent<>(ChangeType.REMOVE, 0, "v");
    assertNotEquals(a, b);
  }

  /** Differing key breaks equality. */
  @Test
  public void inequalityFromKey() {
    var a = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v");
    var b = new MultiValueChangeEvent<>(ChangeType.ADD, 1, "v");
    assertNotEquals(a, b);
  }

  /** Differing value breaks equality. */
  @Test
  public void inequalityFromValue() {
    var a = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v");
    var b = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "w");
    assertNotEquals(a, b);
  }

  /**
   * Differing {@code oldValue} breaks equality even though the 3-arg ctor sets it to null —
   * pin the field's participation in equals.
   */
  @Test
  public void inequalityFromOldValue() {
    var a = new MultiValueChangeEvent<>(ChangeType.UPDATE, 0, "v");
    var b = new MultiValueChangeEvent<>(ChangeType.UPDATE, 0, "v", "old");
    assertNotEquals(a, b);
  }

  /** All-null components hash to a deterministic value (zero) — pin against accidental NPE. */
  @Test
  public void hashCodeWithAllNullsDoesNotThrow() {
    var event = new MultiValueChangeEvent<String, String>(null, null, null, null);
    assertEquals(0, event.hashCode());
  }

  // ---------- toString ----------

  @Test
  public void toStringIncludesAllFieldsLabelled() {
    var s = new MultiValueChangeEvent<>(ChangeType.UPDATE, 7, "newV", "oldV").toString();

    assertTrue("missing changeType label: " + s, s.contains("changeType=UPDATE"));
    assertTrue("missing key label: " + s, s.contains("key=7"));
    assertTrue("missing value label: " + s, s.contains("value=newV"));
    assertTrue("missing oldValue label: " + s, s.contains("oldValue=oldV"));
  }

  @Test
  public void toStringHasClassPrefix() {
    var s = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v").toString();
    assertTrue("missing class prefix: " + s, s.startsWith("MultiValueChangeEvent{"));
    assertTrue("missing closing brace: " + s, s.endsWith("}"));
  }
}
