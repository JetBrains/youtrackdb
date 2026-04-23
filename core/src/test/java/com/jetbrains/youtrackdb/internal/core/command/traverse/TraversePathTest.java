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
package com.jetbrains.youtrackdb.internal.core.command.traverse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Standalone unit tests for {@link TraversePath} — the immutable path descriptor used during a
 * traversal to report the parent-child chain of records, fields, and indices visited so far. All
 * branches in this class are pure data structure manipulation (no DB session required), so tests
 * exercise append variants, depth accounting, and {@code toString} layout directly.
 *
 * <p>Track 9 Step 3 coverage: path assembly (record / field / index), parent linkage, depth
 * increment rules, and the four {@code PathItem} subtype {@code toString} formats.
 */
public class TraversePathTest {

  /**
   * {@link TraversePath#empty()} must return the shared sentinel and expose an empty path string
   * with depth -1 (the placeholder {@code FirstPathItem} starting depth).
   */
  @Test
  public void emptyReturnsSharedSentinelWithDepthMinusOneAndBlankRender() {
    var empty1 = TraversePath.empty();
    var empty2 = TraversePath.empty();

    assertSame("empty() must return a shared singleton", empty1, empty2);
    assertEquals("empty path renders to an empty string", "", empty1.toString());
    assertEquals("empty path starts at depth -1 per FirstPathItem", -1, empty1.getDepth());
  }

  /**
   * {@code append(record)} increments the depth by 1 and renders the record's identity in
   * parentheses. The returned path must be a new instance — paths are immutable.
   */
  @Test
  public void appendRecordIncrementsDepthAndRendersIdentityInParentheses() {
    RID rid = new RecordId(7, 42L);
    Identifiable target = rid;

    var appended = TraversePath.empty().append(target);

    assertNotSame("append must return a new TraversePath instance", TraversePath.empty(), appended);
    assertEquals("append(record) increments depth from -1 to 0", 0, appended.getDepth());
    assertEquals("toString renders the record identity in parentheses",
        "(#7:42)", appended.toString());
  }

  /**
   * {@code appendField(name)} prepends a {@code .name} component but leaves the depth unchanged —
   * field access is a within-record step, not a descent into a new record. {@code toString}
   * concatenates all ancestors root-first.
   */
  @Test
  public void appendFieldKeepsDepthAndPrependsDotName() {
    RID rid = new RecordId(3, 9L);

    var withRecord = TraversePath.empty().append(rid);
    var withField = withRecord.appendField("friends");

    assertEquals("appendField does not increment depth", withRecord.getDepth(),
        withField.getDepth());
    assertEquals("toString concatenates record(parent) + .field(child)",
        "(#3:9).friends", withField.toString());
  }

  /**
   * {@code appendIndex(n)} renders as {@code [n]} and, like {@code appendField}, leaves the depth
   * unchanged — it is a within-collection step inside a field.
   */
  @Test
  public void appendIndexKeepsDepthAndRendersBracketedIndex() {
    RID rid = new RecordId(1, 2L);

    var withField = TraversePath.empty().append(rid).appendField("items");
    var withIndex = withField.appendIndex(5);

    assertEquals("appendIndex does not increment depth",
        withField.getDepth(), withIndex.getDepth());
    assertEquals("toString renders index as bracketed number after field",
        "(#1:2).items[5]", withIndex.toString());
  }

  /**
   * {@code appendRecordSet()} is the no-op append used by {@link TraverseRecordSetProcess}: it
   * returns {@code this} unchanged so record-set boundaries do not show up in the path string or
   * depth counter.
   */
  @Test
  public void appendRecordSetReturnsSameInstanceAndDoesNotMutatePath() {
    var original = TraversePath.empty().append(new RecordId(4, 8L));

    var recordSet = original.appendRecordSet();

    assertSame("appendRecordSet must return the receiver unchanged", original, recordSet);
    assertEquals("depth is preserved across appendRecordSet", 0, recordSet.getDepth());
    assertEquals("toString is preserved across appendRecordSet",
        "(#4:8)", recordSet.toString());
  }

  /**
   * A realistic chain: record → field → index → record models descending into the N-th element of
   * a collection-valued field. Depth increments only on record appends (from -1 → 0 → 1), not on
   * field/index traversal steps.
   */
  @Test
  public void chainedAppendsProduceRootFirstRenderAndCorrectDepth() {
    RID root = new RecordId(10, 1L);
    RID inner = new RecordId(10, 2L);

    var path = TraversePath.empty()
        .append(root)
        .appendField("friends")
        .appendIndex(0)
        .append(inner);

    assertEquals("only record appends bump depth — two records → depth 1",
        1, path.getDepth());
    assertEquals("chain renders root-first: record, field, index, child record",
        "(#10:1).friends[0](#10:2)", path.toString());
  }

  /**
   * Immutability pin: appending to a shared parent must not mutate the parent. This guards the
   * invariant that a single {@code TraversePath} instance can be shared as a parent by multiple
   * child paths without interference.
   */
  @Test
  public void appendDoesNotMutateParentPath() {
    var parent = TraversePath.empty().append(new RecordId(5, 5L));
    var priorRender = parent.toString();
    var priorDepth = parent.getDepth();

    parent.appendField("a");
    parent.appendIndex(1);
    parent.append(new RecordId(5, 6L));

    assertEquals("parent toString is unchanged after child appends",
        priorRender, parent.toString());
    assertEquals("parent depth is unchanged after child appends",
        priorDepth, parent.getDepth());
  }
}
