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

/** Shared T5 populations. Archive observations do not certify current cut admission. */
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

  /** Archive identity and logical bindings for the independent B fixture. */
  public record WhereForm(String variant, String q, String name, int cat, String slug,
      String email, boolean archivedBypass, List<String> expected) {
    public WhereForm {
      expected = List.copyOf(expected);
    }

    public String identity() {
      return "B/" + variant + "/" + q + "-" + name;
    }
  }

  public static List<WhereForm> loadWhere() {
    try (var in = T5BenchmarkCatalogue.class.getResourceAsStream("/t5/where-gate.json")) {
      if (in == null) {
        throw new IllegalStateException("Missing B catalogue");
      }
      return decodeWhere(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<WhereForm> decodeWhere(byte[] bytes) {
    try {
      if (!"a77a5e60ec9c4f55e3f4b827016720dc49c1c093649a8b4d8ed596d950a4ffcd".equals(
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
        throw new IllegalStateException("B catalogue digest mismatch");
      }
      return List.copyOf(new ObjectMapper().readValue(bytes,
          new TypeReference<List<WhereForm>>() {
          }));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Executable C declarations. Feed names refer to the unchanged benchmark accessors. */
  public record LdbcForm(String identity, String builder, List<String> arguments,
      T5ResultComparison.Order order, boolean crossLabel) {
    public LdbcForm {
      arguments = List.copyOf(arguments);
    }

    public org.apache.tinkerpop.gremlin.process.traversal.Traversal<?, ?> traversal(
        YTDBGraphTraversalSource g, java.util.function.Function<String, Object> feeds) {
      var args = new java.util.ArrayList<Object>();
      args.add(g);
      for (String binding : arguments) {
        boolean window = binding.endsWith("+2592000000");
        Object value = feeds.apply(window ? binding.substring(0, binding.indexOf('+')) : binding);
        if (window) {
          value = new java.util.Date(Math.addExact(((java.util.Date) value).getTime(),
              2_592_000_000L));
        }
        args.add(java.util.Objects.requireNonNull(value, binding));
      }
      if (builder.startsWith("BulkingForm.")) {
        return GremlinTraversalShapes.BulkingForm.valueOf(builder.substring(12))
            .traversal(g, (Long) args.get(1), crossLabel);
      }
      boolean partial = builder.startsWith("PartialTraversalShapes.");
      var owner = partial ? PartialTraversalShapes.class : GremlinTraversalShapes.class;
      String method = partial ? builder.substring(23) : builder;
      var candidates = java.util.Arrays.stream(owner.getMethods())
          .filter(m -> m.getName().equals(method) && m.getParameterCount() == args.size())
          .toList();
      if (candidates.size() != 1) {
        throw new IllegalStateException("Ambiguous or missing builder: " + builder);
      }
      try {
        return (org.apache.tinkerpop.gremlin.process.traversal.Traversal<?, ?>) candidates
            .getFirst().invoke(null, args.toArray());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Cannot bind " + identity, e);
      }
    }
  }

  public static List<LdbcForm> loadLdbc() {
    try (var in = T5BenchmarkCatalogue.class.getResourceAsStream("/t5/ldbc-gate.json")) {
      if (in == null) {
        throw new IllegalStateException("Missing C declarations");
      }
      byte[] bytes = in.readAllBytes();
      try {
        if (!"000bdf69a42e0db552dc4bbf661d154e7bb5485a0f5e35bfa24c490bd9881664".equals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
          throw new IllegalStateException("C declaration digest mismatch");
        }
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
      return List.copyOf(new ObjectMapper().readValue(bytes,
          new TypeReference<List<LdbcForm>>() {
          }));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A and C typed captures enter the same contract. No rendered archive answer is a capture. */
  public static T5ResultComparison.Expected expected(String identity,
      T5ResultComparison.Order order, List<?> typedRows) {
    return new T5ResultComparison.Expected(identity, order, typedRows);
  }

  /** B answers use explicit logical identities, not arbitrary toString conversion. */
  public static T5ResultComparison.Expected expected(WhereForm form) {
    var rows = form.expected().stream().map(s -> {
      if (form.q().equals("LONG")) {
        return (Object) Integer.valueOf(s);
      }
      int slash = s.indexOf('/');
      if (slash < 1 || !List.of("Article", "Category", "JPEmailContact", "OrderedItem")
          .contains(s.substring(0, slash))) {
        throw new IllegalArgumentException("Unsupported archived B answer: " + s);
      }
      Object key = s.startsWith("OrderedItem/") ? Integer.valueOf(s.substring(slash + 1))
          : s.substring(slash + 1);
      return new T5ResultComparison.ElementKey("vertex", s.substring(0, slash), key);
    }).toList();
    return expected(form.identity(), form.q().equals("U1")
        ? T5ResultComparison.Order.SEQUENCE : T5ResultComparison.Order.MULTISET, rows);
  }

  /** Exact formula of the shared 64-slot IC2-limit/IC9 feed, after SF1 seed resolution. */
  public static java.util.Date derivedDate(long minMs, long maxMs, int slot) {
    if (slot < 0 || slot >= 64 || maxMs < minMs) {
      throw new IllegalArgumentException("Invalid derived date slot or range");
    }
    long span = Math.max(1L, maxMs - minMs);
    return new java.util.Date(slot == 63 ? maxMs + 1L
        : minMs + 1L + (span * slot) / 63);
  }

  private T5BenchmarkCatalogue() {
  }
}
