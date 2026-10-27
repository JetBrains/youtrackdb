package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Component mapping and compatibility must not diverge when equality keys precede ORDER BY. */
public class IndexSearchDescriptorOrderTest {

  private SQLBinaryCondition condition(String field, SQLBinaryCompareOperator operator) {
    var condition = mock(SQLBinaryCondition.class);
    var expression = mock(SQLExpression.class);
    when(expression.toString()).thenReturn(field);
    when(condition.getLeft()).thenReturn(expression);
    when(condition.getOperator()).thenReturn(operator);
    return condition;
  }

  private IndexSearchDescriptor descriptor(SQLBooleanExpression... conditions) {
    var definition = mock(IndexDefinition.class);
    when(definition.getProperties()).thenReturn(List.of("a", "b", "c"));
    var index = mock(Index.class);
    when(index.getDefinition()).thenReturn(definition);
    var block = new SQLAndBlock(-1);
    block.getSubBlocks().addAll(List.of(conditions));
    return new IndexSearchDescriptor(index, block, null, null);
  }

  /** Equality overlap maps requested keys, including an ORDER BY exhausted before the prefix. */
  @Test
  public void equalityPrefixMapsOriginalOrderItems() {
    var descriptor = descriptor(condition("a", new SQLEqualsOperator(-1)),
        condition("b", new SQLEqualsOperator(-1)));
    assertThat(descriptor.orderedIndexComponents(new ArrayList<>(List.of("c"))))
        .containsExactly(2);
    assertThat(descriptor.orderedIndexComponents(new ArrayList<>(List.of("b", "c"))))
        .containsExactly(1, 2);
    assertThat(descriptor.orderedIndexComponents(new ArrayList<>(List.of("a"))))
        .containsExactly(0);
    assertThat(descriptor.fullySorted(new ArrayList<>(List.of("a", "b", "c")))).isTrue();
    assertThat(descriptor.orderedIndexComponents(new ArrayList<>(List.of("a", "c")))).isNull();
  }

  /** IN, non-final ranges, missing fields and oversized order lists cannot claim index order. */
  @Test
  public void unsupportedFieldOrdersHaveNoMapping() {
    assertThat(descriptor(new SQLInCondition(-1))
        .orderedIndexComponents(new ArrayList<>(List.of("a")))).isNull();
    assertThat(descriptor(condition("a", new SQLGtOperator(-1)),
        condition("b", new SQLEqualsOperator(-1)))
        .orderedIndexComponents(new ArrayList<>(List.of("a")))).isNull();
    assertThat(descriptor().orderedIndexComponents(new ArrayList<>(List.of("b")))).isNull();
    assertThat(descriptor().orderedIndexComponents(new ArrayList<>(List.of("a", "b", "c", "d"))))
        .isNull();
    assertThat(descriptor(condition("a", new SQLGtOperator(-1)))
        .orderedIndexComponents(new ArrayList<>(List.of("a", "b")))).containsExactly(0, 1);
  }

  /** Only exact built-in comparator classes opt in, and both mismatch directions are refused. */
  @Test
  public void builtInComparatorsMustMatchOnBothSides() {
    var ci = new CaseInsensitiveCollate();
    var defaults = new DefaultCollate();
    assertThat(IndexOrderCollations.matches(null, 0, defaults)).isTrue();
    assertThat(IndexOrderCollations.matches(defaults, 0, null)).isTrue();
    assertThat(IndexOrderCollations.matches(ci, 0, ci)).isTrue();
    assertThat(IndexOrderCollations.matches(ci, 0, null)).isFalse();
    assertThat(IndexOrderCollations.matches(defaults, 0, ci)).isFalse();
    assertThat(IndexOrderCollations.matches(ci, 0, new CaseInsensitiveCollate() {
    })).isFalse();
  }
}
