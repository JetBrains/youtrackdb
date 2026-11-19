package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;

/** Archived duplicate-removal populations. Cut admission requires a prepared-shape preflight. */
public final class GeneratedDedupCatalogue {

  public record Definition(int id, String start, String type, List<String> hops, String terminal,
      String filterLabel, int cap, boolean deterministicCaps) {
    public Definition {
      hops = List.copyOf(hops);
    }
  }

  /** Logical ids are executable bindings. Archived RIDs are provenance only. */
  public record Parameter(int slot, long rootId, long endpointId, int bucket,
      Map<String, Object> archive) {
    public Parameter {
      archive = Map.copyOf(archive);
    }
  }

  public record Form(String graph, String family, Definition definition,
      List<Parameter> parameters, boolean ridBearing, String currentOutcome, String cutStatus) {
    public Form {
      parameters = List.copyOf(parameters);
    }

    public String identity() {
      return graph + "/" + family + "/q" + definition.id();
    }

    /** RID forms are retained as whole-native outcomes, never forced partial candidates. */
    public boolean partialCandidate() {
      return !ridBearing;
    }

    /**
     * Replays QueryGrammar's dedup bodies. A bare counterpart omits only terminal order.
     * The resolver binds a logical root id to this database's RID before the traversal is built.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public GraphTraversal<?, ?> traversal(YTDBGraphTraversalSource g, Parameter p, Object rootRid) {
      var q = definition;
      GraphTraversal t = switch (q.start()) {
        case "rid" -> g.V(rootRid);
        case "uniqueId" -> g.V().hasLabel(q.type()).has("id", p.rootId());
        case "nonuniqueLastName" -> g.V().hasLabel("Person")
            .has("lastName", "name" + p.bucket());
        case "unindexedCohort" -> g.V().hasLabel(q.type())
            .has("cohort", "cohort" + p.bucket());
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
      t = t.dedup();
      if (family.equals("count")) {
        return t.count();
      }
      if (!family.equals("bareLimit")) {
        t = t.order().by("id", Order.asc);
      }
      return t.limit(10).values("id");
    }
  }

  public static List<Form> load() {
    try (
        var input = GeneratedDedupCatalogue.class.getResourceAsStream("/t5/generated-dedup.json")) {
      if (input == null) {
        throw new IllegalStateException("Missing generated dedup catalogue");
      }
      return List.copyOf(new ObjectMapper().readValue(input, new TypeReference<List<Form>>() {
      }));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private GeneratedDedupCatalogue() {
  }
}
