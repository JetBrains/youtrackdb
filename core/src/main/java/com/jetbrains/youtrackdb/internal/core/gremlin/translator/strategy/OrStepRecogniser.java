package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchBooleanCheck;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import java.util.ArrayList;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;

/**
 * Recogniser for {@link OrStep}, the {@code ConnectiveStrategy} form of logical OR over child
 * sub-traversals. Pure boundary arms use a WHERE disjunction. Arms containing detached checks
 * form a scoped tree, with each complete arm kept separate. Any failed child declines the step.
 *
 * <p>Captured filters are composed with {@link MatchWhereBuilder#or} and committed once on the
 * boundary alias. Individual {@link RecognitionContext#putAliasFilter} calls are not used for OR
 * children — that API AND-composes same-alias contributions. Each operand keeps its named label
 * conditions. Polymorphic single labels absent from the child's WHERE fold into subclass-expanded
 * {@code classIn} tests. Internal scan classes, including several-label least common ancestors,
 * are not boolean label conditions.
 */
final class OrStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final OrStepRecogniser INSTANCE = new OrStepRecogniser();

  /** Stateless builder for OR composition and WHERE wrapping. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private OrStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof OrStep<?> orStep)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var adapters = ConnectiveStepSupport.walkAcceptedChildren(orStep, ctx);
    if (adapters == null) {
      return Outcome.DECLINE;
    }
    boolean detached = adapters.stream()
        .anyMatch(adapter -> adapter.hasEdges() || adapter.hasCapturedChecks());
    if (!detached) {
      var filters = new ArrayList<SQLBooleanExpression>();
      for (var adapter : adapters) {
        var filter = ConnectiveStepSupport.singleCapturedFilter(adapter, boundary);
        if (filter == null) {
          return Outcome.DECLINE;
        }
        filters.add(filter);
      }
      ctx.putAliasFilter(boundary,
          WHERE.wrap(WHERE.or(filters.toArray(new SQLBooleanExpression[0]))));
    } else {
      var arms = new ArrayList<MatchBooleanCheck>();
      for (var adapter : adapters) {
        var arm = ConnectiveStepSupport.booleanArm(ctx, adapter, boundary);
        if (arm == null) {
          return Outcome.DECLINE;
        }
        arms.add(arm);
      }
      ctx.addBooleanCheck(new MatchBooleanCheck.Or(arms));
    }
    return Outcome.ACCEPTED;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    if (!(step instanceof OrStep<?> connective)) {
      return false;
    }
    // Prefix traversal operands with their arity so sibling and nested children cannot collide.
    encoder.appendToken("children", Integer.toString(connective.getLocalChildren().size()));
    return true;
  }
}
