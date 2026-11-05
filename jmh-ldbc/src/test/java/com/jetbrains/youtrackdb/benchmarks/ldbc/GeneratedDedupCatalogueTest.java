package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.junit.Test;

public class GeneratedDedupCatalogueTest {

  private static final Map<String, List<Integer>> IDS = Map.of(
      "count", List.of(11, 35, 59, 83, 107, 131, 155, 179, 203, 227, 251, 275, 299),
      "orderLimit", List.of(5, 29, 53, 77, 101, 125, 149, 173, 197, 221, 245, 269, 293),
      "nominalLimit", List.of(23, 47, 71, 95, 119, 143, 167, 191, 215, 239, 263, 287),
      "bareLimit", List.of(23, 47, 71, 95, 119, 143, 167, 191, 215, 239, 263, 287));

  private static void requireMembership(List<GeneratedDedupCatalogue.Form> forms) {
    assertEquals(150, forms.size());
    for (String graph : List.of("n20000-skewed", "n20000-uniform", "n200000-skewed")) {
      IDS.forEach((family, ids) -> assertEquals(graph + "/" + family, ids,
          forms.stream().filter(f -> f.graph().equals(graph) && f.family().equals(family))
              .map(f -> f.definition().id()).sorted().toList()));
    }
  }

  @Test
  public void exactArchiveMembershipBindingsAndBareCounterpartsAreImmutable() throws Exception {
    var forms = GeneratedDedupCatalogue.load();
    requireMembership(forms);
    // This digest pins every archived definition and every binding, not only family cardinality.
    try (var input = getClass().getResourceAsStream("/t5/generated-dedup.json")) {
      assertEquals("ec1bdc2819a34010e2ee1ad5f274d1c0e0cefc90bf754bd5f83e10d5e74e2d1d",
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
    }
    var archivedForms = archivedForms();
    for (var f : forms) {
      requireArchivedFields(archivedForms.get(f.identity()), f);
      assertEquals(List.of(0, 1, 2, 3, 4),
          f.parameters().stream().map(GeneratedDedupCatalogue.Parameter::slot).toList());
      assertEquals("WHOLE_NATIVE", f.currentOutcome());
      assertEquals("UNRESOLVED", f.cutStatus());
      assertEquals(f.definition().start().equals("rid"), f.ridBearing());
      if (f.ridBearing()) {
        assertFalse(f.partialCandidate());
      }
      if (f.family().equals("bareLimit")) {
        var ordered = forms.stream().filter(o -> o.graph().equals(f.graph())
            && o.family().equals("nominalLimit") && o.definition().id() == f.definition().id())
            .findFirst().orElseThrow();
        assertEquals(ordered.definition(), f.definition());
        assertEquals(ordered.parameters(), f.parameters());
      }
    }
    assertThrows(UnsupportedOperationException.class, () -> forms.clear());
  }

  @Test
  public void membershipOracleRejectsMissingFormsAndSameSizeWrongIdentity() {
    var changed = new ArrayList<>(GeneratedDedupCatalogue.load());
    changed.removeFirst();
    assertThrows(AssertionError.class, () -> requireMembership(changed));
    changed.add(changed.getFirst());
    assertThrows(AssertionError.class, () -> requireMembership(changed));
  }

  // Compare every parsed field with raw JSON, so fixtures and expectations cannot move together
  // with a constructor defect. Parameter positions are compared before any slot-based lookup.
  private static void requireArchivedFields(JsonNode raw, GeneratedDedupCatalogue.Form f) {
    String identity = f.identity();
    assertTrue(identity, raw != null);
    assertEquals(identity, raw.get("graph").asText(), f.graph());
    assertEquals(identity, raw.get("family").asText(), f.family());
    assertEquals(identity, raw.get("ridBearing").asBoolean(), f.ridBearing());
    assertEquals(identity, raw.get("currentOutcome").asText(), f.currentOutcome());
    assertEquals(identity, raw.get("cutStatus").asText(), f.cutStatus());
    var definition = raw.get("definition");
    var q = f.definition();
    assertEquals(identity, definition.get("id").asInt(), q.id());
    assertEquals(identity, definition.get("start").asText(), q.start());
    assertEquals(identity, definition.get("type").asText(), q.type());
    assertEquals(identity, definition.get("hops"), new ObjectMapper().valueToTree(q.hops()));
    assertEquals(identity, definition.get("terminal").asText(), q.terminal());
    assertEquals(identity, definition.get("filterLabel").asText(), q.filterLabel());
    assertEquals(identity, definition.get("cap").asInt(), q.cap());
    assertEquals(identity, definition.get("deterministicCaps").asBoolean(), q.deterministicCaps());
    var parameters = raw.get("parameters");
    assertEquals(identity, parameters.size(), f.parameters().size());
    for (int i = 0; i < parameters.size(); i++) {
      var expected = parameters.get(i);
      var p = f.parameters().get(i);
      String binding = identity + "/parameter[" + i + "]";
      assertEquals(binding + "/slot", expected.get("slot").asInt(), p.slot());
      assertEquals(binding + "/rootId", expected.get("rootId").asLong(), p.rootId());
      assertEquals(binding + "/endpointId", expected.get("endpointId").asLong(), p.endpointId());
      assertEquals(binding + "/bucket", expected.get("bucket").asInt(), p.bucket());
      assertEquals(binding + "/archive", expected.get("archive"),
          new ObjectMapper().valueToTree(p.archive()));
    }
  }

  // Read the digest-pinned JSON without the catalogue's constructors or parsing code.
  private static Map<String, JsonNode> archivedForms() throws Exception {
    var result = new HashMap<String, JsonNode>();
    try (var input = GeneratedDedupCatalogueTest.class
        .getResourceAsStream("/t5/generated-dedup.json")) {
      for (var form : new ObjectMapper().readTree(input)) {
        String identity = form.get("graph").asText() + "/" + form.get("family").asText()
            + "/q" + form.get("definition").get("id").asInt();
        assertFalse(identity, result.containsKey(identity));
        result.put(identity, form);
      }
    }
    return result;
  }

  // This expectation must not share transformations with Definition.hops().
  private static Map<String, List<String>> archivedHops() throws Exception {
    var result = new HashMap<String, List<String>>();
    archivedForms().forEach((identity, form) -> {
      var hops = new ArrayList<String>();
      form.get("definition").get("hops").forEach(hop -> hops.add(hop.asText()));
      result.put(identity, List.copyOf(hops));
    });
    return result;
  }

  // Inspect before strategies run, so translator rewrites cannot hide a builder defect.
  private static void requireStructure(GeneratedDedupCatalogue.Form f, GraphTraversal<?, ?> t,
      List<String> expectedHops) {
    var steps = t.asAdmin().getSteps();
    var hops = new StringBuilder();
    for (var step : steps) {
      if (step instanceof VertexStep<?> hop) {
        hops.append('.').append(hop.getDirection().name().toLowerCase(java.util.Locale.ROOT))
            .append(hop.returnsEdge() ? "E" : "")
            .append("('").append(String.join("','", hop.getEdgeLabels())).append("')");
      } else if (step instanceof EdgeVertexStep hop) {
        hops.append('.').append(hop.getDirection().name().toLowerCase(java.util.Locale.ROOT))
            .append("V()");
      }
    }
    assertEquals(f.identity(), expectedHops, f.definition().hops());
    assertEquals(f.identity(), String.join("", expectedHops), hops.toString());
    if (f.family().equals("bareLimit")) {
      // Bare builders use only these steps and no child traversals. Reject wrappers as well as
      // global/local ordering, so ordering cannot hide inside a parent step.
      var allowed = Set.of(GraphStep.class, HasStep.class, VertexStep.class, EdgeVertexStep.class,
          DedupGlobalStep.class, RangeGlobalStep.class, PropertiesStep.class);
      for (var step : steps) {
        assertTrue(f.identity() + "/" + step.getClass().getSimpleName(),
            allowed.contains(step.getClass()));
        if (step instanceof TraversalParent parent) {
          assertTrue(f.identity(), parent.getLocalChildren().isEmpty());
          assertTrue(f.identity(), parent.getGlobalChildren().isEmpty());
        }
      }
      var ranges = steps.stream().filter(RangeGlobalStep.class::isInstance)
          .map(RangeGlobalStep.class::cast).toList();
      assertEquals(f.identity(), 1, ranges.size());
      assertEquals(f.identity(), 0L, ranges.getFirst().getLowRange().longValue());
      assertEquals(f.identity(), 10L, ranges.getFirst().getHighRange().longValue());
    }
  }

  @Test
  public void bareLimitCapsSixteenDistinctResultsInBothTranslatorModes() {
    // q167 has no hops and selects a 16-id last-name window. A lost limit returns all 16.
    var f = GeneratedDedupCatalogue.load().stream()
        .filter(form -> form.family().equals("bareLimit") && form.definition().id() == 167)
        .findFirst().orElseThrow();
    var p = f.parameters().getFirst();
    boolean flag =
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValueAsBoolean();
    try (var db = YourTracks.instance("memory:bare-limit-boundary")) {
      db.create("catalogue", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("catalogue", "admin", "admin")) {
        g.executeInTx(t -> {
          t.yql("CREATE CLASS Person EXTENDS V").iterate();
          for (int i = 15; i >= 0; i--) {
            t.addV("Person").property("id", p.rootId() + i)
                .property("lastName", "name" + p.bucket()).iterate();
          }
        });
        for (boolean enabled : List.of(false, true)) {
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(enabled);
          g.executeInTx(t -> {
            try (var traversal = f.traversal(t, p, null)) {
              var actual = traversal.toList();
              assertEquals(f.identity(), 10, actual.size());
              assertEquals(10L, actual.stream().distinct().count());
              assertTrue(actual.stream().allMatch(id -> (long) id >= p.rootId()
                  && (long) id < p.rootId() + 16));
            } catch (Exception e) {
              throw new IllegalStateException(f.identity(), e);
            }
          });
        }
      }
    } finally {
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(flag);
    }
  }

  private record Root(long id, String type, int bucket) {
  }

  @Test
  public void allFrozenBindingsRunBothArmsAndDeduplicateParallelSelfLoops() throws Exception {
    var forms = GeneratedDedupCatalogue.load();
    var archivedHops = archivedHops();
    // Use real archived root ids on a small graph. Self loops preserve each root through every
    // direction and label. Parallel edges make removal of dedup change count and list answers.
    var roots = new TreeMap<Long, Root>(Collections.reverseOrder());
    for (var f : forms) {
      for (var p : f.parameters()) {
        var root = new Root(p.rootId(), f.definition().type(), p.bucket());
        var previous = roots.putIfAbsent(root.id(), root);
        if (previous != null) {
          assertEquals(previous, root);
        }
      }
    }
    boolean flag =
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValueAsBoolean();
    try (var db = YourTracks.instance("memory:generated-dedup")) {
      db.create("catalogue", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("catalogue", "admin", "admin")) {
        var rids = new HashMap<Long, Object>();
        g.executeInTx(t -> {
          for (String type : List.of("Person", "Post", "Comment", "Tag", "Company", "Forum")) {
            t.yql("CREATE CLASS " + type + " EXTENDS V").iterate();
            t.yql("CREATE PROPERTY " + type + ".id LONG").iterate();
            t.yql("CREATE INDEX " + type + ".id ON " + type + " (id) UNIQUE").iterate();
          }
          for (String label : List.of("KNOWS", "HAS_CREATOR", "LIKES", "HAS_TAG", "WORK_AT",
              "CONTAINER_OF", "REPLY_OF")) {
            t.yql("CREATE CLASS " + label + " EXTENDS E").iterate();
          }
          for (var root : roots.values()) {
            var v = t.addV(root.type()).property("id", root.id())
                .property("lastName", "name" + root.bucket())
                .property("cohort", "cohort" + root.bucket()).next();
            rids.put(root.id(), v.id());
            for (String label : List.of("KNOWS", "HAS_CREATOR", "LIKES", "HAS_TAG", "WORK_AT",
                "CONTAINER_OF", "REPLY_OF")) {
              v.addEdge(label, v);
              v.addEdge(label, v);
            }
          }
        });
        g.executeInTx(t -> t.yql("CREATE CLASS HopProbe EXTENDS V").iterate());
        // Asymmetric one-hop probes distinguish every archived direction and edge label.
        for (boolean enabled : List.of(false, true)) {
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(enabled);
          g.executeInTx(t -> {
            var a = t.addV("HopProbe").next();
            var out = t.addV("HopProbe").property("id", -1L).next();
            var in = t.addV("HopProbe").property("id", -2L).next();
            var other = t.addV("HopProbe").property("id", -3L).next();
            for (var hop : forms.stream().flatMap(f -> f.definition().hops().stream())
                .distinct().toList()) {
              String label = hop.substring(hop.indexOf('\'') + 1, hop.lastIndexOf('\''));
              var outgoing = a.addEdge(label, out);
              var incoming = in.addEdge(label, a);
              // A different label must not leak into the selected neighbors.
              String wrongLabel = label.equals("KNOWS") ? "LIKES" : "KNOWS";
              var decoy = hop.startsWith(".in(") ? other.addEdge(wrongLabel, a)
                  : a.addEdge(wrongLabel, other);
              var q = new GeneratedDedupCatalogue.Definition(0, "rid", "Person", List.of(hop),
                  "orderLimit", "dedup", 10, true);
              var probe = new GeneratedDedupCatalogue.Form("probe", "orderLimit", q,
                  List.of(), true, "WHOLE_NATIVE", "UNRESOLVED");
              var p = new GeneratedDedupCatalogue.Parameter(0, 0, 0, 0, Map.of());
              try (var traversal = probe.traversal(t, p, a.id())) {
                assertEquals(hop, hop.startsWith(".both(") ? List.of(-2L, -1L)
                    : hop.startsWith(".in(") ? List.of(-2L) : List.of(-1L), traversal.toList());
              } catch (Exception e) {
                throw new IllegalStateException(hop, e);
              }
              List.of(outgoing, incoming, decoy).forEach(edge -> edge.remove());
            }
          });
        }
        for (var f : forms) {
          for (var p : f.parameters()) {
            List<?> reference = null;
            for (boolean enabled : List.of(false, true)) {
              GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(enabled);
              var actual = g.computeInTx(t -> {
                try (var traversal = f.traversal(t, p, rids.get(p.rootId()))) {
                  requireStructure(f, traversal, archivedHops.get(f.identity()));
                  return traversal.toList();
                } catch (Exception e) {
                  throw new IllegalStateException(f.identity(), e);
                }
              });
              var expectedIds = roots.values().stream().filter(r -> startsAt(f, p, r))
                  .map(Root::id).sorted().toList();
              if (f.family().equals("count")) {
                assertEquals(f.identity(), List.of((long) expectedIds.size()), actual);
              } else if (!f.family().equals("bareLimit")) {
                assertEquals(f.identity(), expectedIds.stream().limit(10).toList(), actual);
              } else {
                assertEquals(Math.min(10, expectedIds.size()), actual.size());
                assertEquals(actual.size(), actual.stream().distinct().count());
                assertTrue(expectedIds.containsAll(actual));
              }
              if (reference != null) {
                assertEquals(f.identity() + "/p" + p.slot(), reference, actual);
              }
              reference = actual;
            }
          }
        }
      }
    } finally {
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(flag);
    }
  }

  private static boolean startsAt(GeneratedDedupCatalogue.Form f,
      GeneratedDedupCatalogue.Parameter p, Root r) {
    return switch (f.definition().start()) {
      case "rid", "uniqueId" -> r.id() == p.rootId();
      case "nonuniqueLastName", "unindexedCohort" -> r.type().equals(f.definition().type())
          && r.bucket() == p.bucket() && r.id() >= p.rootId() && r.id() < p.rootId() + 16;
      default -> r.type().equals(f.definition().type()) && r.id() >= p.rootId()
          && r.id() < p.rootId() + 2 + 2 * p.slot();
    };
  }
}
