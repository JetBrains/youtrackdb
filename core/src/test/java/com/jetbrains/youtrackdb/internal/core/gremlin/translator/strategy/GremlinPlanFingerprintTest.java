package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AliasPropertyPresence;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.TailListShapingOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.Test;

/**
 * Low-level fingerprint regression tests for planner-visible distinctions that are easier to pin on
 * raw {@link MatchPlanInputs} than through a full Gremlin traversal. Covers fields that Gremlin
 * currently leaves at defaults but the planner still reads — so a future front-end setting them
 * cannot collide with today's empty/default shapes.
 */
public class GremlinPlanFingerprintTest {

  /**
   * Nested projections are carried in {@link MatchPlanInputs#returnNestedProjections()} and affect
   * the planner's output shaping. Two MATCH inputs that differ only in the nested projection must
   * not share a Gremlin plan-cache fingerprint.
   */
  @Test
  public void nestedProjections_distinguishFingerprint() {
    var withName = parse("MATCH {class: V, as: a} RETURN a:{name}");
    var withSurname = parse("MATCH {class: V, as: a} RETURN a:{surname}");

    var fpName = fingerprintForProjection(withName);
    var fpSurname = fingerprintForProjection(withSurname);

    assertThat(fpName).isNotEqualTo(fpSurname);
  }

  /**
   * A plain return and a nested-projection return must not collide even when the return item itself
   * is the same identifier.
   */
  @Test
  public void plainReturn_and_nestedProjection_distinguishFingerprint() {
    var plain = parse("MATCH {class: V, as: a} RETURN a");
    var nested = parse("MATCH {class: V, as: a} RETURN a:{name}");

    var fpPlain = fingerprintForProjection(plain);
    var fpNested = fingerprintForProjection(nested);

    assertThat(fpPlain).isNotEqualTo(fpNested);
  }

  /**
   * {@code PatternNode.optional} changes whether a missing hop drops the row. Required vs optional
   * on the same alias topology must not share a fingerprint.
   */
  @Test
  public void optionalNode_distinguishesFingerprint() {
    var required = patternWithOptional("friend", false);
    var optional = patternWithOptional("friend", true);

    assertThat(GremlinPlanFingerprint.fingerprint(required, ResultShaping.NONE))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(optional, ResultShaping.NONE));
  }

  /**
   * Positive {@code matchExpressions} are planner inputs even when the pattern graph is empty.
   * Two different expression lists must not collide.
   */
  @Test
  public void matchExpressions_distinguishFingerprint() {
    var knows = parse("MATCH {class: V, as: a}.out('knows'){as: b} RETURN a");
    var likes = parse("MATCH {class: V, as: a}.out('likes'){as: b} RETURN a");

    var fpKnows =
        MatchPlanInputs.builder(new Pattern())
            .matchExpressions(knows.getMatchExpressions())
            .returnItems(knows.getReturnItems())
            .returnAliases(knows.getReturnAliases())
            .returnNestedProjections(knows.getReturnNestedProjections())
            .build();
    var fpLikes =
        MatchPlanInputs.builder(new Pattern())
            .matchExpressions(likes.getMatchExpressions())
            .returnItems(likes.getReturnItems())
            .returnAliases(likes.getReturnAliases())
            .returnNestedProjections(likes.getReturnNestedProjections())
            .build();

    assertThat(GremlinPlanFingerprint.fingerprint(fpKnows, ResultShaping.NONE))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(fpLikes, ResultShaping.NONE));
  }

  /** UNWIND expands collections; present vs absent must not share a fingerprint. */
  @Test
  public void unwind_distinguishesFingerprint() {
    var withUnwind = parse("MATCH {class: V, as: a} RETURN a.name AS x UNWIND x");
    var withoutUnwind = parse("MATCH {class: V, as: a} RETURN a.name AS x");

    assertThat(fingerprintFromStatementClauses(withUnwind))
        .isNotEqualTo(fingerprintFromStatementClauses(withoutUnwind));
  }

  /**
   * Return-mode flags select different planner projection paths. {@code RETURN a} and {@code
   * RETURN $paths} must not share a fingerprint.
   */
  @Test
  public void returnPathsMode_distinguishesFingerprint() {
    var items = parse("MATCH {class: V, as: a} RETURN a");
    var paths = parse("MATCH {class: V, as: a} RETURN $paths");

    assertThat(fingerprintFromStatementClauses(items))
        .isNotEqualTo(fingerprintFromStatementClauses(paths));
  }

  /** {@code RETURN $elements} vs plain item return must not collide. */
  @Test
  public void returnElementsMode_distinguishesFingerprint() {
    var items = parse("MATCH {class: V, as: a} RETURN a");
    var elements = parse("MATCH {class: V, as: a} RETURN $elements");

    assertThat(fingerprintFromStatementClauses(items))
        .isNotEqualTo(fingerprintFromStatementClauses(elements));
  }

  /**
   * Each return-mode flag is independent: flipping only {@code returnPatterns} must change the
   * fingerprint even when return-item lists stay empty/default.
   */
  @Test
  public void returnPatternsFlag_alone_distinguishesFingerprint() {
    var base =
        MatchPlanInputs.builder(new Pattern()).returnPatterns(false).build();
    var patterns =
        MatchPlanInputs.builder(new Pattern()).returnPatterns(true).build();

    assertThat(GremlinPlanFingerprint.fingerprint(base, ResultShaping.NONE))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(patterns, ResultShaping.NONE));
  }

  /** Same for {@code returnPathElements}. */
  @Test
  public void returnPathElementsFlag_alone_distinguishesFingerprint() {
    var base =
        MatchPlanInputs.builder(new Pattern()).returnPathElements(false).build();
    var pathElements =
        MatchPlanInputs.builder(new Pattern()).returnPathElements(true).build();

    assertThat(GremlinPlanFingerprint.fingerprint(base, ResultShaping.NONE))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(pathElements, ResultShaping.NONE));
  }

  /**
   * Sweep: each planner-visible {@link MatchPlanInputs} distinction that the fingerprint claims to
   * cover must actually change the key when flipped in isolation. Catches a future edit that drops a
   * section while leaving the Javadoc claiming full coverage.
   */
  @Test
  public void everyCoveredField_inIsolation_changesFingerprint() {
    assertDistinct(
        "optional node",
        patternWithOptional("n", false),
        patternWithOptional("n", true));

    var knows = parse("MATCH {class: V, as: a}.out('knows'){as: b} RETURN a");
    var likes = parse("MATCH {class: V, as: a}.out('likes'){as: b} RETURN a");
    assertDistinct(
        "matchExpressions",
        MatchPlanInputs.builder(new Pattern())
            .matchExpressions(knows.getMatchExpressions())
            .returnItems(knows.getReturnItems())
            .returnAliases(knows.getReturnAliases())
            .returnNestedProjections(knows.getReturnNestedProjections())
            .build(),
        MatchPlanInputs.builder(new Pattern())
            .matchExpressions(likes.getMatchExpressions())
            .returnItems(likes.getReturnItems())
            .returnAliases(likes.getReturnAliases())
            .returnNestedProjections(likes.getReturnNestedProjections())
            .build());

    var withNot =
        parse("MATCH {class: V, as: a}, NOT {as: a}.out('knows'){as: b} RETURN a");
    var withoutNot = parse("MATCH {class: V, as: a} RETURN a");
    assertDistinct(
        "notMatchExpressions",
        fingerprintInputsFromClauses(withNot),
        fingerprintInputsFromClauses(withoutNot));

    assertDistinct(
        "unwind",
        fingerprintInputsFromClauses(
            parse("MATCH {class: V, as: a} RETURN a.name AS x UNWIND x")),
        fingerprintInputsFromClauses(
            parse("MATCH {class: V, as: a} RETURN a.name AS x")));

    assertDistinct(
        "returnDistinct",
        MatchPlanInputs.builder(new Pattern()).returnDistinct(false).build(),
        MatchPlanInputs.builder(new Pattern()).returnDistinct(true).build());

    assertDistinct(
        "returnElements",
        MatchPlanInputs.builder(new Pattern()).returnElements(false).build(),
        MatchPlanInputs.builder(new Pattern()).returnElements(true).build());
    assertDistinct(
        "returnPaths",
        MatchPlanInputs.builder(new Pattern()).returnPaths(false).build(),
        MatchPlanInputs.builder(new Pattern()).returnPaths(true).build());
    assertDistinct(
        "returnPatterns",
        MatchPlanInputs.builder(new Pattern()).returnPatterns(false).build(),
        MatchPlanInputs.builder(new Pattern()).returnPatterns(true).build());
    assertDistinct(
        "returnPathElements",
        MatchPlanInputs.builder(new Pattern()).returnPathElements(false).build(),
        MatchPlanInputs.builder(new Pattern()).returnPathElements(true).build());

    var plain = parse("MATCH {class: V, as: a} RETURN a");
    var nested = parse("MATCH {class: V, as: a} RETURN a:{name}");
    assertDistinct(
        "returnNestedProjections",
        fingerprintInputsFromProjection(plain),
        fingerprintInputsFromProjection(nested));

    var limit2 = parse("MATCH {class: V, as: a} RETURN a LIMIT 2");
    var limit5 = parse("MATCH {class: V, as: a} RETURN a LIMIT 5");
    assertDistinct(
        "limit",
        fingerprintInputsFromClauses(limit2),
        fingerprintInputsFromClauses(limit5));

    var skip1 = parse("MATCH {class: V, as: a} RETURN a SKIP 1");
    var skip2 = parse("MATCH {class: V, as: a} RETURN a SKIP 2");
    assertDistinct(
        "skip",
        fingerprintInputsFromClauses(skip1),
        fingerprintInputsFromClauses(skip2));

    var personClass =
        MatchPlanInputs.builder(patternWithAlias("a"))
            .aliasClasses(java.util.Map.of("a", "Person"))
            .build();
    var companyClass =
        MatchPlanInputs.builder(patternWithAlias("a"))
            .aliasClasses(java.util.Map.of("a", "Company"))
            .build();
    assertDistinct("aliasClasses", personClass, companyClass);
  }

  private static void assertDistinct(String field, MatchPlanInputs left, MatchPlanInputs right) {
    assertThat(GremlinPlanFingerprint.fingerprint(left, ResultShaping.NONE))
        .as("fingerprints must differ when only %s changes", field)
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(right, ResultShaping.NONE));
  }

  private static Pattern patternWithAlias(String alias) {
    var pattern = new Pattern();
    var node = new PatternNode();
    node.alias = alias;
    pattern.aliasToNode.put(alias, node);
    return pattern;
  }

  /**
   * PF10: the fingerprint buffer must be pre-sized for the widest key the translator builds.
   *
   * <p>This test MEASURES that key rather than assuming it. The shape is a six-column
   * {@code select().by()}: a three-alias pattern, six RETURN columns, six presence entries and
   * six emit columns. The assertions state that the key passes the old pre-size and fits the
   * new one, so a later key section that pushes it past the buffer fails here instead of
   * silently reintroducing the array copies.
   */
  @Test
  public void sixColumnSelectFingerprintFitsThePreSizedBuffer() {
    var statement = parse(
        "MATCH {class: Person, as: p}.out('KNOWS'){as: f}"
            + ".in('HAS_CREATOR'){class: Message, as: m}"
            + " RETURN f.id as personId, f.firstName as firstName,"
            + " f.lastName as lastName, m.id as messageId,"
            + " m.content as messageContent, m.creationDate as messageCreationDate");
    var presences = List.of(
        new AliasPropertyPresence("$g2m_pe_$g2m_v1", "id", "personId"),
        new AliasPropertyPresence("$g2m_pe_$g2m_v1", "firstName", "firstName"),
        new AliasPropertyPresence("$g2m_pe_$g2m_v1", "lastName", "lastName"),
        new AliasPropertyPresence("$g2m_pe_$g2m_v2", "id", "messageId"),
        new AliasPropertyPresence("$g2m_pe_$g2m_v2", "content", "messageContent"),
        new AliasPropertyPresence("$g2m_pe_$g2m_v2", "creationDate", "messageCreationDate"));
    var shaping = ResultShaping.NONE
        .withDropOnAbsent(true)
        .withAliasPropertyPresences(presences)
        .withMapEmitColumnOrder(List.of(
            "personId", "firstName", "lastName",
            "messageId", "messageContent", "messageCreationDate"));

    var key = GremlinPlanFingerprint.fingerprint(
        fingerprintInputsFromProjection(statement), shaping);

    assertThat(key.length())
        .as("the six-column select key runs past the old 256-character pre-size")
        .isGreaterThan(256);
    assertThat(key.length())
        .as("and it must fit the pre-size, or every build pays array copies again")
        .isLessThanOrEqualTo(GremlinPlanFingerprint.INITIAL_KEY_CAPACITY);
  }

  /**
   * {@code ;AP:} encodes each alias-property presence. Two selects that differ only in the
   * presence property key (same entity column and map key) must not share a fingerprint.
   */
  @Test
  public void aliasPropertyPresence_distinguishesFingerprint() {
    var inputs = MatchPlanInputs.builder(new Pattern()).build();
    var byName = ResultShaping.NONE.withAliasPropertyPresences(List.of(
        new AliasPropertyPresence("$g2m_pe_$g2m_v0", "name", "v")));
    var byAge = ResultShaping.NONE.withAliasPropertyPresences(List.of(
        new AliasPropertyPresence("$g2m_pe_$g2m_v0", "age", "v")));

    assertThat(GremlinPlanFingerprint.fingerprint(inputs, byName))
        .as(";AP: must encode the presence property key")
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(inputs, byAge));
  }

  /**
   * {@code ;MO:} encodes map emit column order. Swapping emit column order with identical
   * presence entries must change the fingerprint.
   */
  @Test
  public void mapEmitColumnOrder_distinguishesFingerprint() {
    var inputs = MatchPlanInputs.builder(new Pattern()).build();
    var nameFirst = ResultShaping.NONE.withMapEmitColumnOrder(List.of("name", "age"));
    var ageFirst = ResultShaping.NONE.withMapEmitColumnOrder(List.of("age", "name"));

    assertThat(GremlinPlanFingerprint.fingerprint(inputs, nameFirst))
        .as(";MO: must encode emit column order")
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(inputs, ageFirst));
  }

  /**
   * {@code ;LS:} encodes TailListShapingOp's limit. {@code tail(2)} and {@code tail(5)} share a
   * class name, so omitting the limit would let them collide on one plan-cache entry.
   */
  @Test
  public void tailListShapingLimit_distinguishesFingerprint() {
    var inputs = MatchPlanInputs.builder(new Pattern()).build();
    var tail2 = ResultShaping.NONE.withListShapingOps(List.of(new TailListShapingOp(2)));
    var tail5 = ResultShaping.NONE.withListShapingOps(List.of(new TailListShapingOp(5)));

    assertThat(GremlinPlanFingerprint.fingerprint(inputs, tail2))
        .as(";LS: must encode TailListShapingOp.limit()")
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(inputs, tail5));
  }

  private static MatchPlanInputs patternWithOptional(String alias, boolean optional) {
    var pattern = new Pattern();
    var node = new PatternNode();
    node.alias = alias;
    node.optional = optional;
    pattern.aliasToNode.put(alias, node);
    return MatchPlanInputs.builder(pattern).build();
  }

  private static String fingerprintForProjection(SQLMatchStatement statement) {
    return GremlinPlanFingerprint.fingerprint(
        fingerprintInputsFromProjection(statement), ResultShaping.NONE);
  }

  private static MatchPlanInputs fingerprintInputsFromProjection(SQLMatchStatement statement) {
    return MatchPlanInputs.builder(new Pattern())
        .returnItems(statement.getReturnItems())
        .returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections())
        .build();
  }

  /**
   * Builds inputs from the statement's clause fields (expressions, unwind, return modes) without
   * running the planner's pattern-build pass — enough to pin clause-level distinctions.
   */
  private static String fingerprintFromStatementClauses(SQLMatchStatement statement) {
    return GremlinPlanFingerprint.fingerprint(
        fingerprintInputsFromClauses(statement), ResultShaping.NONE);
  }

  private static MatchPlanInputs fingerprintInputsFromClauses(SQLMatchStatement statement) {
    return MatchPlanInputs.builder(new Pattern())
        .matchExpressions(statement.getMatchExpressions())
        .notMatchExpressions(statement.getNotMatchExpressions())
        .returnItems(statement.getReturnItems())
        .returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections())
        .groupBy(statement.getGroupBy())
        .orderBy(statement.getOrderBy())
        .unwind(statement.getUnwind())
        .limit(statement.getLimit())
        .skip(statement.getSkip())
        .returnDistinct(statement.isReturnDistinct())
        .returnElements(statement.returnsElements())
        .returnPaths(statement.returnsPaths())
        .returnPatterns(statement.returnsPatterns())
        .returnPathElements(statement.returnsPathElements())
        .build();
  }

  private static SQLMatchStatement parse(String query) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(query.getBytes()));
      return (SQLMatchStatement) parser.parse();
    } catch (ParseException e) {
      throw new RuntimeException("Failed to parse MATCH statement: " + query, e);
    }
  }
}
