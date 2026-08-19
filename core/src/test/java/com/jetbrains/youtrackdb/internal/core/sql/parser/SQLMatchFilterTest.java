package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link SQLMatchFilter}'s class-name accessors.
 *
 * <p>{@code SQLMatchFilter} stores its attributes across a list of {@code SQLMatchFilterItem}s, so
 * "set the class" is either an append onto a filter that carries none or a rewrite of the item that
 * already does. Both in-tree callers guard on the class being absent, which means the rewrite arm
 * has no caller and would otherwise ship untested against a documented contract. These cases make
 * the contract real for the next caller, which may not carry the guard.
 */
public class SQLMatchFilterTest {

  /**
   * Setting a class on an alias-only filter appends an item carrying it, and {@code getClassName}
   * reads it back. This is the arm both current callers take: a path item's target filter is built
   * alias-only and its class becomes known later, once the step that types the target is read.
   */
  @Test
  public void setClassName_onAliasOnlyFilter_appendsAndReadsBack() {
    var filter = SQLMatchFilter.fromAliasAndClass("t", null);
    assertThat(filter.getClassName(null)).isNull();

    filter.setClassName("Person");

    assertThat(filter.getClassName(null)).isEqualTo("Person");
    assertThat(filter.getAlias()).isEqualTo("t");
  }

  /**
   * Setting a class on a filter that already carries one rewrites it in place rather than appending
   * a second class item. Appending would leave two class names in one filter block and
   * {@code getClassName} would return whichever came first, so the read and the write would disagree
   * about which class the filter constrains.
   */
  @Test
  public void setClassName_onClassCarryingFilter_rewritesInPlace() {
    var filter = SQLMatchFilter.fromAliasAndClass("t", "Person");

    filter.setClassName("Employee");

    assertThat(filter.getClassName(null)).isEqualTo("Employee");
    assertThat(filter.getAlias()).isEqualTo("t");
  }
}
