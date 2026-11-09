package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class T5PreparedShapeProbeTest {
  @Rule
  public TemporaryFolder temporary = TemporaryFolder.builder().assureDeletion().build();

  /** Real selection-phase captures keep milliseconds and scalar types and own mutable inputs. */
  @Test
  public void typedBindingsRejectDateAndTypeDriftAndRetainInputSnapshot() throws Exception {
    try (var db = YourTracks.instance(temporary.newFolder().toString())) {
      db.create("typed", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("typed", "admin", "admin")) {
        g.executeInTx(t -> {
          var captures = new ArrayList<T5PreparedShapeProbe.Declaration>();
          var source = t.withStrategies(new T5PreparedShapeProbe(captures::add),
              new GremlinToMatchStrategy(query -> null));
          java.util.function.Function<Object, T5PreparedShapeProbe.Declaration> capture = value -> {
            source.V().has("binding", value).optional(__.out()).asAdmin().applyStrategies();
            return captures.getLast();
          };
          var input = new java.util.Date(100);
          var first = capture.apply(input);
          var matching = capture.apply(new java.util.Date(100));
          first.requireCut(matching, 2);
          assertEquals(new java.util.Date(100).toString(), new java.util.Date(200).toString());
          var changed = capture.apply(new java.util.Date(200));
          assertThrows(IllegalArgumentException.class, () -> first.requireCut(changed, 2));
          input.setTime(200);
          assertEquals(matching, first);
          var numeric = capture.apply(1L);
          numeric.requireCut(capture.apply(1L), 2);
          var text = capture.apply("1");
          assertThrows(IllegalArgumentException.class, () -> numeric.requireCut(text, 2));
          assertThrows(UnsupportedOperationException.class, () -> first.steps().clear());
          assertThrows(IllegalArgumentException.class, () -> capture.apply(new Object()));
        });
      }
    }
  }

  /** Nested operand trees are copied, unsupported and cyclic values fail closed. */
  @Test
  public void nestedSnapshotOwnsCollectionsArraysPredicatesAndScalarTypes() {
    var captures = new ArrayList<T5PreparedShapeProbe.Declaration>();
    var probe = new T5PreparedShapeProbe(captures::add);
    var values = new ArrayList<Object>(List.of("x", 1, 1L, 1.0, 1.0f, (short) 1,
        (byte) 1, true, 'x', java.math.BigInteger.ONE, java.math.BigDecimal.ONE,
        new java.util.Date(100), Map.of("key", List.of(1L)), new int[] {1, 2}));
    var admin = __.has("value", org.apache.tinkerpop.gremlin.process.traversal.P.within(values))
        .asAdmin();
    probe.apply(admin);
    var first = captures.getLast();
    values.clear();
    assertFalse(first.toString().contains("unsupported"));
    assertThrows(UnsupportedOperationException.class,
        () -> first.steps().getFirst().parts().clear());
    probe.apply(__.has("value", org.apache.tinkerpop.gremlin.process.traversal.P.eq(null))
        .asAdmin());
    probe.apply(__.has("value", org.apache.tinkerpop.gremlin.process.traversal.P.gt(1)
        .and(org.apache.tinkerpop.gremlin.process.traversal.P.lt(3))).asAdmin());
    var cycle = new ArrayList<Object>();
    cycle.add(cycle);
    var cyclicTraversal = __.has("value", "placeholder").asAdmin();
    var has = ((org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder<?,
        ?>) cyclicTraversal.getEndStep()).getHasContainers().getFirst();
    ((org.apache.tinkerpop.gremlin.process.traversal.P) has.getPredicate()).setValue(cycle);
    assertThrows(IllegalArgumentException.class, () -> probe.apply(cyclicTraversal));
    assertThrows(IllegalArgumentException.class,
        () -> probe.apply(__.has("value", new Object()).asAdmin()));
    assertEquals("The old snapshot still contains the original collection", first,
        captures.getFirst());
  }

  /** Production identity-sensitive admissions and probe boundaries agree for real root inputs. */
  @Test
  public void postCardinalityAndPostUnionUseUnwrappedProductionAdmission() throws Exception {
    try (var db = YourTracks.instance(temporary.newFolder().toString())) {
      db.create("units", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("units", "admin", "admin")) {
        g.executeInTx(t -> {
          for (var shape : List.of(t.V().order().by("id").limit(2).values("id"),
              t.V().union(__.out(), __.in()).count())) {
            var admin = shape.asAdmin();
            var events = new ArrayList<Integer>();
            assertTrue(GremlinStepWalker.production().walk(admin) != null);
            assertTrue(GremlinStepWalker.production().walkObserved(admin, events::add) != null);
            var expected = admin.getSteps().size() == 4 ? List.of(1, 2, 3, 4)
                : List.of(1, 2, 3);
            assertEquals(expected, events);
            var captures = new ArrayList<T5PreparedShapeProbe.Declaration>();
            new T5PreparedShapeProbe(captures::add).apply(admin);
            assertEquals(expected, captures.getFirst().completedUnits());
          }
        });
      }
    }
  }

  /** The probe sees GraphStep before native folding. It records whole recogniser units only. */
  @Test
  public void selectionPhaseRejectsShapeDriftLostBoundariesAndFoldedUnitInterior()
      throws Exception {
    try (var db = YourTracks.instance(temporary.newFolder().toString())) {
      db.create("probe", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("probe", "admin", "admin")) {
        g.executeInTx(t -> {
          t.yql("CREATE CLASS Person EXTENDS V").iterate();
          t.yql("CREATE CLASS KNOWS EXTENDS E").iterate();
        });
        g.executeInTx(t -> {
          var captures = new ArrayList<T5PreparedShapeProbe.Declaration>();
          var source = t.withStrategies(new T5PreparedShapeProbe(captures::add),
              new GremlinToMatchStrategy(query -> null));
          var q = source.V().hasLabel("Person").outE("KNOWS").has("weight", 1).inV()
              .optional(__.out("KNOWS"));
          q.asAdmin().applyStrategies();
          assertEquals("Only the root is captured", 1, captures.size());
          var declaration = captures.getFirst();
          assertTrue(declaration.steps().getFirst().kind().endsWith(".GraphStep"));
          assertFalse(declaration.steps().getFirst().kind().contains("YTDBGraphStep"));
          assertTrue(declaration.toString(), declaration.completedUnits().contains(5));
          assertFalse("outE/inV is one folded unit", declaration.completedUnits().contains(3));
          declaration.requireCut(declaration, 5);
          assertThrows(IllegalArgumentException.class,
              () -> declaration.requireCut(declaration, 3));
          assertThrows(IllegalArgumentException.class,
              () -> declaration.requireCut(declaration, 0));
          assertThrows(IllegalArgumentException.class, () -> declaration.requireCut(declaration,
              declaration.steps().size()));
          var drift = new T5PreparedShapeProbe.Declaration(
              List.of(new T5PreparedShapeProbe.Identity("changed", List.of())),
              declaration.completedUnits());
          assertThrows(IllegalArgumentException.class, () -> declaration.requireCut(drift, 5));
          var boundaryDrift = new T5PreparedShapeProbe.Declaration(declaration.steps(), List.of(1));
          assertThrows(IllegalArgumentException.class,
              () -> declaration.requireCut(boundaryDrift, 5));
          assertFalse("Preparation is not partial execution", q.asAdmin().getSteps().stream()
              .anyMatch(
                  s -> s instanceof com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep));
        });
      }
    }
  }
}
