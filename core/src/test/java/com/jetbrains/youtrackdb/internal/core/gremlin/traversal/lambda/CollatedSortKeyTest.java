package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import org.junit.Test;

/**
 * The collated sort key compares two property values exactly as the engine comparison does, through
 * {@link Collate#compareForOrderBy}. No database is needed — a collation is a value type.
 */
public class CollatedSortKeyTest {

  private static final Collate CASE_INSENSITIVE = Collate.caseInsensitiveCollate();

  private static final Collate DEFAULT = Collate.defaultCollate();

  /**
   * Scenario: two names whose letter case is the only difference in their first character, under the
   * case-insensitive collation. Expected: alphabetical order, so the lower-case {@code ada} precedes
   * the capitalised {@code Bob}, where a code-point comparison puts every capital first.
   */
  @Test
  public void compareTo_caseInsensitiveCollation_ignoresLetterCase() {
    var ada = CollatedSortKey.of("ada", CASE_INSENSITIVE);
    var bob = CollatedSortKey.of("Bob", CASE_INSENSITIVE);

    assertThat(ada).isLessThan(bob);
  }

  /**
   * Scenario: two spellings of one name under the case-insensitive collation. Expected: they do not
   * tie — the collation falls back to the raw comparison when the folded forms match, which is what
   * keeps a tie group in a stable order on both arms.
   */
  @Test
  public void compareTo_caseInsensitiveCollation_breaksTiesOnTheRawValue() {
    var capitalised = CollatedSortKey.of("Ada", CASE_INSENSITIVE);
    var lowerCase = CollatedSortKey.of("ada", CASE_INSENSITIVE);

    assertThat(capitalised).isLessThan(lowerCase);
  }

  /**
   * Scenario: the same two names under the default collation. Expected: code-point order, so the
   * capital sorts first. The default collation must change nothing about plain comparison.
   */
  @Test
  public void compareTo_defaultCollation_comparesByCodePoint() {
    var ada = CollatedSortKey.of("ada", DEFAULT);
    var bob = CollatedSortKey.of("Bob", DEFAULT);

    assertThat(ada).isGreaterThan(bob);
  }

  /** A null value sorts before every value, as it does in the engine comparison. */
  @Test
  public void compareTo_nullValue_sortsFirst() {
    var absent = CollatedSortKey.of(null, CASE_INSENSITIVE);
    var present = CollatedSortKey.of("ada", CASE_INSENSITIVE);

    assertThat(absent).isLessThan(present);
    assertThat(present).isGreaterThan(absent);
    assertThat(absent).isEqualByComparingTo(CollatedSortKey.of(null, CASE_INSENSITIVE));
  }

  /**
   * Scenario: a text value against a number, which one schema-less column can hold. Expected: they
   * are reported equal rather than throwing, which is what the engine comparison answers for the
   * same pair, so a sort over such a column returns rows on both arms.
   */
  @Test
  public void compareTo_incompatibleValueClasses_reportsEquality() {
    var text = CollatedSortKey.of("ada", CASE_INSENSITIVE);
    var number = CollatedSortKey.of(42, CASE_INSENSITIVE);

    assertThat(text).isEqualByComparingTo(number);
  }

  /** Two keys over one value are equal and hash alike, whatever their letter case folds to. */
  @Test
  public void equals_sameValueAndCollation_areEqual() {
    var first = CollatedSortKey.of("ada", CASE_INSENSITIVE);
    var second = CollatedSortKey.of("ada", CASE_INSENSITIVE);

    assertThat(first).isEqualTo(second).isEqualTo(first);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
    assertThat(first.value()).isEqualTo("ada");
  }

  /** A key of one collation is not equal to a key of another, because they order differently. */
  @Test
  public void equals_differentCollations_areNotEqual() {
    assertThat(CollatedSortKey.of("ada", CASE_INSENSITIVE))
        .isNotEqualTo(CollatedSortKey.of("ada", DEFAULT))
        .isNotEqualTo("ada");
  }

  /** A null value hashes to zero rather than throwing, and renders as the collation of nothing. */
  @Test
  public void hashCode_nullValue_isZero() {
    assertThat(CollatedSortKey.of(null, CASE_INSENSITIVE).hashCode()).isZero();
    assertThat(CollatedSortKey.of(null, CASE_INSENSITIVE)).hasToString("ci(null)");
  }

  /** The text form names the collation and the value, so a shape key stays stable across runs. */
  @Test
  public void toString_namesTheCollationAndTheValue() {
    assertThat(CollatedSortKey.of("Ada", CASE_INSENSITIVE)).hasToString("ci(Ada)");
  }
}
