package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter.YTDBHasLabelStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GraphStepStrategyTest extends GraphBaseTest {

  /**
   * Isolate the native {@code YTDBGraphStepStrategy} has-container fold under test. With the
   * Gremlin-to-MATCH translator on (its default), a fully recognised {@code g.V().has(...)} shape is
   * rewritten to a single boundary step before the native fold runs, so this test would see one
   * {@code YTDBMatchPlanStep} rather than the folded {@code YTDBGraphStep} it asserts on. Disabling
   * the translator exercises the native folding path this test targets.
   */
  @Before
  public void disableGremlinToMatchTranslator() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    tx.getDatabaseSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
  }

  @Test
  public void shouldFoldInHasContainers() {
    var g = graph.traversal();
    ////
    var traversal = g.V().has("name", "marko").asAdmin();
    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    Assert.assertEquals(2, traversal.getSteps().size());
    Assert.assertEquals(HasStep.class, traversal.getEndStep().getClass());
    traversal.applyStrategies();
    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    Assert.assertEquals(1, traversal.getSteps().size());
    Assert.assertEquals(YTDBGraphStep.class, traversal.getStartStep().getClass());
    Assert.assertEquals(YTDBGraphStep.class, traversal.getEndStep().getClass());
    assertEquals(1, ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().size());
    assertEquals(
        "name",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getKey());
    assertEquals(
        "marko",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getValue());
    ////
    traversal = g.V().has("name", "marko").has("age", P.gt(20)).asAdmin();
    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    traversal.applyStrategies();

    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    Assert.assertEquals(1, traversal.getSteps().size());
    Assert.assertEquals(YTDBGraphStep.class, traversal.getStartStep().getClass());
    assertEquals(2, ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().size());
    ////
    traversal = g.V().has("name", "marko").out().has("name", "daniel").asAdmin();

    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    traversal.applyStrategies();

    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    Assert.assertEquals(3, traversal.getSteps().size());
    Assert.assertEquals(YTDBGraphStep.class, traversal.getStartStep().getClass());
    assertEquals(1, ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().size());
    assertEquals(
        "name",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getKey());
    assertEquals(
        "marko",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getValue());
    Assert.assertEquals(HasStep.class, traversal.getEndStep().getClass());
  }

  /** At the source, only the Employee record matches both Employee and User labels. */
  @Test
  public void chainedLabelsAtSourceKeepTheirIntersection() {
    var fixture = createLabelFixture();
    enableDefaultTranslator();

    assertEquals(Set.of(fixture.employeeId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V().hasLabel("Employee").hasLabel("User").id().toSet());
    assertEquals(Set.of(fixture.employeeId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V(fixture.employeeId(), fixture.contractorId())
            .hasLabel("Employee").hasLabel("User").id().toSet());
  }

  /** After limit, both labels are required and a Contractor must not pass as User alone. */
  @Test
  public void chainedLabelsAfterLimitRequireBothLabels() {
    var fixture = createLabelFixture();
    enableDefaultTranslator();

    assertEquals(Set.of(fixture.employeeId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V().limit(10).hasLabel("Employee").hasLabel("User").id().toSet());
  }

  /** After navigation, the Member edge reaches both siblings but only Employee matches both. */
  @Test
  public void chainedLabelsAfterNavigationRequireBothLabels() {
    var fixture = createLabelFixture();
    enableDefaultTranslator();

    assertEquals(Set.of(fixture.employeeId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V(fixture.groupId()).out("Member")
            .hasLabel("Employee").hasLabel("User").id().toSet());
  }

  /** A shared property between label calls does not let Contractor pass the Employee filter. */
  @Test
  public void chainedLabelsSeparatedByPropertyStillRequireBothLabels() {
    var fixture = createLabelFixture();
    enableDefaultTranslator();

    assertEquals(Set.of(fixture.employeeId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V().limit(10).hasLabel("Employee").has("tag", "shared")
            .hasLabel("User").id().toSet());
  }

  /** One multi-label call is an alternative, so both sibling vertices pass after limit. */
  @Test
  public void multiLabelCallAfterLimitAcceptsEitherLabel() {
    var fixture = createLabelFixture();
    enableDefaultTranslator();

    assertEquals(Set.of(fixture.employeeId(), fixture.contractorId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V().limit(10).hasLabel("Employee", "Contractor").id().toSet());
  }

  /** In exact mode two different labels intersect to empty, but identical labels keep Employee. */
  @Test
  public void chainedLabelsAfterLimitInExactModeRequireBothLabels() {
    var fixture = createLabelFixture();
    enableDefaultTranslator();

    assertEquals(Set.of(), graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, false)
        .V().limit(10).hasLabel("Employee").hasLabel("User").id().toSet());
    assertEquals(Set.of(fixture.employeeId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, false)
            .V().limit(10).hasLabel("Employee").hasLabel("Employee").id().toSet());
  }

  /** A match pattern filters members after a hop and binds only the matching Employee ID. */
  @Test
  public void chainedLabelsInsideMatchAfterNavigationRequireBothLabels() {
    var fixture = createLabelFixture();
    enableDefaultTranslator();

    assertEquals(Set.of(fixture.employeeId(), fixture.contractorId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V(fixture.groupId())
            .match(__.as("g").out("Member").id().as("m"))
            .select("m").toSet());
    assertEquals(Set.of(fixture.employeeId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V(fixture.groupId())
            .match(__.as("g").out("Member")
                .hasLabel("Employee").hasLabel("User").id().as("m"))
            .select("m").toSet());
  }

  /** Sorting after the limit and label chain cannot restore the excluded Contractor. */
  @Test
  public void chainedLabelsBeforeSortAfterLimitRequireBothLabels() {
    var fixture = createLabelFixture();
    enableDefaultTranslator();

    assertEquals(List.of(fixture.employeeId()),
        graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
            .V().limit(10).hasLabel("Employee").hasLabel("User")
            .order().by("name").id().toList());
  }

  /** The native rewrite holds two single-predicate steps, not one OR-combined label step. */
  @Test
  public void chainedLabelsAfterLimitProduceOneStepPerContainer() {
    createLabelFixture();
    enableDefaultTranslator();
    var traversal = graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, true)
        .V().limit(10).hasLabel("Employee").hasLabel("User").asAdmin();

    traversal.applyStrategies();

    var labelSteps = traversal.getSteps().stream()
        .filter(YTDBHasLabelStep.class::isInstance)
        .map(step -> (YTDBHasLabelStep<?>) step)
        .toList();
    assertEquals(2, labelSteps.size());
    assertEquals(List.of("Employee", "User"), labelSteps.stream()
        .map(step -> {
          assertEquals(1, step.getPredicates().size());
          return step.getPredicates().getFirst().getValue();
        }).toList());
  }

  /** A replaced label filter keeps both aliases for select in polymorphic and exact modes. */
  @Test
  public void replacedLabelFilterKeepsAllAliasesForSelect() {
    var fixture = createLabelFixture();
    for (var polymorphic : List.of(true, false)) {
      var selected = graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, polymorphic)
          .V(fixture.groupId()).out("Member").hasLabel("Employee")
          .as("member", "alias").select("member", "alias").by(T.id).toList();
      assertEquals(List.of(Map.of(
          "member", fixture.employeeId(), "alias", fixture.employeeId())), selected);
      assertEquals(polymorphic ? Set.of(fixture.employeeId(), fixture.contractorId()) : Set.of(),
          graph.traversal().with(YTDBQueryConfigParam.polymorphicQuery, polymorphic)
              .V(fixture.groupId()).out("Member").hasLabel("User").as("member")
              .select("member").by(T.id).toSet());
    }
  }

  /** Multiple containers put aliases only on the last replacement and retain them in paths. */
  @Test
  public void replacedLabelIntersectionKeepsAliasesOnLastStepForPath() {
    var fixture = createLabelFixture();
    for (var polymorphic : List.of(true, false)) {
      var traversal = graph.traversal()
          .with(YTDBQueryConfigParam.polymorphicQuery, polymorphic)
          .V(fixture.groupId()).out("Member").hasLabel("Employee").hasLabel("User")
          .as("member", "alias").path().by(T.id).asAdmin();
      var hasStep = (HasStep<?>) traversal.getSteps().stream()
          .filter(HasStep.class::isInstance).findFirst().orElseThrow();
      assertEquals(2, hasStep.getHasContainers().size());
      YTDBGraphStepStrategy.instance().apply(traversal);
      var replacements = traversal.getSteps().stream()
          .filter(YTDBHasLabelStep.class::isInstance).toList();
      assertEquals(2, replacements.size());
      assertEquals(Set.of(), replacements.getFirst().getLabels());
      assertEquals(Set.of("member", "alias"), replacements.getLast().getLabels());
      var paths = traversal.toList();
      assertEquals(polymorphic ? 1 : 0, paths.size());
      for (var path : paths) {
        assertEquals(fixture.employeeId(), path.get("member"));
        assertEquals(fixture.employeeId(), path.get("alias"));
        assertEquals(List.of(fixture.groupId(), fixture.employeeId()), path.objects());
      }
    }
  }

  /** A mixed filter retains aliases on the property step and still rejects other members. */
  @Test
  public void mixedLabelAndPropertyFilterKeepsAliasesOnOriginalStep() {
    var fixture = createLabelFixture();
    for (var polymorphic : List.of(true, false)) {
      var traversal = graph.traversal()
          .with(YTDBQueryConfigParam.polymorphicQuery, polymorphic)
          .V(fixture.groupId()).out("Member").hasLabel("User").has("name", "Alice")
          .as("member", "alias").select("member", "alias").by(T.id).asAdmin();
      YTDBGraphStepStrategy.instance().apply(traversal);
      var propertyStep = traversal.getSteps().stream().filter(HasStep.class::isInstance)
          .findFirst().orElseThrow();
      assertEquals(Set.of("member", "alias"), propertyStep.getLabels());
      var labelStep = traversal.getSteps().stream().filter(YTDBHasLabelStep.class::isInstance)
          .findFirst().orElseThrow();
      assertEquals(Set.of(), labelStep.getLabels());
      assertEquals(polymorphic ? List.of(Map.of(
          "member", fixture.employeeId(), "alias", fixture.employeeId())) : List.of(),
          traversal.toList());
    }
  }

  private LabelFixture createLabelFixture() {
    var schema = ((YTDBTransaction) graph.tx()).getDatabaseSession().getSchema();
    var user = schema.createClass("User", schema.getClass("V"));
    schema.createClass("Employee", user);
    schema.createClass("Contractor", user);
    var employee = graph.addVertex(T.label, "Employee", "name", "Alice", "tag", "shared");
    var contractor = graph.addVertex(T.label, "Contractor", "name", "Bob", "tag", "shared");
    var group = graph.addVertex(T.label, "V", "name", "Group");
    group.addEdge("Member", employee);
    group.addEdge("Member", contractor);
    graph.tx().commit();
    return new LabelFixture(employee.id(), contractor.id(), group.id());
  }

  /** The older fold test turns translation off. These cases run with the production default. */
  private void enableDefaultTranslator() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    tx.getDatabaseSession().getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
  }

  private record LabelFixture(Object employeeId, Object contractorId, Object groupId) {
  }
}
