package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Verifies Track 6 walker foundation fields for result-shaping ({@code DISTINCT}, {@code GROUP BY},
 * order/pagination clauses, and boundary drop flags) default cleanly and accept recogniser writes.
 */
public class WalkerContextResultShapingTest {

  @Test
  public void resultShapingFields_defaultUnset() {
    var ctx = new WalkerContext(true, false);

    assertThat(ctx.returnDistinct).isFalse();
    assertThat(ctx.groupBy).isNull();
    assertThat(ctx.orderBy).isNull();
    assertThat(ctx.limit).isNull();
    assertThat(ctx.skip).isNull();
    assertThat(ctx.dropNullRows).isFalse();
    assertThat(ctx.dropOnAbsent).isFalse();
    assertThat(ctx.presencePropertyKeys).isEmpty();
    assertThat(ctx.wrapMapValuesInLists).isFalse();
    assertThat(ctx.accumulateMap).isFalse();
    assertThat(ctx.lastPropertyProjection).isNull();
  }

  @Test
  public void resultShaping_dropFlags_roundTrip() {
    var ctx = new WalkerContext(true, false);

    ctx.setReturnDistinct(true);
    ctx.setDropNullRows(true);
    ctx.setDropOnAbsent(true);

    assertThat(ctx.returnDistinct).isTrue();
    assertThat(ctx.dropNullRows).isTrue();
    assertThat(ctx.dropOnAbsent).isTrue();
  }
}
