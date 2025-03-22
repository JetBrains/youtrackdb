package com.jetbrains.youtrack.db.internal.core.command.traverse;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
    var activeTx15 = session.getActiveTransaction();
    rootDocument = activeTx15.load(rootDocument);
    var activeTx = session.getActiveTransaction();
    var activeTx1 = session.getActiveTransaction();
    var activeTx2 = session.getActiveTransaction();
    var activeTx3 = session.getActiveTransaction();
    var activeTx4 = session.getActiveTransaction();
    var activeTx5 = session.getActiveTransaction();
    var activeTx6 = session.getActiveTransaction();
    var activeTx7 = session.getActiveTransaction();
    var activeTx8 = session.getActiveTransaction();
    var activeTx9 = session.getActiveTransaction();
    var activeTx10 = session.getActiveTransaction();
    var activeTx11 = session.getActiveTransaction();
    var activeTx12 = session.getActiveTransaction();
    var activeTx13 = session.getActiveTransaction();
    var activeTx14 = session.getActiveTransaction();
    final var expectedResult =
        new HashSet<>(Arrays.asList(
            rootDocument,
            activeTx14.load(a),
            activeTx13.load(aa),
            activeTx12.load(ab),
            activeTx11.load(b),
            activeTx10.load(ba),
            activeTx9.load(bb),
            activeTx8.load(c1),
            activeTx7.load(c1a),
            activeTx6.load(c1b),
            activeTx5.load(c2),
            activeTx4.load(c2a),
            activeTx3.load(c2b),
            activeTx2.load(c3),
            activeTx1.load(c3a),
            activeTx.load(c3b)));

    traverse = new Traverse(session);
    traverse.target(rootDocument).fields("*");
    final var results = new HashSet<>(traverse.execute(session));

    Assert.assertEquals(expectedResult, results);
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
    var activeTx15 = session.getActiveTransaction();
    rootDocument = activeTx15.load(rootDocument);
    traverse = new Traverse(session);

    traverse.target(rootDocument).fields("*");
    traverse.setStrategy(Traverse.STRATEGY.BREADTH_FIRST);

    var activeTx = session.getActiveTransaction();
    var activeTx1 = session.getActiveTransaction();
    var activeTx2 = session.getActiveTransaction();
    var activeTx3 = session.getActiveTransaction();
    var activeTx4 = session.getActiveTransaction();
    var activeTx5 = session.getActiveTransaction();
    var activeTx6 = session.getActiveTransaction();
    var activeTx7 = session.getActiveTransaction();
    var activeTx8 = session.getActiveTransaction();
    var activeTx9 = session.getActiveTransaction();
    var activeTx10 = session.getActiveTransaction();
    var activeTx11 = session.getActiveTransaction();
    var activeTx12 = session.getActiveTransaction();
    var activeTx13 = session.getActiveTransaction();
    var activeTx14 = session.getActiveTransaction();
    final var expectedResult =
        new HashSet<>(Arrays.asList(
            rootDocument,
            activeTx14.load(a),
            activeTx13.load(b),
            activeTx12.load(aa),
            activeTx11.load(ab),
            activeTx10.load(ba),
            activeTx9.load(bb),
            activeTx8.load(c1),
            activeTx7.load(c2),
            activeTx6.load(c3),
            activeTx5.load(c1a),
            activeTx4.load(c1b),
            activeTx3.load(c2a),
            activeTx2.load(c2b),
            activeTx1.load(c3a),
            activeTx.load(c3b)));
    final var results = new HashSet<>(traverse.execute(session));
    Assert.assertEquals(expectedResult, results);
    session.rollback();
  }


}
