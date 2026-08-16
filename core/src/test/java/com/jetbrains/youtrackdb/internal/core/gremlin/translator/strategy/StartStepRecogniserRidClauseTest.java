package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchLiteralBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import java.util.List;
import org.junit.Test;

/**
 * Pins the one hand-built AST node the Gremlin translator keeps outside {@code match/builder/}:
 * {@code StartStepRecogniser.buildRidInExpression}, the {@code @rid IN [...]} alias filter behind
 * {@code g.V(id…)} and {@code hasId(id…)}.
 *
 * <h2>What is being pinned, and why it is not just style</h2>
 *
 * Every other hand-assembled node in the translator now routes through the shared builders. This
 * one cannot, because {@code MatchWhereBuilder.in(field, values)} puts a plain property identifier
 * on the left while {@code @rid} has to arrive as a record attribute. The consequence is not a
 * rendering difference: {@code SQLWhereClause.findRidInList()} recognises only the record-attribute
 * form, and it is the entry point
 * {@code MatchExecutionPlanner.promoteStaticRidsFromFilters} reads to lift a static RID list into
 * the alias's pinned-RID slot. Lose the recognition and the planner emits a full class scan with an
 * {@code @rid} post-filter where it could have emitted {@code SELECT FROM [#X:Y, …]} — the same
 * rows, no error, no log line, and O(class size) work for a lookup of known records.
 *
 * <p>So a mechanical conversion of that site to the shared builder is a silent performance
 * regression, which is exactly the failure a row-comparing test cannot see. The negative control
 * below measures it directly rather than asserting it in prose: the {@code MatchWhereBuilder.in}
 * spelling of the identical RID list is invisible to {@code findRidInList}. Without that control
 * the positive case would pass under any left-hand shape that happened to be recognised, and the
 * justification comment on the production method would be an unmeasured claim.
 *
 * <p>The end-to-end half of the pin lives in {@code YTDBQueryMetricsStrategyTest
 * .byIdLookupSurfacesRidFetchPlanWhenTranslatedAndNoPlanWhenNative}, which drives a real
 * {@code g.V(id)} through the translator and asserts the captured plan reaches the record through a
 * {@code FetchFromRidsStep} and never scans the class. This class pins the AST property that one
 * depends on, at unit speed and without a database.
 */
public class StartStepRecogniserRidClauseTest {

  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private static final RecordIdInternal FIRST_RID = new RecordId(25, 7);
  private static final RecordIdInternal SECOND_RID = new RecordId(26, 8);

  /**
   * The clause the recogniser installs as the boundary alias filter is visible to
   * {@code findRidInList}, which is the precondition for the planner's static-RID promotion. Both
   * RIDs are present and in declared order, because the promoted list becomes the fetch target
   * rather than a filter — a dropped element is a row the query never reads.
   */
  @Test
  public void ridInClause_isVisibleToTheStaticRidPromoter() {
    var clause = WHERE.wrap(StartStepRecogniser.buildRidInExpression(
        List.of(FIRST_RID, SECOND_RID)));

    var found = clause.findRidInList();

    assertThat(found)
        .as("the promoter must be able to find the translator's @rid IN clause")
        .isNotNull();
    assertThat(found.getLeft().toString()).isEqualToIgnoringCase("@rid");
    assertThat(found.toString()).contains("#25:7", "#26:8");
  }

  /**
   * The negative control that makes the case above discriminating: the same RID list built through
   * {@code MatchWhereBuilder.in("@rid", …)} — the mechanical conversion an audit of hand-built
   * nodes would otherwise apply to that site — is <em>not</em> visible to the promoter, because the
   * builder's left side is a plain property identifier rather than an
   * {@code SQLRecordAttribute}. Both clauses evaluate to the same rows, which is why only a
   * plan-shape assertion catches the difference.
   */
  @Test
  public void theSharedBuilderSpelling_isNotVisibleToTheStaticRidPromoter() {
    List<SQLExpression> values =
        List.of(MatchLiteralBuilder.toLiteral(FIRST_RID),
            MatchLiteralBuilder.toLiteral(SECOND_RID));

    var clause = WHERE.wrap(WHERE.in("@rid", values));

    assertThat(clause.findRidInList())
        .as("MatchWhereBuilder.in puts a property identifier on the left, which the promoter"
            + " does not recognise as @rid — converting the production site would lose the"
            + " RID fetch silently")
        .isNull();
  }

  /**
   * The clause reaches the planner unwrapped — the {@code SQLInCondition} is the base expression
   * itself, with none of the {@code OrBlock} / {@code AndBlock} / {@code NotBlock} chain the
   * grammar's {@code WhereClause()} production adds around a parsed condition. The promoter's tree
   * search has to bottom out on that leaf, so a future change that wrapped the condition "for
   * parser parity" would break the promotion the same silent way.
   */
  @Test
  public void ridInClause_carriesNoBlockWrapping() {
    var clause = WHERE.wrap(StartStepRecogniser.buildRidInExpression(List.of(FIRST_RID)));

    assertThat(clause.getBaseExpression())
        .isInstanceOf(SQLInCondition.class)
        .isNotInstanceOfAny(SQLOrBlock.class, SQLAndBlock.class, SQLNotBlock.class);
  }

  /**
   * The {@code IN} operator is populated rather than left null. Plan-time paths such as
   * {@code SQLInCondition.supportsBasicCalculation} dereference it, and {@code toString} renders
   * through it, so an unset operator turns an explain or a cost-model read into an NPE well away
   * from the site that built the node.
   */
  @Test
  public void ridInClause_populatesTheInOperator() {
    var condition = (SQLInCondition) StartStepRecogniser.buildRidInExpression(List.of(FIRST_RID));

    assertThat(condition.getOperator()).isNotNull();
    assertThat(condition.toString()).containsIgnoringCase("in");
  }
}
