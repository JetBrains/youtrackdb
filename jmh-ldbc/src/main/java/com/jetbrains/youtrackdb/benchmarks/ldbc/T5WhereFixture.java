package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Objects;
import java.util.stream.IntStream;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;

/** Deterministic archived B graph. Physical ids belong only to this replay database. */
public final class T5WhereFixture {
  private final Object[] categories = new Object[1000];
  private final Object[] users = new Object[1000];

  static int target(int i) {
    if (i == 1) {
      return 997;
    }
    if (i == 99_998) {
      return 998;
    }
    if (i % 10 == 9) {
      return -1;
    }
    if (i < 40_000) {
      return 0;
    }
    if (i < 60_000) {
      return 1;
    }
    double z = ((i * 104729L) % 1_000_003) / 1_000_003.0;
    return 2 + Math.min(994, (int) (Math.exp(z * Math.log(996)) - 1));
  }

  /** Requires an empty database and no active transaction. Commits each original 1000-item batch. */
  public void populate(YTDBGraphTraversalSource g) {
    populate(g, IntStream.range(0, 100_000).toArray());
  }

  // Tests retain original logical numbers, including late singletons, without rescaling topology.
  void populate(YTDBGraphTraversalSource g, int[] items) {
    var graph = (YTDBGraphInternal) g.getGraph();
    if (graph.tx().isOpen()) {
      throw new IllegalStateException("Population requires no active transaction");
    }
    var collections = GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getValue();
    GlobalConfiguration.CLASS_COLLECTIONS_COUNT.setValue(1);
    try {
      graph.tx().readWrite();
      var session = graph.tx().getDatabaseSession();
      for (String name : new String[] {"Article", "Category", "JPEmailContact", "HubUser",
          "OrderedItem"}) {
        var c = session.createVertexClass(name);
        String key = switch (name) {
          case "Article" -> "slug";
          case "Category" -> "uid";
          case "JPEmailContact" -> "email";
          case "OrderedItem" -> "rank";
          default -> null;
        };
        if (key != null) {
          c.createProperty(key, key.equals("rank") ? PropertyType.INTEGER : PropertyType.STRING)
              .createIndex(key.equals("rank") ? SchemaClass.INDEX_TYPE.NOTUNIQUE
                  : SchemaClass.INDEX_TYPE.UNIQUE);
        }
        if (name.equals("JPEmailContact")) {
          c.createProperty("verified", PropertyType.BOOLEAN);
        }
      }
      for (String edge : new String[] {"category_link", "user_link", "a", "b"}) {
        session.createEdgeClass(edge);
      }
      for (int i = 0; i < 1000; i++) {
        categories[i] = graph.addVertex(T.label, "Category", "uid", "u" + i).id();
        users[i] = graph.addVertex(T.label, "HubUser").id();
      }
      graph.tx().commit();
      for (int base = 0; base < items.length; base += 1000) {
        graph.tx().readWrite();
        for (int k = base; k < Math.min(items.length, base + 1000); k++) {
          int i = items[k];
          var a = graph.addVertex(T.label, "Article", "slug", "s" + i);
          graph.addVertex(T.label, "OrderedItem", "rank", i);
          var e = graph.addVertex(T.label, "JPEmailContact", "email", "e" + i + "@test",
              "verified", i % 3 != 0);
          if (i % 10 == 0) {
            a.addEdge("a", graph.vertices(categories[0]).next());
          }
          if (i % 13 == 0) {
            a.addEdge("b", graph.vertices(categories[1]).next());
          }
          int c = target(i);
          if (c >= 0) {
            a.addEdge("category_link", graph.vertices(categories[c]).next());
            e.addEdge("user_link", graph.vertices(users[c]).next());
          }
        }
        graph.tx().commit();
      }
    } catch (RuntimeException | Error failure) {
      graph.tx().rollback();
      java.util.Arrays.fill(categories, null);
      java.util.Arrays.fill(users, null);
      throw failure;
    } finally {
      GlobalConfiguration.CLASS_COLLECTIONS_COUNT.setValue(collections);
    }
  }

  /** Drops only the three indexes removed by the archive. Category.uid stays indexed. */
  public void dropIndexes(YTDBGraphTraversalSource g) {
    g.executeInTx(t -> {
      ((YTDBGraphInternal) t.getGraph()).tx().readWrite();
      var session = ((YTDBGraphInternal) t.getGraph()).tx().getDatabaseSession();
      for (String index : new String[] {"Article.slug", "JPEmailContact.email",
          "OrderedItem.rank"}) {
        session.getSharedContext().getIndexManager().dropIndex(session, index);
      }
    });
  }

  public GraphTraversal<?, ?> traversal(YTDBGraphTraversalSource g,
      T5BenchmarkCatalogue.WhereForm c) {
    return switch (c.q()) {
      case "5" -> g.V().has("slug", c.slug()).where(__.out("category_link")
          .hasId(Objects.requireNonNull(categories[c.cat()], "Populate fixture first")))
          .hasLabel("Article");
      case "H", "U2" -> g.V().and(__.where(__.out("user_link")
          .hasId(Objects.requireNonNull(users[c.cat()], "Populate fixture first"))),
          __.has("email", c.email()), __.has("verified", true)).hasLabel("JPEmailContact");
      case "U1" -> g.V().hasLabel("OrderedItem").order().by("rank", Order.desc).limit(20);
      case "CONTROL" -> g.V().hasLabel("Category");
      case "LONG" -> longPrefix(g).fold(0, (n, v) -> n + 1);
      default -> throw new IllegalArgumentException(c.q());
    };
  }

  /** One indexed singleton, then twelve reversible pairs. Every intermediate result has size one. */
  static GraphTraversal<?, ?> longPrefix(YTDBGraphTraversalSource g) {
    var t = g.V().hasLabel("Category").has("uid", "u997");
    for (int i = 0; i < 12; i++) {
      t = t.in("category_link").out("category_link");
    }
    return t;
  }
}
