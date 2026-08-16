package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Verifies the walker's result-shaping foundation — {@code DISTINCT}, {@code GROUP BY},
 * order/pagination clauses, boundary drop flags, and the ordered list-shaping op carrier —
 * defaults cleanly, answers the list-shaping query a terminator reads before it contributes, and
 * accepts recogniser writes through both write paths: the full replace ({@code setResultShaping})
 * and the append ({@code appendListShapingOp}).
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
    assertThat(ctx.shaping().listShapingOps())
        .as("no list-shaping terminator has run, so the boundary base keeps its structural bypass")
        .isEmpty();
    assertThat(ctx.lastPropertyProjection).isNull();
  }

  /**
   * The top-level walk's own answer to the list-shaping query, pinned in the {@code WalkerContext}
   * contract's own test class rather than only as a control inside the adapter's. A top-level context
   * holds the shaping its boundary base reads, so an op appended on it reaches the projected payload
   * stream and a list-shaping terminator may contribute. The paired {@code false} for a combinator
   * child sub-walk is pinned in {@code SubTraversalPredicateAdapterTest}.
   */
  @Test
  public void supportsListShaping_isTrueOnATopLevelWalk() {
    var ctx = new WalkerContext(true, false);

    assertThat(ctx.supportsListShaping())
        .as("a top-level context carries the shaping the boundary base reads")
        .isTrue();
  }

  /**
   * The append seam composes rather than replaces. Two ops land in declared order — the order the
   * boundary base applies them in, which is what makes {@code reverse().unfold()} and
   * {@code unfold().reverse()} observably different shapes — and the flags a sibling recogniser
   * pinned before the appends survive both of them. The flag assertions are the load-bearing half:
   * a naive {@code setResultShaping(NONE.withListShapingOps(...))} implementation would satisfy the
   * order assertion and still wipe {@code dropOnAbsent} and the presence keys, which is the defect
   * the append method exists to prevent. The two ops are separate {@link #taggedOp} instances, which
   * is what lets the order assertion discriminate at all: {@code ListShapingOp} has no value
   * equality, so the comparison is by reference.
   */
  @Test
  public void appendListShapingOp_appendsInDeclaredOrderAndPreservesPinnedFlags() {
    var ctx = new WalkerContext(true, false);
    ctx.setResultShaping(
        ResultShaping.NONE.withDropOnAbsent(true).withPresencePropertyKeys(List.of("age")));
    ListShapingOp first = taggedOp("first");
    ListShapingOp second = taggedOp("second");

    ctx.appendListShapingOp(first);
    ctx.appendListShapingOp(second);

    assertThat(ctx.shaping().listShapingOps())
        .as("declared order is the application order")
        .containsExactly(first, second);
    assertThat(ctx.shaping().dropOnAbsent())
        .as("a sibling recogniser's flag survives the append")
        .isTrue();
    assertThat(ctx.shaping().presencePropertyKeys())
        .as("and so does its presence-key list")
        .containsExactly("age");
  }

  /**
   * The documented limit of the append's no-clobber guarantee: {@code setResultShaping} replaces
   * the whole record, {@code listShapingOps} included, so an op appended before it is gone. Pinned
   * because two rules stated elsewhere depend on this being the behaviour rather than a merge —
   * the terminators are accepted only as the traversal's last step, and
   * {@code UnionStepRecogniser} replaces with the agreed child shaping before any post-union suffix
   * op appends. A change that made the replace merge would leave both rules describing something
   * the code no longer does.
   */
  @Test
  public void setResultShaping_afterAppend_replacesRatherThanMergesOps() {
    var ctx = new WalkerContext(true, false);
    ctx.appendListShapingOp(taggedOp("appended-before-the-replace"));

    ctx.setResultShaping(ResultShaping.NONE.withAccumulateMap(true));

    assertThat(ctx.shaping().listShapingOps())
        .as("the replace drops the appended op")
        .isEmpty();
    assertThat(ctx.shaping().accumulateMap()).isTrue();
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

  /**
   * A pass-through stage that carries a readable identity. Two ops written as identical lambdas would
   * still be distinct instances, so the order assertion would hold, but neither the source nor an
   * {@code AssertionError} would show a reader which op is which — a failure would name two synthetic
   * lambda classes. The {@code tag} makes the discriminator visible in both places.
   */
  private static ListShapingOp taggedOp(String tag) {
    return new ListShapingOp() {
      @Override
      public Iterator<Object> apply(Iterator<Object> upstream) {
        return upstream;
      }

      @Override
      public String toString() {
        return tag;
      }
    };
  }
}
