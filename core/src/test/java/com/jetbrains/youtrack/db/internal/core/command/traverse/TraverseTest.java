package com.jetbrains.youtrack.db.internal.core.command.traverse;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class TraverseTest extends DbTestBase {

  @Test
  public void testDepthTraverse() {
    EntityImpl rootDocument;
    Traverse traverse;

    session.begin();
    rootDocument = (EntityImpl) session.newEntity();

    final var aa = (EntityImpl) session.newEntity();
    final var ab = (EntityImpl) session.newEntity();
    final var ba = (EntityImpl) session.newEntity();
    final var bb = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    a.setProperty("aa", aa, PropertyType.LINK);
    a.setProperty("ab", ab, PropertyType.LINK);
    final var b = (EntityImpl) session.newEntity();
    b.setProperty("ba", ba, PropertyType.LINK);
    b.setProperty("bb", bb, PropertyType.LINK);

    rootDocument.setProperty("a", a, PropertyType.LINK);
    rootDocument.setProperty("b", b, PropertyType.LINK);

    final var c1 = (EntityImpl) session.newEntity();
    final var c1a = (EntityImpl) session.newEntity();
    c1.setProperty("c1a", c1a, PropertyType.LINK);
    final var c1b = (EntityImpl) session.newEntity();
    c1.setProperty("c1b", c1b, PropertyType.LINK);
    final var c2 = (EntityImpl) session.newEntity();
    final var c2a = (EntityImpl) session.newEntity();
    c2.setProperty("c2a", c2a, PropertyType.LINK);
    final var c2b = (EntityImpl) session.newEntity();
    c2.setProperty("c2b", c2b, PropertyType.LINK);
    final var c3 = (EntityImpl) session.newEntity();
    final var c3a = (EntityImpl) session.newEntity();
    c3.setProperty("c3a", c3a, PropertyType.LINK);
    final var c3b = (EntityImpl) session.newEntity();
    c3.setProperty("c3b", c3b, PropertyType.LINK);
    rootDocument.getOrCreateLinkList("c").addAll(new ArrayList<>(Arrays.asList(c1, c2, c3)));

    session.commit();

    session.begin();
    rootDocument = session.bindToSession(rootDocument);
    final var expectedResult =
        Arrays.asList(
            rootDocument,
            session.bindToSession(a),
            session.bindToSession(aa),
            session.bindToSession(ab),
            session.bindToSession(b),
            session.bindToSession(ba),
            session.bindToSession(bb),
            session.bindToSession(c1),
            session.bindToSession(c1a),
            session.bindToSession(c1b),
            session.bindToSession(c2),
            session.bindToSession(c2a),
            session.bindToSession(c2b),
            session.bindToSession(c3),
            session.bindToSession(c3a),
            session.bindToSession(c3b));

    traverse = new Traverse(session);
    traverse.target(rootDocument).fields("*");
    final var results = traverse.execute(session);

    compareTraverseResults(expectedResult, results);
    session.commit();
  }

  @Test
  public void testBreadthTraverse() throws Exception {
    EntityImpl rootDocument;
    Traverse traverse;


    session.begin();
    rootDocument = (EntityImpl) session.newEntity();
    final var aa = (EntityImpl) session.newEntity();
    final var ab = (EntityImpl) session.newEntity();
    final var ba = (EntityImpl) session.newEntity();
    final var bb = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    a.setProperty("aa", aa, PropertyType.LINK);
    a.setProperty("ab", ab, PropertyType.LINK);
    final var b = (EntityImpl) session.newEntity();
    b.setProperty("ba", ba, PropertyType.LINK);
    b.setProperty("bb", bb, PropertyType.LINK);

    rootDocument.setProperty("a", a, PropertyType.LINK);
    rootDocument.setProperty("b", b, PropertyType.LINK);

    final var c1 = (EntityImpl) session.newEntity();
    final var c1a = (EntityImpl) session.newEntity();
    c1.setProperty("c1a", c1a, PropertyType.LINK);
    final var c1b = (EntityImpl) session.newEntity();
    c1.setProperty("c1b", c1b, PropertyType.LINK);
    final var c2 = (EntityImpl) session.newEntity();
    final var c2a = (EntityImpl) session.newEntity();
    c2.setProperty("c2a", c2a, PropertyType.LINK);
    final var c2b = (EntityImpl) session.newEntity();
    c2.setProperty("c2b", c2b, PropertyType.LINK);
    final var c3 = (EntityImpl) session.newEntity();
    final var c3a = (EntityImpl) session.newEntity();
    c3.setProperty("c3a", c3a, PropertyType.LINK);
    final var c3b = (EntityImpl) session.newEntity();
    c3.setProperty("c3b", c3b, PropertyType.LINK);
    rootDocument.getOrCreateLinkList("c").addAll(new ArrayList<>(Arrays.asList(c1, c2, c3)));
    session.commit();

    session.begin();
    rootDocument = session.bindToSession(rootDocument);
    traverse = new Traverse(session);

    traverse.target(rootDocument).fields("*");
    traverse.setStrategy(Traverse.STRATEGY.BREADTH_FIRST);

    final var expectedResult =
        Arrays.asList(
            rootDocument,
            session.bindToSession(a),
            session.bindToSession(b),
            session.bindToSession(aa),
            session.bindToSession(ab),
            session.bindToSession(ba),
            session.bindToSession(bb),
            session.bindToSession(c1),
            session.bindToSession(c2),
            session.bindToSession(c3),
            session.bindToSession(c1a),
            session.bindToSession(c1b),
            session.bindToSession(c2a),
            session.bindToSession(c2b),
            session.bindToSession(c3a),
            session.bindToSession(c3b));
    final var results = traverse.execute(session);
    compareTraverseResults(expectedResult, results);
    session.rollback();
  }

  private void compareTraverseResults(List<EntityImpl> expectedResult,
      List<Identifiable> results) {
    var equality = results.size() == expectedResult.size();
    for (var i = 0; i < expectedResult.size() && equality; i++) {
      equality &= results.get(i).equals(expectedResult.get(i));
    }
    System.out.println("Expected: " + expectedResult);
    System.out.println();
    System.out.println("Actual:   " + results);
    Assert.assertTrue(equality);
  }
}
