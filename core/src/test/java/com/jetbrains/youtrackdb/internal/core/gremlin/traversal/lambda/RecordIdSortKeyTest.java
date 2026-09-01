package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.B_O_Traverser;
import org.junit.Test;

/**
 * The sort key's three promises: it is total, it never throws, and it orders exactly as a MATCH
 * record identifier item does. No database is needed — the identifier classes are value types.
 */
public class RecordIdSortKeyTest {

  /** Collection identifier is the major field, so a lower collection sorts first. */
  @Test
  public void compareTo_ordersByCollectionIdentifierFirst() {
    var lowCollection = RecordIdSortKey.of(new RecordId(3, 9999));
    var highCollection = RecordIdSortKey.of(new RecordId(4, 0));

    assertThat(lowCollection).isLessThan(highCollection);
  }

  /** Within one collection the position decides, compared as a number rather than as text. */
  @Test
  public void compareTo_ordersByPositionWithinOneCollection() {
    var second = RecordIdSortKey.of(new RecordId(3, 2));
    var tenth = RecordIdSortKey.of(new RecordId(3, 10));

    assertThat(second).isLessThan(tenth);
  }

  /**
   * Positions are signed, so the negative position a record created inside a transaction carries
   * sorts before a committed position in the same collection. Text comparison would invert this.
   */
  @Test
  public void compareTo_treatsNegativePositionsAsSmaller() {
    var pending = RecordIdSortKey.of(new RecordId(3, -2));
    var committed = RecordIdSortKey.of(new RecordId(3, 0));

    assertThat(pending).isLessThan(committed);
  }

  /**
   * The parity promise: an immutable identifier and a changeable one holding the same numbers
   * project to one equal key. Comparing the identifiers themselves through TinkerPop orderability
   * would instead separate them by class name.
   */
  @Test
  public void of_collapsesBothIdentifierClassesOntoOneKey() {
    var immutable = RecordIdSortKey.of(new RecordId(7, 42));
    var changeable = RecordIdSortKey.of(new ChangeableRecordId(7, 42));

    assertThat(immutable).isEqualByComparingTo(changeable).isEqualTo(changeable);
    assertThat(immutable.hashCode()).isEqualTo(changeable.hashCode());
  }

  /** A changeable identifier is read at projection time, so a later collection wins its slot. */
  @Test
  public void of_readsChangeableIdentifierAtProjectionTime() {
    var changeable = new ChangeableRecordId(7, 42);
    var before = RecordIdSortKey.of(changeable);

    changeable.setCollectionAndPosition(9, 1);

    assertThat(RecordIdSortKey.of(changeable)).isGreaterThan(before);
  }

  /** A value with no identifier sorts before every value that has one. */
  @Test
  public void absent_sortsBeforeEveryPresentKey() {
    var absent = RecordIdSortKey.absent();

    assertThat(absent).isLessThan(RecordIdSortKey.of(new RecordId(0, 0)));
    assertThat(absent).isLessThan(RecordIdSortKey.of(new RecordId(-1, -1)));
    assertThat(RecordIdSortKey.of(new RecordId(0, 0))).isGreaterThan(absent);
  }

  /** Two absent keys are equal, which keeps the order total rather than merely irreflexive. */
  @Test
  public void absent_comparesEqualToItself() {
    assertThat(RecordIdSortKey.absent())
        .isEqualByComparingTo(RecordIdSortKey.of(null))
        .isEqualTo(RecordIdSortKey.of("no identifier here"));
  }

  /** Anything at all projects, so a wrongly typed stream degrades instead of failing. */
  @Test
  public void of_neverThrowsForAnyValue() {
    assertThat(RecordIdSortKey.of(null)).isEqualTo(RecordIdSortKey.absent());
    assertThat(RecordIdSortKey.of(42)).isEqualTo(RecordIdSortKey.absent());
    assertThat(RecordIdSortKey.of(Map.of("a", 1))).isEqualTo(RecordIdSortKey.absent());
    assertThat(RecordIdSortKey.of(new Object())).isEqualTo(RecordIdSortKey.absent());
  }

  /** A map entry contributes its key, which is what an entry-stream sort needs. */
  @Test
  public void of_readsTheKeyOfAMapEntry() {
    var entry = Map.entry(new RecordId(5, 6), "value");

    assertThat(RecordIdSortKey.of(entry)).isEqualTo(RecordIdSortKey.of(new RecordId(5, 6)));
  }

  /** An entry with a scalar key has no identifier, so it lands on the absent key. */
  @Test
  public void of_returnsAbsentForAnEntryWithAScalarKey() {
    assertThat(RecordIdSortKey.of(Map.entry("name", 1))).isEqualTo(RecordIdSortKey.absent());
  }

  /** An already projected key passes through, so a bypassed modulator does not project twice. */
  @Test
  public void of_returnsAnAlreadyProjectedKeyUnchanged() {
    var key = RecordIdSortKey.of(new RecordId(1, 2));

    assertThat(RecordIdSortKey.of(key)).isSameAs(key);
  }

  /** Equality is by value, and an unrelated type is never equal. */
  @Test
  public void equals_comparesTheThreeFields() {
    var key = RecordIdSortKey.of(new RecordId(1, 2));

    assertThat(key).isEqualTo(key);
    assertThat(key).isEqualTo(RecordIdSortKey.of(new RecordId(1, 2)));
    assertThat(key).isNotEqualTo(RecordIdSortKey.of(new RecordId(1, 3)));
    assertThat(key).isNotEqualTo(RecordIdSortKey.of(new RecordId(2, 2)));
    assertThat(key).isNotEqualTo(RecordIdSortKey.absent());
    assertThat(key).isNotEqualTo("#1:2");
  }

  /** The text form is stable, because a shape cache key can carry a modulator's text. */
  @Test
  public void toString_rendersTheIdentifierText() {
    assertThat(RecordIdSortKey.of(new RecordId(12, 3))).hasToString("#12:3");
    assertThat(RecordIdSortKey.absent()).hasToString("-");
  }

  /** The projection reads the traverser's own value when no bypass traversal is installed. */
  @Test
  public void traversal_projectsTheTraverserValue() {
    var projection = new RecordIdSortKeyTraversal<Object>();

    projection.addStart(new B_O_Traverser<>(new RecordId(2, 5), 1L));

    assertThat(projection.next()).isEqualTo(RecordIdSortKey.of(new RecordId(2, 5)));
    assertThat(projection).hasToString("ridSortKey");
  }

  /** With a bypass traversal installed, the projection reads what the bypass yields. */
  @Test
  public void traversal_projectsThroughAnInstalledBypassTraversal() {
    var projection = new RecordIdSortKeyTraversal<Object>();
    var bypass = new RecordIdSortKeyTraversal<Object>();
    projection.setBypassTraversal(bypass);

    projection.addStart(new B_O_Traverser<>(new RecordId(4, 7), 1L));

    assertThat(projection.next()).isEqualTo(RecordIdSortKey.of(new RecordId(4, 7)));
  }
}
