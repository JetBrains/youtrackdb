package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

/**
 * Unit tests for {@link MatchClassFilters}, the shared factory for the {@code @class} narrowing AST
 * that explicit-class recognisers (the folded {@code hasLabel} in a later track) attach to a pattern
 * node. These pin two things: the produced AST is an exact-class {@code @class = 'X'} predicate (the
 * record-attribute form, not the polymorphic {@code class:} node type), and a blank class name is
 * rejected loudly rather than emitting a silently-wrong {@code @class = ''} predicate.
 *
 * <p>No graph or database is needed — the factory builds detached AST — so this is a plain unit
 * test rather than a {@code GraphBaseTest} subclass.
 */
public class MatchClassFiltersTest {

  /**
   * {@code classEquals("Person")} builds an exact-class equality condition rendering the {@code
   * @class} record attribute, the equals operator, and the class name. Asserting on {@code @class}
   * (not {@code class:}) pins the exact-leaf-class semantics — the record's own class, so subclasses
   * are excluded — which is the whole point of the narrowing.
   */
  @Test
  public void classEquals_rendersExactClassEqualityCondition() {
    var rendered = MatchClassFilters.classEquals("Person").toString();

    assertThat(rendered)
        .as("must be an exact @class equality predicate; was: " + rendered)
        .contains("@class")
        .contains("=")
        .contains("Person");
  }

  /**
   * {@code classEqualsWhere("Person")} wraps the same condition in a {@link
   * com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause} so it drops straight into the
   * MATCH IR's {@code aliasFilters} map. The rendered clause carries the {@code @class} predicate.
   */
  @Test
  public void classEqualsWhere_wrapsConditionInWhereClause() {
    var rendered = MatchClassFilters.classEqualsWhere("Person").toString();

    assertThat(rendered)
        .as("the where clause must carry the @class predicate; was: " + rendered)
        .contains("@class")
        .contains("Person");
  }

  /**
   * A null class name throws {@link IllegalArgumentException}: an explicit-class recogniser reaches
   * this factory only with a concrete user-named class, so a null name is a caller bug that must
   * fail loud rather than produce a broken predicate.
   */
  @Test
  public void classEquals_nullName_throws() {
    assertThatThrownBy(() -> MatchClassFilters.classEquals(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-blank");
  }

  /**
   * An empty class name throws {@link IllegalArgumentException} — the {@code isBlank()} arm of the
   * guard (distinct from the null arm above), reached because the name is non-null but empty.
   */
  @Test
  public void classEquals_emptyName_throws() {
    assertThatThrownBy(() -> MatchClassFilters.classEquals(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * A whitespace-only class name throws {@link IllegalArgumentException} — also the {@code isBlank()}
   * arm, since a blank {@code @class = ''} predicate would be silently wrong.
   */
  @Test
  public void classEquals_whitespaceName_throws() {
    assertThatThrownBy(() -> MatchClassFilters.classEquals("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
