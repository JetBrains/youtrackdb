package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Unit tests for {@link OrderByNullsUtil}.
 *
 * <p>Marked {@code @Category(SequentialTest)} because it mutates the process-wide
 * {@code QUERY_ORDER_BY_NULLS_DEFAULT} global. The default surefire execution runs four test
 * classes in parallel in one virtual machine, so the mutation would leak between classes.
 */
@Category(SequentialTest.class)
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

  /**
   * A value stored or configured as a lower-case string still names the constant. A server property
   * and a storage property both carry plain text. Track 02 also reads this value on every statement.
   */
  @Test
  public void lowerCaseStringValueIsAccepted() {
    var storageConfig = new ContextConfiguration();
    storageConfig.setValue(
        GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT, "nulls_largest");

    assertEquals(OrderByNullsDefault.NULLS_LARGEST, OrderByNullsUtil.resolveDefault(storageConfig));
    assertFalse(OrderByNullsUtil.resolveNullsFirst(null, true, storageConfig));
  }

  /**
   * A value that names no constant never fails a query. It is reported once, and the runtime global
   * applies instead. The global is NULLS_LARGEST here, so a fallback to the declared default would
   * fail this assertion too. The reported warning names the value, the key and the consequence.
   */
  @Test
  public void invalidStringValueIsReportedAndFallsBackToRuntimeGlobal() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_LARGEST);
    var storageConfig = new ContextConfiguration();
    // The reporting set suppresses repeats per distinct value, so this value is unique to this test.
    storageConfig.setValue(
        GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT, "NOT_A_CONSTANT_RESOLVER");

    try (var logs = LogRecordCollector.attachTo(OrderByNullsUtil.class)) {
      assertEquals(
          OrderByNullsDefault.NULLS_LARGEST, OrderByNullsUtil.resolveDefault(storageConfig));
      assertTrue(
          "the unreadable value must be reported, captured: " + logs.messages(),
          logs.warnedWithAll(
              "NOT_A_CONSTANT_RESOLVER",
              GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.getKey(),
              "default null ordering applies"));
    }
  }

  /**
   * A value carrying surrounding whitespace is rejected, exactly as the global setter rejects it.
   * The read path and the setter must agree, so an operator cannot store a value the setter refuses.
   */
  @Test
  public void paddedStringValueIsRejectedLikeTheGlobalSetter() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_SMALLEST);
    var storageConfig = new ContextConfiguration();
    storageConfig.setValue(GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT, " NULLS_LARGEST ");

    assertEquals(OrderByNullsDefault.NULLS_SMALLEST,
        OrderByNullsUtil.resolveDefault(storageConfig));
    assertThrows(
        IllegalArgumentException.class,
        () -> GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(" NULLS_LARGEST "));
  }

  /**
   * A command context that was never bound to a session must not fail a sort. Its session lookup
   * throws, and the resolver has to answer with the runtime global instead.
   */
  @Test
  public void contextWithoutSessionUsesRuntimeGlobal() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_LARGEST);

    assertEquals(
        OrderByNullsDefault.NULLS_LARGEST,
        OrderByNullsUtil.resolveDefaultForSort(new BasicCommandContext()));
  }

  /** With no context at all the runtime global applies. */
  @Test
  public void missingContextUsesRuntimeGlobal() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_LARGEST);

    assertEquals(OrderByNullsDefault.NULLS_LARGEST, OrderByNullsUtil.resolveDefault(null));
    assertEquals(
        OrderByNullsDefault.NULLS_LARGEST, OrderByNullsUtil.resolveDefaultForSort(null));
  }

  /**
   * An already resolved default composes with the item's clause and direction, reading no
   * configuration. An explicit clause stays absolute. An omitted clause follows the passed default.
   */
  @Test
  public void composeUsesPassedDefaultAndIgnoresConfiguration() {
    GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.setValue(OrderByNullsDefault.NULLS_SMALLEST);

    assertTrue(
        OrderByNullsUtil.composeNullsFirst(
            SQLOrderByItem.NULLS_FIRST, false, OrderByNullsDefault.NULLS_SMALLEST));
    assertFalse(
        OrderByNullsUtil.composeNullsFirst(
            SQLOrderByItem.NULLS_LAST, true, OrderByNullsDefault.NULLS_LARGEST));
    assertFalse(
        OrderByNullsUtil.composeNullsFirst(null, true, OrderByNullsDefault.NULLS_LARGEST));
    assertTrue(
        OrderByNullsUtil.composeNullsFirst(null, false, OrderByNullsDefault.NULLS_LARGEST));
    assertTrue(OrderByNullsUtil.composeNullsFirst(null, true, OrderByNullsDefault.NULLS_SMALLEST));
  }
}
