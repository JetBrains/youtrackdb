package com.jetbrains.youtrackdb.internal.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * {@link OrderByRidTieBreakUtil} appends {@code boundary.@rid ASC} to translated Gremlin sort keys.
 */
public class OrderByRidTieBreakUtilTest {

  /** Property-only sorts gain a trailing boundary {@code @rid ASC}. */
  @Test
  public void appendRidTieBreakIfMissing_addsBoundaryRidAsc() {
    var items =
        new ArrayList<>(
            List.of(ProjectionExpressionFactories.orderByProperty("$g2m_v0", "name", true)));

    OrderByRidTieBreakUtil.appendRidTieBreakIfMissing(items, "$g2m_v0");

    assertThat(items).hasSize(2);
    assertThat(OrderByRidTieBreakUtil.sortsByRid(items.getLast())).isTrue();
    assertThat(items.getLast().getAlias()).isEqualTo("$g2m_v0");
  }

  /** An {@code ORDER BY} that already ends on {@code @rid} must not gain a duplicate key. */
  @Test
  public void appendRidTieBreakIfMissing_skipsWhenLastKeyIsRid() {
    var items =
        new ArrayList<>(
            List.of(ProjectionExpressionFactories.orderByRecordAttribute("$g2m_v0", "@rid", true)));

    OrderByRidTieBreakUtil.appendRidTieBreakIfMissing(items, "$g2m_v0");

    assertThat(items).hasSize(1);
  }

  /** LDBC-style {@code ORDER BY …, id ASC} must not gain a trailing {@code @rid}. */
  @Test
  public void appendRidTieBreakIfMissing_skipsWhenLastKeyIsIdProperty() {
    var items =
        new ArrayList<>(
            List.of(
                ProjectionExpressionFactories.orderByProperty("$g2m_msg", "creationDate", false),
                ProjectionExpressionFactories.orderByProperty("$g2m_msg", "id", true)));

    OrderByRidTieBreakUtil.appendRidTieBreakIfMissing(items, "$g2m_msg");

    assertThat(items).hasSize(2);
    assertThat(OrderByRidTieBreakUtil.sortsByIdProperty(items.getLast())).isTrue();
  }

  /** {@link OrderByRidTieBreakUtil#sortsByRid} recognises alias.{@code @rid} items. */
  @Test
  public void sortsByRid_recognisesAliasRecordAttribute() {
    var item = ProjectionExpressionFactories.orderByRecordAttribute("v", "@rid", true);
    assertThat(OrderByRidTieBreakUtil.sortsByRid(item)).isTrue();
  }
}
