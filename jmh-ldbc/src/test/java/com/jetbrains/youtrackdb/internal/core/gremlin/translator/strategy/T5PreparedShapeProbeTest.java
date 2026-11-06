package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class T5PreparedShapeProbeTest {
  @Rule
  public TemporaryFolder temporary = TemporaryFolder.builder().assureDeletion().build();

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
          assertTrue(declaration.steps().getFirst().contains(".GraphStep:"));
          assertFalse(declaration.steps().getFirst().contains("YTDBGraphStep"));
          assertTrue(declaration.toString(), declaration.completedUnits().contains(5));
          assertFalse("outE/inV is one folded unit", declaration.completedUnits().contains(3));
          declaration.requireCut(declaration, 5);
          assertThrows(IllegalArgumentException.class,
              () -> declaration.requireCut(declaration, 3));
          assertThrows(IllegalArgumentException.class,
              () -> declaration.requireCut(declaration, 0));
          assertThrows(IllegalArgumentException.class, () -> declaration.requireCut(declaration,
              declaration.steps().size()));
          var drift = new T5PreparedShapeProbe.Declaration(List.of("changed"),
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
