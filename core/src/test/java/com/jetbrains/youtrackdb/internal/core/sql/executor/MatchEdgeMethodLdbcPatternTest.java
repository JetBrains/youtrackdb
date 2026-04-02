package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Integration tests that validate edge-method MATCH patterns modeled after LDBC
 * Social Network Benchmark queries IC5 and IC11. These are real-world query
 * shapes that natively use indexed edge properties for selective filtering.
 *
 * <p>IC11: Person→WORK_AT(workFrom < threshold)→Company
 * <p>IC5: Person→KNOWS→...→HAS_MEMBER(joinDate >= threshold)→Forum→CONTAINER_OF→Post
 *
 * <p>Each test builds a simplified graph structure matching the LDBC pattern,
 * then runs the MATCH query and verifies correctness.
 */
public class MatchEdgeMethodLdbcPatternTest extends DbTestBase {

  // ---- IC11: Person→WORK_AT(workFrom < threshold)→Company ----

  /**
   * IC11-style pattern: find companies where a person has worked since before a
   * given year, using {@code outE('WORK_AT'){where: (workFrom < ?)}.inV()}.
   *
   * <p>Graph: 8 persons, 4 companies. Each person works at one company with
   * a different workFrom year. Query filters workFrom < 2014 (4 matches).
   */
  @Test
  public void testIC11WorkAtPattern() {
    // Schema
    session.execute("CREATE class IC11Person extends V").close();
    session.execute("CREATE property IC11Person.name STRING").close();

    session.execute("CREATE class IC11Company extends V").close();
    session.execute("CREATE property IC11Company.name STRING").close();

    session.execute("CREATE class IC11WorkAt extends E").close();
    session.execute("CREATE property IC11WorkAt.out LINK IC11Person").close();
    session.execute("CREATE property IC11WorkAt.in LINK IC11Company").close();
    session.execute("CREATE property IC11WorkAt.workFrom INTEGER").close();
    session.execute(
        "CREATE index IC11WorkAt_workFrom on IC11WorkAt (workFrom) NOTUNIQUE")
        .close();

    // Data: 8 persons, 4 companies
    session.begin();
    for (int i = 0; i < 4; i++) {
      session.execute("CREATE VERTEX IC11Company set name = 'corp" + i + "'")
          .close();
    }
    for (int i = 0; i < 8; i++) {
      session.execute("CREATE VERTEX IC11Person set name = 'alice" + i + "'")
          .close();
      // workFrom: 2010, 2011, ..., 2017
      session.execute(
          "CREATE EDGE IC11WorkAt FROM"
              + " (SELECT FROM IC11Person WHERE name = 'alice" + i + "')"
              + " TO (SELECT FROM IC11Company WHERE name = 'corp" + (i % 4) + "')"
              + " SET workFrom = " + (2010 + i))
          .close();
    }
    session.commit();

    // Query: find persons who started working before 2014
    session.begin();
    var query =
        "MATCH {class: IC11Person, as: person}"
            + ".outE('IC11WorkAt'){where: (workFrom < 2014)}"
            + ".inV(){as: company}"
            + " RETURN person.name, company.name";
    var result = session.query(query).toList();

    // persons alice0-alice3 have workFrom 2010-2013 (< 2014)
    assertEquals(4, result.size());

    Set<String> pairs = new HashSet<>();
    for (var r : result) {
      pairs.add(r.getProperty("person.name") + "->"
          + r.getProperty("company.name"));
    }
    Set<String> expected = new HashSet<>();
    for (int i = 0; i < 4; i++) {
      expected.add("alice" + i + "->corp" + (i % 4));
    }
    assertEquals(expected, pairs);

    session.commit();
  }

  /**
   * IC11 variation: query with ORDER BY on the edge property (workFrom) and
   * LIMIT, mimicking the actual IC11 "top N earliest employers" pattern.
   */
  @Test
  public void testIC11WorkAtWithOrderAndLimit() {
    // Schema
    session.execute("CREATE class IC11bPerson extends V").close();
    session.execute("CREATE property IC11bPerson.name STRING").close();

    session.execute("CREATE class IC11bCompany extends V").close();
    session.execute("CREATE property IC11bCompany.name STRING").close();

    session.execute("CREATE class IC11bWorkAt extends E").close();
    session.execute("CREATE property IC11bWorkAt.out LINK IC11bPerson").close();
    session.execute("CREATE property IC11bWorkAt.in LINK IC11bCompany").close();
    session.execute("CREATE property IC11bWorkAt.workFrom INTEGER").close();
    session.execute(
        "CREATE index IC11bWorkAt_workFrom on IC11bWorkAt (workFrom) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX IC11bCompany set name = 'megacorp'").close();
    // 6 persons all working at the same company with different years
    for (int i = 0; i < 6; i++) {
      session.execute(
          "CREATE VERTEX IC11bPerson set name = 'emp" + i + "'").close();
      session.execute(
          "CREATE EDGE IC11bWorkAt FROM"
              + " (SELECT FROM IC11bPerson WHERE name = 'emp" + i + "')"
              + " TO (SELECT FROM IC11bCompany WHERE name = 'megacorp')"
              + " SET workFrom = " + (2010 + i))
          .close();
    }
    session.commit();

    // Query: find the 3 earliest employees (workFrom < 2016, ORDER BY workFrom)
    session.begin();
    var query =
        "MATCH {class: IC11bPerson, as: person}"
            + ".outE('IC11bWorkAt'){as: wa, where: (workFrom < 2016)}"
            + ".inV(){as: company}"
            + " RETURN person.name, wa.workFrom"
            + " ORDER BY wa.workFrom ASC LIMIT 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    // Earliest 3: emp0 (2010), emp1 (2011), emp2 (2012)
    assertEquals("emp0", result.get(0).getProperty("person.name"));
    assertEquals(2010, (int) result.get(0).getProperty("wa.workFrom"));
    assertEquals("emp1", result.get(1).getProperty("person.name"));
    assertEquals(2011, (int) result.get(1).getProperty("wa.workFrom"));
    assertEquals("emp2", result.get(2).getProperty("person.name"));
    assertEquals(2012, (int) result.get(2).getProperty("wa.workFrom"));

    session.commit();
  }

  // ---- IC5: Person→HAS_MEMBER(joinDate >= threshold)→Forum ----

  /**
   * IC5-style pattern: find forums a person joined after a given date, using
   * {@code inE('HAS_MEMBER'){where: (joinDate >= ?)}.outV()}.
   *
   * <p>Graph: 6 persons, 3 forums. Each person is a member of one forum with
   * a different joinDate. Query filters joinDate >= 1400 (2 matches).
   */
  @Test
  public void testIC5HasMemberPattern() {
    // Schema
    session.execute("CREATE class IC5Person extends V").close();
    session.execute("CREATE property IC5Person.name STRING").close();

    session.execute("CREATE class IC5Forum extends V").close();
    session.execute("CREATE property IC5Forum.title STRING").close();

    session.execute("CREATE class IC5HasMember extends E").close();
    session.execute("CREATE property IC5HasMember.out LINK IC5Person").close();
    session.execute("CREATE property IC5HasMember.in LINK IC5Forum").close();
    session.execute("CREATE property IC5HasMember.joinDate LONG").close();
    session.execute(
        "CREATE index IC5HasMember_joinDate on IC5HasMember (joinDate) NOTUNIQUE")
        .close();

    // Data: 6 persons, 3 forums
    session.begin();
    for (int i = 0; i < 3; i++) {
      session.execute("CREATE VERTEX IC5Forum set title = 'board" + i + "'")
          .close();
    }
    for (int i = 0; i < 6; i++) {
      session.execute("CREATE VERTEX IC5Person set name = 'bob" + i + "'")
          .close();
      // joinDate: 1000, 1100, 1200, 1300, 1400, 1500
      session.execute(
          "CREATE EDGE IC5HasMember FROM"
              + " (SELECT FROM IC5Person WHERE name = 'bob" + i + "')"
              + " TO (SELECT FROM IC5Forum WHERE title = 'board" + (i % 3) + "')"
              + " SET joinDate = " + (1000 + i * 100))
          .close();
    }
    session.commit();

    // Query from Forum side: find persons who joined on or after 1400
    session.begin();
    var query =
        "MATCH {class: IC5Forum, as: forum}"
            + ".inE('IC5HasMember'){where: (joinDate >= 1400)}"
            + ".outV(){as: person}"
            + " RETURN person.name, forum.title";
    var result = session.query(query).toList();

    // bob4 (1400) and bob5 (1500) match
    assertEquals(2, result.size());

    Set<String> pairs = new HashSet<>();
    for (var r : result) {
      pairs.add(r.getProperty("person.name") + "->"
          + r.getProperty("forum.title"));
    }
    assertEquals(Set.of("bob4->board1", "bob5->board2"), pairs);

    session.commit();
  }

  // ---- Multi-hop chain: edge-method traversal mixed with regular traversals ----

  /**
   * Multi-hop chain combining regular traversals with edge-method traversals,
   * modeled after the IC5 pattern:
   * {@code Person.out('KNOWS').inE('HAS_MEMBER'){where: joinDate >= ?}.outV()}.
   *
   * <p>Graph structure:
   * <pre>
   *   startPerson --KNOWS--> friend1, friend2, friend3, friend4
   *   friend1 --HAS_MEMBER(joinDate=1000)--> forumA
   *   friend2 --HAS_MEMBER(joinDate=2000)--> forumB
   *   friend3 --HAS_MEMBER(joinDate=3000)--> forumC
   *   friend4 has NO HAS_MEMBER edge (dangling path — must be silently excluded)
   * </pre>
   *
   * <p>Query: starting from startPerson, traverse KNOWS to friends, then use
   * edge-method pattern to filter by joinDate >= 2000. Should return friend2
   * and friend3 with their forums.
   */
  @Test
  public void testMultiHopChainWithEdgeMethodTraversal() {
    // Schema
    session.execute("CREATE class MCPerson extends V").close();
    session.execute("CREATE property MCPerson.name STRING").close();

    session.execute("CREATE class MCForum extends V").close();
    session.execute("CREATE property MCForum.title STRING").close();

    session.execute("CREATE class MCKnows extends E").close();
    session.execute("CREATE property MCKnows.out LINK MCPerson").close();
    session.execute("CREATE property MCKnows.in LINK MCPerson").close();

    session.execute("CREATE class MCHasMember extends E").close();
    session.execute("CREATE property MCHasMember.out LINK MCPerson").close();
    session.execute("CREATE property MCHasMember.in LINK MCForum").close();
    session.execute("CREATE property MCHasMember.joinDate LONG").close();
    session.execute(
        "CREATE index MCHasMember_joinDate on MCHasMember (joinDate) NOTUNIQUE")
        .close();

    session.begin();
    // Create persons
    session.execute("CREATE VERTEX MCPerson set name = 'start'").close();
    session.execute("CREATE VERTEX MCPerson set name = 'friend1'").close();
    session.execute("CREATE VERTEX MCPerson set name = 'friend2'").close();
    session.execute("CREATE VERTEX MCPerson set name = 'friend3'").close();
    // friend4 has a KNOWS edge but NO HAS_MEMBER edge — dangling path
    session.execute("CREATE VERTEX MCPerson set name = 'friend4'").close();

    // Create forums
    session.execute("CREATE VERTEX MCForum set title = 'forumA'").close();
    session.execute("CREATE VERTEX MCForum set title = 'forumB'").close();
    session.execute("CREATE VERTEX MCForum set title = 'forumC'").close();

    // KNOWS edges: start -> friend1, friend2, friend3
    session.execute(
        "CREATE EDGE MCKnows FROM"
            + " (SELECT FROM MCPerson WHERE name = 'start')"
            + " TO (SELECT FROM MCPerson WHERE name = 'friend1')")
        .close();
    session.execute(
        "CREATE EDGE MCKnows FROM"
            + " (SELECT FROM MCPerson WHERE name = 'start')"
            + " TO (SELECT FROM MCPerson WHERE name = 'friend2')")
        .close();
    session.execute(
        "CREATE EDGE MCKnows FROM"
            + " (SELECT FROM MCPerson WHERE name = 'start')"
            + " TO (SELECT FROM MCPerson WHERE name = 'friend3')")
        .close();
    session.execute(
        "CREATE EDGE MCKnows FROM"
            + " (SELECT FROM MCPerson WHERE name = 'start')"
            + " TO (SELECT FROM MCPerson WHERE name = 'friend4')")
        .close();

    // HAS_MEMBER edges: friend1-3 each in one forum; friend4 has NO membership
    session.execute(
        "CREATE EDGE MCHasMember FROM"
            + " (SELECT FROM MCPerson WHERE name = 'friend1')"
            + " TO (SELECT FROM MCForum WHERE title = 'forumA')"
            + " SET joinDate = 1000")
        .close();
    session.execute(
        "CREATE EDGE MCHasMember FROM"
            + " (SELECT FROM MCPerson WHERE name = 'friend2')"
            + " TO (SELECT FROM MCForum WHERE title = 'forumB')"
            + " SET joinDate = 2000")
        .close();
    session.execute(
        "CREATE EDGE MCHasMember FROM"
            + " (SELECT FROM MCPerson WHERE name = 'friend3')"
            + " TO (SELECT FROM MCForum WHERE title = 'forumC')"
            + " SET joinDate = 3000")
        .close();
    session.commit();

    // Multi-hop query: start -> KNOWS -> friend -> HAS_MEMBER(joinDate >= 2000) -> forum
    session.begin();
    var query =
        "MATCH {class: MCPerson, as: person, where: (name = 'start')}"
            + ".out('MCKnows'){as: friend}"
            + ".outE('MCHasMember'){where: (joinDate >= 2000)}"
            + ".inV(){as: forum}"
            + " RETURN friend.name, forum.title";
    var result = session.query(query).toList();

    // friend2 (joinDate=2000) and friend3 (joinDate=3000) match
    assertEquals(2, result.size());

    Set<String> pairs = new HashSet<>();
    for (var r : result) {
      pairs.add(r.getProperty("friend.name") + "->"
          + r.getProperty("forum.title"));
    }
    assertEquals(Set.of("friend2->forumB", "friend3->forumC"), pairs);

    session.commit();
  }

  /**
   * Multi-hop chain with a longer path: regular → edge-method → regular,
   * combining three traversal types in sequence.
   *
   * <p>Pattern: Person→KNOWS→Friend→outE('HAS_MEMBER'){joinDate >= ?}→inV()
   * as Forum→out('CONTAINER_OF')→Post
   *
   * <p>This exercises the planner's ability to handle edge-method patterns
   * in the middle of a longer chain where regular traversals precede and
   * follow.
   */
  @Test
  public void testMultiHopChainRegularEdgeMethodRegular() {
    // Schema
    session.execute("CREATE class MC2Person extends V").close();
    session.execute("CREATE property MC2Person.name STRING").close();

    session.execute("CREATE class MC2Forum extends V").close();
    session.execute("CREATE property MC2Forum.title STRING").close();

    session.execute("CREATE class MC2Post extends V").close();
    session.execute("CREATE property MC2Post.content STRING").close();

    session.execute("CREATE class MC2Knows extends E").close();
    session.execute("CREATE property MC2Knows.out LINK MC2Person").close();
    session.execute("CREATE property MC2Knows.in LINK MC2Person").close();

    session.execute("CREATE class MC2HasMember extends E").close();
    session.execute("CREATE property MC2HasMember.out LINK MC2Person").close();
    session.execute("CREATE property MC2HasMember.in LINK MC2Forum").close();
    session.execute("CREATE property MC2HasMember.joinDate LONG").close();
    session.execute(
        "CREATE index MC2HasMember_joinDate on MC2HasMember (joinDate) NOTUNIQUE")
        .close();

    session.execute("CREATE class MC2ContainerOf extends E").close();
    session.execute("CREATE property MC2ContainerOf.out LINK MC2Forum").close();
    session.execute("CREATE property MC2ContainerOf.in LINK MC2Post").close();

    session.begin();
    // Persons
    session.execute("CREATE VERTEX MC2Person set name = 'alice'").close();
    session.execute("CREATE VERTEX MC2Person set name = 'bob'").close();

    // Forum + posts
    session.execute("CREATE VERTEX MC2Forum set title = 'techForum'").close();
    session.execute("CREATE VERTEX MC2Post set content = 'post1'").close();
    session.execute("CREATE VERTEX MC2Post set content = 'post2'").close();

    // alice -> KNOWS -> bob
    session.execute(
        "CREATE EDGE MC2Knows FROM"
            + " (SELECT FROM MC2Person WHERE name = 'alice')"
            + " TO (SELECT FROM MC2Person WHERE name = 'bob')")
        .close();

    // bob -> HAS_MEMBER(joinDate=2000) -> techForum
    session.execute(
        "CREATE EDGE MC2HasMember FROM"
            + " (SELECT FROM MC2Person WHERE name = 'bob')"
            + " TO (SELECT FROM MC2Forum WHERE title = 'techForum')"
            + " SET joinDate = 2000")
        .close();

    // techForum -> CONTAINER_OF -> post1, post2
    session.execute(
        "CREATE EDGE MC2ContainerOf FROM"
            + " (SELECT FROM MC2Forum WHERE title = 'techForum')"
            + " TO (SELECT FROM MC2Post WHERE content = 'post1')")
        .close();
    session.execute(
        "CREATE EDGE MC2ContainerOf FROM"
            + " (SELECT FROM MC2Forum WHERE title = 'techForum')"
            + " TO (SELECT FROM MC2Post WHERE content = 'post2')")
        .close();
    session.commit();

    // Query: alice -> KNOWS -> friend -> HAS_MEMBER(joinDate >= 1500) -> forum
    //        -> CONTAINER_OF -> post
    session.begin();
    var query =
        "MATCH {class: MC2Person, as: person, where: (name = 'alice')}"
            + ".out('MC2Knows'){as: friend}"
            + ".outE('MC2HasMember'){where: (joinDate >= 1500)}"
            + ".inV(){as: forum}"
            + ".out('MC2ContainerOf'){as: post}"
            + " RETURN friend.name, forum.title, post.content";
    var result = session.query(query).toList();

    // bob (joinDate=2000 >= 1500) -> techForum -> post1, post2
    assertEquals(2, result.size());

    Set<String> contents = new HashSet<>();
    for (var r : result) {
      assertEquals("bob", r.getProperty("friend.name"));
      assertEquals("techForum", r.getProperty("forum.title"));
      contents.add(r.getProperty("post.content"));
    }
    assertEquals(Set.of("post1", "post2"), contents);

    session.commit();
  }
}
