package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.junit.Test;

/**
 * Unit tests for {@link YTDBStrategyUtil}'s traversal-to-session resolution and its null-safety
 * contract. They pin the regression that {@link YTDBStrategyUtil#resolveYtdbSession} — and
 * therefore {@link YTDBStrategyUtil#isPolymorphic}, which delegates to it — must DECLINE with a
 * {@code null} on a traversal that is detached or attached to a non-YTDB / non-transactional
 * graph, rather than throwing. The cast-based predecessor threw {@code
 * UnsupportedOperationException} the moment it called {@code tx()} on TinkerPop's {@code
 * EmptyGraph}. Pure mocks are used (no database) because only the graph-attachment gate is under
 * test; the YTDB-attached happy path is exercised by the strategy and walker suites.
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
  }

  /** Builds a mock {@code Traversal.Admin} whose {@code getGraph()} returns {@code graph} (or empty
   * when {@code graph} is null). */
  @SuppressWarnings("unchecked")
  private static Traversal.Admin<Object, Object> mockTraversalWithGraph(Graph graph) {
    Traversal.Admin<Object, Object> traversal = mock(Traversal.Admin.class);
    when(traversal.getGraph()).thenReturn(Optional.ofNullable(graph));
    return traversal;
  }
}
