package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Seeds TinkerPop's six-vertex "modern" graph — four {@code Person} vertices, two {@code Software}
 * vertices, two {@code knows} edges and four {@code created} edges — shared by the two equivalence
 * fixtures that need it.
 *
 * <p>Two properties of this topology are what the per-alias-filter cases rely on, and neither holds
 * in a smaller fixture:
 *
 * <ul>
 *   <li><b>{@code marko} has three out-neighbours, only one of which any single filter selects.</b>
 *       A post-hop {@code has(...)} / {@code hasId(...)} / {@code hasLabel(...)} therefore returns
 *       one row natively and three rows if the target alias's filter is discarded, so the
 *       comparison is discriminating rather than vacuous.
 *   <li><b>{@code josh} and {@code peter} have out-edges but none to a {@code Person}.</b> That is
 *       what makes {@code not(out().hasLabel(Person))} distinguishable from {@code not(out())}: five
 *       vertices survive the class-carrying form, three survive the class-dropped one. A fixture
 *       whose only edge-bearing vertex points at a {@code Person} cannot tell them apart.
 * </ul>
 *
 * <p>Vertex count is load-bearing for the {@code where(...)} fragment case as well: root selection
 * scores a pinned origin at its RID count and a filtered target at half the class count, so two
 * pinned RIDs beat six vertices' worth of target estimate and the fragment's filter lands on the
 * non-root side — which is the side this fixture exists to witness.
 *
 * <p>Edge {@code weight} is declared {@code DOUBLE} on both edge classes so the fractional weights
 * TinkerPop's fixture uses (0.5, 0.4, 0.2) round-trip, which the edge-filter preservation case
 * compares against.
 */
final class ModernGraphFixture {

  private ModernGraphFixture() {
    // Static fixture seeder — no instances.
  }

  /**
   * The six seeded vertices, named as TinkerPop's modern graph names them so a test body reads the
   * same as the traversal it mirrors.
   */
  record Modern(Vertex marko, Vertex vadas, Vertex lop, Vertex josh, Vertex ripple, Vertex peter) {
  }

  /**
   * Creates the schema, seeds the six vertices and six edges, and commits. Returns the vertices so
   * callers can pin RIDs with {@code g.V(id)}.
   */
  static Modern seed(YTDBGraph graph, DatabaseSessionEmbedded session) {
    session.createVertexClass("Person");
    session.createVertexClass("Software");
    session.createEdgeClass("knows").createProperty("weight", PropertyType.DOUBLE);
    session.createEdgeClass("created").createProperty("weight", PropertyType.DOUBLE);

    var marko = graph.addVertex(T.label, "Person", "name", "marko", "age", 29);
    var vadas = graph.addVertex(T.label, "Person", "name", "vadas", "age", 27);
    var lop = graph.addVertex(T.label, "Software", "name", "lop", "lang", "java");
    var josh = graph.addVertex(T.label, "Person", "name", "josh", "age", 32);
    var ripple = graph.addVertex(T.label, "Software", "name", "ripple", "lang", "java");
    var peter = graph.addVertex(T.label, "Person", "name", "peter", "age", 35);

    marko.addEdge("knows", vadas, "weight", 0.5d);
    marko.addEdge("knows", josh, "weight", 1.0d);
    marko.addEdge("created", lop, "weight", 0.4d);
    josh.addEdge("created", ripple, "weight", 1.0d);
    josh.addEdge("created", lop, "weight", 0.4d);
    peter.addEdge("created", lop, "weight", 0.2d);
    graph.tx().commit();

    return new Modern(marko, vadas, lop, josh, ripple, peter);
  }
}
