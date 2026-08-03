package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import java.util.Set;
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

  /**
   * The three productive-by states, including the configured-key one that decides per key. An
   * absent strategy leaves every {@code by(key)} filtering; the strategy's own default (an empty
   * key set) makes every key productive; a configured set makes productive exactly the keys it does
   * <em>not</em> list, because a listed key is asserted to be present already and the strategy
   * leaves that modulator alone. Reading the membership test the other way round is invisible to a
   * default-instance test, which short-circuits on the empty set before the membership test runs.
   */
  @Test
  public void byModulatorIsProductive_coversAllThreeStates() {
    var absent = new WalkerContext(true, false);
    assertThat(absent.byModulatorIsProductive("age")).isFalse();

    var everyKey = new WalkerContext(true, false);
    everyKey.setProductiveByKeys(Set.of());
    assertThat(everyKey.byModulatorIsProductive("age")).isTrue();

    var configured = new WalkerContext(true, false);
    configured.setProductiveByKeys(Set.of("age"));
    assertThat(configured.byModulatorIsProductive("age")).isFalse();
    assertThat(configured.byModulatorIsProductive("name")).isTrue();
  }
}
