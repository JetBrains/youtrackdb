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
 * Named Gremlin traversal shapes measured by {@link LdbcGremlinTranslatorBenchmark} on the LDBC
 * schema, plus engagement checks ({@link #requireTranslated}, {@link #requireNotTranslated}) that
 * pin whether a shape compiles through the translator.
 *
 * <p>The shapes are named methods rather than inline {@code @Benchmark} expressions so the harness
 * and {@link LdbcGremlinShapeTranslationTest} assert over byte-identical traversals.
 *
 * <h2>How the numbers are read in CI</h2>
 *
 * <p>The {@code ldbc-jmh-compare} workflow compares <b>head against the fork-point with
 * {@code develop}</b>, with {@code translatorEnabled=true} on both sides — the production Gremlin
 * path. A PR delta on a translating shape therefore means "this branch changed MATCH-plan
 * throughput vs {@code develop}", the same framing as the SQL IC/IS benchmarks beside it. The
 * workflow passes {@code -p translatorEnabled=true}; local runs should do the same when reproducing
 * a PR comment.
 *
 * <h2>Optional axis: translator on vs off (same commit)</h2>
 *
 * <p>{@link LdbcGremlinTranslatorBenchmark} still exposes {@code translatorEnabled} as a JMH
 * {@code @Param} for a secondary A/B: MATCH against the native pipeline on one tree. Run both arms
 * with {@code -Djmh.args=".*LdbcGremlinTranslator.*"} and no {@code -p} filter, or pass {@code
 * --gremlin-arms both} to {@code jmh-compare.py}. That axis is for recogniser and kill-switch work,
 * not for the default PR regression comment.
 *
 * <h2>Two shape groups</h2>
 *
 * <p><b>Translating shapes</b> carry one {@link AbstractMatchPlanStep} with the kill-switch on. In
 * CI (translator on both sides) their head-vs-base delta is a regression on the MATCH pipeline. In
 * the optional on/off A/B, the same shape's delta is MATCH vs native on one commit.
 *
 * <p><b>Declining shapes</b> carry no boundary step even with the kill-switch on — the translator
 * walks and declines, or vetoes before the walk ({@code RepeatDeclineStrategy}). Both CI arms
 * therefore run native; head-vs-base measures native-pipeline or decline-overhead changes, not MATCH
 * plan improvements. {@link #requireNotTranslated} on both arms in
 * {@link LdbcGremlinShapeTranslationTest} is the tripwire when a recogniser starts claiming the
 * shape.
 *
 * <h2>Relation to the SQL IC / IS benchmarks</h2>
 *
 * <p><b>Throughput is not comparable across SQL and Gremlin rows</b> — different entry points. The
 * LDBC-derived shapes below are named after the query they echo; per-method Javadoc carries the SQL
 * for auditable correspondence, not a claim that timings can be divided.
 *
 * <p>Three of the twenty-one queries in {@code ldbc-queries/} use {@code LET}; most of the rest are
 * plain MATCH patterns. What blocks them is the recogniser set rather than Gremlin's expressiveness,
 * and the reduced-projection shapes below name the specific gate they hit. The label gate the
 * harness was first written against has since closed: a user {@code as(...)} label parked on a
 * filter step now binds, so IS1's full projection translates ({@link #is1FullProfile}) and the
 * narrower {@link #is1PersonCityProfile} stays beside it as the shorter projection rather than as
 * its translating half. IS3's edge-alias projection ({@link #is3FriendsWithDates}) instead
 * <em>declines</em>: an {@code as(k)} label on {@code outE(L)} would bind to the edge-as-node
 * vertex alias, so {@code select("k")} would return the target vertex rather than the edge, so the
 * shape falls back to native on both arms.
 *
 * <p>{@link #personByRid} is a bare {@code g.V(rid)} point-lookup and now <em>declines</em>: a
 * RID-bearing single-node walk with no hop sets {@code cacheEligible=false} in the translator, so
 * translator-on would compile an uncached MATCH plan every call where translator-off ran no query
 * at all — a net loss with no join to optimise. The translator declines it so both arms run
 * natively; a RID start FOLLOWED by a hop still translates, since the join is where MATCH can win.
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

  /** Vertex class the LDBC schema gives the {@code name} property the anti-join shape filters on. */
  public static final String PLACE_LABEL = "Place";

  private GremlinTraversalShapes() {
  }

  // ---------------------------------------------------------------------------------------------
  // Translating shapes: boundary step with kill-switch on. CI delta = head vs base (translator on).
  // Optional on/off A/B on one commit = MATCH vs native.
  // ---------------------------------------------------------------------------------------------

  /**
   * A bare {@code g.V(rid)} by-id lookup with nothing after it — a DECLINING shape.
   *
   * <p>Held apart from the other walk shapes because it is the only one where the native path issues
   * no query: TinkerPop resolves the id straight to a record. Translating it would compile an
   * uncached MATCH plan every call ({@code cacheEligible=false}) for no join to optimise, so the
   * translator declines the bare lookup and both arms run natively. The RID has to be resolved from
   * an LDBC {@code id} long before the call, which is why the benchmark state builds a RID pool at
   * trial setup.
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
   * <p>The list-shaping terminator the boundary step drains through a {@code ListShapingOp}, and
   * the newest recogniser this harness covers. Its assertion doubles as a classpath check: a
   * {@code youtrackdb-core} without the {@code FoldStep} registry entry declines the shape, both
   * arms measure the native path, and the two numbers coincide instead of failing loudly.
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
   * p.gender, p.creationDate}. This shape keeps the two-class join and the city column, reached
   * through {@code valueMap} on the boundary alias with no {@code select} and no user label.
   * {@link #is1FullProfile} adds the person-side columns through {@code select("p", "city")}, so
   * the pair prices a narrow projection against a wide one over the same join. Both translate.
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
   * hop came from — a back-reference to a mid-walk alias. An earlier note here explained that
   * choice by a start-step label being unresolvable; that is the gate {@link #is1FullProfile}
   * describes, it has since closed, and no shape in this class measures the start-alias variant, so
   * the explanation is withdrawn rather than restated.
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
   * Shape 11 — IS1's full projection through {@code select("p", "city")}.
   *
   * <p>Same SQL as {@link #is1PersonCityProfile}, with the person-side columns added.
   * {@code select("p", "city")} needs both user {@code as(...)} labels to resolve to pattern
   * aliases, and the harness first measured this shape declining because they did not. The reason
   * recorded here at the time — that {@code as("p")} sits on the start step — was wrong twice over:
   * the label is authored on the {@code has("id", …)} step, and what declined was the {@code has}
   * recogniser binding no labels at all. It binds them now, so the shape translates and its
   * previously recorded decline-path number no longer describes it.
   *
   * <p>Two person-side columns rather than IS1's seven. The projection width is not what the shape
   * measures, and a shorter one keeps the hand-computed expected value readable.
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

  /**
   * Shape 13 — a person's friends who are <em>not</em> located in a given place: a hash anti-join.
   *
   * <p>Echoes the shape of LDBC IC-style negation and IS4/IS2's {@code NOT} projections without
   * their declined steps: {@code MATCH {class: Person, where: (id = :personId)}.out('KNOWS'){as:
   * friend} RETURN friend.firstName} restricted to friends for whom no {@code
   * .out('IS_LOCATED_IN'){where: (name = :placeName)}} row exists. Gremlin spells the exclusion
   * {@code not(__.out(IS_LOCATED_IN).has(name, placeName))}, an edge-bearing {@code not(...)}.
   *
   * <p><b>Why MATCH wins.</b> This is the one shape whose optimisation none of the others reach:
   * the edge-bearing {@code not(...)} compiles to a detached NOT {@code MATCH} expression that the
   * planner runs as a <b>hash anti-join</b> — build a hash set of the friends who <em>are</em>
   * located in the place once, then probe. The native TinkerPop pipeline instead re-walks {@code
   * IS_LOCATED_IN} and filters {@code name} per candidate friend, a nested-loop anti-join whose
   * cost is quadratic in the friend count. {@code NotStepRecogniser}'s edge-bearing branch is what
   * makes the boundary step appear; a {@code youtrackdb-core} whose recogniser predates it declines
   * the shape and both arms run natively.
   */
  public static YTDBGraphTraversal<Vertex, String> friendsNotLocatedInPlace(
      YTDBGraphTraversalSource g, long personId, String placeName) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .not(__.out(IS_LOCATED_IN_LABEL).has("name", placeName))
        .values("firstName");
  }

  /**
   * Shape 14 — a mutual-friend triangle closed by a {@code where()} back-reference to the start.
   *
   * <p>Echoes the cyclic patterns the LDBC social queries lean on: three {@code KNOWS} hops that
   * must return to the person they started from, i.e. {@code MATCH {class: Person, as: start,
   * where: (id = :personId)}.out('KNOWS').out('KNOWS').out('KNOWS'){where: (@rid =
   * $matched.start.@rid)}}. Gremlin spells the closure {@code where(P.eq("start"))} against the
   * start-step label.
   *
   * <p><b>Why MATCH wins.</b> The cycle constraint is a self-join back onto a pattern alias, which
   * MATCH schedules <b>topologically</b>: it enters from the bound {@code start} alias at both ends
   * of the pattern and enumerates only the closing paths, rather than materialising every three-hop
   * path and discarding the ones that do not return. The native pipeline has no notion of the
   * closing alias until the {@code where()} step runs, so it expands the full three-hop frontier
   * first and filters last. {@code WherePredicateStepRecogniser} translates the {@code
   * where(P.eq(label))} closure via a {@code $matched.start} accessor; the start-step {@code
   * as("start")} label binds through the {@code has} recogniser (the same label gate {@link
   * #is1FullProfile} relies on).
   */
  public static YTDBGraphTraversal<Vertex, String> mutualFriendTriangle(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId).as("start")
        .out(KNOWS_LABEL)
        .out(KNOWS_LABEL)
        .out(KNOWS_LABEL)
        .where(P.eq("start"))
        .values("firstName");
  }

  // ---------------------------------------------------------------------------------------------
  // Declining shapes: no boundary step with kill-switch on. CI: both sides native — head-vs-base
  // is not a MATCH win/loss. Optional on/off A/B prices decline overhead on one commit.
  // ---------------------------------------------------------------------------------------------

  /**
   * Declining shape 0 — IS3 whole: edge and friend columns through {@code select("k", "friend")}.
   *
   * <p>Same SQL as {@link #is3FriendsWithNames}, but projects the friendship edge's {@code
   * creationDate} via a user {@code as("k")} label on {@code outE(KNOWS)}. That edge {@code as(k)}
   * label declines: it would bind to the edge-as-node <em>vertex</em> alias, so {@code
   * select("k").by("creationDate")} would read the target vertex rather than the friendship edge.
   * Runtime-incorrect, so the whole shape falls back to native on both arms.
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
   * Declining shape 1 — {@code order().by(key)} with {@code range} as paging.
   *
   * <p>{@code ORDER BY firstName SKIP 1 LIMIT 2} is what MATCH would compile this to, and the
   * walker refuses it: a slice sitting behind a captured {@code ORDER BY} declines, so the whole
   * traversal runs natively on both arms. The shape was written as a translating one and measured
   * as such at the time; the slice-after-sort decline widened underneath it afterwards, which is
   * why the group changed rather than the spelling.
   *
   * <p>The spelling is kept as authored rather than nudged into something translatable. Paging a
   * sorted hop is what the LDBC read queries actually ask for. The both-arms assertion fails the
   * day a recogniser claims a slice after a sort and signals that CI baselines need re-reading.
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
   * Declining shape 2 — IC1's variable-depth {@code KNOWS} walk, declined by design.
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
   * optional: true}}. MATCH's {@code optional: true} is a Phase 2 capability, and Gremlin's
   * {@code optional(...)} compiles to a branch step with no recogniser, so the walk declines
   * there. The shape reserves the baseline for the day Phase 2 lands the optional hop.
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
