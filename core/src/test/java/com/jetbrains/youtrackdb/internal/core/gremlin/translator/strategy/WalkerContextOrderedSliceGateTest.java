package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link WalkerContext#orderAllowsSliceOnCurrentBoundary()}: the hop gate and ORDER
 * BY capture reset. Foreign-alias sort keys and multi-alias RETURN are allowed at slice time — tie
 * groups follow the same implementation-defined contract as boundary-only ordered slices.
 */
public class WalkerContextOrderedSliceGateTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String HOP_ALIAS = "$g2m_v1";
  private static final String FOREIGN_ALIAS = "$g2m_edge_0";

  /** Clearing {@code ORDER BY} drops capture metadata so a stale sort cannot license a slice. */
  @Test
  public void clearingOrderBy_resetsSliceGate() {
    var ctx = seededWithCapturedOrder(BOUNDARY_ALIAS);
    assertThat(ctx.orderAllowsSliceOnCurrentBoundary()).isTrue();

    ctx.setOrderBy(null);

    assertThat(ctx.orderAllowsSliceOnCurrentBoundary()).isFalse();
  }

  /**
   * A hop between {@code order()} and the slice moves the boundary away from the captured sort alias.
   */
  @Test
  public void hopAfterOrder_disallowsSlice() {
    var ctx = seededWithCapturedOrder(BOUNDARY_ALIAS);
    ctx.addNode(HOP_ALIAS, "V");
    ctx.pinBoundary(HOP_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);

    assertThat(ctx.orderAllowsSliceOnCurrentBoundary()).isFalse();
  }

  /**
   * A foreign-alias {@code ORDER BY} still licenses a slice when boundary is unchanged — the clause
   * is already in the assembled statement; tie-breaking is implementation-defined.
   */
  @Test
  public void foreignSortKey_allowsSliceWhenBoundaryUnchanged() {
    var ctx = seededWithCapturedOrder(BOUNDARY_ALIAS);
    ctx.recordOrderByCapture(BOUNDARY_ALIAS, false);
    ctx.setOrderBy(
        MatchProjectionBuilder.orderBy(
            List.of(
                ProjectionExpressionFactories.orderByProperty(FOREIGN_ALIAS, "since", true),
                ProjectionExpressionFactories.orderByProperty(BOUNDARY_ALIAS, "id", true))));

    assertThat(ctx.orderAllowsSliceOnCurrentBoundary()).isTrue();
  }

  /** {@link RecognitionContext#markReturnReadsForeignAlias()} no longer blocks the slice gate. */
  @Test
  public void foreignReturnFlag_doesNotBlockSliceGate() {
    var ctx = seededWithCapturedOrder(BOUNDARY_ALIAS);
    ctx.markReturnReadsForeignAlias();

    assertThat(ctx.orderAllowsSliceOnCurrentBoundary()).isTrue();
  }

  private static WalkerContext seededWithCapturedOrder(String boundaryAlias) {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(boundaryAlias, "V");
    ctx.pinBoundary(boundaryAlias, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(boundaryAlias);
    ctx.setOrderBy(
        MatchProjectionBuilder.orderBy(
            List.of(ProjectionExpressionFactories.orderByProperty(boundaryAlias, "name", true))));
    ctx.recordOrderByCapture(boundaryAlias, true);
    return ctx;
  }
}
