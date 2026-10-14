package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.OrderByNullsPlacement;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.junit.Test;

/**
 * Unit tests for {@link YTDBStrategyUtil}'s traversal-to-session resolution and null-safety.
 * The order resolver reads the explicit per-traversal option, a user-supplied
 * {@link StandardOrderSemanticsStrategy}, and the database setting.
 * An unresolved database setting retains records unless a false option or standard-order strategy
 * intervenes.
 * The tests also verify that detached and non-YTDB traversals decline session resolution safely.
 * Detached and non-YTDB cases use mocks, while the child-traversal case uses a real traversal.
 * The YTDB-attached path is exercised by the strategy and walker suites.
 */
public class YTDBStrategyUtilTest {

  /**
   * A detached traversal (empty {@code getGraph()}) resolves to no session and a null polymorphism
   * result — the anonymous {@code __.V()} case.
   */
  @Test
  public void resolveYtdbSession_detachedTraversal_returnsNull() {
    var traversal = mockTraversalWithGraph(null);

    assertThat(YTDBStrategyUtil.resolveYtdbSession(traversal))
        .as("a detached traversal has no YTDB session")
        .isNull();
    assertThat(YTDBStrategyUtil.isPolymorphic(traversal))
        .as("a detached traversal yields a null polymorphism result")
        .isNull();
    assertThat(YTDBStrategyUtil.orderIncludesMissingKey(traversal))
        .as("an unresolved order setting keeps records")
        .isTrue();
  }

  /**
   * A traversal attached to a non-YTDB graph resolves to null WITHOUT calling {@code tx()}. This is
   * the safety guarantee: the {@code instanceof YTDBGraph} gate short-circuits before {@code tx()},
   * so a non-transactional graph (TinkerPop's {@code EmptyGraph}, whose {@code tx()} throws {@code
   * UnsupportedOperationException}) yields a clean decline instead of a thrown exception. The
   * mock's {@code tx()} is stubbed to throw so a regression that reintroduced an eager {@code tx()}
   * call (as the old cast-based code did) would surface as that exception rather than the expected
   * null.
   */
  @Test
  public void resolveYtdbSession_nonYtdbGraph_returnsNullWithoutCallingTx() {
    var nonYtdb = mock(Graph.class);
    when(nonYtdb.tx()).thenThrow(new UnsupportedOperationException("EmptyGraph-like: no tx"));
    var traversal = mockTraversalWithGraph(nonYtdb);

    assertThat(YTDBStrategyUtil.resolveYtdbSession(traversal))
        .as("a non-YTDB graph declines to a null session, never calling tx()")
        .isNull();
    assertThat(YTDBStrategyUtil.isPolymorphic(traversal))
        .as("a non-YTDB graph yields null polymorphism, not a thrown exception")
        .isNull();
    assertThat(YTDBStrategyUtil.orderIncludesMissingKey(traversal))
        .as("a non-YTDB graph keeps records without calling tx")
        .isTrue();
  }

  /** Proves the resolver walks from a child traversal to the root strategy for standard order semantics. */
  @Test
  public void orderIncludesMissingKey_childTraversalWalksRootForStandardOrderSemantics() {
    var root = __.V().union(__.order()).asAdmin();
    var rootStrategies = new DefaultTraversalStrategies();
    rootStrategies.addStrategies(StandardOrderSemanticsStrategy.instance());
    root.setStrategies(rootStrategies);
    var child = ((TraversalParent) root.getEndStep()).getGlobalChildren().get(0);

    assertThat(child.getStrategies().getStrategy(StandardOrderSemanticsStrategy.class)).isEmpty();
    assertThat(YTDBStrategyUtil.orderIncludesMissingKey(child))
        .as("the child resolves standard order semantics from the root strategy")
        .isFalse();
  }

  /** Explicit readers validate only their own options. Detached resolver gates stay intact. */
  @Test
  public void explicitReadersKeepOptionIsolationAndResolverGates() {
    var detached = __.V().asAdmin();
    var options = OptionsStrategy.build()
        .with(YTDBQueryConfigParam.polymorphicQuery.name(), "invalid")
        .with(YTDBQueryConfigParam.orderIncludesMissingKey.name(), false)
        .with(YTDBQueryConfigParam.orderByNullsPlacementAsc.name(), "last").create();
    detached.getStrategies().addStrategies(options);
    assertThat(YTDBStrategyUtil.explicitOrderIncludesMissingKey(options, detached)).isFalse();
    assertThat(YTDBStrategyUtil.orderIncludesMissingKey(detached)).isFalse();
    assertThat(YTDBStrategyUtil.explicitNullPlacements(options).ascending())
        .isEqualTo(OrderByNullsPlacement.LAST);
    assertThat(YTDBStrategyUtil.explicitNullPlacements(options).descending()).isNull();
    assertThat(YTDBStrategyUtil.isPolymorphic(detached)).isNull();
    assertThat(YTDBStrategyUtil.orderByNullsPlacements(detached)).isNull();
    assertThatThrownBy(() -> YTDBStrategyUtil.explicitPolymorphic(options))
        .isInstanceOf(ClassCastException.class);
    assertThat(YTDBStrategyUtil.explicitPolymorphic(null)).isNull();
  }

  /** Builds a mock {@code Traversal.Admin} whose {@code getGraph()} returns {@code graph} (or empty
   * when {@code graph} is null). */
  @SuppressWarnings("unchecked")
  private static Traversal.Admin<Object, Object> mockTraversalWithGraph(Graph graph) {
    Traversal.Admin<Object, Object> traversal = mock(Traversal.Admin.class);
    when(traversal.getGraph()).thenReturn(Optional.ofNullable(graph));
    when(traversal.getStrategies()).thenReturn(new DefaultTraversalStrategies());
    return traversal;
  }
}
