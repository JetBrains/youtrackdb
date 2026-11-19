package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.tinkerpop.gremlin.structure.T;

/** Archived A topology. Logical ids are global, while indexes are class-local. */
public final class T5GeneratedFixture {
  static final String[] CLASSES = {"Person", "Post", "Comment", "Tag", "Company", "Forum"};
  static final String[] LABELS = {
      "KNOWS", "HAS_CREATOR", "LIKES", "HAS_TAG", "WORK_AT", "CONTAINER_OF", "REPLY_OF"};
  final int n;
  final long seed;
  final String distribution;
  final Object[] rids;
  final int[] outgoing;
  final int[] incoming;
  final int[][] targets;
  final byte[][] labels;
  final Map<String, List<Integer>> members = new LinkedHashMap<>();

  T5GeneratedFixture(int n, long seed, String distribution) {
    if (n < 200 || !(distribution.equals("uniform") || distribution.equals("skewed"))) {
      throw new IllegalArgumentException("Use n >= 200 and uniform or skewed");
    }
    this.n = n;
    this.seed = seed;
    this.distribution = distribution;
    rids = new Object[n];
    outgoing = new int[n];
    incoming = new int[n];
    targets = new int[n][];
    labels = new byte[n][];
    for (var name : CLASSES) {
      members.put(name, new ArrayList<>());
    }
    for (int i = 0; i < n; i++) {
      members.get(type(i)).add(i);
    }
    topology();
  }

  String type(int i) {
    int k = i % 20;
    return CLASSES[k < 8 ? 0 : k < 13 ? 1 : k < 17 ? 2 : k < 18 ? 3 : k < 19 ? 4 : 5];
  }

  int bucket(int i) {
    int x = Math.floorMod(mix(i + seed), 1000);
    return x < 500 ? 0 : x < 800 ? 1 : x < 950 ? 2 : x < 990 ? 3 : 4;
  }

  static int mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return (int) (z ^ (z >>> 31));
  }

  int degree(int i) {
    return outgoing[i] + incoming[i];
  }

  private void topology() {
    var random = new Random(seed);
    int[] order = new int[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    for (int i = n - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      int t = order[i];
      order[i] = order[j];
      order[j] = t;
    }
    if (distribution.equals("uniform")) {
      Arrays.fill(outgoing, 5);
    } else {
      // Rank exponent 0.85 gives a truncated heavy tail. Fix the edge budget at 5n.
      // Reserve one 10k-outdegree hub, then distribute the remaining mass by rank.
      int cap = Math.min(10000, n - 1);
      double weights = 0;
      for (int rank = 1; rank < n; rank++) {
        weights += Math.pow(rank + 1, -0.85);
      }
      Arrays.fill(outgoing, 1);
      outgoing[order[0]] = cap;
      int remaining = 5 * n - n - cap + 1;
      int used = 0;
      for (int rank = 1; rank < n; rank++) {
        int extra = Math.min(cap - 1,
            (int) (remaining * Math.pow(rank + 1, -0.85) / weights));
        outgoing[order[rank]] += extra;
        used += extra;
      }
      for (int rank = n - 1; used < remaining; rank--) {
        if (rank == 0) {
          rank = n - 1;
        }
        if (outgoing[order[rank]] < cap) {
          outgoing[order[rank]]++;
          used++;
        }
      }
    }
    int[] seen = new int[n];
    Arrays.fill(seen, -1);
    for (int rank = 0; rank < n; rank++) {
      int source = order[rank];
      targets[source] = new int[outgoing[source]];
      labels[source] = new byte[outgoing[source]];
      for (int j = 0; j < outgoing[source]; j++) {
        int target;
        if (distribution.equals("uniform")) {
          // A seeded vertex permutation and five circulant offsets give exactly 5 in + 5 out.
          target = order[(rank + new int[] {1, 3, 7, 11, 17}[j]) % n];
        } else {
          do {
            target = order[Math.min(n - 1, (int) (n * Math.pow(random.nextDouble(), 1.4)))];
          } while (target == source || seen[target] == source);
          seen[target] = source;
        }
        targets[source][j] = target;
        labels[source][j] = (byte) Math.floorMod(mix(seed + source * 31L + j), LABELS.length);
        incoming[target]++;
      }
    }
  }

  /** Populates an empty database. The source must have no active transaction. */
  public void populate(YTDBGraphTraversalSource g, int batch) {
    if (batch < 1) {
      throw new IllegalArgumentException("Positive batch required");
    }
    var graph = (YTDBGraphInternal) g.getGraph();
    graph.tx().readWrite();
    var session = graph.tx().getDatabaseSession();
    for (var name : CLASSES) {
      var c = session.createVertexClass(name);
      c.createProperty("id", PropertyType.LONG).createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
      var last = c.createProperty("lastName", PropertyType.STRING);
      var date = c.createProperty("creationDate", PropertyType.LONG);
      c.createProperty("cohort", PropertyType.STRING);
      c.createProperty("score", PropertyType.INTEGER);
      if (name.equals("Person")) {
        last.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      }
      if (name.equals("Post")) {
        date.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      }
    }
    for (var label : LABELS) {
      session.createEdgeClass(label);
    }
    graph.tx().commit();
    for (int from = 0; from < n; from += batch) {
      graph.tx().readWrite();
      for (int i = from; i < Math.min(n, from + batch); i++) {
        rids[i] = graph.addVertex(T.label, type(i), "id", (long) i,
            "lastName", "name" + bucket(i), "cohort", "cohort" + bucket(i),
            "creationDate", 1700000000000L + (i % 365) * 86400000L,
            "score", Math.floorMod(mix(seed * 31 + i), 100)).id();
      }
      graph.tx().commit();
    }
    graph.tx().readWrite();
    int count = 0;
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < targets[i].length; j++) {
        graph.vertices(rids[i]).next().addEdge(LABELS[labels[i][j]],
            graph.vertices(rids[targets[i][j]]).next());
        if (++count % batch == 0) {
          graph.tx().commit();
          graph.tx().readWrite();
        }
      }
    }
    graph.tx().commit();
  }

  public Object rid(long logicalId) {
    return java.util.Objects.requireNonNull(rids[Math.toIntExact(logicalId)],
        "Populate the fixture before resolving ids");
  }
}
