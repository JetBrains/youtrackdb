package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongFunction;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

/** Additional archived A gate forms. Archive observations do not certify current cut admission. */
public final class T5BenchmarkCatalogue {
  static final String SHA256 =
      "e95ee18109097d4f83a03ff8295499cb894a6d251e22b7b50c06bbb2bcae4f47";

  public record Definition(int id, String start, @JsonProperty("class") String type,
      List<String> hops, int hopCount, String filter, String filterLabel, String terminal,
      int cap, boolean deterministicCaps) {
    public Definition {
      hops = List.copyOf(hops);
    }
  }

  public record Form(String graph, String family, Definition definition,
      List<GeneratedDedupCatalogue.Parameter> parameters, boolean archivedDecline,
      boolean archivedBypass) {
    public Form {
      parameters = List.copyOf(parameters);
    }

    public String identity() {
      return graph + "/" + family + "/q" + definition.id();
    }

    /** Archived RIDs are never executable. Resolve both logical endpoints in the replay graph. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public GraphTraversal<?, ?> traversal(YTDBGraphTraversalSource g,
        GeneratedDedupCatalogue.Parameter p, LongFunction<Object> rid) {
      var q = definition;
      GraphTraversal t = switch (q.start()) {
        case "rid" -> g.V(rid.apply(p.rootId()));
        case "uniqueId" -> g.V().hasLabel(q.type()).has("id", p.rootId());
        case "nonuniqueLastName" -> g.V().hasLabel("Person").has("lastName", "name" + p.bucket());
        case "unindexedCohort" -> g.V().hasLabel(q.type()).has("cohort", "cohort" + p.bucket());
        case "labelLimit" -> g.V().hasLabel(q.type());
        default -> throw new IllegalArgumentException(q.start());
      };
      if (!q.start().equals("rid") && !q.start().equals("uniqueId")) {
        t = t.has("id", P.gte(p.rootId())).has("id",
            P.lt(p.rootId() + (q.start().equals("labelLimit") ? 2 + 2 * p.slot() : 16)));
      }
      t = t.as("a");
      for (String hop : q.hops()) {
        String label = hop.substring(hop.indexOf('\'') + 1, hop.lastIndexOf('\''));
        if (hop.startsWith(".outE(")) {
          t = t.outE(label).inV();
        } else if (hop.startsWith(".out(")) {
          t = t.out(label);
        } else if (hop.startsWith(".in(")) {
          t = t.in(label);
        } else if (hop.startsWith(".both(")) {
          t = t.both(label);
        } else {
          throw new IllegalArgumentException(hop);
        }
      }
      t = switch (q.filter()) {
        case "none" -> t;
        case "has" -> t.has("score", P.gte(p.slot() * 15));
        case "knownEndpoint" -> t.where(__.out(p.archive().get("endpointLabel").toString())
            .hasId(rid.apply(p.endpointId())));
        case "crossNode" -> t.where(P.neq("a"));
        case "not" -> t.not(__.out(q.filterLabel()));
        case "dedup" -> t.dedup();
        default -> throw new IllegalArgumentException(q.filter());
      };
      if (q.terminal().equals("orderLimit")
          || q.terminal().equals("limit") && q.deterministicCaps()) {
        return t.order().by("id", Order.asc).limit(10).values("id");
      }
      return switch (q.terminal()) {
        case "values" -> t.values("id");
        case "valueMap" -> t.valueMap("id", "lastName", "score");
        case "selectBy" -> t.as("b").select("a", "b").by("id");
        case "count" -> t.count();
        case "groupCount" -> t.groupCount().by("lastName");
        case "fold" -> t.values("id").fold();
        case "limit" -> t.limit(10).values("id");
        default -> throw new IllegalArgumentException(q.terminal());
      };
    }
  }

  public static List<Form> load() {
    try (var input = T5BenchmarkCatalogue.class.getResourceAsStream("/t5/generated-gate.json")) {
      if (input == null) {
        throw new IllegalStateException("Missing generated gate catalogue");
      }
      return decode(input.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<Form> decode(byte[] bytes) {
    try {
      if (!SHA256.equals(HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(bytes)))) {
        throw new IllegalStateException("Generated gate catalogue digest mismatch");
      }
      return List.copyOf(new ObjectMapper().readValue(bytes, new TypeReference<List<Form>>() {
      }));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private T5BenchmarkCatalogue() {
  }
}
