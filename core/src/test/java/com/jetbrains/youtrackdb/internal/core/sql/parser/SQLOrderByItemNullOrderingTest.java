package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for {@link SQLOrderByItem#resolveNullsFirst()}: explicit NULLS FIRST/LAST are
 * absolute; an omitted clause falls back to {@link GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT}
 * composed with ASC/DESC.
 */
public class SQLOrderByItemNullOrderingTest {

  @After
  public void restoreDefault() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.resetToDefault();
  }

  @Test
  public void explicitNullsFirstIsAbsoluteForAscAndDesc() {
    assertTrue(item(SQLOrderByItem.ASC, SQLOrderByItem.NULLS_FIRST).resolveNullsFirst());
    assertTrue(item(SQLOrderByItem.DESC, SQLOrderByItem.NULLS_FIRST).resolveNullsFirst());
  }

  @Test
  public void explicitNullsLastIsAbsoluteForAscAndDesc() {
    assertFalse(item(SQLOrderByItem.ASC, SQLOrderByItem.NULLS_LAST).resolveNullsFirst());
    assertFalse(item(SQLOrderByItem.DESC, SQLOrderByItem.NULLS_LAST).resolveNullsFirst());
  }

  @Test
  public void omittedClauseWithNullsSmallestMatchesLegacyDirectionRelativeBehavior() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_SMALLEST);

    assertTrue(item(SQLOrderByItem.ASC, null).resolveNullsFirst());
    assertFalse(item(SQLOrderByItem.DESC, null).resolveNullsFirst());
  }

  @Test
  public void omittedClauseWithNullsLargestFlipsDirectionRelativeBehavior() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_LARGEST);

    assertFalse(item(SQLOrderByItem.ASC, null).resolveNullsFirst());
    assertTrue(item(SQLOrderByItem.DESC, null).resolveNullsFirst());
  }

  /**
   * Per-storage {@link com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration} overrides
   * the runtime global when resolving an omitted NULLS clause.
   */
  @Test
  public void storageLocalDefaultOverridesRuntimeGlobal() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_SMALLEST);
    var storageConfig = new com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration();
    storageConfig.setValue(
        GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT, OrderByNullsDefault.NULLS_LARGEST);

    assertFalse(item(SQLOrderByItem.ASC, null).resolveNullsFirst(storageConfig));
    assertTrue(item(SQLOrderByItem.DESC, null).resolveNullsFirst(storageConfig));
  }

  private static SQLOrderByItem item(String type, String nullOrdering) {
    var item = new SQLOrderByItem();
    item.setType(type);
    item.setNullOrdering(nullOrdering);
    return item;
  }
}
