package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class T5BenchmarkCatalogueTest {
  @Rule
  public TemporaryFolder temporary = TemporaryFolder.builder().assureDeletion().build();
  private static final String IDS =
      "0,2,8,10,14,15,17,18,20,25,26,30,32,34,38,40,41,42,44,45,50,55,"
          + "56,58,60,62,65,66,68,70,74,75,80,82,85,86,89,90,92,98,100,104,105,106,110,113,114,115,116,"
          + "120,122,128,130,134,135,137,138,140,145,146,150,152,154,158,160,161,162,164,165,170,175,"
          + "176,178,180,182,185,186,188,190,194,195,200,202,205,206,209,210,212,218,220,224,225,226,"
          + "230,233,234,235,236,240,242,248,250,254,255,257,258,260,265,266,270,272,274,278,280,281,"
          + "282,284,285,290,295,296,298";

  private static void membership(List<T5BenchmarkCatalogue.Form> forms) {
    assertEquals(366, forms.size());
    for (String graph : List.of("n20000-skewed", "n20000-uniform", "n200000-skewed")) {
      assertEquals(IDS, forms.stream().filter(f -> f.graph().equals(graph))
          .map(f -> Integer.toString(f.definition().id())).collect(Collectors.joining(",")));
    }
  }

  // Check field sets and JSON kinds as well as values. Java long bindings may serialize wider
  // than the archive's small integer literals, but strings and floating numbers stay distinct.
  private static void fields(com.fasterxml.jackson.databind.JsonNode raw,
      com.fasterxml.jackson.databind.JsonNode parsed) {
    assertEquals(raw.getNodeType(), parsed.getNodeType());
    assertEquals(raw.size(), parsed.size());
    if (raw.isObject()) {
      var names = raw.fieldNames();
      while (names.hasNext()) {
        var name = names.next();
        assertTrue("Missing field " + name, parsed.has(name));
        fields(raw.get(name), parsed.get(name));
      }
    } else if (raw.isArray()) {
      for (int i = 0; i < raw.size(); i++) {
        fields(raw.get(i), parsed.get(i));
      }
    } else if (raw.isIntegralNumber()) {
      assertTrue(parsed.isIntegralNumber());
      assertEquals(raw.bigIntegerValue(), parsed.bigIntegerValue());
    } else {
      assertEquals(raw, parsed);
    }
  }

  @Test
  public void everyParsedFieldMatchesPinnedRawArchiveAndRejectsMembershipAndBindingDrift()
      throws Exception {
    var mapper = new ObjectMapper();
    var forms = T5BenchmarkCatalogue.load();
    membership(forms);
    byte[] bytes;
    try (var input = getClass().getResourceAsStream("/t5/generated-gate.json")) {
      bytes = input.readAllBytes();
    }
    var raw = mapper.readTree(bytes);
    fields(raw, mapper.valueToTree(forms));
    var changed = new ArrayList<>(forms);
    changed.removeFirst();
    assertThrows(AssertionError.class, () -> membership(changed));
    changed.add(changed.getFirst());
    assertThrows(AssertionError.class, () -> membership(changed));
    var drift = mapper.valueToTree(forms);
    ((com.fasterxml.jackson.databind.node.ObjectNode) drift.get(0).get("parameters").get(0))
        .put("endpointId", -1);
    assertThrows(AssertionError.class, () -> fields(raw, drift));
    for (var invalid : List.of(mapper.readTree("\"2057\""), mapper.readTree("2057.0"))) {
      var wrongType = mapper.valueToTree(forms);
      ((com.fasterxml.jackson.databind.node.ObjectNode) wrongType.get(0).get("parameters").get(0))
          .set("rootId", invalid);
      assertThrows(AssertionError.class, () -> fields(raw, wrongType));
    }
    bytes[bytes.length / 2] ^= 1;
    assertThrows(IllegalStateException.class, () -> T5BenchmarkCatalogue.decode(bytes));
    assertThrows(UnsupportedOperationException.class, () -> forms.getFirst().parameters().clear());
    var previous =
        GeneratedDedupCatalogue.load().stream().filter(f -> !f.family().equals("bareLimit"))
            .map(f -> f.graph() + "/" + f.definition().id()).collect(Collectors.toSet());
    for (var f : forms) {
      assertFalse(previous.contains(f.graph() + "/" + f.definition().id()));
      assertTrue(f.archivedDecline() || f.archivedBypass());
      assertEquals(f.definition().terminal(), f.family());
      assertEquals(f.definition().hops().size(), f.definition().hopCount());
      assertEquals(List.of(0, 1, 2, 3, 4), f.parameters().stream()
          .map(GeneratedDedupCatalogue.Parameter::slot).toList());
    }
  }

  @Test
  public void bArchiveMembershipBindingsAndIdentityMultiplicityAreBytePinned() throws Exception {
    try (var provenance = getClass().getResourceAsStream("/t5/where-gate-provenance.json")) {
      assertEquals("ef0d2a178721137c7ede531ca61b7a4cde3441bd73d571a4715e9924fec5fd85",
          java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
              .digest(provenance.readAllBytes())));
    }
    var forms = T5BenchmarkCatalogue.loadWhere();
    assertEquals(32, forms.size());
    assertEquals(32,
        forms.stream().map(T5BenchmarkCatalogue.WhereForm::identity).distinct().count());
    for (String variant : List.of("indexed", "no-index")) {
      var selected = forms.stream().filter(c -> c.variant().equals(variant)).toList();
      assertEquals(16, selected.size());
      assertEquals(variant.equals("indexed") ? 14 : 13,
          selected.stream().filter(T5BenchmarkCatalogue.WhereForm::archivedBypass).count());
      assertEquals(Map.of("5", 4L, "H", 5L, "U2", 4L, "U1", 1L, "CONTROL", 1L, "LONG", 1L),
          selected.stream().collect(Collectors.groupingBy(T5BenchmarkCatalogue.WhereForm::q,
              Collectors.counting())));
      assertEquals(List.of("e2@test", "e1@test", "e99998@test", "e0@test"), selected.stream()
          .filter(c -> c.q().equals("U2")).map(T5BenchmarkCatalogue.WhereForm::email).toList());
    }
    byte[] bytes;
    try (var input = getClass().getResourceAsStream("/t5/where-gate.json")) {
      bytes = input.readAllBytes();
    }
    fields(new ObjectMapper().readTree(bytes), new ObjectMapper().valueToTree(forms));
    for (String changed : List.of(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        .replace("e2@test", "e3@test"), "[]")) {
      assertThrows(IllegalStateException.class, () -> T5BenchmarkCatalogue.decodeWhere(
          changed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    assertThrows(UnsupportedOperationException.class, () -> forms.getFirst().expected().clear());
  }

  private static T5BenchmarkCatalogue.Form probe(String start, List<String> hops,
      String filter, String terminal) {
    return new T5BenchmarkCatalogue.Form("probe", terminal,
        new T5BenchmarkCatalogue.Definition(0, start, "Person", hops, hops.size(), filter,
            "LIKES", terminal, 32, true),
        List.of(), true, false);
  }

  @Test
  public void distinctBindingsClassDecoysAndExactPredicateBoundariesHaveIndependentAnswers()
      throws Exception {
    boolean flag =
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValueAsBoolean();
    GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(false);
    try (var db = YourTracks.instance(temporary.newFolder().toString())) {
      db.create("boundaries", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("boundaries", "admin", "admin")) {
        g.executeInTx(t -> {
          t.yql("CREATE CLASS Person EXTENDS V").iterate();
          t.yql("CREATE CLASS Post EXTENDS V").iterate();
          t.yql("CREATE CLASS KNOWS EXTENDS E").iterate();
          t.yql("CREATE CLASS HAS_TAG EXTENDS E").iterate();
          // All decoys match the bucket predicates. Only labels and window bounds exclude them.
          for (long id : List.of(9L, 10L, 11L, 12L, 13L, 14L, 25L, 26L)) {
            t.addV(id == 12 ? "Post" : "Person").property("id", id)
                .property("lastName", "name3").property("cohort", "cohort3")
                .property("score", id == 10 ? 15 : id == 11 ? 14 : 16).iterate();
          }
          var root = t.V().has("id", 10L).next();
          var middle = t.V().has("id", 11L).next();
          var end = t.V().has("id", 13L).next();
          root.addEdge("KNOWS", middle);
          root.addEdge("KNOWS", middle);
          middle.addEdge("HAS_TAG", end);
          var p = new GeneratedDedupCatalogue.Parameter(1, 10, 11, 3, Map.of());
          try {
            for (String start : List.of("rid", "uniqueId", "nonuniqueLastName",
                "unindexedCohort", "labelLimit")) {
              // Windows are [10,14) for labelLimit and [10,26) for bucket starts.
              var expected = switch (start) {
                case "rid", "uniqueId" -> List.of(10L);
                case "labelLimit" -> List.of(10L, 11L, 13L);
                default -> List.of(10L, 11L, 13L, 14L, 25L);
              };
              for (String filter : List.of("none", "has")) {
                try (var q = probe(start, List.of(), filter, "values")
                    .traversal(t, p, id -> t.V().has("id", id).next().id())) {
                  // Score 15 is included, score 14 is excluded, and score 16 is included.
                  var filtered = filter.equals("has")
                      ? expected.stream().filter(id -> id != 11L).toList() : expected;
                  assertEquals(start + "/" + filter, filtered,
                      q.toList().stream().map(Long.class::cast).sorted().toList());
                }
              }
            }
            for (String start : List.of("rid", "uniqueId")) {
              try (var q = probe(start, List.of(".out('KNOWS')", ".outE('HAS_TAG').inV()"),
                  "none", "values").traversal(t, p,
                      id -> t.V().has("id", id).next().id())) {
                // Root 10 has two paths to 13. Endpoint 11 has no outgoing KNOWS edge.
                assertEquals(start, List.of(13L, 13L), q.toList());
              }
            }
            var wrongClass = new GeneratedDedupCatalogue.Parameter(1, 12, 11, 3, Map.of());
            try (var q = probe("uniqueId", List.of(), "none", "values")
                .traversal(t, wrongClass, id -> t.V().has("id", id).next().id())) {
              assertEquals("A Post with the requested id is not a Person", List.of(), q.toList());
            }
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
      }
    } finally {
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(flag);
    }
  }

  @Test
  public void asymmetricPathsFiltersAndEveryTerminalHaveIndependentAnswers() throws Exception {
    boolean flag =
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValueAsBoolean();
    GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(false);
    try (var db = YourTracks.instance(temporary.newFolder().toString())) {
      db.create("queries", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("queries", "admin", "admin")) {
        g.executeInTx(t -> {
          t.yql("CREATE CLASS Person EXTENDS V").iterate();
          for (String label : List.of("KNOWS", "LIKES", "HAS_TAG")) {
            t.yql("CREATE CLASS " + label + " EXTENDS E").iterate();
          }
          var a = t.addV("Person").property("id", 0L).property("lastName", "name0")
              .property("cohort", "cohort0").property("score", 0).next();
          var b = t.addV("Person").property("id", 1L).property("lastName", "B")
              .property("score", 5).next();
          var c = t.addV("Person").property("id", 2L).property("lastName", "C")
              .property("score", 90).next();
          a.addEdge("KNOWS", b);
          a.addEdge("KNOWS", b);
          b.addEdge("HAS_TAG", c);
          c.addEdge("LIKES", a);
          var p =
              new GeneratedDedupCatalogue.Parameter(1, 0, 0, 0, Map.of("endpointLabel", "LIKES"));
          var hops = List.of(".out('KNOWS')", ".outE('HAS_TAG').inV()");
          for (String filter : List.of("none", "has", "knownEndpoint", "crossNode", "not",
              "dedup")) {
            try (var q = probe("rid", hops, filter, "values").traversal(t, p, id -> a.id())) {
              assertEquals(filter, filter.equals("not") ? List.of()
                  : filter.equals("dedup") ? List.of(2L) : List.of(2L, 2L), q.toList());
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
          for (String start : List.of("rid", "uniqueId", "nonuniqueLastName", "unindexedCohort",
              "labelLimit")) {
            for (String terminal : List.of("values", "valueMap", "selectBy", "count", "groupCount",
                "orderLimit", "fold", "limit")) {
              try (var q = probe(start, hops, "none", terminal).traversal(t, p, id -> a.id())) {
                Object row = switch (terminal) {
                  case "valueMap" -> Map.of("id", List.of(2L), "lastName", List.of("C"),
                      "score", List.of(90));
                  case "selectBy" -> Map.of("a", 0L, "b", 2L);
                  default -> 2L;
                };
                var expected = switch (terminal) {
                  case "count" -> List.of(2L);
                  case "groupCount" -> List.of(Map.of("C", 2L));
                  case "fold" -> List.of(List.of(2L, 2L));
                  default -> List.of(row, row);
                };
                assertEquals(start + "/" + terminal, expected, q.toList());
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            }
          }
          for (var test : Map.of(".in('LIKES')", List.of(2L), ".both('LIKES')", List.of(2L),
              ".out('LIKES')", List.of()).entrySet()) {
            try (var q = probe("rid", List.of(test.getKey()), "none", "values")
                .traversal(t, p, id -> a.id())) {
              assertEquals(test.getKey(), test.getValue(), q.toList());
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
          // Every archived hop sequence is checked before any strategy can rewrite it.
          for (var f : T5BenchmarkCatalogue.load()) {
            for (var binding : f.parameters()) {
              try (var q = f.traversal(t, binding, id -> a.id())) {
                var actual = new StringBuilder();
                for (var step : q.asAdmin().getSteps()) {
                  if (step instanceof org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep<
                      ?> v) {
                    actual.append('.')
                        .append(v.getDirection().name().toLowerCase(java.util.Locale.ROOT))
                        .append(v.returnsEdge() ? "E" : "").append("('")
                        .append(String.join("','", v.getEdgeLabels())).append("')");
                  } else if (step instanceof org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep v) {
                    actual.append('.')
                        .append(v.getDirection().name().toLowerCase(java.util.Locale.ROOT))
                        .append("V()");
                  }
                }
                assertEquals(f.identity(), String.join("", f.definition().hops()),
                    actual.toString());
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            }
          }
          // Failed predicates and reversed hops must not accidentally pass on symmetric data.
          for (var test : List.of(probe("rid", List.of(".out('KNOWS')"), "has", "values"),
              probe("rid", List.of(), "crossNode", "values"),
              probe("rid", List.of(".out('KNOWS')"), "knownEndpoint", "values"),
              probe("rid", List.of(".out('HAS_TAG')", ".out('KNOWS')"), "none", "values"))) {
            try (var q = test.traversal(t, p, id -> a.id())) {
              assertEquals(List.of(), q.toList());
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
          var endpoint = new GeneratedDedupCatalogue.Parameter(1, 0, 2, 0,
              Map.of("endpointLabel", "HAS_TAG"));
          for (String filter : List.of("not", "knownEndpoint")) {
            try (var q = probe("rid", List.of(".out('KNOWS')"), filter, "values")
                .traversal(t, endpoint, id -> id == 2 ? c.id() : a.id())) {
              assertEquals(List.of(1L, 1L), q.toList());
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
          // Cross the terminal cap with reverse insertion order and retain duplicate endpoints.
          for (long id = 14; id >= 3; id--) {
            a.addEdge("KNOWS", t.addV("Person").property("id", id).next());
          }
          for (String terminal : List.of("orderLimit", "limit")) {
            try (var q = probe("rid", List.of(".out('KNOWS')"), "none", terminal)
                .traversal(t, p, id -> a.id())) {
              assertEquals(List.of(1L, 1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), q.toList());
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        });
      }
    } finally {
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(flag);
    }
  }
}
