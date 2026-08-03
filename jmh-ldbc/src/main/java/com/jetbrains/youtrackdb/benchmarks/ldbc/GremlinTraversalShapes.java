package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * The Gremlin traversal shapes the on/off translator benchmark measures, plus the two engagement
 * checks that decide whether a measurement is an arm of that A/B or a mislabelled repeat of the
 * same path.
 *
 * <p>The shapes are named methods here rather than inline expressions in the {@code @Benchmark}
 * bodies so the JMH harness and the in-track JUnit test measure and assert over byte-identical
 * traversals. A benchmark whose shape drifted from the shape the test verified would report an
 * A/B over something nobody checked engages the translator.
 *
 * <h2>Two groups, and why the group a shape sits in is asserted</h2>
 *
 * <p><b>Translating shapes</b> carry one {@link AbstractMatchPlanStep} with the kill-switch on and
 * none with it off, so their A/B measures MATCH against the native pipeline. <b>Declining shapes</b>
 * carry none on either arm. Their A/B still measures something: {@code GremlinToMatchStrategy} runs,
 * walks the traversal and only then declines, so the on-arm pays a per-compile walk the off-arm does
 * not. Nobody on this branch has priced that walk. A recorded zero today is also the baseline the
 * day the shape starts translating — including the case where the new MATCH plan turns out slower
 * than the native pipeline it replaced.
 *
 * <p>Every declining shape asserts {@link #requireNotTranslated} on <em>both</em> arms. A reader
 * cannot then mistake a 0% delta for "MATCH does not help here" when it means "MATCH never ran
 * here", and the assertion is a tripwire: the day a recogniser claims that shape, the test fails and
 * says the recorded baseline needs re-reading.
 *
 * <h2>Relation to the SQL IC / IS benchmarks</h2>
 *
 * <p><b>These numbers are not comparable to this module's IC / IS figures.</b> Those measure SQL
 * MATCH text; these measure a Gremlin traversal with the translator on against the same traversal
 * with it off. The LDBC-derived shapes below are named after the query they follow and carry that
 * query's SQL in their Javadoc, which makes the correspondence auditable — not a claim that the two
 * timings can be divided.
 *
 * <p>Three of the twenty-one queries in {@code ldbc-queries/} use {@code LET}; most of the rest are
 * plain MATCH patterns. What blocks them is the recogniser set rather than Gremlin's expressiveness,
 * and the reduced-projection shapes below name the specific gate they hit: a user {@code as(...)}
 * label on the start step is unresolvable, so IS1's person-side columns decline (see {@link
 * #is1FullProfile}), and a label on the edge step of a folded {@code outE(L)…inV()} hop declines, so
 * IS3's {@code k.creationDate} column declines (see {@link #is3FriendsWithDates}).
 *
 * <p>The load-bearing translating shape is {@link #personByRid}. A RID-bearing walk sets
 * {@code cacheEligible=false} in the translator, so translator-on compiles an uncached MATCH plan
 * where translator-off ran no query at all — the one shape where the translator can be strictly
 * slower than native.
 */
public final class GremlinTraversalShapes {

  /** Vertex class the LDBC schema gives the {@code id} and {@code firstName} properties. */
  public static final String PERSON_LABEL = "Person";

  /** Edge label the LDBC schema uses for the friendship graph. */
  public static final String KNOWS_LABEL = "KNOWS";

  /** Vertex superclass of {@code Post} and {@code Comment}; the IS queries start from it. */
  public static final String MESSAGE_LABEL = "Message";

  /** Edge label from a {@code Message} to its authoring {@code Person}. */
  public static final String HAS_CREATOR_LABEL = "HAS_CREATOR";

  /** Edge label from a {@code Person} to the {@code Place} they live in. */
  public static final String IS_LOCATED_IN_LABEL = "IS_LOCATED_IN";

  private GremlinTraversalShapes() {
  }

  // ---------------------------------------------------------------------------------------------
  // Translating shapes: one boundary step with the kill-switch on, none with it off.
  // ---------------------------------------------------------------------------------------------

  /**
   * Shape 1 — {@code g.V(rid)}: a by-id lookup with nothing after it.
   *
   * <p>Held apart from the other walk shapes because it is the only one where the native path issues
   * no query: TinkerPop resolves the id straight to a record, while the translator compiles a
   * MATCH plan for it. The RID has to be resolved from an LDBC {@code id} long before the call,
   * which is why the benchmark state builds a RID pool at trial setup.
   */
  public static YTDBGraphTraversal<Vertex, Vertex> personByRid(
      YTDBGraphTraversalSource g, Object rid) {
    return g.V(rid);
  }

  /**
   * Shape 2 — the {@code KNOWS} walk under {@code values}: one property value per friend.
   *
   * <p>This is the witness shape for the kill-switch installation check, because every step in it
   * ({@code V}, {@code hasLabel}, {@code has}, {@code out}, {@code values}) has been in the
   * recognised set since well before the terminators, so a missing boundary step on the on-arm
   * means the flag flip did not reach the traversal rather than that the shape declined.
   */
  public static YTDBGraphTraversal<Vertex, String> knowsFirstNames(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .values("firstName");
  }

  /** Shape 3 — the same walk under {@code count()}: one scalar per traversal. */
  public static YTDBGraphTraversal<Vertex, Long> knowsFirstNameCount(
      YTDBGraphTraversalSource g, long personId) {
    return knowsFirstNames(g, personId).count();
  }

  /**
   * Shape 4 — the same walk under {@code fold()}: one list per traversal.
   *
   * <p>The reason the harness lands in Track 11 rather than earlier. {@code fold()} is registered
   * by this track's own {@code FoldStep} recogniser, so until that lands the shape declines and
   * both arms measure the native path — the two numbers coincide, which is itself the signal that
   * the recogniser is not in yet.
   *
   * <p>Kept in the translating group on purpose. Moving it to the declining group would turn its
   * {@link #requireTranslated} assertion green and erase the gate on Track 11 items 2-3.
   */
  public static YTDBGraphTraversal<Vertex, List<String>> knowsFirstNamesFolded(
      YTDBGraphTraversalSource g, long personId) {
    return knowsFirstNames(g, personId).fold();
  }

  /**
   * Shape 5 — IS1 reduced to its city-side columns.
   *
   * <p>IS1 is {@code MATCH {class: Person, as: p, where: (id = :personId)}.out('IS_LOCATED_IN'){as:
   * city} RETURN p.firstName, p.lastName, p.birthday, p.locationIP, p.browserUsed, city.id,
   * p.gender, p.creationDate}. The two-class join and the city column translate; the seven
   * person-side columns need {@code select("p", "city")}, which declines because the {@code as("p")}
   * label sits on the start step. {@link #is1FullProfile} measures the full projection as a
   * declining shape, so the pair prices both halves of IS1.
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Object>> is1PersonCityProfile(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(IS_LOCATED_IN_LABEL)
        .valueMap("id", "name");
  }

  /**
   * Shape 6 — IS3 reduced to its friend-side columns.
   *
   * <p>IS3 is {@code MATCH {class: Person, as: p, where: (id = :personId)}.outE('KNOWS'){as: k}
   * .inV(){as: friend} RETURN friend.id, friend.firstName, friend.lastName, k.creationDate ORDER BY
   * friendshipCreationDate DESC, personId ASC}. Kept here: the {@code outE(KNOWS).inV()} hop, the
   * three friend columns and an {@code ORDER BY}. Dropped: {@code k.creationDate}, because naming
   * the edge with {@code as("k")} declines the folded hop, and the second sort key, because every
   * {@code order().by(...)} modulator resolves against the boundary alias only.
   *
   * <p>The single sort key is {@code firstName} rather than IS3's {@code creationDate} for the same
   * reason — the sort key has to live on the friend, and only the edge carries a date.
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Object>> is3FriendsWithNames(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .outE(KNOWS_LABEL)
        .inV()
        .order().by("firstName")
        .valueMap("id", "firstName", "lastName");
  }

  /**
   * Shape 7 — IS5 whole, the one IS query that translates without losing a column.
   *
   * <p>IS5 is {@code MATCH {class: Message, as: m, where: (id = :messageId)}.out('HAS_CREATOR'){as:
   * author} RETURN author.id, author.firstName, author.lastName}. Every RETURN column comes from the
   * boundary alias, so {@code valueMap} covers the projection and no {@code as(...)} label is needed.
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Object>> is5MessageCreator(
      YTDBGraphTraversalSource g, long messageId) {
    return g.V()
        .hasLabel(MESSAGE_LABEL)
        .has("id", messageId)
        .out(HAS_CREATOR_LABEL)
        .valueMap("id", "firstName", "lastName");
  }

  /**
   * Shape 8 — two chained {@code KNOWS} hops.
   *
   * <p>The first shape where the two engines can disagree on plan shape rather than on overhead: the
   * native pipeline walks adjacency twice with a barrier between the hops, while MATCH enumerates
   * one row per distinct two-hop path. Both emit one result per path, so the answer sets match and
   * only the cost differs.
   */
  public static YTDBGraphTraversal<Vertex, String> twoHopKnows(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .out(KNOWS_LABEL)
        .values("firstName");
  }

  /**
   * Shape 9 — a two-hop walk with a property filter on the intermediate hop.
   *
   * <p>Where index selection should tell: {@code Person.firstName} carries a {@code NOTUNIQUE}
   * index, so a MATCH planner is free to enter the pattern from the filtered alias and intersect
   * back, while the native pipeline can only expand the first hop and filter the result.
   */
  public static YTDBGraphTraversal<Vertex, String> knowsFilteredByFriendFirstName(
      YTDBGraphTraversalSource g, long personId, String friendFirstName) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .has("firstName", friendFirstName)
        .out(KNOWS_LABEL)
        .values("firstName");
  }

  /**
   * Shape 10 — three hops with a {@code where()} back-reference to the first hop's alias.
   *
   * <p>{@code where(P.neq("f"))} drops the paths whose third hop returns to the friend the second
   * hop came from. The back-reference is to a mid-walk alias, not to the start alias: {@code
   * where(P.neq(startLabel))} declines, because a user label on the start step does not resolve to a
   * pattern alias.
   */
  public static YTDBGraphTraversal<Vertex, String> threeHopKnowsExcludingIntermediate(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL).as("f")
        .out(KNOWS_LABEL)
        .out(KNOWS_LABEL)
        .where(P.neq("f"))
        .values("firstName");
  }

  /**
   * Shape 11 — {@code order().by(key)} with {@code range} as paging.
   *
   * <p>{@code ORDER BY firstName SKIP 1 LIMIT 2} against a native sort of the whole hop followed by
   * a stream slice. The {@code order()} comes before the {@code values(...)} projection on purpose:
   * spelling it the other way round ({@code values(key).order().range(a, b)}) mistranslates today —
   * the two arms return different rows — so this shape would measure a wrong answer.
   */
  public static YTDBGraphTraversal<Vertex, String> knowsOrderedPage(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .order().by("firstName")
        .range(1, 3)
        .values("firstName");
  }

  /**
   * Shape 12 — {@code groupCount().by(key)}: {@code GROUP BY lastName} with {@code count(*)}.
   *
   * <p>The aggregate pushes into the MATCH plan, so the on-arm returns a grouped result set while
   * the off-arm builds the map in the traverser pipeline.
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Long>> knowsGroupCountByLastName(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .groupCount().by("lastName");
  }

  // ---------------------------------------------------------------------------------------------
  // Declining shapes: no boundary step on either arm. The A/B prices the decline path and reserves
  // a baseline for the day the shape starts translating. Each asserts requireNotTranslated on both
  // arms, so it cannot drift into the translating group unnoticed.
  // ---------------------------------------------------------------------------------------------

  /**
   * Declining shape 1 — IS1's full projection, blocked by the start-step label.
   *
   * <p>Same SQL as {@link #is1PersonCityProfile}. {@code select("p", "city")} needs both aliases to
   * resolve, and {@code as("p")} sits on the start step, whose user label is not registered as a
   * pattern alias — so {@code SelectStepRecogniser} declines on the unresolved label and the whole
   * walk goes native. Registering start-step labels would move this shape into the translating group
   * and fail its assertion, which is the intended signal.
   *
   * <p>Two person-side columns rather than IS1's seven: the gate is the label, not the column count,
   * and a shorter projection keeps the hand-computed expected value readable.
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> is1FullProfile(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId).as("p")
        .out(IS_LOCATED_IN_LABEL).as("city")
        .select("p", "city").by("firstName").by("id");
  }

  /**
   * Declining shape 2 — IS3 whole, blocked by the edge alias.
   *
   * <p>Same SQL as {@link #is3FriendsWithNames}. {@code VertexStepRecogniser} folds {@code
   * outE(L).inV()} into one hop, and a user {@code as(...)} label on the edge step of that fold has
   * no pattern alias to bind to, so the walk declines. The edge property can be <em>filtered</em>
   * — {@code outE(L).has(prop, P).inV()} translates — but not projected, and IS3 projects it.
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> is3FriendsWithDates(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId).as("p")
        .outE(KNOWS_LABEL).as("k")
        .inV().as("friend")
        .order().by("firstName")
        .select("k", "friend").by("creationDate").by("firstName");
  }

  /**
   * Declining shape 3 — IC1's variable-depth {@code KNOWS} walk, declined by design.
   *
   * <p>IC1 walks {@code KNOWS} to depth three. {@code RepeatDeclineStrategy} vetoes any traversal
   * whose subtree carries a {@code RepeatStep}, because {@code RepeatUnrollStrategy} rewrites the
   * repeat into a chain the walker cannot tell from a hand-written one, and MATCH enumerates paths
   * where the native barriers merge traversers into bulks. Declining is what makes the TinkerPop
   * feature suite terminate.
   *
   * <p>This is the cheapest decline route in the group: the veto is a marker on the traversal's
   * strategy list, so {@code GremlinToMatchStrategy} exits before walking a single step. The other
   * declining shapes walk until they meet an unregistered step class, so the two prices differ and
   * the group measures both.
   *
   * <p>The same veto covers IS2 and IS6, whose {@code while:} recursion over {@code REPLY_OF} is the
   * same shape over a different edge.
   */
  public static YTDBGraphTraversal<Vertex, String> repeatKnowsToThreeHops(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .repeat(__.out(KNOWS_LABEL))
        .times(3)
        .emit()
        .dedup()
        .values("firstName");
  }

  /**
   * Declining shape 4 — IS4's {@code coalesce(imageFile, content)} projection.
   *
   * <p>IS4 is {@code SELECT coalesce(imageFile, content) as messageContent, creationDate FROM
   * Message WHERE id = :messageId}; IS2 projects the same expression. Gremlin spells it {@code
   * coalesce(__.values(a), __.values(b))}, whose {@code CoalesceStep} has no recogniser, so the walk
   * declines at that step — after {@code V}, {@code hasLabel} and {@code has} have already been
   * recognised, which is the walk-then-decline price this shape measures.
   */
  public static YTDBGraphTraversal<Vertex, String> coalesceMessageContent(
      YTDBGraphTraversalSource g, long messageId) {
    return g.V()
        .hasLabel(MESSAGE_LABEL)
        .has("id", messageId)
        .coalesce(__.values("imageFile"), __.values("content"));
  }

  /**
   * Declining shape 5 — IS7's optional hop.
   *
   * <p>IS7 ends with {@code .out('KNOWS'){as: knowsCheck, where: (@rid = $matched.author.@rid),
   * optional: true}}. MATCH's {@code optional: true} is Phase 2 and out of scope for Track 11;
   * Gremlin's {@code optional(...)} compiles to a branch step with no recogniser, so the walk
   * declines there. The shape reserves the baseline for the day Phase 2 lands the optional hop.
   */
  public static YTDBGraphTraversal<Vertex, String> optionalFriendOfCreator(
      YTDBGraphTraversalSource g, long messageId) {
    return g.V()
        .hasLabel(MESSAGE_LABEL)
        .has("id", messageId)
        .out(HAS_CREATOR_LABEL)
        .optional(__.out(KNOWS_LABEL))
        .values("firstName");
  }

  // ---------------------------------------------------------------------------------------------
  // Engagement checks.
  // ---------------------------------------------------------------------------------------------

  /**
   * Counts boundary steps in a strategy-applied traversal.
   *
   * <p>Keys on {@link AbstractMatchPlanStep} rather than a concrete boundary class so every
   * boundary form counts — the single-plan step and the multi-plan step share the base. {@code
   * core}'s test-side {@code countBoundarySteps} helper is unreachable from here because this
   * module declares no {@code core} test-jar dependency, so the check is restated rather than
   * imported.
   *
   * @param strategyApplied a traversal on which {@code applyStrategies()} has already run;
   *     counting before that always returns zero and would make every caller vacuous
   */
  public static int countBoundarySteps(Traversal.Admin<?, ?> strategyApplied) {
    var count = 0;
    for (var step : strategyApplied.getSteps()) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }

  /**
   * Throws unless the traversal translated to exactly one boundary step.
   *
   * <p>Throws rather than asserting. The JMH launcher in {@code jmh-ldbc/pom.xml} runs {@code java}
   * with no {@code -ea} and no {@code @Fork(jvmArgsAppend)} adds one, while surefire's
   * {@code argLine} does carry it — so a Java {@code assert} here would hold in-track and become a
   * no-op under measurement, which is the one place the check has to hold.
   *
   * @param shape human-readable shape name, so a failure names which shape broke
   * @param strategyApplied a traversal on which {@code applyStrategies()} has already run
   */
  public static void requireTranslated(String shape, Traversal.Admin<?, ?> strategyApplied) {
    var boundaries = countBoundarySteps(strategyApplied);
    if (boundaries != 1) {
      throw new IllegalStateException(
          "translator-on arm: shape '" + shape + "' must carry exactly one AbstractMatchPlanStep"
              + " after applyStrategies(), found " + boundaries
              + ". Either the kill-switch flip did not reach this traversal or the shape declined."
              + " Step list: " + strategyApplied.getSteps());
    }
  }

  /**
   * Throws unless the traversal carries no boundary step over a non-empty native pipeline.
   *
   * <p>The empty-step-list guard is not defensive padding. "No boundary step" is also what a
   * traversal that never built, a closed session, or a degenerate fixture produces, so the absence
   * check alone would pass for the wrong reason. Requiring a non-empty step list leaves absence as
   * the only reading.
   *
   * <p>Two callers with different meanings. On a translating shape this is the off-arm check, and a
   * successful {@link #requireTranslated} on the same shape has already shown the translator does
   * engage there. On a declining shape it is the check for <em>both</em> arms, and a failure on the
   * on-arm means the shape has started translating — see the class Javadoc on why that is a
   * deliberate tripwire rather than good news.
   *
   * @param shape human-readable shape name, so a failure names which shape broke
   * @param strategyApplied a traversal on which {@code applyStrategies()} has already run
   */
  public static void requireNotTranslated(String shape, Traversal.Admin<?, ?> strategyApplied) {
    var steps = strategyApplied.getSteps();
    if (steps.isEmpty()) {
      throw new IllegalStateException(
          "translator-off arm: shape '" + shape + "' produced an empty step list, so the absence"
              + " of a boundary step says nothing about the kill-switch. The traversal never"
              + " built.");
    }
    var boundaries = countBoundarySteps(strategyApplied);
    if (boundaries != 0) {
      throw new IllegalStateException(
          "shape '" + shape
              + "' must carry no AbstractMatchPlanStep after applyStrategies(), found "
              + boundaries
              + ". On a translating shape's off-arm the kill-switch flip did not reach this"
              + " traversal — a session-local override shadowing the global flag is the usual cause."
              + " On a declining shape this means a recogniser now claims the shape, so its recorded"
              + " decline-path baseline needs re-reading. Step list: " + steps);
    }
  }
}
