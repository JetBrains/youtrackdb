package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
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
    assertThat(ctx.shaping()).isEqualTo(ResultShaping.NONE);
    assertThat(ctx.shaping().dropNullRows()).isFalse();
    assertThat(ctx.shaping().dropOnAbsent()).isFalse();
    assertThat(ctx.shaping().presencePropertyKeys()).isEmpty();
    assertThat(ctx.shaping().wrapMapValuesInLists()).isFalse();
    assertThat(ctx.shaping().accumulateMap()).isFalse();
    assertThat(ctx.lastPropertyProjection).isNull();
  }

  @Test
  public void resultShaping_dropFlags_roundTrip() {
    var ctx = new WalkerContext(true, false);

    ctx.setReturnDistinct(true);
    ctx.setResultShaping(ResultShaping.NONE.withDropNullRows(true).withDropOnAbsent(true));

    assertThat(ctx.returnDistinct).isTrue();
    assertThat(ctx.shaping().dropNullRows()).isTrue();
    assertThat(ctx.shaping().dropOnAbsent()).isTrue();
  }
}
