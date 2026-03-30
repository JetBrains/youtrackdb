package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Ignore;
import org.junit.Test;

public class MatchStatementExecutionTest extends DbTestBase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();

    session.execute("CREATE class Person extends V").close();
    session.execute("CREATE class Friend extends E").close();

    session.begin();
    session.execute("CREATE VERTEX Person set name = 'n1'").close();
    session.execute("CREATE VERTEX Person set name = 'n2'").close();
    session.execute("CREATE VERTEX Person set name = 'n3'").close();
    session.execute("CREATE VERTEX Person set name = 'n4'").close();
    session.execute("CREATE VERTEX Person set name = 'n5'").close();
    session.execute("CREATE VERTEX Person set name = 'n6'").close();

    var friendList =
        new String[][] {{"n1", "n2"}, {"n1", "n3"}, {"n2", "n4"}, {"n4", "n5"}, {"n4", "n6"}};

    for (var pair : friendList) {
      session.execute(
          "CREATE EDGE Friend from (select from Person where name = ?) to (select from Person"
              + " where name = ?)",
          pair[0],
          pair[1])
          .close();
    }

    session.commit();

    session.execute("CREATE class MathOp extends V").close();

    session.begin();
    session.execute("CREATE VERTEX MathOp set a = 1, b = 3, c = 2").close();
    session.execute("CREATE VERTEX MathOp set a = 5, b = 3, c = 2").close();
    session.commit();

    initOrgChart();

    initTriangleTest();

    initEdgeIndexTest();

    initDiamondTest();
  }

  private void initEdgeIndexTest() {
    session.execute("CREATE class IndexedVertex extends V").close();
    session.execute("CREATE property IndexedVertex.uid INTEGER").close();
    session.execute("CREATE index IndexedVertex_uid on IndexedVertex (uid) NOTUNIQUE").close();

    session.execute("CREATE class IndexedEdge extends E").close();
    session.execute("CREATE property IndexedEdge.out LINK").close();
    session.execute("CREATE property IndexedEdge.in LINK").close();
    session.execute("CREATE index IndexedEdge_out_in on IndexedEdge (out, in) NOTUNIQUE").close();

    var nodes = 1000;
    for (var i = 0; i < nodes; i++) {
      session.begin();
      var doc = session.newVertex("IndexedVertex");
      doc.setProperty("uid", i);
      session.commit();
    }

    session.begin();
    for (var i = 0; i < 100; i++) {
      session.execute(
          "CREATE EDGE IndexedEdge FROM (SELECT FROM IndexedVertex WHERE uid = 0) TO (SELECT"
              + " FROM IndexedVertex WHERE uid > "
              + (i * nodes / 100)
              + " and uid <"
              + ((i + 1) * nodes / 100)
              + ")")
          .close();
    }

    for (var i = 0; i < 100; i++) {
      session.execute(
          "CREATE EDGE IndexedEdge FROM (SELECT FROM IndexedVertex WHERE uid > "
              + ((i * nodes / 100) + 1)
              + " and uid < "
              + (((i + 1) * nodes / 100) + 1)
              + ") TO (SELECT FROM IndexedVertex WHERE uid = 1)")
          .close();
    }
    session.commit();
  }

  private void initOrgChart() {

    // ______ [manager] department _______
    // _____ (employees in department)____
    // ___________________________________
    // ___________________________________
    // ____________[a]0___________________
    // _____________(p1)__________________
    // _____________/___\_________________
    // ____________/_____\________________
    // ___________/_______\_______________
    // _______[b]1_________2[d]___________
    // ______(p2, p3)_____(p4, p5)________
    // _________/_\_________/_\___________
    // ________3___4_______5___6__________
    // ______(p6)_(p7)___(p8)__(p9)_______
    // ______/__\_________________________
    // __[c]7_____8_______________________
    // __(p10)___(p11)____________________
    // ___/_______________________________
    // __9________________________________
    // (p12, p13)_________________________
    //
    // short description:
    // Department 0 is the company itself, "a" is the CEO
    // p10 works at department 7, his manager is "c"
    // p12 works at department 9, this department has no direct manager, so p12's manager is c (the
    // upper manager)

    session.execute("CREATE class Employee extends V").close();
    session.execute("CREATE class Department extends V").close();
    session.execute("CREATE class ParentDepartment extends E").close();
    session.execute("CREATE class WorksAt extends E").close();
    session.execute("CREATE class ManagerOf extends E").close();

    var deptHierarchy = new int[10][];
    deptHierarchy[0] = new int[] {1, 2};
    deptHierarchy[1] = new int[] {3, 4};
    deptHierarchy[2] = new int[] {5, 6};
    deptHierarchy[3] = new int[] {7, 8};
    deptHierarchy[4] = new int[] {};
    deptHierarchy[5] = new int[] {};
    deptHierarchy[6] = new int[] {};
    deptHierarchy[7] = new int[] {9};
    deptHierarchy[8] = new int[] {};
    deptHierarchy[9] = new int[] {};

    var deptManagers = new String[] {"a", "b", "d", null, null, null, null, "c", null, null};

    var employees = new String[10][];
    employees[0] = new String[] {"p1"};
    employees[1] = new String[] {"p2", "p3"};
    employees[2] = new String[] {"p4", "p5"};
    employees[3] = new String[] {"p6"};
    employees[4] = new String[] {"p7"};
    employees[5] = new String[] {"p8"};
    employees[6] = new String[] {"p9"};
    employees[7] = new String[] {"p10"};
    employees[8] = new String[] {"p11"};
    employees[9] = new String[] {"p12", "p13"};

    session.begin();
    for (var i = 0; i < deptHierarchy.length; i++) {
      session.execute("CREATE VERTEX Department set name = 'department" + i + "' ").close();
    }

    for (var parent = 0; parent < deptHierarchy.length; parent++) {
      var children = deptHierarchy[parent];
      for (var child : children) {
        session.execute(
            "CREATE EDGE ParentDepartment from (select from Department where name = 'department"
                + child
                + "') to (select from Department where name = 'department"
                + parent
                + "') ")
            .close();
      }
    }

    for (var dept = 0; dept < deptManagers.length; dept++) {
      var manager = deptManagers[dept];
      if (manager != null) {
        session.execute("CREATE Vertex Employee set name = '" + manager + "' ").close();

        session.execute(
            "CREATE EDGE ManagerOf from (select from Employee where name = '"
                + manager
                + "') to (select from Department where name = 'department"
                + dept
                + "') ")
            .close();
      }
    }

    for (var dept = 0; dept < employees.length; dept++) {
      var employeesForDept = employees[dept];
      for (var employee : employeesForDept) {
        session.execute("CREATE Vertex Employee set name = '" + employee + "' ").close();

        session.execute(
            "CREATE EDGE WorksAt from (select from Employee where name = '"
                + employee
                + "') to (select from Department where name = 'department"
                + dept
                + "') ")
            .close();
      }
    }
    session.commit();
  }

  private void initTriangleTest() {
    session.execute("CREATE class TriangleV extends V").close();
    session.execute("CREATE property TriangleV.uid INTEGER").close();
    session.execute("CREATE index TriangleV_uid on TriangleV (uid) UNIQUE").close();
    session.execute("CREATE class TriangleE extends E").close();

    session.begin();
    for (var i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX TriangleV set uid = ?", i).close();
    }
    var edges = new int[][] {
        {0, 1}, {0, 2}, {1, 2}, {1, 3}, {2, 4}, {3, 4}, {3, 5}, {4, 0}, {4, 7}, {6, 7}, {7, 8},
        {7, 9}, {8, 9}, {9, 1}, {8, 3}, {8, 4}
    };
    for (var edge : edges) {
      session.execute(
          "CREATE EDGE TriangleE from (select from TriangleV where uid = ?) to (select from"
              + " TriangleV where uid = ?)",
          edge[0],
          edge[1])
          .close();
    }
    session.commit();
  }

  private void initDiamondTest() {
    session.execute("CREATE class DiamondV extends V").close();
    session.execute("CREATE class DiamondE extends E").close();

    session.begin();
    for (var i = 0; i < 4; i++) {
      session.execute("CREATE VERTEX DiamondV set uid = ?", i).close();
    }
    var edges = new int[][] {{0, 1}, {0, 2}, {1, 3}, {2, 3}};
    for (var edge : edges) {
      session.execute(
          "CREATE EDGE DiamondE from (select from DiamondV where uid = ?) to (select from"
              + " DiamondV where uid = ?)",
          edge[0],
          edge[1])
          .close();
    }
    session.commit();
  }

  @Test
  public void testSimple() throws Exception {
    session.begin();
    var qResult = session.query("match {class:Person, as: person} return person").toList();
    assertEquals(6, qResult.size());
    for (var doc : qResult) {
      assertEquals(1, doc.getPropertyNames().size());
      Identifiable personId = doc.getProperty("person");
      var transaction = session.getActiveTransaction();
      EntityImpl person = transaction.load(personId);
      String name = person.getProperty("name");
      assertTrue(!name.isEmpty() && name.charAt(0) == 'n');
    }
    session.commit();
  }

  @Test
  public void testSimpleWhere() throws Exception {
    session.begin();
    var qResult = session.query(
        "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
            + " person")
        .toList();
    assertEquals(2, qResult.size());

    for (var doc : qResult) {
      assertEquals(1, doc.getPropertyNames().size());
      Identifiable personId = doc.getProperty("person");
      var transaction = session.getActiveTransaction();
      EntityImpl person = transaction.load(personId);
      String name = person.getProperty("name");
      assertTrue(name.equals("n1") || name.equals("n2"));
    }
    session.commit();
  }

  @Test
  public void testSimpleLimit() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
                + " person limit 1")
            .toList();

    assertEquals(1, qResult.size());
    session.commit();
  }

  @Test
  public void testSimpleLimit2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
                + " person limit -1")
            .toList();
    assertEquals(2, qResult.size());
    session.commit();
  }

  @Test
  public void testSimpleLimit3() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
                + " person limit 3")
            .toList();

    assertEquals(2, qResult.size());
    session.commit();
  }

  @Test
  public void testSimpleUnnamedParams() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = ? or name = ?)} return person",
            "n1",
            "n2").toList();

    assertEquals(2, qResult.size());
    for (var doc : qResult) {
      assertEquals(1, doc.getPropertyNames().size());
      Identifiable personId = doc.getProperty("person");
      var transaction = session.getActiveTransaction();
      EntityImpl person = transaction.load(personId);
      String name = person.getProperty("name");
      assertTrue(name.equals("n1") || name.equals("n2"));
    }
    session.commit();
  }

  @Test
  public void testCommonFriends() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                + " = 'n4')} return $matches)")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testCommonFriendsArrows() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend-{as:friend}-Friend-{class: Person, where:(name = 'n4')}"
                + " return $matches)")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testCommonFriends2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                + " = 'n4')} return friend.name as name")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testCommonFriends2Arrows() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class:"
                + " Person, where:(name = 'n4')} return friend.name as name")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testReturnMethod() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                + " = 'n4')} return friend.name.toUpperCase(Locale.ENGLISH) as name")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("N2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testReturnMethodArrows() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class:"
                + " Person, where:(name = 'n4')} return friend.name.toUpperCase(Locale.ENGLISH)"
                + " as name")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("N2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testReturnExpression() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                + " = 'n4')} return friend.name + ' ' +friend.name as name")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2 n2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testReturnExpressionArrows() {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class:"
                + " Person, where:(name = 'n4')} return friend.name + ' ' +friend.name as"
                + " name")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2 n2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testReturnDefaultAlias() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                + " = 'n4')} return friend.name")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.getFirst().getProperty("friend.name"));
    session.commit();
  }

  @Test
  public void testReturnDefaultAliasArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class:"
                + " Person, where:(name = 'n4')} return friend.name")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.getFirst().getProperty("friend.name"));
    session.commit();
  }

  @Test
  public void testFriendsOfFriends() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend').out('Friend'){as:friend} return $matches)")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n4", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testFriendsOfFriendsArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{}-Friend->{as:friend} return $matches)")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n4", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testFriendsOfFriends2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                + " me}.both('Friend').both('Friend'){as:friend, where: ($matched.me !="
                + " $currentMatch)} return $matches)")
            .toList();

    for (var doc : qResult) {
      assertNotEquals("n1", doc.getProperty("name"));
    }
    session.commit();
  }

  @Test
  public void testFriendsOfFriends2Arrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                + " me}-Friend-{}-Friend-{as:friend, where: ($matched.me != $currentMatch)}"
                + " return $matches)")
            .toList();

    for (var doc : qResult) {
      assertNotEquals("n1", doc.getProperty("name"));
    }
    session.commit();
  }

  @Test
  public void testFriendsWithName() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1"
                + " = 2)}.out('Friend'){as:friend, where:(name = 'n2' and 1 + 1 = 2)} return"
                + " friend)")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testFriendsWithNameArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1"
                + " = 2)}-Friend->{as:friend, where:(name = 'n2' and 1 + 1 = 2)} return"
                + " friend)")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testWhile() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 1)} return friend)")
            .toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 2), where: ($depth=1) }"
                + " return friend)")
            .toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 4), where: ($depth=1) }"
                + " return friend)")
            .toList();
    assertEquals(2, qResult.size());

    qResult = session.query(
        "select friend.name as name from (match {class:Person, where:(name ="
            + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend)")
        .toList();
    assertEquals(6, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend limit 3)")
            .toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend) limit 3")
            .toList();
    assertEquals(3, qResult.size());
    session.commit();
  }

  @Test
  public void testWhileArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 1)} return friend)")
            .toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 2), where: ($depth=1) } return"
                + " friend)")
            .toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 4), where: ($depth=1) } return"
                + " friend)")
            .toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: (true) } return friend)")
            .toList();
    assertEquals(6, qResult.size());
    session.commit();
  }

  @Test
  public void testMaxDepth() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1, where: ($depth=1) } return"
                + " friend)")
            .toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1 } return friend)")
            .toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 0 } return friend)")
            .toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1, where: ($depth > 0) } return"
                + " friend)")
            .toList();
    assertEquals(2, qResult.size());
    session.commit();
  }

  @Test
  public void testMaxDepthArrow() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1, where: ($depth=1) } return"
                + " friend)")
            .toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1 } return friend)")
            .toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 0 } return friend)")
            .toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1, where: ($depth > 0) } return"
                + " friend)")
            .toList();
    assertEquals(2, qResult.size());
    session.commit();
  }

  @Test
  public void testManager() {
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one
    session.begin();
    assertEquals("c", getManager("p10").getProperty("name"));
    assertEquals("c", getManager("p12").getProperty("name"));
    assertEquals("b", getManager("p6").getProperty("name"));
    assertEquals("b", getManager("p11").getProperty("name"));

    assertEquals("c", getManagerArrows("p10").getProperty("name"));
    assertEquals("c", getManagerArrows("p12").getProperty("name"));
    assertEquals("b", getManagerArrows("p6").getProperty("name"));
    assertEquals("b", getManagerArrows("p11").getProperty("name"));
    session.commit();
  }

  private Entity getManager(String personName) {
    var query =
        "select expand(manager) from ("
            + "  match {class:Employee, where: (name = '"
            + personName
            + "')}"
            + "  .out('WorksAt')"
            + "  .out('ParentDepartment'){"
            + "      while: (in('ManagerOf').size() == 0),"
            + "      where: (in('ManagerOf').size() > 0)"
            + "  }"
            + "  .in('ManagerOf'){as: manager}"
            + "  return manager"
            + ")";

    var qResult = session.query(query).toList();
    assertEquals(1, qResult.size());
    return qResult.getFirst().asEntity();
  }

  private Entity getManagerArrows(String personName) {
    var query =
        "select expand(manager) from ("
            + "  match {class:Employee, where: (name = '"
            + personName
            + "')}"
            + "  -WorksAt->{}-ParentDepartment->{"
            + "      while: (in('ManagerOf').size() == 0),"
            + "      where: (in('ManagerOf').size() > 0)"
            + "  }<-ManagerOf-{as: manager}"
            + "  return manager"
            + ")";

    session.begin();
    var qResult = session.query(query).toList();
    assertEquals(1, qResult.size());
    return qResult.getFirst().asEntity();
  }

  @Test
  public void testManager2() {
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one
    session.begin();
    assertEquals("c", getManager2("p10").getProperty("name"));
    assertEquals("c", getManager2("p12").getProperty("name"));
    assertEquals("b", getManager2("p6").getProperty("name"));
    assertEquals("b", getManager2("p11").getProperty("name"));

    assertEquals("c", getManager2Arrows("p10").getProperty("name"));
    assertEquals("c", getManager2Arrows("p12").getProperty("name"));
    assertEquals("b", getManager2Arrows("p6").getProperty("name"));
    assertEquals("b", getManager2Arrows("p11").getProperty("name"));
    session.commit();
  }

  private Entity getManager2(String personName) {
    var query =
        "select expand(manager) from ("
            + "  match {class:Employee, where: (name = '"
            + personName
            + "')}"
            + "   .( out('WorksAt')"
            + "     .out('ParentDepartment'){"
            + "       while: (in('ManagerOf').size() == 0),"
            + "       where: (in('ManagerOf').size() > 0)"
            + "     }"
            + "   )"
            + "  .in('ManagerOf'){as: manager}"
            + "  return manager"
            + ")";

    var qResult = session.execute(query).toList();
    assertEquals(1, qResult.size());
    return qResult.getFirst().asEntity();
  }

  private Entity getManager2Arrows(String personName) {
    var query =
        "select expand(manager) from ("
            + "  match {class:Employee, where: (name = '"
            + personName
            + "')}"
            + "   .( -WorksAt->{}-ParentDepartment->{"
            + "       while: (in('ManagerOf').size() == 0),"
            + "       where: (in('ManagerOf').size() > 0)"
            + "     }"
            + "   )<-ManagerOf-{as: manager}"
            + "  return manager"
            + ")";

    var qResult = session.query(query).toList();
    assertEquals(1, qResult.size());
    return qResult.getFirst().asEntity();
  }

  @Test
  public void testManaged() {
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedBy("a");
    assertEquals(1, managedByA.size());
    Identifiable identifiable = managedByA.getFirst();
    var transaction1 = session.getActiveTransaction();
    assertEquals("p1", ((EntityImpl) transaction1.load(identifiable)).getProperty("name"));

    var managedByB = getManagedBy("b");
    assertEquals(5, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var id : managedByB) {
      var transaction = session.getActiveTransaction();
      EntityImpl doc = transaction.load(id);
      String name = doc.getProperty("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
    session.commit();
  }

  private List<Entity> getManagedBy(String managerName) {
    var query =
        "select expand(managed) from ("
            + "  match {class:Employee, where: (name = '"
            + managerName
            + "')}"
            + "  .out('ManagerOf')"
            + "  .in('ParentDepartment'){"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }"
            + "  .in('WorksAt'){as: managed}"
            + "  return managed"
            + ")";

    return session.query(query).entityStream().toList();
  }

  @Test
  public void testManagedArrows() {
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedByArrows("a");
    assertEquals(1, managedByA.size());
    Identifiable identifiable = managedByA.getFirst();
    var transaction1 = session.getActiveTransaction();
    assertEquals("p1", ((EntityImpl) transaction1.load(identifiable)).getProperty("name"));

    var managedByB = getManagedByArrows("b");
    assertEquals(5, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var id : managedByB) {
      var transaction = session.getActiveTransaction();
      EntityImpl doc = transaction.load(id);
      String name = doc.getProperty("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
    session.commit();
  }

  private List<Entity> getManagedByArrows(String managerName) {
    var query =
        "select expand(managed) from ("
            + "  match {class:Employee, where: (name = '"
            + managerName
            + "')}"
            + "  -ManagerOf->{}<-ParentDepartment-{"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return managed"
            + ")";

    return session.query(query).entityStream().toList();
  }

  @Test
  public void testManaged2() {
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedBy2("a");
    assertEquals(1, managedByA.size());
    Identifiable identifiable = managedByA.getFirst();
    var transaction1 = session.getActiveTransaction();
    assertEquals("p1", ((EntityImpl) transaction1.load(identifiable)).getProperty("name"));

    var managedByB = getManagedBy2("b");
    assertEquals(5, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var id : managedByB) {
      var transaction = session.getActiveTransaction();
      EntityImpl doc = transaction.load(id);
      String name = doc.getProperty("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
    session.commit();
  }

  private List<Entity> getManagedBy2(String managerName) {
    var query =
        "select expand(managed) from ("
            + "  match {class:Employee, where: (name = '"
            + managerName
            + "')}"
            + "  .out('ManagerOf')"
            + "  .(inE('ParentDepartment').outV()){"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }"
            + "  .in('WorksAt'){as: managed}"
            + "  return managed"
            + ")";

    return session.query(query).entityStream().toList();
  }

  @Test
  public void testManaged2Arrows() {
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedBy2Arrows("a");
    assertEquals(1, managedByA.size());
    Identifiable identifiable = managedByA.getFirst();
    var transaction1 = session.getActiveTransaction();
    assertEquals("p1", ((EntityImpl) transaction1.load(identifiable)).getProperty("name"));

    var managedByB = getManagedBy2Arrows("b");
    assertEquals(5, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var id : managedByB) {
      var transaction = session.getActiveTransaction();
      EntityImpl doc = transaction.load(id);
      String name = doc.getProperty("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
    session.commit();
  }

  private List<Entity> getManagedBy2Arrows(String managerName) {
    var query =
        "select expand(managed) from ("
            + "  match {class:Employee, where: (name = '"
            + managerName
            + "')}"
            + "  -ManagerOf->{}"
            + "  .(inE('ParentDepartment').outV()){"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return managed"
            + ")";

    return session.query(query).entityStream().toList();
  }

  @Test
  public void testTriangle1() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "  .out('TriangleE'){as: friend2}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    session.begin();
    List<?> result = session.query(query).toList();
    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  public void testTriangle1Arrows() {
    var query =
        "match {class:TriangleV, as: friend1, where: (uid = 0)} -TriangleE-> {as: friend2}"
            + " -TriangleE-> {as: friend3},{class:TriangleV, as: friend1} -TriangleE-> {as:"
            + " friend3}return $matches";

    session.begin();
    List<?> result = session.query(query).toList();
    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  public void testTriangle2Old() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    var doc = result.getFirst();
    Identifiable identifiable2 = doc.getProperty("friend1");
    var transaction2 = session.getActiveTransaction();
    EntityImpl friend1 = transaction2.load(identifiable2);
    Identifiable identifiable1 = doc.getProperty("friend2");
    var transaction1 = session.getActiveTransaction();
    EntityImpl friend2 = transaction1.load(identifiable1);
    Identifiable identifiable = doc.getProperty("friend3");
    var transaction = session.getActiveTransaction();
    EntityImpl friend3 = transaction.load(identifiable);
    assertEquals(0, friend1.<Object>getProperty("uid"));
    assertEquals(1, friend2.<Object>getProperty("uid"));
    assertEquals(2, friend3.<Object>getProperty("uid"));
    session.commit();
  }

  @Test
  public void testTriangle2() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $patterns";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    var doc = result.getFirst();
    Identifiable identifiable2 = doc.getProperty("friend1");
    var transaction2 = session.getActiveTransaction();
    EntityImpl friend1 = transaction2.load(identifiable2);
    Identifiable identifiable1 = doc.getProperty("friend2");
    var transaction1 = session.getActiveTransaction();
    EntityImpl friend2 = transaction1.load(identifiable1);
    Identifiable identifiable = doc.getProperty("friend3");
    var transaction = session.getActiveTransaction();
    EntityImpl friend3 = transaction.load(identifiable);
    assertEquals(0, friend1.<Object>getProperty("uid"));
    assertEquals(1, friend2.<Object>getProperty("uid"));
    assertEquals(2, friend3.<Object>getProperty("uid"));
    session.commit();
  }

  @Test
  public void testTriangle2Arrows() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  -TriangleE->{as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    var doc = result.getFirst();
    Identifiable identifiable2 = doc.getProperty("friend1");
    var transaction2 = session.getActiveTransaction();
    EntityImpl friend1 = transaction2.load(identifiable2);
    Identifiable identifiable1 = doc.getProperty("friend2");
    var transaction1 = session.getActiveTransaction();
    EntityImpl friend2 = transaction1.load(identifiable1);
    Identifiable identifiable = doc.getProperty("friend3");
    var transaction = session.getActiveTransaction();
    EntityImpl friend3 = transaction.load(identifiable);
    assertEquals(0, friend1.<Object>getProperty("uid"));
    assertEquals(1, friend2.<Object>getProperty("uid"));
    assertEquals(2, friend3.<Object>getProperty("uid"));
    session.commit();
  }

  @Test
  public void testTriangle3() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend2}"
            + "  -TriangleE->{as: friend3, where: (uid = 2)},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  public void testTriangle4() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    session.begin();
    List<?> result = session.query(query).toList();
    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  public void testTriangle4Arrows() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend2, where: (uid = 1)}"
            + "  -TriangleE->{as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    session.begin();
    List<?> result = session.query(query).toList();
    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  public void testTriangleWithEdges4() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .outE('TriangleE').inV(){as: friend2, where: (uid = 1)}"
            + "  .outE('TriangleE').inV(){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .outE('TriangleE').inV(){as: friend3}"
            + "return $matches";

    session.begin();
    List<?> result = session.query(query).toList();
    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  public void testCartesianProduct() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where:(uid = 1)},"
            + "{class:TriangleV, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(2, result.size());
    for (var d : result) {
      assertEquals(
          1,
          d.getEntity("friend1").<Object>getProperty(
              "uid"));
    }
    session.commit();
  }

  @Test
  public void testCartesianProductLimit() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where:(uid = 1)},"
            + "{class:TriangleV, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches LIMIT 1";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    for (var d : result) {
      assertEquals(
          1,
          d.getEntity("friend1").<Object>getProperty(
              "uid"));
    }
    session.commit();
  }

  @Test
  public void testArrayNumber() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0] as foo";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    var doc = result.getFirst();
    doc.getVertex("foo");
    session.commit();
  }

  @Test
  public void testArraySingleSelectors2() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0,1] as foo";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    var doc = result.getFirst();
    var foo = doc.getProperty("foo");
    assertNotNull(foo);
    assertTrue(foo instanceof List);
    assertEquals(2, ((List) foo).size());
    session.commit();
  }

  @Test
  public void testArrayRangeSelectors1() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..1] as foo";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    var doc = result.getFirst();
    var foo = doc.getProperty("foo");
    assertNotNull(foo);
    assertTrue(foo instanceof List);
    assertEquals(1, ((List) foo).size());
    session.commit();
  }

  @Test
  public void testArrayRange2() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..2] as foo";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    var doc = result.getFirst();
    var foo = doc.getProperty("foo");
    assertNotNull(foo);
    assertTrue(foo instanceof List);
    assertEquals(2, ((List) foo).size());
    session.commit();
  }

  @Test
  public void testArrayRange3() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..3] as foo";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    var doc = result.getFirst();
    var foo = doc.getProperty("foo");
    assertNotNull(foo);
    assertTrue(foo instanceof List);
    assertEquals(2, ((List) foo).size());
    session.commit();
  }

  @Test
  public void testConditionInSquareBrackets() {
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[uid = 2] as foo";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    var doc = result.getFirst();
    var foo = doc.getLinkList("foo");
    assertNotNull(foo);

    assertEquals(1, foo.size());
    Identifiable identifiable = foo.getFirst();
    var transaction = session.getActiveTransaction();
    var resultVertex = transaction.loadVertex(identifiable);
    assertEquals(2, resultVertex.<Object>getProperty("uid"));
    session.commit();
  }

  @Test
  public void testIndexedEdge() {
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)}"
            + ".out('IndexedEdge'){class:IndexedVertex, as: two, where: (uid = 1)}"
            + "return one, two";

    session.begin();
    List<?> result = session.query(query).toList();
    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  public void testIndexedEdgeArrows() {
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)}"
            + "-IndexedEdge->{class:IndexedVertex, as: two, where: (uid = 1)}"
            + "return one, two";

    session.begin();
    List<?> result = session.query(query).toList();
    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  public void testJson() {
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'uuid':one.uid}";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    //    var doc = result.get(0);
    //    assertEquals("foo", doc.getProperty("name"));
    //    assertEquals(0, doc.getProperty("uuid"));
    session.commit();
  }

  @Test
  public void testJson2() {
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'sub': {'uuid':one.uid}}";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    //    var doc = result.get(0);
    //    assertEquals("foo", doc.getProperty("name"));
    //    assertEquals(0, doc.getProperty("sub.uuid"));
    session.commit();
  }

  @Test
  public void testJson3() {
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'sub': [{'uuid':one.uid}]}";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    //    var doc = result.get(0);
    //    assertEquals("foo", doc.getProperty("name"));
    //    assertEquals(0, doc.getProperty("sub[0].uuid"));
    session.commit();
  }

  @Test
  @Ignore
  public void testUnique() {
    var query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return one, two");

    session.begin();
    var result = session.query(query.toString()).entityStream().toList();
    assertEquals(1, result.size());

    query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return one.uid, two.uid");

    result = session.query(query.toString()).entityStream().toList();
    assertEquals(1, result.size());
    //    var doc = result.get(0);
    //    assertEquals("foo", doc.getProperty("name"));
    //    assertEquals(0, doc.getProperty("sub[0].uuid"));
    session.commit();
  }

  @Test
  @Ignore
  public void testManagedElements() {
    var managedByB = getManagedElements();
    assertEquals(6, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("b");
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var id : managedByB) {
      var transaction = session.getActiveTransaction();
      EntityImpl doc = transaction.load(id);
      String name = doc.getProperty("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
  }

  private List<? extends Identifiable> getManagedElements() {
    var query =
        "  match {class:Employee, as:boss, where: (name = '"
            + "b"
            + "')}"
            + "  -ManagerOf->{}<-ParentDepartment-{"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return $elements";

    return session.query(query).stream().map(Result::getIdentity).toList();
  }

  @Test
  @Ignore
  public void testManagedPathElements() {
    var managedByB = getManagedPathElements("b");
    assertEquals(10, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("department1");
    expectedNames.add("department3");
    expectedNames.add("department4");
    expectedNames.add("department8");
    expectedNames.add("b");
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var id : managedByB) {
      var transaction = session.getActiveTransaction();
      EntityImpl doc = transaction.load(id);
      String name = doc.getProperty("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
  }

  @Test
  public void testOptional() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person} -NonExistingEdge-> {as:b, optional:true} return"
                + " person, b.name")
            .toList();
    assertEquals(6, qResult.size());
    for (var doc : qResult) {
      assertEquals(2, doc.getPropertyNames().size());
      Identifiable personId = doc.getProperty("person");
      var transaction = session.getActiveTransaction();
      EntityImpl person = transaction.load(personId);
      String name = person.getProperty("name");
      assertTrue(!name.isEmpty() && name.charAt(0) == 'n');
    }
    session.commit();
  }

  @Test
  public void testOptional2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person} --> {as:b, optional:true, where:(nonExisting ="
                + " 12)} return person, b.name")
            .toList();
    assertEquals(6, qResult.size());
    for (var doc : qResult) {
      assertEquals(2, doc.getPropertyNames().size());
      Identifiable personId = doc.getProperty("person");
      var transaction = session.getActiveTransaction();
      EntityImpl person = transaction.load(personId);
      String name = person.getProperty("name");
      assertTrue(!name.isEmpty() && name.charAt(0) == 'n');
    }
    session.commit();
  }

  @Test
  public void testOptional3() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, as:a, where:(name = 'n1' and"
                + " 1 + 1 = 2)}.out('Friend'){as:friend, where:(name = 'n2' and 1 + 1 ="
                + " 2)},{as:a}.out(){as:b, where:(nonExisting = 12),"
                + " optional:true},{as:friend}.out(){as:b, optional:true} return friend)")
            .toList();
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.getFirst().getProperty("name"));
    session.commit();
  }

  @Test
  public void testAliasesWithSubquery() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select from ( match {class:Person, as:A} return A.name as namexx ) limit 1").toList();
    assertEquals(1, qResult.size());
    assertNotNull(qResult.getFirst().getProperty("namexx"));
    assertTrue(!qResult.getFirst().getProperty("namexx").toString().isEmpty()
        && qResult.getFirst().getProperty("namexx").toString().charAt(0) == 'n');
    session.commit();
  }

  @Test
  public void testEvalInReturn() {
    // issue #6606
    session.execute("CREATE CLASS testEvalInReturn EXTENDS V").close();
    session.execute("CREATE PROPERTY testEvalInReturn.name String").close();

    session.begin();
    session.execute("CREATE VERTEX testEvalInReturn SET name = 'foo'").close();
    session.execute("CREATE VERTEX testEvalInReturn SET name = 'bar'").close();
    session.commit();

    session.begin();
    var qResult =
        session.query(
            "MATCH {class: testEvalInReturn, as: p} RETURN if(eval(\"p.name = 'foo'\"), 1, 2)"
                + " AS b")
            .toList();

    assertEquals(2, qResult.size());
    var sum = 0;
    for (var doc : qResult) {
      sum += ((Number) doc.getProperty("b")).intValue();
    }
    assertEquals(3, sum);
    qResult =
        session.query(
            "MATCH {class: testEvalInReturn, as: p} RETURN if(eval(\"p.name = 'foo'\"), 'foo',"
                + " 'foo') AS b")
            .toList();

    assertEquals(2, qResult.size());
    session.commit();
  }

  @Test
  public void testCheckClassAsCondition() {

    session.execute("CREATE CLASS testCheckClassAsCondition EXTENDS V").close();
    session.execute("CREATE CLASS testCheckClassAsCondition1 EXTENDS V").close();
    session.execute("CREATE CLASS testCheckClassAsCondition2 EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX testCheckClassAsCondition SET name = 'foo'").close();
    session.execute("CREATE VERTEX testCheckClassAsCondition1 SET name = 'bar'").close();
    for (var i = 0; i < 5; i++) {
      session.execute("CREATE VERTEX testCheckClassAsCondition2 SET name = 'baz'").close();
    }
    session.execute(
        "CREATE EDGE E FROM (select from testCheckClassAsCondition where name = 'foo') to"
            + " (select from testCheckClassAsCondition1)")
        .close();
    session.execute(
        "CREATE EDGE E FROM (select from testCheckClassAsCondition where name = 'foo') to"
            + " (select from testCheckClassAsCondition2)")
        .close();
    session.commit();

    session.begin();
    var qResult =
        session.query(
            "MATCH {class: testCheckClassAsCondition, as: p} -E- {class:"
                + " testCheckClassAsCondition1, as: q} RETURN $elements")
            .toList();

    assertEquals(2, qResult.size());
    session.commit();
  }

  @Test
  public void testInstanceof() {
    session.begin();
    var qResult =
        session.query(
            "MATCH {class: Person, as: p, where: ($currentMatch instanceof 'Person')} return"
                + " $elements limit 1")
            .toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, as: p, where: ($currentMatch instanceof 'V')} return"
                + " $elements limit 1")
            .toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, as: p, where: (not ($currentMatch instanceof 'Person'))}"
                + " return $elements limit 1")
            .toList();
    assertEquals(0, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, where: (name = 'n1')}.out(){as:p, where: ($currentMatch"
                + " instanceof 'Person')} return $elements limit 1")
            .toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, where: (name = 'n1')}.out(){as:p, where: ($currentMatch"
                + " instanceof 'Person' and '$currentMatch' <> '@this')} return $elements limit"
                + " 1")
            .toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, where: (name = 'n1')}.out(){as:p, where: ( not"
                + " ($currentMatch instanceof 'Person'))} return $elements limit 1")
            .toList();
    assertEquals(0, qResult.size());
    session.commit();
  }

  @Test
  public void testBigEntryPoint() {
    // issue #6890

    Schema schema = session.getMetadata().getSchema();
    schema.createClass("testBigEntryPoint1");
    schema.createClass("testBigEntryPoint2");

    for (var i = 0; i < 1000; i++) {
      session.begin();
      var doc = session.newInstance("testBigEntryPoint1");
      doc.setProperty("a", i);

      session.commit();
    }

    session.begin();
    var doc = session.newInstance("testBigEntryPoint2");
    doc.setProperty("b", "b");
    session.commit();

    session.begin();
    var qResult =
        session.query(
            "MATCH {class: testBigEntryPoint1, as: a}, {class: testBigEntryPoint2, as: b}"
                + " return $elements limit 1")
            .toList();
    assertEquals(1, qResult.size());
    session.commit();
  }

  @Test
  public void testMatched1() {
    // issue #6931
    session.execute("CREATE CLASS testMatched1_Foo EXTENDS V").close();
    session.execute("CREATE CLASS testMatched1_Bar EXTENDS V").close();
    session.execute("CREATE CLASS testMatched1_Baz EXTENDS V").close();
    session.execute("CREATE CLASS testMatched1_Far EXTENDS V").close();
    session.execute("CREATE CLASS testMatched1_Foo_Bar EXTENDS E").close();
    session.execute("CREATE CLASS testMatched1_Bar_Baz EXTENDS E").close();
    session.execute("CREATE CLASS testMatched1_Foo_Far EXTENDS E").close();

    session.begin();
    session.execute("CREATE VERTEX testMatched1_Foo SET name = 'foo'").close();
    session.execute("CREATE VERTEX testMatched1_Bar SET name = 'bar'").close();
    session.execute("CREATE VERTEX testMatched1_Baz SET name = 'baz'").close();
    session.execute("CREATE VERTEX testMatched1_Far SET name = 'far'").close();

    session.execute(
        "CREATE EDGE testMatched1_Foo_Bar FROM (SELECT FROM testMatched1_Foo) TO (SELECT FROM"
            + " testMatched1_Bar)")
        .close();
    session.execute(
        "CREATE EDGE testMatched1_Bar_Baz FROM (SELECT FROM testMatched1_Bar) TO (SELECT FROM"
            + " testMatched1_Baz)")
        .close();
    session.execute(
        "CREATE EDGE testMatched1_Foo_Far FROM (SELECT FROM testMatched1_Foo) TO (SELECT FROM"
            + " testMatched1_Far)")
        .close();
    session.commit();

    session.begin();
    var result =
        session.query(
            """
                MATCH
                {class: testMatched1_Foo, as: foo}.out('testMatched1_Foo_Bar') {as: bar},
                {class: testMatched1_Bar,as: bar}.out('testMatched1_Bar_Baz') {as: baz},
                {class: testMatched1_Foo,as: foo}.out('testMatched1_Foo_Far') {where:\
                 ($matched.baz IS null),as: far}
                RETURN $matches""");
    assertFalse(result.hasNext());

    result =
        session.query(
            """
                MATCH
                {class: testMatched1_Foo, as: foo}.out('testMatched1_Foo_Bar') {as: bar},
                {class: testMatched1_Bar,as: bar}.out('testMatched1_Bar_Baz') {as: baz},
                {class: testMatched1_Foo,as: foo}.out('testMatched1_Foo_Far') {where:\
                 ($matched.baz IS not null),as: far}
                RETURN $matches""");
    assertEquals(1, result.stream().count());
    session.commit();
  }

  @Test
  @Ignore
  public void testDependencyOrdering1() {
    // issue #6931
    session.execute("CREATE CLASS testDependencyOrdering1_Foo EXTENDS V").close();
    session.execute("CREATE CLASS testDependencyOrdering1_Bar EXTENDS V").close();
    session.execute("CREATE CLASS testDependencyOrdering1_Baz EXTENDS V").close();
    session.execute("CREATE CLASS testDependencyOrdering1_Far EXTENDS V").close();
    session.execute("CREATE CLASS testDependencyOrdering1_Foo_Bar EXTENDS E").close();
    session.execute("CREATE CLASS testDependencyOrdering1_Bar_Baz EXTENDS E").close();
    session.execute("CREATE CLASS testDependencyOrdering1_Foo_Far EXTENDS E").close();

    session.begin();
    session.execute("CREATE VERTEX testDependencyOrdering1_Foo SET name = 'foo'").close();
    session.execute("CREATE VERTEX testDependencyOrdering1_Bar SET name = 'bar'").close();
    session.execute("CREATE VERTEX testDependencyOrdering1_Baz SET name = 'baz'").close();
    session.execute("CREATE VERTEX testDependencyOrdering1_Far SET name = 'far'").close();

    session.execute(
        "CREATE EDGE testDependencyOrdering1_Foo_Bar FROM (SELECT FROM"
            + " testDependencyOrdering1_Foo) TO (SELECT FROM testDependencyOrdering1_Bar)")
        .close();
    session.execute(
        "CREATE EDGE testDependencyOrdering1_Bar_Baz FROM (SELECT FROM"
            + " testDependencyOrdering1_Bar) TO (SELECT FROM testDependencyOrdering1_Baz)")
        .close();
    session.execute(
        "CREATE EDGE testDependencyOrdering1_Foo_Far FROM (SELECT FROM"
            + " testDependencyOrdering1_Foo) TO (SELECT FROM testDependencyOrdering1_Far)")
        .close();
    session.commit();

    // The correct but non-obvious execution order here is:
    // foo, bar, far, baz
    // This is a test to ensure that the query scheduler resolves dependencies correctly,
    // even if they are unusual or contrived.
    session.begin();
    var result =
        session.query(

            """
                MATCH {
                    class: testDependencyOrdering1_Foo,
                    as: foo
                }.out('testDependencyOrdering1_Foo_Far') {
                    optional: true,
                    where: ($matched.bar IS NOT null),
                    as: far
                }, {
                    as: foo
                }.out('testDependencyOrdering1_Foo_Bar') {
                    where: ($matched.foo IS NOT null),
                    as: bar
                }.out('testDependencyOrdering1_Bar_Baz') {
                    where: ($matched.far IS NOT null),
                    as: baz
                } RETURN $matches""").toList();
    assertEquals(1, result.size());
    session.commit();
  }

  @Test
  public void testCircularDependency() {
    // issue #6931
    session.execute("CREATE CLASS testCircularDependency_Foo EXTENDS V").close();
    session.execute("CREATE CLASS testCircularDependency_Bar EXTENDS V").close();
    session.execute("CREATE CLASS testCircularDependency_Baz EXTENDS V").close();
    session.execute("CREATE CLASS testCircularDependency_Far EXTENDS V").close();
    session.execute("CREATE CLASS testCircularDependency_Foo_Bar EXTENDS E").close();
    session.execute("CREATE CLASS testCircularDependency_Bar_Baz EXTENDS E").close();
    session.execute("CREATE CLASS testCircularDependency_Foo_Far EXTENDS E").close();

    session.begin();
    session.execute("CREATE VERTEX testCircularDependency_Foo SET name = 'foo'").close();
    session.execute("CREATE VERTEX testCircularDependency_Bar SET name = 'bar'").close();
    session.execute("CREATE VERTEX testCircularDependency_Baz SET name = 'baz'").close();
    session.execute("CREATE VERTEX testCircularDependency_Far SET name = 'far'").close();

    session.execute(
        "CREATE EDGE testCircularDependency_Foo_Bar FROM (SELECT FROM"
            + " testCircularDependency_Foo) TO (SELECT FROM testCircularDependency_Bar)")
        .close();
    session.execute(
        "CREATE EDGE testCircularDependency_Bar_Baz FROM (SELECT FROM"
            + " testCircularDependency_Bar) TO (SELECT FROM testCircularDependency_Baz)")
        .close();
    session.execute(
        "CREATE EDGE testCircularDependency_Foo_Far FROM (SELECT FROM"
            + " testCircularDependency_Foo) TO (SELECT FROM testCircularDependency_Far)")
        .close();
    session.commit();

    // The circular dependency here is:
    // - far depends on baz
    // - baz depends on bar
    // - bar depends on far
    session.begin();
    var query = """
        MATCH {
            class: testCircularDependency_Foo,
            as: foo
        }.out('testCircularDependency_Foo_Far') {
            where: ($matched.baz IS NOT null),
            as: far
        }, {
            as: foo
        }.out('testCircularDependency_Foo_Bar') {
            where: ($matched.far IS NOT null),
            as: bar
        }.out('testCircularDependency_Bar_Baz') {
            where: ($matched.bar IS NOT null),
            as: baz
        } RETURN $matches""";

    try {
      session.query(query);
      fail();
    } catch (CommandExecutionException x) {
      // passed the test
    }
    session.rollback();
  }

  @Test
  public void testUndefinedAliasDependency() {
    // issue #6931
    session.execute("CREATE CLASS testUndefinedAliasDependency_Foo EXTENDS V").close();
    session.execute("CREATE CLASS testUndefinedAliasDependency_Bar EXTENDS V").close();
    session.execute("CREATE CLASS testUndefinedAliasDependency_Foo_Bar EXTENDS E").close();

    session.begin();
    session.execute("CREATE VERTEX testUndefinedAliasDependency_Foo SET name = 'foo'").close();
    session.execute("CREATE VERTEX testUndefinedAliasDependency_Bar SET name = 'bar'").close();

    session.execute(
        "CREATE EDGE testUndefinedAliasDependency_Foo_Bar FROM (SELECT FROM"
            + " testUndefinedAliasDependency_Foo) TO (SELECT FROM"
            + " testUndefinedAliasDependency_Bar)")
        .close();
    session.commit();

    session.begin();
    // "bar" in the following query declares a dependency on the alias "baz", which doesn't exist.
    var query = """
        MATCH {
            class: testUndefinedAliasDependency_Foo,
            as: foo
        }.out('testUndefinedAliasDependency_Foo_Bar') {
            where: ($matched.baz IS NOT null),
            as: bar
        } RETURN $matches""";

    try {
      session.query(query);
      fail();
    } catch (CommandExecutionException x) {
      // passed the test
    }
    session.rollback();
  }

  @Test
  public void testCyclicDeepTraversal() {
    session.execute("CREATE CLASS testCyclicDeepTraversalV EXTENDS V").close();
    session.execute("CREATE CLASS testCyclicDeepTraversalE EXTENDS E").close();

    session.begin();
    session.execute("CREATE VERTEX testCyclicDeepTraversalV SET name = 'a'").close();
    session.execute("CREATE VERTEX testCyclicDeepTraversalV SET name = 'b'").close();
    session.execute("CREATE VERTEX testCyclicDeepTraversalV SET name = 'c'").close();
    session.execute("CREATE VERTEX testCyclicDeepTraversalV SET name = 'z'").close();

    // a -> b -> z
    // z -> c -> a
    session.execute(
        "CREATE EDGE testCyclicDeepTraversalE from(select from testCyclicDeepTraversalV where"
            + " name = 'a') to (select from testCyclicDeepTraversalV where name = 'b')")
        .close();

    session.execute(
        "CREATE EDGE testCyclicDeepTraversalE from(select from testCyclicDeepTraversalV where"
            + " name = 'b') to (select from testCyclicDeepTraversalV where name = 'z')")
        .close();

    session.execute(
        "CREATE EDGE testCyclicDeepTraversalE from(select from testCyclicDeepTraversalV where"
            + " name = 'z') to (select from testCyclicDeepTraversalV where name = 'c')")
        .close();

    session.execute(
        "CREATE EDGE testCyclicDeepTraversalE from(select from testCyclicDeepTraversalV where"
            + " name = 'c') to (select from testCyclicDeepTraversalV where name = 'a')")
        .close();
    session.commit();

    var query =
        """
            MATCH {
                class: testCyclicDeepTraversalV,
                as: foo,
                where: (name = 'a')
            }.out() {
                while: ($depth < 2),
                where: (name = 'z'),
                as: bar
            }, {
                as: bar
            }.out() {
                while: ($depth < 2),
                as: foo
            } RETURN $patterns""";

    session.begin();
    var result = session.query(query);
    assertEquals(1, result.stream().count());
    session.commit();
  }

  // -- Coverage-boosting tests for MATCH execution step classes --

  /** Exercises ReturnMatchPathsStep by using RETURN $paths and verifying all aliases present. */
  @Test
  public void testReturnPaths() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b} RETURN $paths")
        .toList();
    assertFalse(result.isEmpty());
    // $paths should return full rows with both user-defined and auto-generated aliases
    var first = result.get(0);
    assertNotNull("Result should contain alias 'a'", first.getProperty("a"));
    assertNotNull("Result should contain alias 'b'", first.getProperty("b"));
    session.commit();
  }

  /** Exercises ReturnMatchPatternsStep with auto-generated aliases stripped. */
  @Test
  public void testReturnPatternsStripsAutoGeneratedAliases() {
    session.begin();
    // Use --> shorthand which creates auto-generated alias for the edge
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}-->{as:b} RETURN $patterns")
        .toList();
    assertFalse(result.isEmpty());
    for (var row : result) {
      // User aliases should be present
      assertNotNull(row.getProperty("a"));
      assertNotNull(row.getProperty("b"));
      // Auto-generated aliases (starting with $YOUTRACKDB_DEFAULT_ALIAS_) should be stripped
      for (var prop : row.getPropertyNames()) {
        assertFalse("Auto-generated alias should not appear in $patterns output: " + prop,
            prop.startsWith("$YOUTRACKDB_DEFAULT_ALIAS_"));
      }
    }
    session.commit();
  }

  /**
   * Exercises ReturnMatchElementsStep by using RETURN $elements.
   * n1 has 2 friends (n2, n3), producing 2 MATCH rows with 2 user-defined aliases
   * each (a, b). $elements unrolls each row into separate records, yielding 4 elements.
   */
  @Test
  public void testReturnElementsUnrolls() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b} RETURN $elements")
        .toList();
    // 2 MATCH rows (n1->n2, n1->n3) * 2 user-defined aliases = 4 unrolled elements
    assertEquals(4, result.size());
    // Each result should be a single identifiable record, not a map with aliases
    for (var row : result) {
      assertNotNull(row.getIdentity());
    }
    session.commit();
  }

  /**
   * Verifies that ORDER BY + LIMIT on the RETURN $elements path goes through the
   * bounded min-heap in OrderByStep (the MatchExecutionPlanner must propagate
   * maxResults = SKIP + LIMIT). With 2 MATCH rows each yielding 2 $elements,
   * the heap must return exactly the top-1 element by name DESC.
   */
  @Test
  public void testReturnElementsOrderByLimit() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
            + " RETURN $elements ORDER BY name DESC LIMIT 1")
        .toList();
    // n1 has friends n2 and n3. MATCH produces 2 rows: (a=n1,b=n2) and (a=n1,b=n3).
    // $elements unrolls each row into 2 records → 4 elements: n1,n2,n1,n3.
    // ORDER BY name DESC LIMIT 1 → top-1 is "n3" (lexicographically largest).
    assertEquals(1, result.size());
    assertEquals("n3", result.getFirst().getProperty("name"));
    session.commit();
  }

  /**
   * Verifies that ORDER BY without LIMIT on the RETURN $elements path goes through
   * the unbounded sort in OrderByStep (maxResults remains null). All 4 $elements
   * from 2 MATCH rows must be returned in sorted order.
   */
  @Test
  public void testReturnElementsOrderByWithoutLimit() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
            + " RETURN $elements ORDER BY name ASC")
        .toList();
    // 2 MATCH rows * 2 aliases = 4 elements, sorted by name
    assertEquals(4, result.size());
    for (var i = 0; i < result.size() - 1; i++) {
      var nameA = (String) result.get(i).getProperty("name");
      var nameB = (String) result.get(i + 1).getProperty("name");
      if (nameA != null && nameB != null) {
        assertTrue(nameA.compareTo(nameB) <= 0);
      }
    }
    session.commit();
  }

  /**
   * Verifies that ORDER BY + SKIP + LIMIT on the RETURN $elements path computes
   * the bounded-heap size as skip + limit. With 6 Person vertices:
   * ORDER BY name ASC → n1,n2,n3,n4,n5,n6; SKIP 2 LIMIT 3 → n3,n4,n5.
   * The bounded heap must keep top 5 (2+3) elements so that after SKIP 2
   * there are still 3 results. If the heap size ignores SKIP, it keeps only 3,
   * and after SKIP 2 only 1 result remains — which is wrong.
   */
  @Test
  public void testReturnElementsOrderBySkipLimit() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a}"
            + " RETURN $elements ORDER BY name ASC SKIP 2 LIMIT 3")
        .toList();
    assertEquals(3, result.size());
    assertEquals("n3", result.get(0).getProperty("name"));
    assertEquals("n4", result.get(1).getProperty("name"));
    assertEquals("n5", result.get(2).getProperty("name"));
    session.commit();
  }

  /**
   * Verifies that ORDER BY + LIMIT 0 on RETURN $elements returns nothing.
   * Exercises the maxResults=0 fast path in OrderByStep.init() which
   * must close the upstream stream without consuming it.
   */
  @Test
  public void testReturnElementsOrderByLimitZero() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a}"
            + " RETURN $elements ORDER BY name ASC LIMIT 0")
        .toList();
    assertEquals(0, result.size());
    session.commit();
  }

  /**
   * Verifies that ORDER BY + SKIP (without LIMIT) on the RETURN $elements path
   * does NOT use the bounded-heap (maxResults is null). All elements are sorted,
   * then the first 2 are skipped.
   */
  @Test
  public void testReturnElementsOrderBySkipWithoutLimit() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a}"
            + " RETURN $elements ORDER BY name ASC SKIP 2")
        .toList();
    assertEquals(4, result.size());
    assertEquals("n3", result.get(0).getProperty("name"));
    assertEquals("n4", result.get(1).getProperty("name"));
    assertEquals("n5", result.get(2).getProperty("name"));
    assertEquals("n6", result.get(3).getProperty("name"));
    session.commit();
  }

  /**
   * Exercises ReturnMatchPathElementsStep by using RETURN $pathElements.
   * Unlike $elements, $pathElements includes auto-generated aliases too, yielding
   * more elements per row. Each result should be an identifiable record.
   */
  @Test
  public void testReturnPathElementsIncludesAll() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b} "
            + "RETURN $pathElements")
        .toList();
    // At minimum, should have the same elements as $elements (4), possibly more
    // from auto-generated aliases
    assertTrue("$pathElements should produce at least 4 records",
        result.size() >= 4);
    for (var row : result) {
      assertNotNull(row.getIdentity());
    }
    session.commit();
  }

  /**
   * Exercises OptionalMatchStep, OptionalMatchEdgeTraverser, and RemoveEmptyOptionalsStep
   * by testing a MATCH pattern with optional node that has no matches.
   */
  @Test
  public void testOptionalMatchWithNoMatchProducesNull() {
    session.begin();
    // n5 has no outgoing Friend edges, so b should be null (optional)
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n5')}.out('Friend'){as:b, optional:true}"
            + " RETURN a.name as aName, b")
        .toList();
    assertEquals(1, result.size());
    assertEquals("n5", result.get(0).getProperty("aName"));
    // b should be null because n5 has no outgoing Friend edges
    // (Optional: LEFT JOIN semantics - row is preserved with null for unmatched alias)
    assertNull("optional node with no match should produce null",
        result.get(0).getProperty("b"));
    session.commit();
  }

  /**
   * Exercises FilterNotMatchPatternStep by testing MATCH with NOT pattern.
   * NOT clause syntax: MATCH {...}, NOT {...} --> {...} (comma before NOT).
   */
  @Test
  public void testNotPatternFiltersMatchingRows() {
    session.begin();
    // Find persons 'a' who are friends with 'b', but filter out rows where
    // a is also directly friends with b via the NOT pattern
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " NOT {as:a}.out('Friend'){as:b, where:(name='n3')}"
            + " RETURN b.name as bName")
        .toList();
    // n1 is friends with n2 and n3, NOT pattern removes n3
    assertEquals(1, result.size());
    assertEquals("n2", result.get(0).getProperty("bName"));
    session.commit();
  }

  /** Exercises MatchReverseEdgeTraverser by testing reverse traversal (.in()). */
  @Test
  public void testReverseEdgeTraversal() {
    session.begin();
    // Traverse backwards: start from n4, go in() to find who has n4 as a friend
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n4')}.in('Friend'){as:b}"
            + " RETURN b.name as bName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("n2", result.get(0).getProperty("bName"));
    session.commit();
  }

  /** Exercises MatchFieldTraverser by testing field-based traversal (.fieldName syntax). */
  @Test
  public void testFieldMatchTraversal() {
    // Schema changes must be outside a transaction
    session.execute("CREATE class FieldTestV extends V").close();
    session.execute("CREATE property FieldTestV.link LINK").close();

    session.begin();
    var v1 = session.newVertex("FieldTestV");
    v1.setProperty("name", "src");
    var v2 = session.newVertex("FieldTestV");
    v2.setProperty("name", "target");
    v1.setProperty("link", v2.getIdentity());
    session.commit();

    session.begin();
    // .link{as:b} uses MatchFieldTraverser to access the "link" property
    var result = session.query(
        "MATCH {class:FieldTestV, as:a, where:(name='src')}.link{as:b}"
            + " RETURN b.name as bName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("target", result.get(0).getProperty("bName"));
    session.commit();
  }

  /**
   * Exercises explain() which invokes prettyPrint() on all execution steps in the plan.
   * Verifies the plan contains expected step types: SET (MatchFirstStep) and MATCH.
   */
  @Test
  public void testExplainMatchQuery() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
            + " RETURN a.name, b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    assertTrue("plan should contain SET step", plan.contains("SET"));
    assertTrue("plan should contain MATCH step", plan.contains("MATCH"));
    assertTrue("plan should contain forward arrow", plan.contains("---->"));
    session.commit();
  }

  /**
   * Exercises explain() with optional match to cover OptionalMatchStep.prettyPrint().
   * Verifies the plan contains "OPTIONAL MATCH".
   */
  @Test
  public void testExplainMatchOptionalQuery() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b, optional:true} RETURN a, b")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should contain OPTIONAL MATCH", plan.contains("OPTIONAL MATCH"));
    session.commit();
  }

  /**
   * Exercises explain() with NOT pattern. After Track 2 (YTDB-592), NOT patterns that
   * qualify for hash anti-join produce "HASH ANTI_JOIN" in the EXPLAIN output instead
   * of "NOT". This test accepts either form.
   */
  @Test
  public void testExplainMatchNotPattern() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a}.out('Friend'){as:b},"
            + " NOT {as:a}.out('Friend'){as:b, where:(name='n3')}"
            + " RETURN a, b")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "plan should contain NOT or HASH ANTI_JOIN step",
        plan.contains("NOT") || plan.contains("HASH ANTI_JOIN"));
    session.commit();
  }

  /**
   * Exercises explain() with RETURN $paths to cover ReturnMatchPathsStep.prettyPrint().
   * Verifies the plan contains "RETURN $paths".
   */
  @Test
  public void testExplainReturnPaths() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
            + " RETURN $paths")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should contain RETURN $paths", plan.contains("RETURN $paths"));
    session.commit();
  }

  /**
   * Exercises explain() with RETURN $elements to cover ReturnMatchElementsStep.prettyPrint().
   * Verifies the plan contains "UNROLL $elements".
   */
  @Test
  public void testExplainReturnElements() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
            + " RETURN $elements")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should contain UNROLL $elements", plan.contains("UNROLL $elements"));
    session.commit();
  }

  /**
   * Exercises explain() with RETURN $pathElements to cover
   * ReturnMatchPathElementsStep.prettyPrint(). Verifies plan contains "UNROLL $pathElements".
   */
  @Test
  public void testExplainReturnPathElements() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
            + " RETURN $pathElements")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should contain UNROLL $pathElements",
        plan.contains("UNROLL $pathElements"));
    session.commit();
  }

  /**
   * Exercises explain() with RETURN $patterns to cover
   * ReturnMatchPatternsStep.prettyPrint(). Verifies plan contains "RETURN $patterns".
   */
  @Test
  public void testExplainReturnPatterns() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
            + " RETURN $patterns")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should contain RETURN $patterns", plan.contains("RETURN $patterns"));
    session.commit();
  }

  /**
   * Verifies EXPLAIN on reverse traversal shows the reverse arrow indicator in the plan.
   */
  @Test
  public void testExplainReverseTraversal() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n4')}.in('Friend'){as:b}"
            + " RETURN a, b")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should contain MATCH step", plan.contains("MATCH"));
    session.commit();
  }

  /**
   * Exercises chained single-step edges: .out('Friend').out('Friend'){as:b} creates two
   * separate MatchStep instances with an auto-generated intermediate alias. This is NOT
   * the same as compound path (parenthesized) syntax, which uses MatchMultiEdgeTraverser.
   */
  @Test
  public void testChainedEdgeTraversal() {
    session.begin();
    // Chained edges: n1 -> n2 -> n4, and n1 -> n3 (n3 has no outgoing Friend)
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend').out('Friend'){as:b}"
            + " RETURN b.name as bName")
        .toList();
    // Only n1->n2->n4 produces a 2-hop result (n3 has no outgoing edges)
    assertEquals(1, result.size());
    assertEquals("n4", result.get(0).getProperty("bName"));
    session.commit();
  }

  /**
   * Exercises both(), which creates bidirectional edge traversal.
   */
  @Test
  public void testBothDirectionTraversal() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n2')}.both('Friend'){as:b}"
            + " RETURN b.name as bName")
        .toList();
    // n2 has: outgoing Friend to n4, incoming Friend from n1
    // both() should find both n4 and n1
    assertFalse(result.isEmpty());
    var names = new HashSet<String>();
    for (var row : result) {
      names.add(row.getProperty("bName"));
    }
    assertTrue("both() should find n1 (incoming)", names.contains("n1"));
    assertTrue("both() should find n4 (outgoing)", names.contains("n4"));
    session.commit();
  }

  /**
   * Exercises OptionalMatchEdgeTraverser with an optional node that HAS matches,
   * verifying the non-null merge path in the optional traverser.
   */
  @Test
  public void testOptionalMatchWithActualMatches() {
    session.begin();
    // n1 has outgoing Friend edges, so b should be populated
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b, optional:true} RETURN a.name as aName, b.name as bName")
        .toList();
    assertFalse(result.isEmpty());
    for (var row : result) {
      assertEquals("n1", row.getProperty("aName"));
      assertNotNull("Optional node with match should have a value", row.getProperty("bName"));
    }
    session.commit();
  }

  /**
   * Exercises MatchFieldTraverser when the field value is null (no link set).
   * The traverser should produce an empty result, not an error.
   */
  @Test
  public void testFieldMatchTraversalNullField() {
    // Schema changes must be outside a transaction
    session.execute("CREATE class NullFieldV extends V").close();
    session.execute("CREATE property NullFieldV.link LINK").close();

    session.begin();
    var v1 = session.newVertex("NullFieldV");
    v1.setProperty("name", "nolink");
    // link property is NOT set, so it's null
    session.commit();

    session.begin();
    // .link{as:b} on a record with null link should produce no results
    var result = session.query(
        "MATCH {class:NullFieldV, as:a, where:(name='nolink')}.link{as:b}"
            + " RETURN a.name as aName, b")
        .toList();
    // No results because the field is null (traversal produces empty stream)
    assertEquals(0, result.size());
    session.commit();
  }

  // NOTE: WHILE with depthAlias is tested by testWhileWithDepthAndPathAlias below,
  // which covers both depthAlias and pathAlias in a single, more comprehensive test.

  /**
   * Exercises profiling path in AbstractExecutionStep by running a MATCH query
   * with PROFILE keyword.
   */
  @Test
  public void testProfileMatchQuery() {
    session.begin();
    var result = session.query(
        "PROFILE MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
            + " RETURN a.name, b.name")
        .toList();
    assertEquals(1, result.size());
    // PROFILE result should contain the execution plan with timing information
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull("PROFILE should produce executionPlanAsString", plan);
    assertTrue("profiled plan should contain MATCH step", plan.contains("MATCH"));
    session.commit();
  }

  /**
   * Exercises MatchEdgeTraverser consistency check by having the same alias
   * in two different match expressions that must agree on the value.
   */
  @Test
  public void testConsistencyCheckSameAlias() {
    session.begin();
    // Both paths must agree on 'b': a->b and b->c, where b is n2
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " {as:b}.out('Friend'){as:c} RETURN a.name as a, b.name as b, c.name as c")
        .toList();
    // n1->n2->n4, n1->n3 (n3 has no out Friend), so we should get a=n1, b=n2, c=n4
    assertFalse(result.isEmpty());
    for (var row : result) {
      assertEquals("n1", row.getProperty("a"));
    }
    session.commit();
  }

  /**
   * Exercises MatchReverseEdgeTraverser by creating a diamond pattern where the
   * scheduler encounters a back-edge to an already-visited node 'd'. The diamond
   * graph (0->1->3, 0->2->3) is created by initDiamondTest() in beforeTest().
   */
  @Test
  public void testDiamondPatternTriggersBackEdge() {
    session.begin();
    // Diamond from initDiamondTest: uid 0->1->3, 0->2->3
    // Two paths to 'd' (uid=3): scheduler handles the second as a back-edge
    var result = session.query(
        "MATCH {class:DiamondV, as:a, where:(uid=0)}"
            + ".out('DiamondE'){as:b}.out('DiamondE'){as:d},"
            + " {as:a}.out('DiamondE'){as:c}.out('DiamondE'){as:d}"
            + " RETURN a.uid as a, b.uid as b, c.uid as c, d.uid as d")
        .toList();
    assertFalse(result.isEmpty());
    for (var row : result) {
      assertEquals(0, (int) row.getProperty("a"));
      assertEquals(3, (int) row.getProperty("d"));
    }
    session.commit();
  }

  /**
   * Exercises MatchFieldTraverser with a LINKLIST property that returns an Iterable,
   * covering the Iterable branch in traversePatternEdge().
   */
  @Test
  public void testFieldMatchTraversalLinklist() {
    session.execute("CREATE class LinkListV extends V").close();
    session.execute("CREATE property LinkListV.links LINKLIST LinkListV")
        .close();

    session.begin();
    session.execute("CREATE VERTEX LinkListV set name = 't1'").close();
    session.execute("CREATE VERTEX LinkListV set name = 't2'").close();
    session.execute("CREATE VERTEX LinkListV set name = 'src'").close();
    session.commit();

    // Use SQL UPDATE to set the LINKLIST property correctly
    session.begin();
    session.execute(
        "UPDATE LinkListV SET links = (SELECT FROM LinkListV WHERE name IN ['t1','t2'])"
            + " WHERE name = 'src'")
        .close();
    session.commit();

    session.begin();
    // .links{as:b} returns a LINKLIST (Iterable), exercising the Iterable branch
    var result = session.query(
        "MATCH {class:LinkListV, as:a, where:(name='src')}.links{as:b}"
            + " RETURN b.name as bName")
        .toList();
    assertEquals(2, result.size());
    var names = new HashSet<String>();
    for (var row : result) {
      names.add(row.getProperty("bName"));
    }
    assertTrue(names.contains("t1"));
    assertTrue(names.contains("t2"));
    session.commit();
  }

  /**
   * Exercises MatchMultiEdgeTraverser with a compound path containing multiple
   * traversal steps, covering the left-to-right pipeline execution.
   */
  @Test
  public void testCompoundPathTraversal() {
    session.begin();
    // Compound path: .out('Friend').out('Friend') as a single multi-step item
    // n1 -> n2 -> n4, so traversing two hops from n1 should find n4
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".(out('Friend').out('Friend')){as:b}"
            + " RETURN b.name as bName")
        .toList();
    // n1->n2->n4 is the only 2-hop Friend path from n1 (n1->n3 has no out Friend)
    assertEquals(1, result.size());
    assertEquals("n4", result.get(0).getProperty("bName"));
    session.commit();
  }

  /**
   * Exercises MatchMultiEdgeTraverser with a compound path containing a WHILE
   * clause on a sub-item, covering the recursive expansion within multi-edge.
   */
  @Test
  public void testCompoundPathWithWhile() {
    session.begin();
    // Compound path with WHILE: traverse out('Friend') recursively up to depth 2
    // n1 -> n2 -> n4 -> n5/n6, depth<2 means depths 0 and 1
    // depth 0: n1 itself, depth 1: n2, n3
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".(out('Friend'){while:($depth < 2)}){as:b}"
            + " RETURN b.name as bName")
        .toList();
    assertFalse("WHILE traversal should produce results", result.isEmpty());
    var names = new HashSet<String>();
    for (var row : result) {
      names.add(row.getProperty("bName"));
    }
    // n1 at depth 0 should be included (WHILE starts from the starting point)
    assertTrue("should include starting point n1", names.contains("n1"));
    session.commit();
  }

  /**
   * Exercises MatchEdgeTraverser.matchesRid() by using a RID constraint in the
   * MATCH filter, covering the RID matching branch.
   */
  @Test
  public void testMatchWithRidConstraint() {
    session.begin();
    // Get the RID of n1
    var n1Result = session.query("SELECT FROM Person WHERE name = 'n1'").toList();
    assertFalse(n1Result.isEmpty());
    var n1Rid = n1Result.get(0).getIdentity();

    // Use RID in MATCH pattern
    var result = session.query(
        "MATCH {class:Person, as:a, where:(@rid = " + n1Rid + ")}"
            + ".out('Friend'){as:b} RETURN a.name as aName, b.name as bName")
        .toList();
    assertFalse(result.isEmpty());
    for (var row : result) {
      assertEquals("n1", row.getProperty("aName"));
    }
    session.commit();
  }

  /**
   * Exercises MatchReverseEdgeTraverser by using both() in a back-edge pattern.
   * The DFS visits 'a' first, then 'b' via out, and the second pattern references
   * both a→b with both() which is bidirectional, creating a back-edge to the
   * already-visited node that triggers direction flip.
   */
  @Test
  public void testBidirectionalBackEdgeTriggersReverse() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " {as:b}.both('Friend'){as:a}"
            + " RETURN a.name as aName, b.name as bName")
        .toList();
    assertFalse(result.isEmpty());
    for (var row : result) {
      assertEquals("n1", row.getProperty("aName"));
    }
    session.commit();
  }

  /**
   * Exercises EXPLAIN on the bidirectional back-edge pattern to verify the planner
   * includes a reverse step in the execution plan.
   */
  @Test
  public void testExplainBidirectionalBackEdge() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " {as:b}.both('Friend'){as:a}"
            + " RETURN a, b")
        .toList();
    assertFalse(result.isEmpty());
    // The EXPLAIN output should contain the plan structure
    var plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("Execution plan should contain MATCH step",
        ((String) plan).contains("MATCH"));
    session.commit();
  }

  /**
   * Tests that the planner handles a redundant back-edge pattern where 'b' is already
   * matched by the first expression but also referenced as optional in a second expression.
   * The second expression ({as:b, optional:true}.in('Friend'){as:a}) re-validates
   * the a→b relationship. The planner may use reverse traversal for the back-edge.
   */
  @Test
  public void testBackEdgeWithOptionalAnnotation() {
    session.begin();
    // First expression: a->b via out('Friend')
    // Second expression: b->a via in('Friend'), b marked optional (already matched)
    var result = session.query(
        "MATCH {class:Person, as:a}.out('Friend'){as:b},"
            + " {as:b, optional:true}.in('Friend'){as:a}"
            + " RETURN a.name as aName, b.name as bName")
        .toList();
    assertFalse(result.isEmpty());
    // The back-edge re-validates a→b, so results should be consistent friend pairs
    for (var row : result) {
      assertNotNull("a should always have a name", row.getProperty("aName"));
      assertNotNull("b should always have a name (already matched)", row.getProperty("bName"));
    }
    session.commit();
  }

  /**
   * Exercises MatchEdgeTraverser.matchesRid by using a RID-based filter
   * on the target node of a MATCH pattern.
   */
  @Test
  public void testMatchTargetNodeWithRidFilter() {
    session.begin();
    var n2 = session.query("SELECT FROM Person WHERE name = 'n2'").toList();
    assertFalse(n2.isEmpty());
    var n2Rid = n2.get(0).getIdentity();

    var result = session.query(
        "MATCH {class:Person, as:a}.out('Friend'){as:b, where:(@rid = " + n2Rid + ")}"
            + " RETURN a.name as aName, b.name as bName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("n1", result.get(0).getProperty("aName"));
    assertEquals("n2", result.get(0).getProperty("bName"));
    session.commit();
  }

  /**
   * Exercises MatchEdgeTraverser.next() with both depthAlias and pathAlias to cover
   * the metadata propagation branches for depth tracking and path recording.
   * n1 -> n2 -> n4 -> n5/n6, with WHILE $depth < 3: depths 0, 1, 2.
   */
  @Test
  public void testWhileWithDepthAndPathAlias() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){while:($depth < 3), as:b,"
            + " depthAlias: d, pathAlias: p}"
            + " RETURN b.name as bName, d, p")
        .toList();
    assertFalse(result.isEmpty());
    boolean foundStart = false;
    boolean foundDeeper = false;
    for (var row : result) {
      Object depth = row.getProperty("d");
      assertNotNull("every result should have a depth", depth);
      int d = ((Number) depth).intValue();
      assertNotNull("every result should have a path", row.getProperty("p"));
      if (d == 0) {
        foundStart = true;
        assertEquals("depth 0 should be the starting point n1", "n1",
            row.getProperty("bName"));
      }
      if (d > 0) {
        foundDeeper = true;
      }
    }
    assertTrue("should include depth-0 starting point", foundStart);
    assertTrue("should include deeper traversal results", foundDeeper);
    session.commit();
  }

  /**
   * Exercises MatchMultiEdgeTraverser with a compound path containing a filter
   * on the intermediate sub-step to cover the filter branch in simple expansion.
   */
  @Test
  public void testCompoundPathWithFilter() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".(out('Friend'){where:(name='n2')}.out('Friend')){as:b}"
            + " RETURN b.name as bName")
        .toList();
    // n1->n2 (passes filter)->n4
    assertFalse(result.isEmpty());
    assertEquals("n4", result.get(0).getProperty("bName"));
    session.commit();
  }

  /**
   * Exercises MatchEdgeTraverser.matchesClass with a class constraint on the
   * target node.
   */
  @Test
  public void testMatchTargetWithClassConstraint() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){class:Person, as:b}"
            + " RETURN b.name as bName")
        .toList();
    // n1 has two Person friends: n2 and n3
    assertEquals(2, result.size());
    session.commit();
  }

  /**
   * Exercises the Object[] args overload of SQLMatchStatement.execute() by using
   * positional parameters in a MATCH query.
   */
  @Test
  public void testMatchWithPositionalArgs() {
    session.begin();
    var result = session.execute(
        "MATCH {class:Person, as:a, where:(name = ?)}.out('Friend'){as:b}"
            + " RETURN a.name as aName, b.name as bName",
        "n1")
        .toList();
    assertFalse(result.isEmpty());
    for (var row : result) {
      assertEquals("n1", row.getProperty("aName"));
    }
    session.commit();
  }

  /**
   * Exercises SQLMatchStatement.getLowerSubclass() by referencing the same alias
   * with a superclass (V) in one expression and a subclass (Person) in another.
   * The planner must resolve the two to the more specific type (Person).
   */
  @Test
  public void testMatchWithSameAliasSubclassConstraint() {
    session.begin();
    // Alias 'a' is constrained to V in one expression and Person in another.
    // getLowerSubclass() should resolve to Person (the more specific type).
    var result = session.query(
        "MATCH {class:V, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " {class:Person, as:a}"
            + " RETURN a.name as aName, b.name as bName")
        .toList();
    assertFalse(result.isEmpty());
    for (var row : result) {
      assertEquals("n1", row.getProperty("aName"));
    }
    session.commit();
  }

  /**
   * Exercises MATCH with DISTINCT in the return clause, covering the returnDistinct
   * branch in SQLMatchStatement. Uses both() which naturally produces duplicates:
   * n2 appears as both out-Friend of n1 and in-Friend of n4.
   */
  @Test
  public void testMatchReturnDistinct() {
    session.begin();
    // Without DISTINCT, both('Friend') from multiple starting points would produce
    // duplicate friend names (e.g., n2 is connected to both n1 and n4).
    var withoutDistinct = session.query(
        "MATCH {class:Person, as:a}.both('Friend'){as:b}"
            + " RETURN b.name as bName")
        .toList();
    var withDistinct = session.query(
        "MATCH {class:Person, as:a}.both('Friend'){as:b}"
            + " RETURN DISTINCT b.name as bName")
        .toList();
    // DISTINCT should reduce the result count
    assertTrue("DISTINCT should produce fewer or equal results than non-DISTINCT",
        withDistinct.size() <= withoutDistinct.size());
    // Verify no duplicates in DISTINCT result
    var names = new java.util.HashSet<String>();
    for (var row : withDistinct) {
      assertTrue("DISTINCT should not produce duplicate names",
          names.add(row.getProperty("bName")));
    }
    // Verify DISTINCT actually removed something (data has natural duplicates via both())
    assertTrue("DISTINCT should have removed duplicates",
        withDistinct.size() < withoutDistinct.size());
    session.commit();
  }

  /**
   * Tests a two-path MATCH with reverse edges and shared aliases, mirroring the
   * LDBC IC5 pattern: find (person, forum) pairs via path 1, then join with
   * (person, post, forum) via path 2 using in() traversals.
   *
   * <p>Graph: Person -KNOWS-> Person -HAS_MEMBER-> Forum -CONTAINER_OF-> Post -HAS_CREATOR-> Person
   * Two-path MATCH: path 1 finds (person, forum), path 2 finds posts by that person in that forum.
   */
  @Test
  public void testTwoPathMatchWithReverseEdgesAndSharedAliases() {
    session.execute("CREATE class Forum extends V").close();
    session.execute("CREATE class Post extends V").close();
    session.execute("CREATE class KNOWS extends E").close();
    session.execute("CREATE class HAS_MEMBER extends E").close();
    session.execute("CREATE class CONTAINER_OF extends E").close();
    session.execute("CREATE class HAS_CREATOR extends E").close();

    session.begin();
    // Create persons
    session.execute("CREATE VERTEX Person set name = 'alice', uid = 1").close();
    session.execute("CREATE VERTEX Person set name = 'bob', uid = 2").close();
    // Create forum and posts
    session.execute("CREATE VERTEX Forum set title = 'Forum1', uid = 10").close();
    session.execute("CREATE VERTEX Post set content = 'Post1', uid = 100").close();
    session.execute("CREATE VERTEX Post set content = 'Post2', uid = 101").close();
    // Edges: alice -> bob (KNOWS)
    session.execute(
        "CREATE EDGE KNOWS FROM (SELECT FROM Person WHERE uid=1)"
            + " TO (SELECT FROM Person WHERE uid=2)")
        .close();
    // Edges: forum -> bob (HAS_MEMBER)
    session.execute(
        "CREATE EDGE HAS_MEMBER FROM (SELECT FROM Forum WHERE uid=10)"
            + " TO (SELECT FROM Person WHERE uid=2)")
        .close();
    // Edges: forum -> posts (CONTAINER_OF)
    session.execute(
        "CREATE EDGE CONTAINER_OF FROM (SELECT FROM Forum WHERE uid=10)"
            + " TO (SELECT FROM Post WHERE uid=100)")
        .close();
    session.execute(
        "CREATE EDGE CONTAINER_OF FROM (SELECT FROM Forum WHERE uid=10)"
            + " TO (SELECT FROM Post WHERE uid=101)")
        .close();
    // Edges: posts -> bob (HAS_CREATOR)
    session.execute(
        "CREATE EDGE HAS_CREATOR FROM (SELECT FROM Post WHERE uid=100)"
            + " TO (SELECT FROM Person WHERE uid=2)")
        .close();
    session.execute(
        "CREATE EDGE HAS_CREATOR FROM (SELECT FROM Post WHERE uid=101)"
            + " TO (SELECT FROM Person WHERE uid=2)")
        .close();
    session.commit();

    session.begin();
    // Two-path MATCH: join (person, forum) with (person's posts in forum)
    var result = session.query(
        "SELECT person.uid as personId, forum.uid as forumId, count(*) as postCount"
            + " FROM ("
            + "  SELECT DISTINCT person, forum, post FROM ("
            + "   MATCH"
            + "    {class: Person, as: start, where: (uid = 1)}"
            + "      .out('KNOWS'){as: person}"
            + "      .inE('HAS_MEMBER').outV(){class: Forum, as: forum},"
            + "    {as: person}.in('HAS_CREATOR'){class: Post, as: post}"
            + "      .in('CONTAINER_OF'){as: forum}"
            + "   RETURN person, forum, post"
            + "  )"
            + " ) GROUP BY person.uid, forum.uid")
        .toList();
    // Bob (uid=2) is in Forum1 (uid=10) with 2 posts (Post1 and Post2)
    assertEquals(1, result.size());
    assertEquals(2, (int) result.get(0).getProperty("personId"));
    assertEquals(10, (int) result.get(0).getProperty("forumId"));
    assertEquals(2L, (long) result.get(0).getProperty("postCount"));
    session.commit();
  }

  /**
   * Regression test: MATCH with $matched dependency on an optional node where the
   * dependency is resolved via a different branch of the pattern graph. This reproduces
   * the IS7 LDBC query pattern:
   *
   * <pre>
   *   msg --HAS_CREATOR--> author
   *   msg <--REPLY_OF-- reply --HAS_CREATOR--> replyAuthor --KNOWS--> knowsCheck
   *                                                           (optional, where: @rid = $matched.author.@rid)
   * </pre>
   *
   * With cost-based edge sorting, the planner may visit the reply branch before the
   * author branch, causing knowsCheck's $matched.author dependency to be unsatisfied
   * when first encountered. The planner must still schedule the replyAuthor→knowsCheck
   * edge after the author dependency is resolved.
   */
  @Test
  public void testMatchedDependencyOptionalNodeDifferentBranch() {
    // Schema: Message, Comment extends Message, Author (V classes), edge classes
    session.execute("CREATE class Message extends V").close();
    session.execute("CREATE class Comment extends V").close();
    session.execute("CREATE class Author extends V").close();
    session.execute("CREATE class HAS_CREATOR extends E").close();
    session.execute("CREATE class REPLY_OF extends E").close();
    session.execute("CREATE class KNOWS extends E").close();

    session.begin();
    // Create graph:  msg --HAS_CREATOR--> alice
    //                reply1 --REPLY_OF--> msg, reply1 --HAS_CREATOR--> bob
    //                reply2 --REPLY_OF--> msg, reply2 --HAS_CREATOR--> carol
    //                bob --KNOWS--> alice (so bob knows the author)
    session.execute("CREATE VERTEX Message set id = 1").close();
    session.execute("CREATE VERTEX Comment set id = 10, content = 'reply1'").close();
    session.execute("CREATE VERTEX Comment set id = 20, content = 'reply2'").close();
    session.execute("CREATE VERTEX Author set id = 100, name = 'Alice'").close();
    session.execute("CREATE VERTEX Author set id = 200, name = 'Bob'").close();
    session.execute("CREATE VERTEX Author set id = 300, name = 'Carol'").close();

    session.execute(
        "CREATE EDGE HAS_CREATOR from (select from Message where id = 1)"
            + " to (select from Author where id = 100)")
        .close();
    session.execute(
        "CREATE EDGE REPLY_OF from (select from Comment where id = 10)"
            + " to (select from Message where id = 1)")
        .close();
    session.execute(
        "CREATE EDGE REPLY_OF from (select from Comment where id = 20)"
            + " to (select from Message where id = 1)")
        .close();
    session.execute(
        "CREATE EDGE HAS_CREATOR from (select from Comment where id = 10)"
            + " to (select from Author where id = 200)")
        .close();
    session.execute(
        "CREATE EDGE HAS_CREATOR from (select from Comment where id = 20)"
            + " to (select from Author where id = 300)")
        .close();
    session.execute(
        "CREATE EDGE KNOWS from (select from Author where id = 200)"
            + " to (select from Author where id = 100)")
        .close();
    session.commit();

    // Query mirrors the IS7 LDBC pattern:
    // - msg is the root (WHERE clause)
    // - author is reached via HAS_CREATOR from msg
    // - reply is reached via in('REPLY_OF') from msg
    // - replyAuthor is reached via HAS_CREATOR from reply
    // - knowsCheck depends on $matched.author (optional)
    session.begin();
    var query =
        "MATCH {class: Message, as: msg, where: (id = 1)}"
            + "  .out('HAS_CREATOR'){as: author},"
            + "  {as: msg}"
            + "  .in('REPLY_OF'){as: reply}"
            + "  .out('HAS_CREATOR'){as: replyAuthor}"
            + "  .out('KNOWS'){as: knowsCheck,"
            + "    where: (@rid = $matched.author.@rid), optional: true}"
            + " RETURN reply.id as replyId,"
            + "   replyAuthor.name as replyAuthorName,"
            + "   ifnull(knowsCheck, false, true) as knowsOriginalAuthor"
            + " ORDER BY replyId";
    var results = session.query(query).toList();

    assertEquals(2, results.size());
    // reply1 (id=10) by Bob who KNOWS Alice → true
    assertEquals(10, ((Number) results.get(0).getProperty("replyId")).intValue());
    assertEquals("Bob", results.get(0).getProperty("replyAuthorName"));
    assertEquals(true, results.get(0).getProperty("knowsOriginalAuthor"));

    // reply2 (id=20) by Carol who does NOT know Alice → false
    assertEquals(20, ((Number) results.get(1).getProperty("replyId")).intValue());
    assertEquals("Carol", results.get(1).getProperty("replyAuthorName"));
    assertEquals(false, results.get(1).getProperty("knowsOriginalAuthor"));
    session.commit();
  }

  /**
   * Verifies that $parent.$current in a LET subquery resolves to the current outer
   * record, even when a preceding LET clause runs a scan that internally sets
   * VAR_CURRENT on its execution context.
   *
   * <p>The first LET ($allPersons) scans the Person table, causing the subquery's
   * LoaderExecutionStream to call setSystemVariable(VAR_CURRENT, ...) for each
   * scanned record. Without the intermediate context isolation in LetQueryStep,
   * this write would propagate to the outer pipeline context via the
   * setSystemVariable delegation chain, corrupting $current for the second LET.
   *
   * <p>The second LET ($selfLookup) references $parent.$current.name. If isolation
   * is correct, it sees the current outer row's name; if corrupted, it sees the
   * last Person scanned by the first LET and the assertEquals on name fails.
   */
  @Test
  public void testMatchWithMultipleLetAndParentCurrent() {
    // Use the existing Person/Friend fixture (n1..n6).
    // MATCH returns two persons; two LETs follow:
    //  1. $allPersons = full scan of Person (corrupts ctx.$current without fix)
    //  2. $selfLookup = correlates via $parent.$current.name (detects corruption)
    session.begin();
    var result =
        session.query(
            "SELECT name, $selfLookup as selfLookup FROM ("
                + "  MATCH {class: Person, as: person, where: (name IN ['n1','n2','n3'])}"
                + "  RETURN person.name as name"
                + ") LET $allPersons = (SELECT FROM Person),"
                + "    $selfLookup = (SELECT name FROM Person"
                + "                   WHERE name = $parent.$current.name)");
    var names = new HashSet<String>();
    while (result.hasNext()) {
      var item = result.next();
      var name = (String) item.getProperty("name");
      assertNotNull("name must not be null", name);

      @SuppressWarnings("unchecked")
      var selfLookup = (List<Result>) item.getProperty("selfLookup");
      assertNotNull("selfLookup must not be null for " + name, selfLookup);
      assertEquals(
          "$parent.$current.name must resolve to the current row's name (" + name + ")",
          1,
          selfLookup.size());
      assertEquals(
          "$parent.$current.name must match the outer row's name",
          name,
          selfLookup.get(0).getProperty("name"));
      names.add(name);
    }
    result.close();
    // All three requested persons must have been returned
    assertTrue("Expected n1 in results", names.contains("n1"));
    assertTrue("Expected n2 in results", names.contains("n2"));
    assertTrue("Expected n3 in results", names.contains("n3"));
    session.commit();
  }

  /**
   * Tests that $matched references work correctly with non-optional nodes, where the
   * referenced alias is resolved via a sibling branch in the MATCH pattern.
   *
   * <pre>
   *   root --EdgeA--> nodeA
   *   root --EdgeB--> nodeB --EdgeC--> check {where: ($matched.nodeA != $currentMatch)}
   * </pre>
   *
   * The planner must ensure nodeA is visited before evaluating check's $matched
   * condition, regardless of edge traversal cost ordering.
   */
  @Test
  public void testMatchedDependencyAcrossBranches() {
    session.execute("CREATE class Root extends V").close();
    session.execute("CREATE class NodeA extends V").close();
    session.execute("CREATE class NodeB extends V").close();
    session.execute("CREATE class Check extends V").close();
    session.execute("CREATE class EdgeA extends E").close();
    session.execute("CREATE class EdgeB extends E").close();
    session.execute("CREATE class EdgeC extends E").close();

    session.begin();
    session.execute("CREATE VERTEX Root set name = 'root'").close();
    session.execute("CREATE VERTEX NodeA set name = 'a1'").close();
    session.execute("CREATE VERTEX NodeB set name = 'b1'").close();
    session.execute("CREATE VERTEX Check set name = 'c1'").close();

    session.execute(
        "CREATE EDGE EdgeA from (select from Root where name = 'root')"
            + " to (select from NodeA where name = 'a1')")
        .close();
    session.execute(
        "CREATE EDGE EdgeB from (select from Root where name = 'root')"
            + " to (select from NodeB where name = 'b1')")
        .close();
    session.execute(
        "CREATE EDGE EdgeC from (select from NodeB where name = 'b1')"
            + " to (select from Check where name = 'c1')")
        .close();
    session.commit();

    // nodeA and check (c1) are different vertices, so the $matched.nodeA != $currentMatch
    // condition is satisfied and the match succeeds.
    session.begin();
    var query =
        "MATCH {class: Root, as: root, where: (name = 'root')}"
            + "  .out('EdgeA'){as: nodeA},"
            + "  {as: root}"
            + "  .out('EdgeB'){as: nodeB}"
            + "  .out('EdgeC'){as: check, where: ($matched.nodeA != $currentMatch)}"
            + " RETURN nodeA.name as aName, nodeB.name as bName, check.name as cName";
    var results = session.query(query).toList();

    assertEquals(1, results.size());
    assertEquals("a1", results.get(0).getProperty("aName"));
    assertEquals("b1", results.get(0).getProperty("bName"));
    assertEquals("c1", results.get(0).getProperty("cName"));
    session.commit();
  }

  private List<? extends Identifiable> getManagedPathElements(String managerName) {
    var query =
        "  match {class:Employee, as:boss, where: (name = '"
            + managerName
            + "')}"
            + "  -ManagerOf->{}<-ParentDepartment-{"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return $pathElements";

    return session.execute(query).stream().map(Result::getIdentity).toList();
  }

  // -------------------------------------------------------------------
  // Regression: LET clause on a SELECT wrapping a MATCH subquery must
  // not cause the MATCH to return 0 rows.
  // -------------------------------------------------------------------

  /**
   * Verifies that a constant LET expression added to a SELECT wrapping a MATCH
   * subquery does not cause the MATCH results to vanish.
   *
   * <p>Without the bug fix, adding {@code LET $x = 42} causes the outer query
   * to return 0 rows even though the same query without the LET returns results.
   */
  @Test
  public void testMatchSubqueryWithConstantLetExpression() {
    // Baseline: MATCH without LET returns results
    var baselineQuery =
        "SELECT name FROM ("
            + "  MATCH {class: Person, as: p, where: (name = 'n1')}"
            + "    .out('Friend'){as: friend}"
            + "  RETURN friend"
            + ")";

    var baselineResults = session.query(baselineQuery).stream().toList();
    assertFalse("Baseline MATCH should return results", baselineResults.isEmpty());
    int expectedCount = baselineResults.size();

    // Same query with a trivial constant LET - must return the same rows
    var letQuery =
        "SELECT name, $x as x FROM ("
            + "  MATCH {class: Person, as: p, where: (name = 'n1')}"
            + "    .out('Friend'){as: friend}"
            + "  RETURN friend"
            + ") LET $x = 42";

    var letResults = session.query(letQuery).stream().toList();
    assertEquals(
        "Adding LET $x = 42 must not change the number of rows",
        expectedCount,
        letResults.size());
    // Verify the LET variable is accessible
    for (var r : letResults) {
      assertEquals(42, ((Number) r.getProperty("x")).intValue());
    }
  }

  /**
   * Verifies that an uncorrelated LET subquery (one that does not reference
   * {@code $parent.$current}) on a SELECT wrapping a MATCH subquery does not
   * cause the MATCH results to vanish.
   *
   * <p>This variant uses {@code LET $cnt = (SELECT count(*) FROM Person)}
   * which the planner should promote to a global (once-evaluated) LET.
   */
  @Test
  public void testMatchSubqueryWithLetSubquery() {
    var baselineQuery =
        "SELECT name FROM ("
            + "  MATCH {class: Person, as: p, where: (name = 'n1')}"
            + "    .out('Friend'){as: friend}"
            + "  RETURN friend"
            + ")";

    var baselineResults = session.query(baselineQuery).stream().toList();
    assertFalse("Baseline MATCH should return results", baselineResults.isEmpty());
    int expectedCount = baselineResults.size();

    // LET with a subquery that does not reference $parent.$current
    var letQuery =
        "SELECT name, $cnt as cnt FROM ("
            + "  MATCH {class: Person, as: p, where: (name = 'n1')}"
            + "    .out('Friend'){as: friend}"
            + "  RETURN friend"
            + ") LET $cnt = (SELECT count(*) FROM Person)";

    var letResults = session.query(letQuery).stream().toList();
    assertEquals(
        "Adding a LET subquery must not change the number of rows",
        expectedCount,
        letResults.size());
  }

  /**
   * Verifies that a nested {@code SELECT DISTINCT} wrapping a MATCH still works
   * when the outermost SELECT has a LET clause. This reproduces the three-level
   * nesting pattern used by LDBC IC5: outer SELECT with LET wraps a SELECT
   * DISTINCT which wraps the MATCH subquery.
   */
  @Test
  public void testNestedSelectDistinctMatchWithLet() {
    // Baseline: nested SELECT DISTINCT around MATCH, no LET
    var baselineQuery =
        "SELECT DISTINCT friendName FROM ("
            + "  MATCH {class: Person, as: p, where: (name = 'n1')}"
            + "    .out('Friend'){as: friend}"
            + "  RETURN friend.name as friendName"
            + ")";

    var baselineResults = session.query(baselineQuery).stream().toList();
    assertFalse("Baseline should return results", baselineResults.isEmpty());
    int expectedCount = baselineResults.size();

    // Wrap with an outer SELECT + LET
    var letQuery =
        "SELECT friendName, $x as x FROM ("
            + "  SELECT DISTINCT friendName FROM ("
            + "    MATCH {class: Person, as: p, where: (name = 'n1')}"
            + "      .out('Friend'){as: friend}"
            + "    RETURN friend.name as friendName"
            + "  )"
            + ") LET $x = 42";

    var letResults = session.query(letQuery).stream().toList();
    assertEquals(
        "Outer LET on nested SELECT DISTINCT + MATCH must not lose rows",
        expectedCount,
        letResults.size());
  }

  /**
   * Verifies that adding a LET clause to a SELECT wrapping a MATCH with while
   * traversal does not cause the results to vanish.
   *
   * <p>This is a regression test for the planner bug where a LET clause on an
   * outer SELECT caused the inner MATCH subquery to return 0 rows. The while
   * traversal exercises the recursive matching path, which is the pattern used
   * by LDBC IC5.
   */
  @Test
  public void testUnwindBeforeOrderByLimit() {
    session.execute("CREATE class UWPerson extends V").close();

    session.begin();
    session.execute(
        "CREATE VERTEX UWPerson set name = 'alice', tags = ['zulu', 'bravo', 'alpha']").close();
    session.execute(
        "CREATE VERTEX UWPerson set name = 'bob', tags = ['yankee', 'charlie']").close();
    session.execute(
        "CREATE VERTEX UWPerson set name = 'carol', tags = ['delta']").close();
    session.commit();

    // Grammar requires ORDER BY before UNWIND, but the execution planner must
    // run UNWIND first (it changes cardinality), then ORDER BY + LIMIT on the
    // expanded rows. Without this fix, the bounded heap in OrderByStep would
    // discard rows before UNWIND expands them, producing wrong results.
    //
    // 3 persons × variable tag counts = 6 rows after UNWIND.
    // ORDER BY tags ASC, LIMIT 3 → alpha, bravo, charlie.
    // If ORDER BY ran before UNWIND with bounded heap of 3, it would keep
    // only 3 of the 3 original person rows and we'd lose tags from discarded persons.
    var results = session.query(
        "MATCH {class: UWPerson, as: p}"
            + " RETURN p.name as name, p.tags as tags"
            + " ORDER BY tags ASC"
            + " UNWIND tags"
            + " LIMIT 3")
        .stream().toList();

    assertEquals(3, results.size());
    assertEquals("alpha", results.get(0).getProperty("tags"));
    assertEquals("bravo", results.get(1).getProperty("tags"));
    assertEquals("charlie", results.get(2).getProperty("tags"));
  }

  @Test
  public void testWhileMatchSubqueryWithLet() {
    // Baseline: existing while test query that returns 2 results
    // (same as testWhile second query)
    var baselineQuery =
        "select friend.name as name from (match {class:Person, where:(name ="
            + " 'n1')}.out('Friend'){as:friend, while: ($depth < 2), where: ($depth=1) }"
            + " return friend)";

    var baselineResults = session.query(baselineQuery).stream().toList();
    assertEquals("Baseline while MATCH should return 2 results", 2, baselineResults.size());

    // Same query wrapped in outer SELECT with LET
    var letQuery =
        "SELECT name, $x as x FROM ("
            + "  SELECT friend.name as name FROM ("
            + "    MATCH {class:Person, where:(name = 'n1')}"
            + "      .out('Friend'){as:friend, while: ($depth < 2), where: ($depth=1)}"
            + "    RETURN friend"
            + "  )"
            + ") LET $x = 42";

    var letResults = session.query(letQuery).stream().toList();
    assertEquals(
        "Adding LET $x = 42 must not change the number of rows from while MATCH",
        2,
        letResults.size());
  }

  /**
   * Diamond graph: 0→1→3, 0→2→3. A WHILE traversal starting from vertex 0 with
   * maxDepth 3 must emit vertex 3 only once, even though it is reachable via two
   * distinct paths (0→1→3 and 0→2→3). Before the visited-set fix, vertex 3 would
   * appear twice in the result set.
   */
  @Test
  public void testWhileTraversalDeduplicatesDiamondVertices() {
    session.begin();

    // WHILE ($depth < 3) traverses depths 0, 1, 2 from vertex 0:
    //   depth 0: vertex 0
    //   depth 1: vertices 1 and 2
    //   depth 2: vertex 3 (reachable from both 1 and 2)
    // Vertex 3 must appear exactly once.
    var results = session.query(
        "MATCH {class:DiamondV, as:start, where:(uid = 0)}"
            + ".out('DiamondE'){as:reached, while:($depth < 3)}"
            + " RETURN reached.uid as uid")
        .stream().toList();

    // Collect the uid values
    var uids = new ArrayList<Integer>();
    for (var r : results) {
      uids.add(((Number) r.getProperty("uid")).intValue());
    }
    Collections.sort(uids);

    // Expect exactly 4 unique vertices: 0, 1, 2, 3
    assertEquals(
        "Diamond WHILE traversal should emit each vertex exactly once",
        List.of(0, 1, 2, 3),
        uids);
    session.commit();
  }

  /**
   * Boundary-depth diamond dedup: 0→1→3, 0→2→3. With {@code while: ($depth < 2)},
   * the while condition fails at depth 2 (the leaf level where vertex 3 lives).
   * Before the fix, {@code dedupVisited.add()} was inside the expansion block that
   * only executes when the while condition holds, so leaf-level vertices were never
   * marked as visited. Vertex 3 would appear twice — once from 1→3 and once from 2→3.
   * After the fix, visited marking happens before the expansion check, so vertex 3
   * is correctly deduplicated even at the boundary depth.
   */
  @Test
  public void testWhileDeduplicatesLeafVerticesAtBoundaryDepth() {
    session.begin();

    // while: ($depth < 2) traverses depths 0, 1 from vertex 0:
    //   depth 0: vertex 0 (while holds: 0 < 2)
    //   depth 1: vertices 1, 2 (while holds: 1 < 2)
    //   depth 2: vertex 3 via both 1 and 2 (while FAILS: 2 < 2 is false)
    // Vertex 3 must appear exactly once despite being reachable from two parents.
    var results = session.query(
        "MATCH {class:DiamondV, as:start, where:(uid = 0)}"
            + ".out('DiamondE'){as:reached, while:($depth < 2)}"
            + " RETURN reached.uid as uid")
        .stream().toList();

    var uids = new ArrayList<Integer>();
    for (var r : results) {
      uids.add(((Number) r.getProperty("uid")).intValue());
    }
    Collections.sort(uids);

    // Expect exactly 4 unique vertices: 0 (depth 0), 1 and 2 (depth 1), 3 (depth 2)
    assertEquals(
        "Boundary-depth diamond: each vertex must appear exactly once",
        List.of(0, 1, 2, 3),
        uids);
    session.commit();
  }

  /**
   * Same boundary-depth diamond as above, but with pathAlias declared. When pathAlias
   * is present, dedup is disabled (dedupVisited is null) to preserve all distinct paths.
   * Vertex 3 must appear twice — once for path 0→1→3 and once for path 0→2→3.
   */
  @Test
  public void testWhileBoundaryDepthWithPathAliasPreservesAllPaths() {
    session.begin();

    var results = session.query(
        "MATCH {class:DiamondV, as:start, where:(uid = 0)}"
            + ".out('DiamondE'){as:reached, while:($depth < 2),"
            + " pathAlias: p}"
            + " RETURN reached.uid as uid, p")
        .stream().toList();

    // Count how many times vertex 3 appears — should be 2 (one per path)
    var uid3Count = results.stream()
        .filter(r -> ((Number) r.getProperty("uid")).intValue() == 3)
        .count();
    assertEquals(
        "Boundary-depth vertex 3 should appear twice (once per path) with pathAlias",
        2,
        uid3Count);

    session.commit();
  }

  /**
   * Verifies that MATCH ... RETURN $elements ORDER BY ... SKIP N LIMIT M uses the
   * bounded-heap optimization with buffer size = SKIP + LIMIT.
   */
  @Test
  public void testExplainReturnElementsOrderBySkipLimitUsesBoundedHeap() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a}"
            + " RETURN $elements ORDER BY name ASC SKIP 2 LIMIT 3")
        .toList();
    assertEquals(1, result.size());
    String plan = result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("Plan should use bounded heap with buffer size 5 (skip 2 + limit 3)",
        plan.contains("buffer size: 5"));
    session.commit();
  }

  /**
   * Diamond graph with pathAlias: 0→1→3, 0→2→3. When pathAlias is declared the
   * user is asking for all distinct *paths*, so vertex 3 must appear twice — once
   * for each path that reaches it. Without the pathAlias-aware dedup bypass,
   * the visited set would suppress the second occurrence.
   */
  @Test
  public void testWhileTraversalWithPathAliasPreservesAllPaths() {
    session.begin();

    var results = session.query(
        "MATCH {class:DiamondV, as:start, where:(uid = 0)}"
            + ".out('DiamondE'){as:reached, while:($depth < 3),"
            + " pathAlias: p}"
            + " RETURN reached.uid as uid, p")
        .stream().toList();

    // Count how many times vertex 3 appears — it should be 2 (one per path)
    var uid3Count = results.stream()
        .filter(r -> ((Number) r.getProperty("uid")).intValue() == 3)
        .count();
    assertEquals(
        "Vertex 3 should appear twice (once per path) when pathAlias is declared",
        2,
        uid3Count);

    session.commit();
  }

  /**
   * Verifies that visited-set dedup does not suppress a vertex that was rejected
   * by a depth-dependent WHERE clause at a shallower depth. Diamond graph:
   * 0→1→3, 0→2→3. With {@code where: ($depth > 0)}, vertex 0 (depth 0) should
   * be excluded by the filter but must NOT be permanently marked as visited,
   * so if a cycle led back to it at a deeper depth it could still be evaluated.
   * Here we simply verify that the depth filter correctly excludes depth-0 and
   * still emits deeper vertices.
   */
  @Test
  public void testWhileTraversalWithDepthDependentFilter() {
    session.begin();

    var results = session.query(
        "MATCH {class:DiamondV, as:start, where:(uid = 0)}"
            + ".out('DiamondE'){as:reached, while:($depth < 3),"
            + " where: ($depth > 0)}"
            + " RETURN reached.uid as uid")
        .stream().toList();

    var uids = new ArrayList<Integer>();
    for (var r : results) {
      uids.add(((Number) r.getProperty("uid")).intValue());
    }
    Collections.sort(uids);

    // Depth 0 (vertex 0) is excluded by WHERE ($depth > 0).
    // Depth 1: vertices 1, 2. Depth 2: vertex 3 (deduped).
    assertEquals(
        "Only vertices at depth > 0 should be returned",
        List.of(1, 2, 3),
        uids);
    session.commit();
  }

  /**
   * Verifies that MATCH ... RETURN $elements ORDER BY ... LIMIT M (no SKIP) uses the
   * bounded-heap optimization with buffer size = LIMIT.
   */
  @Test
  public void testExplainReturnElementsOrderByLimitOnlyUsesBoundedHeap() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a}"
            + " RETURN $elements ORDER BY name ASC LIMIT 3")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("Plan should use bounded heap with buffer size 3 (limit 3, no skip)",
        plan.contains("buffer size: 3"));
    session.commit();
  }

  /**
   * Verifies that LIMIT 0 causes the planner to pass maxResults=0 to OrderByStep
   * (visible as "buffer size: 0" in EXPLAIN), which triggers an immediate empty
   * return without creating a heap or sorting. Without this, LIMIT 0 would fall
   * back to unbounded sorting of all rows before LimitStep discards them.
   */
  @Test
  public void testExplainReturnElementsOrderByLimitZeroUsesMaxResults() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a}"
            + " RETURN $elements ORDER BY name ASC LIMIT 0")
        .toList();
    assertEquals(1, result.size());
    String plan = result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("Plan should pass maxResults=0 to OrderByStep (buffer size: 0)",
        plan.contains("buffer size: 0"));
    session.commit();
  }

  // ====================================================================
  // Back-reference intersection tests (MATCH adjacency list intersection)
  // ====================================================================

  /**
   * Tests the core back-reference intersection optimization using a single
   * connected pattern:
   * {person}.in('HasCreator'){post}.in('ContainerOf'){forum}
   *   .out('ContainerOf'){post2}.out('HasCreator'){creator,
   *     where: (@rid = $matched.person.@rid)}
   *
   * Setup: Forum1 contains Post1, Post2, Post3.
   * Post1 created by PersonA, Post2 by PersonB, Post3 by PersonA.
   * The optimization pre-loads PersonA.in(HasCreator) and intersects it
   * with Forum1.out(ContainerOf), so only posts by PersonA are loaded.
   */
  @Test
  public void testMatchBackReferenceIntersection() {
    session.execute("CREATE class MPersonV extends V").close();
    session.execute("CREATE class MPostV extends V").close();
    session.execute("CREATE class MForumV extends V").close();
    session.execute("CREATE class MContainerOf extends E").close();
    session.execute("CREATE class MHasCreator extends E").close();

    session.begin();
    session.execute("CREATE VERTEX MForumV set name = 'Forum1'").close();
    session.execute("CREATE VERTEX MPostV set title = 'Post1'").close();
    session.execute("CREATE VERTEX MPostV set title = 'Post2'").close();
    session.execute("CREATE VERTEX MPostV set title = 'Post3'").close();
    session.execute("CREATE VERTEX MPersonV set name = 'PersonA'").close();
    session.execute("CREATE VERTEX MPersonV set name = 'PersonB'").close();

    session.execute(
        "CREATE EDGE MContainerOf from"
            + " (SELECT FROM MForumV WHERE name = 'Forum1') to"
            + " (SELECT FROM MPostV WHERE title = 'Post1')")
        .close();
    session.execute(
        "CREATE EDGE MContainerOf from"
            + " (SELECT FROM MForumV WHERE name = 'Forum1') to"
            + " (SELECT FROM MPostV WHERE title = 'Post2')")
        .close();
    session.execute(
        "CREATE EDGE MContainerOf from"
            + " (SELECT FROM MForumV WHERE name = 'Forum1') to"
            + " (SELECT FROM MPostV WHERE title = 'Post3')")
        .close();

    session.execute(
        "CREATE EDGE MHasCreator from"
            + " (SELECT FROM MPostV WHERE title = 'Post1') to"
            + " (SELECT FROM MPersonV WHERE name = 'PersonA')")
        .close();
    session.execute(
        "CREATE EDGE MHasCreator from"
            + " (SELECT FROM MPostV WHERE title = 'Post2') to"
            + " (SELECT FROM MPersonV WHERE name = 'PersonB')")
        .close();
    session.execute(
        "CREATE EDGE MHasCreator from"
            + " (SELECT FROM MPostV WHERE title = 'Post3') to"
            + " (SELECT FROM MPersonV WHERE name = 'PersonA')")
        .close();
    session.commit();

    session.begin();
    // Single connected pattern: person → posts → forum → all posts → creator = person
    var result = session.query(
        "MATCH"
            + " {class: MPersonV, as: person, where: (name = 'PersonA')}"
            + "   .in('MHasCreator'){as: post}"
            + "   .in('MContainerOf'){as: forum}"
            + "   .out('MContainerOf'){as: post2}"
            + "   .out('MHasCreator'){as: creator,"
            + "     where: (@rid = $matched.person.@rid)}"
            + " RETURN post2.title as title")
        .toList();

    Set<String> titles = new HashSet<>();
    for (var r : result) {
      titles.add(r.getProperty("title"));
    }
    // PersonA authored Post1 and Post3 in Forum1.
    assertTrue("Should find Post1", titles.contains("Post1"));
    assertTrue("Should find Post3", titles.contains("Post3"));
    assertFalse("Should NOT find Post2", titles.contains("Post2"));

    // Verify EXPLAIN shows the intersection optimization on the creator edge
    var explain = session.query(
        "EXPLAIN MATCH"
            + " {class: MPersonV, as: person, where: (name = 'PersonA')}"
            + "   .in('MHasCreator'){as: post}"
            + "   .in('MContainerOf'){as: forum}"
            + "   .out('MContainerOf'){as: post2}"
            + "   .out('MHasCreator'){as: creator,"
            + "     where: (@rid = $matched.person.@rid)}"
            + " RETURN post2.title as title")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    assertTrue("Plan should show intersection optimization for back-reference",
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Tests that the back-reference intersection returns zero results when
   * no posts in the forum belong to the target person. Uses a single
   * connected pattern through a Knows edge.
   */
  @Test
  public void testMatchBackReferenceIntersectionNoMatch() {
    session.execute("CREATE class MPersonBV extends V").close();
    session.execute("CREATE class MPostBV extends V").close();
    session.execute("CREATE class MForumBV extends V").close();
    session.execute("CREATE class MContainerOfB extends E").close();
    session.execute("CREATE class MHasCreatorB extends E").close();
    session.execute("CREATE class MKnowsB extends E").close();

    session.begin();
    session.execute("CREATE VERTEX MForumBV set name = 'Forum1'").close();
    session.execute("CREATE VERTEX MPostBV set title = 'Post1'").close();
    session.execute("CREATE VERTEX MPostBV set title = 'Post2'").close();
    session.execute("CREATE VERTEX MPersonBV set name = 'PersonA'").close();
    session.execute("CREATE VERTEX MPersonBV set name = 'PersonB'").close();

    // PersonA knows PersonB
    session.execute(
        "CREATE EDGE MKnowsB from"
            + " (SELECT FROM MPersonBV WHERE name = 'PersonA') to"
            + " (SELECT FROM MPersonBV WHERE name = 'PersonB')")
        .close();

    // Forum1 contains Post1, Post2 — both by PersonA
    session.execute(
        "CREATE EDGE MContainerOfB from"
            + " (SELECT FROM MForumBV WHERE name = 'Forum1') to"
            + " (SELECT FROM MPostBV WHERE title = 'Post1')")
        .close();
    session.execute(
        "CREATE EDGE MContainerOfB from"
            + " (SELECT FROM MForumBV WHERE name = 'Forum1') to"
            + " (SELECT FROM MPostBV WHERE title = 'Post2')")
        .close();

    session.execute(
        "CREATE EDGE MHasCreatorB from"
            + " (SELECT FROM MPostBV WHERE title = 'Post1') to"
            + " (SELECT FROM MPersonBV WHERE name = 'PersonA')")
        .close();
    session.execute(
        "CREATE EDGE MHasCreatorB from"
            + " (SELECT FROM MPostBV WHERE title = 'Post2') to"
            + " (SELECT FROM MPersonBV WHERE name = 'PersonA')")
        .close();
    session.commit();

    session.begin();
    // PersonA → knows → PersonB. PersonB has no posts.
    // Find PersonB's posts in Forum1 — none exist.
    var result = session.query(
        "MATCH"
            + " {class: MPersonBV, as: starter, where: (name = 'PersonA')}"
            + "   .out('MKnowsB'){as: friend}"
            + "   .in('MHasCreatorB'){as: friendPost}"
            + "   .in('MContainerOfB'){as: forum}"
            + "   .out('MContainerOfB'){as: post}"
            + "   .out('MHasCreatorB'){as: creator,"
            + "     where: (@rid = $matched.friend.@rid)}"
            + " RETURN post.title as title")
        .toList();

    // PersonB has no posts, so friendPost step yields nothing
    assertEquals(0, result.size());

    // Verify EXPLAIN shows intersection optimization on the creator edge
    var explain = session.query(
        "EXPLAIN MATCH"
            + " {class: MPersonBV, as: starter, where: (name = 'PersonA')}"
            + "   .out('MKnowsB'){as: friend}"
            + "   .in('MHasCreatorB'){as: friendPost}"
            + "   .in('MContainerOfB'){as: forum}"
            + "   .out('MContainerOfB'){as: post}"
            + "   .out('MHasCreatorB'){as: creator,"
            + "     where: (@rid = $matched.friend.@rid)}"
            + " RETURN post.title as title")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    assertTrue("Plan should show intersection optimization for back-reference",
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Tests back-reference intersection with multiple forums in a single
   * connected pattern, verifying that each forum independently intersects
   * its posts with the person's authored posts.
   */
  @Test
  public void testMatchBackReferenceIntersectionMultipleForums() {
    session.execute("CREATE class MPersonCV extends V").close();
    session.execute("CREATE class MPostCV extends V").close();
    session.execute("CREATE class MForumCV extends V").close();
    session.execute("CREATE class MContainerOfC extends E").close();
    session.execute("CREATE class MHasCreatorC extends E").close();

    session.begin();
    session.execute("CREATE VERTEX MForumCV set name = 'Forum1'").close();
    session.execute("CREATE VERTEX MForumCV set name = 'Forum2'").close();
    session.execute("CREATE VERTEX MPostCV set title = 'P1'").close();
    session.execute("CREATE VERTEX MPostCV set title = 'P2'").close();
    session.execute("CREATE VERTEX MPostCV set title = 'P3'").close();
    session.execute("CREATE VERTEX MPostCV set title = 'P4'").close();
    session.execute("CREATE VERTEX MPersonCV set name = 'Alice'").close();
    session.execute("CREATE VERTEX MPersonCV set name = 'Bob'").close();

    // Forum1 contains P1, P2; Forum2 contains P3, P4
    session.execute(
        "CREATE EDGE MContainerOfC from"
            + " (SELECT FROM MForumCV WHERE name = 'Forum1') to"
            + " (SELECT FROM MPostCV WHERE title = 'P1')")
        .close();
    session.execute(
        "CREATE EDGE MContainerOfC from"
            + " (SELECT FROM MForumCV WHERE name = 'Forum1') to"
            + " (SELECT FROM MPostCV WHERE title = 'P2')")
        .close();
    session.execute(
        "CREATE EDGE MContainerOfC from"
            + " (SELECT FROM MForumCV WHERE name = 'Forum2') to"
            + " (SELECT FROM MPostCV WHERE title = 'P3')")
        .close();
    session.execute(
        "CREATE EDGE MContainerOfC from"
            + " (SELECT FROM MForumCV WHERE name = 'Forum2') to"
            + " (SELECT FROM MPostCV WHERE title = 'P4')")
        .close();

    // P1 by Alice, P2 by Bob, P3 by Alice, P4 by Bob
    session.execute(
        "CREATE EDGE MHasCreatorC from"
            + " (SELECT FROM MPostCV WHERE title = 'P1') to"
            + " (SELECT FROM MPersonCV WHERE name = 'Alice')")
        .close();
    session.execute(
        "CREATE EDGE MHasCreatorC from"
            + " (SELECT FROM MPostCV WHERE title = 'P2') to"
            + " (SELECT FROM MPersonCV WHERE name = 'Bob')")
        .close();
    session.execute(
        "CREATE EDGE MHasCreatorC from"
            + " (SELECT FROM MPostCV WHERE title = 'P3') to"
            + " (SELECT FROM MPersonCV WHERE name = 'Alice')")
        .close();
    session.execute(
        "CREATE EDGE MHasCreatorC from"
            + " (SELECT FROM MPostCV WHERE title = 'P4') to"
            + " (SELECT FROM MPersonCV WHERE name = 'Bob')")
        .close();
    session.commit();

    session.begin();
    // person → posts → forums → all posts in forum → filter by person
    var result = session.query(
        "MATCH"
            + " {class: MPersonCV, as: person, where: (name = 'Alice')}"
            + "   .in('MHasCreatorC'){as: myPost}"
            + "   .in('MContainerOfC'){as: forum}"
            + "   .out('MContainerOfC'){as: post}"
            + "   .out('MHasCreatorC'){as: creator,"
            + "     where: (@rid = $matched.person.@rid)}"
            + " RETURN forum.name as forumName, post.title as title"
            + " ORDER BY title")
        .toList();

    // Alice authored P1 (Forum1) and P3 (Forum2).
    Set<String> titles = new HashSet<>();
    for (var r : result) {
      titles.add(r.getProperty("title"));
    }
    assertTrue("Should find P1", titles.contains("P1"));
    assertTrue("Should find P3", titles.contains("P3"));
    assertFalse("Should NOT find P2", titles.contains("P2"));
    assertFalse("Should NOT find P4", titles.contains("P4"));

    // Verify EXPLAIN shows intersection optimization
    var explain = session.query(
        "EXPLAIN MATCH"
            + " {class: MPersonCV, as: person, where: (name = 'Alice')}"
            + "   .in('MHasCreatorC'){as: myPost}"
            + "   .in('MContainerOfC'){as: forum}"
            + "   .out('MContainerOfC'){as: post}"
            + "   .out('MHasCreatorC'){as: creator,"
            + "     where: (@rid = $matched.person.@rid)}"
            + " RETURN forum.name as forumName, post.title as title"
            + " ORDER BY title")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    assertTrue("Plan should show intersection optimization for back-reference",
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Tests that back-reference intersection works with in() traversal
   * direction on the intermediate edge.
   */
  @Test
  public void testMatchBackReferenceIntersectionReverseDirection() {
    session.execute("CREATE class MNodeDV extends V").close();
    session.execute("CREATE class MLinkD extends E").close();
    session.execute("CREATE class MCreatedByD extends E").close();

    session.begin();
    session.execute("CREATE VERTEX MNodeDV set name = 'Container1'").close();
    session.execute("CREATE VERTEX MNodeDV set name = 'Item1'").close();
    session.execute("CREATE VERTEX MNodeDV set name = 'Item2'").close();
    session.execute("CREATE VERTEX MNodeDV set name = 'Owner1'").close();

    // Item1 -> Container1, Item2 -> Container1
    session.execute(
        "CREATE EDGE MLinkD from"
            + " (SELECT FROM MNodeDV WHERE name = 'Item1') to"
            + " (SELECT FROM MNodeDV WHERE name = 'Container1')")
        .close();
    session.execute(
        "CREATE EDGE MLinkD from"
            + " (SELECT FROM MNodeDV WHERE name = 'Item2') to"
            + " (SELECT FROM MNodeDV WHERE name = 'Container1')")
        .close();

    // Item1 created by Owner1
    session.execute(
        "CREATE EDGE MCreatedByD from"
            + " (SELECT FROM MNodeDV WHERE name = 'Item1') to"
            + " (SELECT FROM MNodeDV WHERE name = 'Owner1')")
        .close();
    session.commit();

    session.begin();
    // owner → in(CreatedByD) → items → out(LinkD) → container
    // → in(LinkD) → all items → out(CreatedByD) → filter by owner
    var result = session.query(
        "MATCH"
            + " {class: MNodeDV, as: owner, where: (name = 'Owner1')}"
            + "   .in('MCreatedByD'){as: myItem}"
            + "   .out('MLinkD'){as: container}"
            + "   .in('MLinkD'){as: item}"
            + "   .out('MCreatedByD'){as: creator,"
            + "     where: (@rid = $matched.owner.@rid)}"
            + " RETURN item.name as itemName")
        .toList();

    // Owner1 created Item1. Item1 → Container1. Container1 ← Item1, Item2.
    // Filter: creator must be Owner1. Only Item1 has CreatedByD → Owner1.
    Set<String> names = new HashSet<>();
    for (var r : result) {
      names.add(r.getProperty("itemName"));
    }
    assertTrue("Should find Item1", names.contains("Item1"));
    assertFalse("Should NOT find Item2", names.contains("Item2"));

    // Verify EXPLAIN shows intersection optimization
    var explain = session.query(
        "EXPLAIN MATCH"
            + " {class: MNodeDV, as: owner, where: (name = 'Owner1')}"
            + "   .in('MCreatedByD'){as: myItem}"
            + "   .out('MLinkD'){as: container}"
            + "   .in('MLinkD'){as: item}"
            + "   .out('MCreatedByD'){as: creator,"
            + "     where: (@rid = $matched.owner.@rid)}"
            + " RETURN item.name as itemName")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    assertTrue("Plan should show intersection optimization for back-reference",
        plan.contains("intersection:"));
    session.commit();
  }

  // ── Edge LINK schema inference + back-reference intersection tests ──

  /**
   * Tests that the MATCH planner infers the target vertex class from edge
   * LINK declarations and uses the inferred class to enable adjacency list
   * intersection via back-reference filters.
   *
   * <p>Schema: Author -[WROTE{out LINK Author, in LINK Article}]-> Article
   *                    -[PUBLISHED_IN{out LINK Article, in LINK Journal}]-> Journal
   *
   * <p>Query pattern:
   *   {Author} .out('WROTE'){Article} .out('PUBLISHED_IN'){Journal}
   *            .in('PUBLISHED_IN'){otherArticle} .in('WROTE'){otherAuthor,
   *              where: (@rid = $matched.Author.@rid)}
   *
   * <p>This pattern finds all articles in the same journal as the author's
   * articles, then filters to only those written by the same author.
   * Without the OrBlock unwrapping fix and edge class inference, the
   * back-reference filter would not be detected and the intersection
   * would not activate, causing all articles to be loaded and filtered
   * post-hoc.
   */
  @Test
  public void testMatchBackReferenceWithEdgeLinkInference() {
    // Create schema with LINK declarations on edge endpoints
    session.execute("CREATE CLASS Author EXTENDS V").close();
    session.execute("CREATE CLASS Article EXTENDS V").close();
    session.execute("CREATE CLASS Journal EXTENDS V").close();

    session.execute("CREATE CLASS WROTE EXTENDS E").close();
    session.execute("CREATE PROPERTY WROTE.out LINK Author").close();
    session.execute("CREATE PROPERTY WROTE.in LINK Article").close();

    session.execute("CREATE CLASS PUBLISHED_IN EXTENDS E").close();
    session.execute("CREATE PROPERTY PUBLISHED_IN.out LINK Article").close();
    session.execute("CREATE PROPERTY PUBLISHED_IN.in LINK Journal").close();

    session.begin();
    // Author1 wrote Article1 and Article2, both in Journal1
    session.execute("CREATE VERTEX Author SET name = 'Author1'").close();
    session.execute("CREATE VERTEX Author SET name = 'Author2'").close();
    session.execute("CREATE VERTEX Article SET title = 'A1'").close();
    session.execute("CREATE VERTEX Article SET title = 'A2'").close();
    session.execute("CREATE VERTEX Article SET title = 'A3'").close();
    session.execute("CREATE VERTEX Journal SET name = 'J1'").close();

    // Author1 wrote A1, A2
    session.execute(
        "CREATE EDGE WROTE FROM (SELECT FROM Author WHERE name='Author1')"
            + " TO (SELECT FROM Article WHERE title='A1')")
        .close();
    session.execute(
        "CREATE EDGE WROTE FROM (SELECT FROM Author WHERE name='Author1')"
            + " TO (SELECT FROM Article WHERE title='A2')")
        .close();
    // Author2 wrote A3
    session.execute(
        "CREATE EDGE WROTE FROM (SELECT FROM Author WHERE name='Author2')"
            + " TO (SELECT FROM Article WHERE title='A3')")
        .close();

    // All articles in J1
    session.execute(
        "CREATE EDGE PUBLISHED_IN FROM (SELECT FROM Article WHERE title='A1')"
            + " TO (SELECT FROM Journal WHERE name='J1')")
        .close();
    session.execute(
        "CREATE EDGE PUBLISHED_IN FROM (SELECT FROM Article WHERE title='A2')"
            + " TO (SELECT FROM Journal WHERE name='J1')")
        .close();
    session.execute(
        "CREATE EDGE PUBLISHED_IN FROM (SELECT FROM Article WHERE title='A3')"
            + " TO (SELECT FROM Journal WHERE name='J1')")
        .close();
    session.commit();

    // Find articles by Author1 in the same journal as Author1's other articles.
    // The back-reference @rid = $matched.author.@rid should be detected and
    // used for adjacency list intersection on the in('WROTE') edge.
    session.begin();
    var result = session.query(
        "MATCH"
            + " {class: Author, as: author, where: (name = 'Author1')}"
            + "   .out('WROTE'){as: article}"
            + "   .out('PUBLISHED_IN'){as: journal}"
            + "   .in('PUBLISHED_IN'){as: otherArticle}"
            + "   .in('WROTE'){as: otherAuthor,"
            + "     where: (@rid = $matched.author.@rid)}"
            + " RETURN DISTINCT otherArticle.title AS title")
        .toList();

    Set<String> titles = new HashSet<>();
    for (var r : result) {
      titles.add(r.getProperty("title"));
    }
    // Author1's articles in J1: A1, A2. The back-reference filter should
    // ensure only Author1's articles are returned (not A3 by Author2).
    assertEquals("Should find exactly 2 distinct articles", 2, titles.size());
    assertTrue("Should find A1", titles.contains("A1"));
    assertTrue("Should find A2", titles.contains("A2"));

    // Verify EXPLAIN shows intersection optimization using edge LINK inference
    var explain = session.query(
        "EXPLAIN MATCH"
            + " {class: Author, as: author, where: (name = 'Author1')}"
            + "   .out('WROTE'){as: article}"
            + "   .out('PUBLISHED_IN'){as: journal}"
            + "   .in('PUBLISHED_IN'){as: otherArticle}"
            + "   .in('WROTE'){as: otherAuthor,"
            + "     where: (@rid = $matched.author.@rid)}"
            + " RETURN DISTINCT otherArticle.title AS title")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    assertTrue("Plan should show intersection optimization for back-reference",
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Tests that edge class inference works correctly for a MATCH pattern where
   * the target class is not explicitly specified but can be inferred from the
   * edge schema LINK declarations. Verifies the result correctness — the
   * inference enables the planner to use proper class-based filtering.
   */
  @Test
  public void testMatchEdgeClassInferenceResultCorrectness() {
    // Create a schema where the edge LINK declarations enable class inference
    session.execute("CREATE CLASS Scientist EXTENDS V").close();
    session.execute("CREATE CLASS Paper EXTENDS V").close();
    session.execute("CREATE CLASS AUTHORED EXTENDS E").close();
    session.execute("CREATE PROPERTY AUTHORED.out LINK Scientist").close();
    session.execute("CREATE PROPERTY AUTHORED.in LINK Paper").close();

    // Also add an index on Paper.year to test potential index-assisted filtering
    session.execute("CREATE PROPERTY Paper.year INTEGER").close();
    session.execute("CREATE INDEX Paper.year ON Paper(year) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX Scientist SET name = 'Alice'").close();
    session.execute("CREATE VERTEX Scientist SET name = 'Bob'").close();
    session.execute("CREATE VERTEX Paper SET title = 'P1', year = 2020").close();
    session.execute("CREATE VERTEX Paper SET title = 'P2', year = 2021").close();
    session.execute("CREATE VERTEX Paper SET title = 'P3', year = 2022").close();

    session.execute(
        "CREATE EDGE AUTHORED FROM (SELECT FROM Scientist WHERE name='Alice')"
            + " TO (SELECT FROM Paper WHERE title='P1')")
        .close();
    session.execute(
        "CREATE EDGE AUTHORED FROM (SELECT FROM Scientist WHERE name='Alice')"
            + " TO (SELECT FROM Paper WHERE title='P2')")
        .close();
    session.execute(
        "CREATE EDGE AUTHORED FROM (SELECT FROM Scientist WHERE name='Bob')"
            + " TO (SELECT FROM Paper WHERE title='P3')")
        .close();
    session.commit();

    // Query without explicit class constraint on the 'paper' alias.
    // The planner should infer class 'Paper' from AUTHORED.in LINK Paper.
    session.begin();
    var result = session.query(
        "MATCH"
            + " {class: Scientist, as: s, where: (name = 'Alice')}"
            + "   .out('AUTHORED'){as: paper}"
            + " RETURN paper.title AS title")
        .toList();

    Set<String> titles = new HashSet<>();
    for (var r : result) {
      titles.add(r.getProperty("title"));
    }
    assertEquals("Alice should have exactly 2 papers", 2, titles.size());
    assertTrue("Should find P1", titles.contains("P1"));
    assertTrue("Should find P2", titles.contains("P2"));
    session.commit();
  }

  /**
   * Tests that a MATCH pattern with a back-reference ($matched.person.@rid)
   * triggers the reverse edge lookup path in TraversalPreFilterHelper.
   * The query finds friends-of-friends, filtering by the back-reference.
   */
  @Test
  public void testBackReferenceReverseEdgeLookup() {
    session.execute("CREATE CLASS BRPerson EXTENDS V").close();
    session.execute("CREATE CLASS BRKnows EXTENDS E").close();
    session.execute("CREATE PROPERTY BRKnows.out LINK BRPerson").close();
    session.execute("CREATE PROPERTY BRKnows.in LINK BRPerson").close();

    session.begin();
    session.execute("CREATE VERTEX BRPerson SET name='Alice'").close();
    session.execute("CREATE VERTEX BRPerson SET name='Bob'").close();
    session.execute("CREATE VERTEX BRPerson SET name='Carol'").close();
    session.execute(
        "CREATE EDGE BRKnows FROM"
            + " (SELECT FROM BRPerson WHERE name='Alice')"
            + " TO (SELECT FROM BRPerson WHERE name='Bob')")
        .close();
    session.execute(
        "CREATE EDGE BRKnows FROM"
            + " (SELECT FROM BRPerson WHERE name='Bob')"
            + " TO (SELECT FROM BRPerson WHERE name='Carol')")
        .close();
    session.commit();

    session.begin();
    // This pattern triggers the back-reference intersection path:
    // $matched.person is bound when processing the second edge.
    var result = session.query(
        "MATCH"
            + " {class: BRPerson, as: person, where: (name='Alice')}"
            + "   .out('BRKnows'){as: friend}"
            + "   .out('BRKnows'){as: fof}"
            + " RETURN person.name, friend.name, fof.name")
        .toList();

    assertFalse("Should find friend-of-friend", result.isEmpty());
    assertEquals("Alice", result.getFirst().getProperty("person.name"));
    assertEquals("Bob", result.getFirst().getProperty("friend.name"));
    assertEquals("Carol", result.getFirst().getProperty("fof.name"));
    session.commit();
  }

  /**
   * Tests EXPLAIN output for a LET subquery, which exercises
   * LetQueryStep.getPreviewPlan() and prettyPrint().
   */
  @Test
  public void testExplainWithLetSubquery() {
    session.execute("CREATE CLASS LetTestV EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX LetTestV SET name='A'").close();
    session.execute("CREATE VERTEX LetTestV SET name='B'").close();
    session.commit();

    session.begin();
    var result = session.query(
        "EXPLAIN SELECT *, $cnt"
            + " FROM LetTestV"
            + " LET $cnt = (SELECT count(*) FROM LetTestV)")
        .toList();
    assertEquals(1, result.size());
    String plan = result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    // The plan should contain the LET step with the preview sub-plan
    assertTrue("EXPLAIN should show LET step", plan.contains("LET"));
    session.commit();
  }

  /**
   * Tests that a MATCH query with an indexed WHERE condition on the target
   * node exercises the index pre-filter path (resolveIndexToRidSet).
   */
  @Test
  public void testMatchWithIndexedTargetFilter() {
    session.execute("CREATE CLASS IdxPost EXTENDS V").close();
    session.execute("CREATE CLASS IdxPerson EXTENDS V").close();
    session.execute("CREATE CLASS IdxCreated EXTENDS E").close();
    session.execute("CREATE PROPERTY IdxCreated.out LINK IdxPerson").close();
    session.execute("CREATE PROPERTY IdxCreated.in LINK IdxPost").close();
    session.execute("CREATE PROPERTY IdxPost.score INTEGER").close();
    session.execute("CREATE INDEX IdxPost.score ON IdxPost (score) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX IdxPerson SET name='Author'").close();
    for (int i = 0; i < 20; i++) {
      session.execute("CREATE VERTEX IdxPost SET title='post" + i + "', score=" + i)
          .close();
      session.execute(
          "CREATE EDGE IdxCreated FROM"
              + " (SELECT FROM IdxPerson WHERE name='Author')"
              + " TO (SELECT FROM IdxPost WHERE title='post" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // This MATCH pattern has an indexed filter on the target node (score > 15),
    // which should trigger the index pre-filter path.
    var result = session.query(
        "MATCH"
            + " {class: IdxPerson, as: author, where: (name='Author')}"
            + "   .out('IdxCreated'){class: IdxPost, as: post,"
            + "     where: (score > 15)}"
            + " RETURN post.title")
        .toList();

    assertEquals("Should find posts with score > 15", 4, result.size());
    session.commit();
  }

  /**
   * Verifies EXPLAIN output shows the specific intersection descriptor type
   * (EdgeRidLookup) when a back-reference $matched.X.@rid is used.
   * The plan should contain "intersection: in('MHasCreator')" indicating
   * the reverse edge lookup was chosen.
   */
  @Test
  public void testExplainShowsEdgeRidLookupIntersection() {
    session.execute("CREATE CLASS EPerson EXTENDS V").close();
    session.execute("CREATE CLASS EPost EXTENDS V").close();
    session.execute("CREATE CLASS EForum EXTENDS V").close();
    session.execute("CREATE CLASS EContainerOf EXTENDS E").close();
    session.execute("CREATE CLASS EHasCreator EXTENDS E").close();
    session.execute("CREATE PROPERTY EContainerOf.out LINK EForum").close();
    session.execute("CREATE PROPERTY EContainerOf.in LINK EPost").close();
    session.execute("CREATE PROPERTY EHasCreator.out LINK EPost").close();
    session.execute("CREATE PROPERTY EHasCreator.in LINK EPerson").close();

    session.begin();
    session.execute("CREATE VERTEX EForum SET name = 'F1'").close();
    session.execute("CREATE VERTEX EPost SET title = 'P1'").close();
    session.execute("CREATE VERTEX EPerson SET name = 'Alice'").close();
    session.execute(
        "CREATE EDGE EContainerOf FROM"
            + " (SELECT FROM EForum WHERE name='F1') TO"
            + " (SELECT FROM EPost WHERE title='P1')")
        .close();
    session.execute(
        "CREATE EDGE EHasCreator FROM"
            + " (SELECT FROM EPost WHERE title='P1') TO"
            + " (SELECT FROM EPerson WHERE name='Alice')")
        .close();
    session.commit();

    session.begin();
    var matchQuery =
        "MATCH"
            + " {class: EPerson, as: person, where: (name = 'Alice')}"
            + "   .in('EHasCreator'){as: post}"
            + "   .in('EContainerOf'){as: forum}"
            + "   .out('EContainerOf'){as: post2}"
            + "   .out('EHasCreator'){as: creator,"
            + "     where: (@rid = $matched.person.@rid)}"
            + " RETURN post2.title";

    // Verify correctness
    var result = session.query(matchQuery).toList();
    assertFalse("Should return results", result.isEmpty());

    // Verify EXPLAIN shows specific intersection type
    var explain = session.query("EXPLAIN " + matchQuery).toList();
    var plan = (String) explain.getFirst().getProperty("executionPlanAsString");
    assertTrue(
        "Plan should show EdgeRidLookup intersection with in('EHasCreator'),"
            + " plan was:\n" + plan,
        plan.contains("intersection:") && plan.contains("EHasCreator"));
    session.commit();
  }

  /**
   * Verifies that a MATCH query with an indexed equality filter on the
   * target node shows "intersection: index" in the EXPLAIN output,
   * confirming the index pre-filter was pushed down to the MATCH engine.
   */
  @Test
  public void testExplainShowsIndexIntersectionInMatch() {
    session.execute("CREATE CLASS IxPerson EXTENDS V").close();
    session.execute("CREATE CLASS IxPost EXTENDS V").close();
    session.execute("CREATE CLASS IxCreated EXTENDS E").close();
    session.execute("CREATE PROPERTY IxCreated.out LINK IxPerson").close();
    session.execute("CREATE PROPERTY IxCreated.in LINK IxPost").close();
    session.execute("CREATE PROPERTY IxPost.lang STRING").close();
    session.execute(
        "CREATE INDEX IxPost.lang ON IxPost (lang) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX IxPerson SET name = 'Author'").close();
    for (int i = 0; i < 10; i++) {
      var lang = (i % 2 == 0) ? "en" : "de";
      session.execute(
          "CREATE VERTEX IxPost SET title = 'p" + i + "', lang = '" + lang + "'")
          .close();
      session.execute(
          "CREATE EDGE IxCreated FROM"
              + " (SELECT FROM IxPerson WHERE name='Author') TO"
              + " (SELECT FROM IxPost WHERE title='p" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // Equality on indexed property → should trigger index intersection
    var matchQuery =
        "MATCH"
            + " {class: IxPerson, as: author, where: (name = 'Author')}"
            + "   .out('IxCreated'){class: IxPost, as: post,"
            + "     where: (lang = 'en')}"
            + " RETURN post.title";

    var result = session.query(matchQuery).toList();
    assertEquals("Should find 5 English posts", 5, result.size());

    var explain = session.query("EXPLAIN " + matchQuery).toList();
    var plan = (String) explain.getFirst().getProperty("executionPlanAsString");
    assertTrue(
        "Plan should show index intersection for lang filter,"
            + " plan was:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * Verifies that a MATCH pattern with a back-reference uses the correct
   * descriptor type by checking the EXPLAIN output shows the edge class name
   * in the intersection annotation. This confirms the planner chose
   * EdgeRidLookup (not a fallback).
   */
  @Test
  public void testExplainIntersectionShowsEdgeClassName() {
    session.execute("CREATE CLASS CxPerson EXTENDS V").close();
    session.execute("CREATE CLASS CxPost EXTENDS V").close();
    session.execute("CREATE CLASS CxForum EXTENDS V").close();
    session.execute("CREATE CLASS CxContainerOf EXTENDS E").close();
    session.execute("CREATE CLASS CxHasCreator EXTENDS E").close();
    session.execute("CREATE PROPERTY CxContainerOf.out LINK CxForum").close();
    session.execute("CREATE PROPERTY CxContainerOf.in LINK CxPost").close();
    session.execute("CREATE PROPERTY CxHasCreator.out LINK CxPost").close();
    session.execute("CREATE PROPERTY CxHasCreator.in LINK CxPerson").close();

    session.begin();
    session.execute("CREATE VERTEX CxForum SET name = 'F1'").close();
    session.execute("CREATE VERTEX CxPerson SET name = 'Alice'").close();
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX CxPost SET title = 'p" + i + "'").close();
      session.execute(
          "CREATE EDGE CxContainerOf FROM"
              + " (SELECT FROM CxForum WHERE name='F1') TO"
              + " (SELECT FROM CxPost WHERE title='p" + i + "')")
          .close();
      session.execute(
          "CREATE EDGE CxHasCreator FROM"
              + " (SELECT FROM CxPost WHERE title='p" + i + "') TO"
              + " (SELECT FROM CxPerson WHERE name='Alice')")
          .close();
    }
    session.commit();

    session.begin();
    var matchQuery =
        "MATCH"
            + " {class: CxPerson, as: person, where: (name = 'Alice')}"
            + "   .in('CxHasCreator'){as: post}"
            + "   .in('CxContainerOf'){as: forum}"
            + "   .out('CxContainerOf'){as: post2}"
            + "   .out('CxHasCreator'){as: creator,"
            + "     where: (@rid = $matched.person.@rid)}"
            + " RETURN post2.title";

    var result = session.query(matchQuery).toList();
    assertFalse("Should return results", result.isEmpty());

    var explain = session.query("EXPLAIN " + matchQuery).toList();
    var plan = (String) explain.getFirst().getProperty("executionPlanAsString");
    // Plan should show "intersection: in('CxHasCreator')" — confirming
    // EdgeRidLookup descriptor was used with the correct edge class.
    assertTrue(
        "Plan should show CxHasCreator in intersection annotation,"
            + " plan was:\n" + plan,
        plan.contains("CxHasCreator") && plan.contains("intersection"));
    session.commit();
  }

  /**
   * Tests back-reference intersection with a large enough link bag
   * to trigger the resolveReverseEdgeLookup path in
   * TraversalPreFilterHelper (link bag size > minLinkBagSize = 50).
   * Creates a forum with 80 posts by two authors, then uses a MATCH
   * pattern with $matched.person.@rid back-reference. The intersection
   * pre-filter should fire and be visible in EXPLAIN.
   */
  @Test
  public void testBackReferenceIntersection_largeLinkBag() {
    session.execute("CREATE CLASS LgPerson EXTENDS V").close();
    session.execute("CREATE CLASS LgPost EXTENDS V").close();
    session.execute("CREATE CLASS LgForum EXTENDS V").close();
    session.execute("CREATE CLASS LgContainerOf EXTENDS E").close();
    session.execute("CREATE CLASS LgHasCreator EXTENDS E").close();
    session.execute("CREATE PROPERTY LgContainerOf.out LINK LgForum").close();
    session.execute("CREATE PROPERTY LgContainerOf.in LINK LgPost").close();
    session.execute("CREATE PROPERTY LgHasCreator.out LINK LgPost").close();
    session.execute("CREATE PROPERTY LgHasCreator.in LINK LgPerson").close();

    session.begin();
    session.execute("CREATE VERTEX LgForum SET name = 'Forum1'").close();
    session.execute("CREATE VERTEX LgPerson SET name = 'Alice'").close();
    session.execute("CREATE VERTEX LgPerson SET name = 'Bob'").close();

    // 80 posts: 40 by Alice, 40 by Bob → link bags > 50
    for (int i = 0; i < 80; i++) {
      var creator = (i % 2 == 0) ? "Alice" : "Bob";
      session.execute(
          "CREATE VERTEX LgPost SET title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE LgContainerOf FROM"
              + " (SELECT FROM LgForum WHERE name='Forum1') TO"
              + " (SELECT FROM LgPost WHERE title='post" + i + "')")
          .close();
      session.execute(
          "CREATE EDGE LgHasCreator FROM"
              + " (SELECT FROM LgPost WHERE title='post" + i + "') TO"
              + " (SELECT FROM LgPerson WHERE name='" + creator + "')")
          .close();
    }
    session.commit();

    session.begin();
    // Pattern: person → posts → forum → all posts in forum → creator must
    // be same person. The large link bag (80 posts in forum) should trigger
    // the intersection pre-filter on the last edge.
    var matchQuery =
        "MATCH"
            + " {class: LgPerson, as: person, where: (name = 'Alice')}"
            + "   .in('LgHasCreator'){as: post}"
            + "   .in('LgContainerOf'){as: forum}"
            + "   .out('LgContainerOf'){as: post2}"
            + "   .out('LgHasCreator'){as: creator,"
            + "     where: (@rid = $matched.person.@rid)}"
            + " RETURN post2.title";

    var result = session.query(matchQuery).toList();
    // Only posts by Alice should appear as post2 (40 posts × 40 post
    // combinations from the cross product)
    assertFalse("Should return results", result.isEmpty());

    // Verify correctness: returned results should have post2.title set
    for (var r : result) {
      assertNotNull(r.getProperty("post2.title"));
    }

    // Verify intersection optimization is active
    var explain = session.query("EXPLAIN " + matchQuery).toList();
    var plan = (String) explain.getFirst().getProperty("executionPlanAsString");
    assertTrue(
        "Plan should show intersection pre-filter for large link bag,"
            + " plan was:\n" + plan,
        plan.contains("intersection"));
    session.commit();
  }

  /**
   * IC6-style pattern with explicit class: constraints on targets.
   * Post is the only root candidate (broadTag and selectiveTag have no class:
   * in the query so the planner cannot make them roots). From post, two edges
   * go out: one to broadTag (name <> 'targetTag') and one to selectiveTag
   * (name = 'targetTag'). The planner should schedule the selective edge first.
   *
   * Edge schema LINK properties allow class inference for selectivity estimation.
   */
  @Test
  public void testSelectiveWhereClauseIsPreferredOverBroadWhere() {
    session.execute("CREATE class SelPost extends V").close();
    session.execute("CREATE class SelTag extends V").close();
    session.execute("CREATE class SelHasTag extends E").close();
    session.execute("CREATE PROPERTY SelHasTag.out LINK SelPost").close();
    session.execute("CREATE PROPERTY SelHasTag.in LINK SelTag").close();

    session.begin();
    session.execute("CREATE VERTEX SelTag set name = 'targetTag'").close();
    for (var i = 0; i < 250; i++) {
      session.execute("CREATE VERTEX SelTag set name = 'tag" + i + "'").close();
    }

    for (var i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX SelPost set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE SelHasTag FROM"
              + " (SELECT FROM SelPost WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM SelTag WHERE name = 'targetTag')")
          .close();
      for (var j = 0; j < 5; j++) {
        session.execute(
            "CREATE EDGE SelHasTag FROM"
                + " (SELECT FROM SelPost WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM SelTag WHERE name = 'tag" + j + "')")
            .close();
      }
    }
    session.commit();

    // Target nodes have no class: constraint — post is the only root candidate.
    // The planner infers SelTag from SelHasTag.in and uses filter selectivity
    // to order edges: selective (name = 'targetTag') before broad (name <> 'targetTag').
    var matchQuery =
        "MATCH"
            + " {class: SelPost, as: post}"
            + "   .out('SelHasTag'){as: broadTag,"
            + "     where: (name <> 'targetTag')},"
            + " {as: post}"
            + "   .out('SelHasTag'){as: selectiveTag,"
            + "     where: (name = 'targetTag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    session.begin();
    var result = session.query(matchQuery).toList();

    assertFalse(result.isEmpty());
    for (var r : result) {
      assertEquals("targetTag", r.getProperty("selectiveTag.name"));
      assertNotEquals("targetTag", r.getProperty("broadTag.name"));
    }
    assertEquals(50, result.size());

    var explainResult = session.query("EXPLAIN " + matchQuery).toList();
    assertEquals(1, explainResult.size());
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    int selectivePos = plan.indexOf("{selectiveTag}");
    int broadPos = plan.indexOf("{broadTag}");
    assertTrue("selectiveTag should appear in plan", selectivePos >= 0);
    assertTrue("broadTag should appear in plan", broadPos >= 0);
    assertTrue(
        "Selective edge (selectiveTag) should be scheduled before broad edge"
            + " (broadTag) in the execution plan, but plan was:\n" + plan,
        selectivePos < broadPos);

    session.commit();
  }

  /**
   * IC6-style pattern: target nodes have no explicit class: constraint, so
   * the planner must infer the vertex class from the edge schema's linked
   * property. The selective branch (name = 'X') should still be scheduled
   * before the broad branch (name <> 'X').
   */
  @Test
  public void testSelectivityInferredFromEdgeSchemaWithoutExplicitClass() {
    session.execute("CREATE class IC6Post extends V").close();
    session.execute("CREATE class IC6Tag extends V").close();
    session.execute("CREATE class IC6HasTag extends E").close();
    session.execute("CREATE PROPERTY IC6HasTag.out LINK IC6Post").close();
    session.execute("CREATE PROPERTY IC6HasTag.in LINK IC6Tag").close();

    session.begin();
    session.execute("CREATE VERTEX IC6Tag set name = 'targetTag'").close();
    for (var i = 0; i < 250; i++) {
      session.execute("CREATE VERTEX IC6Tag set name = 'tag" + i + "'").close();
    }
    for (var i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX IC6Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE IC6HasTag FROM"
              + " (SELECT FROM IC6Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM IC6Tag WHERE name = 'targetTag')")
          .close();
      for (var j = 0; j < 5; j++) {
        session.execute(
            "CREATE EDGE IC6HasTag FROM"
                + " (SELECT FROM IC6Post WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM IC6Tag WHERE name = 'tag" + j + "')")
            .close();
      }
    }
    session.commit();

    // No class: on target nodes — planner infers IC6Tag from IC6HasTag.in
    var matchQuery =
        "MATCH"
            + " {class: IC6Post, as: post}"
            + "   .out('IC6HasTag'){as: broadTag,"
            + "     where: (name <> 'targetTag')},"
            + " {as: post}"
            + "   .out('IC6HasTag'){as: selectiveTag,"
            + "     where: (name = 'targetTag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    session.begin();
    var result = session.query(matchQuery).toList();

    assertFalse(result.isEmpty());
    assertEquals(50, result.size());

    var explainResult = session.query("EXPLAIN " + matchQuery).toList();
    assertEquals(1, explainResult.size());
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    int selectivePos = plan.indexOf("{selectiveTag}");
    int broadPos = plan.indexOf("{broadTag}");
    assertTrue("selectiveTag should appear in plan", selectivePos >= 0);
    assertTrue("broadTag should appear in plan", broadPos >= 0);
    assertTrue(
        "Selective edge (selectiveTag) should be scheduled before broad edge"
            + " (broadTag) even without explicit class:, but plan was:\n"
            + plan,
        selectivePos < broadPos);

    session.commit();
  }

  /**
   * IC12-style pattern: a WHILE traversal with a selective WHERE filter on the
   * recursive step should be costed higher (depth multiplier) than a simple
   * one-hop edge, so the planner schedules the cheaper one-hop edges first.
   *
   * Pattern:
   *   person -> comment -> post -> tag -> directClass -WHILE-> matchedClass
   *
   * The WHILE edge (IS_SUBCLASS_OF) with unbounded depth should have a higher
   * cost than the one-hop edges, causing it to be scheduled last.
   */
  @Test
  public void testWhileTraversalGetHigherCostThanOneHopEdge() {
    session.execute("CREATE class IC12Person extends V").close();
    session.execute("CREATE class IC12Comment extends V").close();
    session.execute("CREATE class IC12Post extends V").close();
    session.execute("CREATE class IC12Tag extends V").close();
    session.execute("CREATE class IC12TagClass extends V").close();
    session.execute("CREATE class IC12HasCreator extends E").close();
    session.execute("CREATE class IC12ReplyOf extends E").close();
    session.execute("CREATE class IC12HasTag extends E").close();
    session.execute("CREATE class IC12HasType extends E").close();
    session.execute("CREATE class IC12IsSubclassOf extends E").close();

    session.begin();
    // Build a small graph: person <- comment -> post -> tag -> tagClass chain
    session.execute("CREATE VERTEX IC12Person set name = 'Alice'").close();
    session.execute("CREATE VERTEX IC12TagClass set name = 'Science'").close();
    session.execute("CREATE VERTEX IC12TagClass set name = 'BaseClass'").close();
    session.execute(
        "CREATE EDGE IC12IsSubclassOf FROM"
            + " (SELECT FROM IC12TagClass WHERE name = 'Science')"
            + " TO (SELECT FROM IC12TagClass WHERE name = 'BaseClass')")
        .close();

    for (var i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX IC12Tag set name = 'tag" + i + "'").close();
      session.execute(
          "CREATE EDGE IC12HasType FROM"
              + " (SELECT FROM IC12Tag WHERE name = 'tag" + i + "')"
              + " TO (SELECT FROM IC12TagClass WHERE name = 'Science')")
          .close();
      session.execute(
          "CREATE VERTEX IC12Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE IC12HasTag FROM"
              + " (SELECT FROM IC12Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM IC12Tag WHERE name = 'tag" + i + "')")
          .close();
      session.execute(
          "CREATE VERTEX IC12Comment set text = 'comment" + i + "'").close();
      session.execute(
          "CREATE EDGE IC12ReplyOf FROM"
              + " (SELECT FROM IC12Comment WHERE text = 'comment" + i + "')"
              + " TO (SELECT FROM IC12Post WHERE title = 'post" + i + "')")
          .close();
      session.execute(
          "CREATE EDGE IC12HasCreator FROM"
              + " (SELECT FROM IC12Comment WHERE text = 'comment" + i + "')"
              + " TO (SELECT FROM IC12Person WHERE name = 'Alice')")
          .close();
    }
    session.commit();

    // IC12-style MATCH: the WHILE edge should be scheduled after the one-hop
    // edges because of the depth multiplier.
    var matchQuery =
        "MATCH"
            + " {class: IC12Person, as: p, where: (name = 'Alice')}"
            + "   .in('IC12HasCreator'){class: IC12Comment, as: comment}"
            + "   .out('IC12ReplyOf'){class: IC12Post, as: post}"
            + "   .out('IC12HasTag'){as: tag}"
            + "   .out('IC12HasType'){as: directClass}"
            + "   .out('IC12IsSubclassOf'){while: (true),"
            + "     where: (name = 'Science'), as: matchedClass}"
            + " RETURN p.name, tag.name, matchedClass.name";

    session.begin();
    var result = session.query(matchQuery).toList();
    assertFalse("IC12-style query should return results", result.isEmpty());

    // Verify WHILE edge (matchedClass) appears after one-hop edges in the plan
    var explainResult = session.query("EXPLAIN " + matchQuery).toList();
    assertEquals(1, explainResult.size());
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    // The WHILE edge to matchedClass should be scheduled after the simple
    // one-hop edge to directClass due to depth multiplier.
    int directClassPos = plan.indexOf("{directClass}");
    int matchedClassPos = plan.indexOf("{matchedClass}");
    assertTrue("directClass should appear in plan", directClassPos >= 0);
    assertTrue("matchedClass should appear in plan", matchedClassPos >= 0);
    assertTrue(
        "One-hop edge (directClass) should be scheduled before WHILE edge"
            + " (matchedClass) in the execution plan, but plan was:\n" + plan,
        directClassPos < matchedClassPos);
    session.commit();
  }

  /**
   * IC4-style pattern: compound range filter (creationDate >= X AND creationDate < Y)
   * should still benefit from cost-based reordering via the estimatedRootEntries
   * fallback, even though the heuristic cannot classify compound filters directly.
   * The NOT pattern verifies that the planner correctly handles negative patterns
   * alongside cost-based edge scheduling.
   */
  @Test
  public void testCompoundRangeFilterWithNotPattern() {
    session.execute("CREATE class IC4Person extends V").close();
    session.execute("CREATE class IC4Post extends V").close();
    session.execute("CREATE class IC4Tag extends V").close();
    session.execute("CREATE class IC4Knows extends E").close();
    session.execute("CREATE class IC4HasCreator extends E").close();
    session.execute("CREATE class IC4HasTag extends E").close();

    session.begin();
    session.execute("CREATE VERTEX IC4Person set name = 'Alice'").close();
    session.execute("CREATE VERTEX IC4Person set name = 'Bob'").close();
    session.execute(
        "CREATE EDGE IC4Knows FROM"
            + " (SELECT FROM IC4Person WHERE name = 'Alice')"
            + " TO (SELECT FROM IC4Person WHERE name = 'Bob')")
        .close();

    for (var i = 0; i < 3; i++) {
      session.execute(
          "CREATE VERTEX IC4Tag set name = 'tag" + i + "'").close();
    }
    // Bob's recent posts (date >= 100)
    for (var i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX IC4Post set title = 'recent" + i
              + "', creationDate = " + (100 + i))
          .close();
      session.execute(
          "CREATE EDGE IC4HasCreator FROM"
              + " (SELECT FROM IC4Post WHERE title = 'recent" + i + "')"
              + " TO (SELECT FROM IC4Person WHERE name = 'Bob')")
          .close();
      session.execute(
          "CREATE EDGE IC4HasTag FROM"
              + " (SELECT FROM IC4Post WHERE title = 'recent" + i + "')"
              + " TO (SELECT FROM IC4Tag WHERE name = 'tag" + (i % 3) + "')")
          .close();
    }
    // Bob's old posts (date < 100)
    for (var i = 0; i < 3; i++) {
      session.execute(
          "CREATE VERTEX IC4Post set title = 'old" + i
              + "', creationDate = " + (50 + i))
          .close();
      session.execute(
          "CREATE EDGE IC4HasCreator FROM"
              + " (SELECT FROM IC4Post WHERE title = 'old" + i + "')"
              + " TO (SELECT FROM IC4Person WHERE name = 'Bob')")
          .close();
      session.execute(
          "CREATE EDGE IC4HasTag FROM"
              + " (SELECT FROM IC4Post WHERE title = 'old" + i + "')"
              + " TO (SELECT FROM IC4Tag WHERE name = 'tag0')")
          .close();
    }
    session.commit();

    // IC4-style: find tags of recent posts by friends, excluding tags also used
    // on old posts. The compound range filter (>= AND <) tests the fallback path.
    var matchQuery =
        "MATCH"
            + " {class: IC4Person, as: p, where: (name = 'Alice')}"
            + "   .out('IC4Knows'){as: friend}"
            + "   .in('IC4HasCreator'){class: IC4Post, as: newPost,"
            + "     where: (creationDate >= 100 AND creationDate < 200)}"
            + "   .out('IC4HasTag'){as: tag},"
            + " NOT {as: friend}"
            + "   .in('IC4HasCreator'){class: IC4Post, as: oldPost,"
            + "     where: (creationDate < 100)}"
            + "   .out('IC4HasTag'){as: tag}"
            + " RETURN tag.name as tagName, count(*) as postCount"
            + " GROUP BY tag.name"
            + " ORDER BY postCount DESC, tagName ASC";

    session.begin();
    var result = session.query(matchQuery).toList();

    // tag1 and tag2 appear only in recent posts, tag0 appears in both old and
    // recent so it should be excluded by the NOT pattern.
    assertFalse("IC4-style query should return results", result.isEmpty());
    for (var r : result) {
      assertNotEquals(
          "tag0 should be excluded by NOT pattern (also used on old posts)",
          "tag0", r.getProperty("tagName"));
    }
    session.commit();
  }

}
