package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Test;

/**
 * Integration tests for the correlated optional hash join optimization (IC7 pattern).
 *
 * <pre>
 *   Graph:
 *     Person(alice, id=1) --KNOWS--> Person(bob, id=2)
 *     Person(alice, id=1) --KNOWS--> Person(carol, id=3)
 *     Person(alice, id=1) --HAS_CREATOR-- Message(m1)
 *     Person(alice, id=1) --HAS_CREATOR-- Message(m2)
 *     Message(m1) <--LIKES-- Person(bob, id=2)   (bob likes alice's message)
 *     Message(m2) <--LIKES-- Person(dave, id=4)  (dave likes alice's message)
 *
 *   IC7-like query pattern:
 *     MATCH {class:Person, as:startPerson, where:(id=1)}
 *       .in('HAS_CREATOR'){as:message}
 *       .in('LIKES'){as:liker}
 *       .out('KNOWS'){as:knowsStart, where:(@rid = $matched.startPerson.@rid), optional:true}
 *
 *   Expected: bob (liker of m1, KNOWS alice) → knowsStart = alice
 *             dave (liker of m2, does NOT know alice) → knowsStart = null
 * </pre>
 */
public class CorrelatedOptionalHashJoinTest extends DbTestBase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();

    session.execute("CREATE class Person extends V").close();
    session.execute("CREATE class Message extends V").close();
    session.execute("CREATE class KNOWS extends E").close();
    session.execute("CREATE class HAS_CREATOR extends E").close();
    session.execute("CREATE class LIKES extends E").close();

    session.begin();
    session.execute("CREATE VERTEX Person set name='alice', id=1").close();
    session.execute("CREATE VERTEX Person set name='bob', id=2").close();
    session.execute("CREATE VERTEX Person set name='carol', id=3").close();
    session.execute("CREATE VERTEX Person set name='dave', id=4").close();
    session.execute("CREATE VERTEX Message set content='m1'").close();
    session.execute("CREATE VERTEX Message set content='m2'").close();

    // KNOWS edges: bob→alice, carol→alice (bob and carol KNOW alice)
    session.execute(
        "CREATE EDGE KNOWS from (select from Person where name='bob')"
            + " to (select from Person where name='alice')")
        .close();
    session.execute(
        "CREATE EDGE KNOWS from (select from Person where name='carol')"
            + " to (select from Person where name='alice')")
        .close();

    // HAS_CREATOR edges: m1→alice, m2→alice
    session.execute(
        "CREATE EDGE HAS_CREATOR from (select from Message where content='m1')"
            + " to (select from Person where name='alice')")
        .close();
    session.execute(
        "CREATE EDGE HAS_CREATOR from (select from Message where content='m2')"
            + " to (select from Person where name='alice')")
        .close();

    // LIKES edges: bob→m1, dave→m2
    session.execute(
        "CREATE EDGE LIKES from (select from Person where name='bob')"
            + " to (select from Message where content='m1')")
        .close();
    session.execute(
        "CREATE EDGE LIKES from (select from Person where name='dave')"
            + " to (select from Message where content='m2')")
        .close();
    session.commit();
  }

  /**
   * EXPLAIN should show CORRELATED OPTIONAL HASH JOIN for the KNOWS edge.
   */
  @Test
  public void explain_optionalCorrelatedEdge_usesHashJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:startPerson, where:(id=1)}"
            + ".in('HAS_CREATOR'){as:message}"
            + ".in('LIKES'){as:liker}"
            + ".out('KNOWS'){as:knowsStart,"
            + " where:(@rid = $matched.startPerson.@rid), optional:true}"
            + " RETURN liker.name as likerName, knowsStart")
        .toList();
    assertEquals(1, result.size());
    String plan = (String) result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should use CORRELATED OPTIONAL HASH JOIN, got:\n" + plan,
        plan.contains("CORRELATED OPTIONAL HASH JOIN"));
    session.commit();
  }

  /**
   * Correctness: bob likes m1, bob KNOWS alice → knowsStart = alice vertex.
   * dave likes m2, dave does NOT know alice → knowsStart = null.
   */
  @Test
  public void correlatedOptional_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:startPerson, where:(id=1)}"
            + ".in('HAS_CREATOR'){as:message}"
            + ".in('LIKES'){as:liker}"
            + ".out('KNOWS'){as:knowsStart,"
            + " where:(@rid = $matched.startPerson.@rid), optional:true}"
            + " RETURN liker.name as likerName, knowsStart.name as knowsName")
        .toList();

    assertEquals(2, result.size());
    for (var row : result) {
      var likerName = (String) row.getProperty("likerName");
      if ("bob".equals(likerName)) {
        assertEquals("alice", row.getProperty("knowsName"));
      } else if ("dave".equals(likerName)) {
        assertNull("dave should not know alice", row.getProperty("knowsName"));
      }
    }
    session.commit();
  }

  /**
   * All likers are friends of startPerson → all rows have knowsStart set.
   */
  @Test
  public void correlatedOptional_allMatch() {
    session.begin();
    // Add dave→alice KNOWS edge so both likers (bob, dave) know alice
    session.execute(
        "CREATE EDGE KNOWS from (select from Person where name='dave')"
            + " to (select from Person where name='alice')")
        .close();

    var result = session.query(
        "MATCH {class:Person, as:startPerson, where:(id=1)}"
            + ".in('HAS_CREATOR'){as:message}"
            + ".in('LIKES'){as:liker}"
            + ".out('KNOWS'){as:knowsStart,"
            + " where:(@rid = $matched.startPerson.@rid), optional:true}"
            + " RETURN liker.name as likerName, knowsStart.name as knowsName")
        .toList();

    assertEquals(2, result.size());
    for (var row : result) {
      assertEquals("alice", row.getProperty("knowsName"));
    }
    session.rollback();
  }

  /**
   * startPerson has no KNOWS edges → all likers get knowsStart = null.
   */
  @Test
  public void correlatedOptional_noKnowsEdges() {
    session.begin();
    // Use carol (id=3) who has no messages and no one knows her
    // Actually, let's use a Person with messages but no KNOWS edges
    session.execute("CREATE VERTEX Person set name='lonely', id=5").close();
    session.execute("CREATE VERTEX Message set content='m3'").close();
    session.execute(
        "CREATE EDGE HAS_CREATOR from (select from Message where content='m3')"
            + " to (select from Person where name='lonely')")
        .close();
    session.execute(
        "CREATE EDGE LIKES from (select from Person where name='bob')"
            + " to (select from Message where content='m3')")
        .close();

    var result = session.query(
        "MATCH {class:Person, as:startPerson, where:(id=5)}"
            + ".in('HAS_CREATOR'){as:message}"
            + ".in('LIKES'){as:liker}"
            + ".out('KNOWS'){as:knowsStart,"
            + " where:(@rid = $matched.startPerson.@rid), optional:true}"
            + " RETURN liker.name as likerName, knowsStart")
        .toList();

    assertEquals(1, result.size());
    assertNull("no KNOWS edges → knowsStart should be null",
        result.get(0).getProperty("knowsStart"));
    session.rollback();
  }

  /**
   * Verifies that the correlated optional hash join correctly rebuilds the
   * neighbor set when the correlated alias value changes across upstream rows.
   *
   * Graph extension: two startPersons (alice id=1, bob id=2) each have messages
   * liked by different people. The correlated KNOWS edge references
   * $matched.startPerson, so the hash join must re-evaluate the build side
   * for each distinct startPerson value.
   *
   * alice (id=1): messages m1 liked by bob, m2 liked by dave
   *   bob KNOWS alice → knowsStart = alice
   *   dave does NOT know alice → knowsStart = null
   *
   * bob (id=2): create message m4 liked by carol
   *   carol KNOWS alice but NOT bob → knowsStart = null for bob's context
   */
  @Test
  public void correlatedOptional_correlatedValueChanges_rebuildsNeighborSet() {
    session.begin();
    // Create message m4 owned by bob, liked by carol
    session.execute("CREATE VERTEX Message set content='m4'").close();
    session.execute(
        "CREATE EDGE HAS_CREATOR from (select from Message where content='m4')"
            + " to (select from Person where name='bob')")
        .close();
    session.execute(
        "CREATE EDGE LIKES from (select from Person where name='carol')"
            + " to (select from Message where content='m4')")
        .close();

    // Query with multiple startPerson values — the correlated alias changes
    var result = session.query(
        "MATCH {class:Person, as:startPerson, where:(id in [1, 2])}"
            + ".in('HAS_CREATOR'){as:message}"
            + ".in('LIKES'){as:liker}"
            + ".out('KNOWS'){as:knowsStart,"
            + " where:(@rid = $matched.startPerson.@rid), optional:true}"
            + " RETURN startPerson.name as spName,"
            + " liker.name as likerName, knowsStart.name as knowsName")
        .toList();

    // alice's context: bob→alice (match), dave→null (no match)
    // bob's context: carol→null (carol KNOWS alice, not bob)
    assertTrue("should return at least 3 rows", result.size() >= 3);

    for (var row : result) {
      var sp = (String) row.getProperty("spName");
      var liker = (String) row.getProperty("likerName");
      var knows = (String) row.getProperty("knowsName");
      if ("alice".equals(sp) && "bob".equals(liker)) {
        // bob KNOWS alice → knowsStart = alice
        assertEquals("alice", knows);
      } else if ("alice".equals(sp) && "dave".equals(liker)) {
        // dave does NOT know alice
        assertNull("dave should not know alice", knows);
      } else if ("bob".equals(sp) && "carol".equals(liker)) {
        // carol KNOWS alice, NOT bob → knowsStart should be null
        assertNull("carol should not know bob", knows);
      }
    }
    session.rollback();
  }
}
