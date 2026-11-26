package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.GlobalConfigurationScope;
import org.junit.Test;

/**
 * A MATCH node with both {@code class:} and a literal {@code rid:} must treat them as a
 * conjunction. A RID from an unrelated class yields no rows; a RID from a subclass still matches a
 * superclass alias.
 */
public class MatchClassLiteralRidTest extends DbTestBase {

  /**
   * Issue example: source RID belongs to {@code Other}, alias demands
   * {@code StartPerson}. Before the fix the RID-only fetch dropped the class and
   * the outgoing edge still returned destinations.
   */
  @Test
  public void literalRidOutsideAliasClassReturnsNoRows() {
    session.execute("CREATE class StartPerson extends V").close();
    session.execute("CREATE class Other extends V").close();
    session.execute("CREATE class Knows extends E").close();

    session.begin();
    var other = session.newVertex("Other");
    other.setProperty("name", "src");
    var target = session.newVertex("Other");
    target.setProperty("name", "dst");
    session.commit();
    var otherRid = other.getIdentity();
    var targetRid = target.getIdentity();

    session.begin();
    session.execute("CREATE EDGE Knows FROM " + otherRid + " TO " + targetRid).close();
    session.commit();

    session.begin();
    try (var wrong =
        session.query(
            "MATCH {class: StartPerson, rid: " + otherRid + ", as: s}"
                + ".out('Knows'){as: t} RETURN t")) {
      assertFalse(
          "a RID from an unrelated class must not satisfy a class-constrained alias",
          wrong.hasNext());
    }
    try (var right =
        session.query(
            "MATCH {class: Other, rid: " + otherRid + ", as: s}"
                + ".out('Knows'){as: t} RETURN t")) {
      assertEquals(
          "the same RID under its own class must still traverse",
          1,
          right.stream().count());
    }
    session.commit();
  }

  /**
   * Polymorphic {@code class:} includes subclasses: a subclass RID under a
   * superclass alias remains a match.
   */
  @Test
  public void literalRidOfSubclassMatchesSuperclassAlias() {
    session.execute("CREATE class RidPolyBase extends V").close();
    session.execute("CREATE class RidPolySub extends RidPolyBase").close();

    session.begin();
    var sub = session.newVertex("RidPolySub");
    sub.setProperty("name", "sub");
    session.commit();
    var subRid = sub.getIdentity();

    session.begin();
    try (var result =
        session.query(
            "MATCH {class: RidPolyBase, rid: " + subRid + ", as: a} RETURN a")) {
      assertEquals(
          "a subclass RID must satisfy a superclass alias",
          1,
          result.stream().count());
    }
    session.commit();
  }

  /**
   * Same conjunction on the index-ordered single-source path: wrong-class
   * {@code rid:} yields no rows; correct-class {@code rid:} still uses the
   * index-ordered step.
   */
  @Test
  public void literalRidOutsideClassEmptyOnIndexOrderedPath() {
    session.execute("CREATE class IoPerson extends V").close();
    session.execute("CREATE class IoWrong extends V").close();
    session.execute("CREATE class IoMessage extends V").close();
    session.execute("CREATE property IoMessage.ts LONG").close();
    session.execute("CREATE index IoMessage_ts on IoMessage (ts) NOTUNIQUE").close();
    session.execute("CREATE class IoCreated extends E").close();

    session.begin();
    var person = session.newVertex("IoPerson");
    person.setProperty("name", "p");
    var wrong = session.newVertex("IoWrong");
    wrong.setProperty("name", "w");
    for (var i = 0; i < 20; i++) {
      var msg = session.newVertex("IoMessage");
      msg.setProperty("ts", (long) i);
      session.newEdge(wrong, msg, "IoCreated");
      session.newEdge(person, msg, "IoCreated");
    }
    session.commit();
    var wrongRid = wrong.getIdentity();
    var personRid = person.getIdentity();

    try (var cfg =
        GlobalConfigurationScope.set(GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 1)) {
      session.begin();
      try (var empty =
          session.query(
              "MATCH {class: IoPerson, rid: " + wrongRid + ", as: p}"
                  + ".out('IoCreated'){class: IoMessage, as: m} "
                  + "RETURN m.ts as ts ORDER BY ts ASC LIMIT 5")) {
        assertFalse(
            "index-ordered single-source must not load a LinkBag for a wrong-class rid",
            empty.hasNext());
      }
      try (var ok =
          session.query(
              "MATCH {class: IoPerson, rid: " + personRid + ", as: p}"
                  + ".out('IoCreated'){class: IoMessage, as: m} "
                  + "RETURN m.ts as ts ORDER BY ts ASC LIMIT 5")) {
        var plan = ok.getExecutionPlan().prettyPrint(0, 2);
        assertTrue(
            "correct-class rid: must still use INDEX ORDERED MATCH, plan was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        assertEquals(5, ok.stream().count());
      }
      session.commit();
    }
  }
}
