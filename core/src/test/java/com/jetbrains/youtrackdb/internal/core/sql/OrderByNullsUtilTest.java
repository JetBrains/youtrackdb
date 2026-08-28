package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import org.junit.After;
import org.junit.Test;

/** Unit tests for {@link OrderByNullsUtil}. */
public class OrderByNullsUtilTest {

  @After
  public void restoreDefault() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.resetToDefault();
  }

  @Test
  public void explicitNullsFirstAndLastAreAbsolute() {
    assertTrue(
        OrderByNullsUtil.resolveNullsFirst(SQLOrderByItem.NULLS_FIRST, false, null));
    assertFalse(
        OrderByNullsUtil.resolveNullsFirst(SQLOrderByItem.NULLS_LAST, true, null));
  }

  /** A {@code null} config falls back to the runtime global default. */
  @Test
  public void nullConfigUsesRuntimeGlobalDefault() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_LARGEST);

    assertFalse(OrderByNullsUtil.resolveNullsFirst(null, true, null));
    assertTrue(OrderByNullsUtil.resolveNullsFirst(null, false, null));
  }

  /** Storage-local override wins over the runtime global when the clause is omitted. */
  @Test
  public void storageConfigOverridesRuntimeGlobal() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_SMALLEST);
    var storageConfig = new ContextConfiguration();
    storageConfig.setValue(
        GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT, OrderByNullsDefault.NULLS_LARGEST);

    assertFalse(OrderByNullsUtil.resolveNullsFirst(null, true, storageConfig));
  }
}
