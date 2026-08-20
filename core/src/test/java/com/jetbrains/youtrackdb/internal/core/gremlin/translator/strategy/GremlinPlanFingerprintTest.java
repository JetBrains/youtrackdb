package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
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

    assertThat(GremlinPlanFingerprint.fingerprint(required))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(optional));
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

    assertThat(GremlinPlanFingerprint.fingerprint(fpKnows))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(fpLikes));
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

    assertThat(GremlinPlanFingerprint.fingerprint(base))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(patterns));
  }

  /** Same for {@code returnPathElements}. */
  @Test
  public void returnPathElementsFlag_alone_distinguishesFingerprint() {
    var base =
        MatchPlanInputs.builder(new Pattern()).returnPathElements(false).build();
    var pathElements =
        MatchPlanInputs.builder(new Pattern()).returnPathElements(true).build();

    assertThat(GremlinPlanFingerprint.fingerprint(base))
        .isNotEqualTo(GremlinPlanFingerprint.fingerprint(pathElements));
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
    var inputs =
        MatchPlanInputs.builder(new Pattern())
            .returnItems(statement.getReturnItems())
            .returnAliases(statement.getReturnAliases())
            .returnNestedProjections(statement.getReturnNestedProjections())
            .build();
    return GremlinPlanFingerprint.fingerprint(inputs);
  }

  /**
   * Builds a fingerprint from the statement's clause fields (expressions, unwind, return modes)
   * without running the planner's pattern-build pass — enough to pin clause-level distinctions.
   */
  private static String fingerprintFromStatementClauses(SQLMatchStatement statement) {
    var inputs =
        MatchPlanInputs.builder(new Pattern())
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
    return GremlinPlanFingerprint.fingerprint(inputs);
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
