package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Ignore;
import org.junit.Test;

public class MatchStatementExecutionTest extends DbTestBase {

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
        new String[][]{{"n1", "n2"}, {"n1", "n3"}, {"n2", "n4"}, {"n4", "n5"}, {"n4", "n6"}};

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
              "CREATE EDGE IndexedEDGE FROM (SELECT FROM IndexedVertex WHERE uid = 0) TO (SELECT"
                  + " FROM IndexedVertex WHERE uid > "
                  + (i * nodes / 100)
                  + " and uid <"
                  + ((i + 1) * nodes / 100)
                  + ")")
          .close();
    }

    for (var i = 0; i < 100; i++) {
      session.execute(
              "CREATE EDGE IndexedEDGE FROM (SELECT FROM IndexedVertex WHERE uid > "
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
    deptHierarchy[0] = new int[]{1, 2};
    deptHierarchy[1] = new int[]{3, 4};
    deptHierarchy[2] = new int[]{5, 6};
    deptHierarchy[3] = new int[]{7, 8};
    deptHierarchy[4] = new int[]{};
    deptHierarchy[5] = new int[]{};
    deptHierarchy[6] = new int[]{};
    deptHierarchy[7] = new int[]{9};
    deptHierarchy[8] = new int[]{};
    deptHierarchy[9] = new int[]{};

    var deptManagers = new String[]{"a", "b", "d", null, null, null, null, "c", null, null};

    var employees = new String[10][];
    employees[0] = new String[]{"p1"};
    employees[1] = new String[]{"p2", "p3"};
    employees[2] = new String[]{"p4", "p5"};
    employees[3] = new String[]{"p6"};
    employees[4] = new String[]{"p7"};
    employees[5] = new String[]{"p8"};
    employees[6] = new String[]{"p9"};
    employees[7] = new String[]{"p10"};
    employees[8] = new String[]{"p11"};
    employees[9] = new String[]{"p12", "p13"};

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
    var edges = new int[][]{
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
    var edges = new int[][]{{0, 1}, {0, 2}, {1, 3}, {2, 3}};
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
            + " person").toList();
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
                + " person limit 1").toList();

    assertEquals(1, qResult.size());
    session.commit();
  }

  @Test
  public void testSimpleLimit2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
                + " person limit -1").toList();
    assertEquals(2, qResult.size());
    session.commit();
  }

  @Test
  public void testSimpleLimit3() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
                + " person limit 3").toList();

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
                + " = 'n4')} return $matches)").toList();
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
                + " return $matches)").toList();
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
                + " = 'n4')} return friend.name as name").toList();
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
                + " Person, where:(name = 'n4')} return friend.name as name").toList();
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
                + " = 'n4')} return friend.name.toUpperCase(Locale.ENGLISH) as name").toList();
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
                + " as name").toList();
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
                + " = 'n4')} return friend.name + ' ' +friend.name as name").toList();
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
                + " name").toList();
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
                + " = 'n4')} return friend.name").toList();
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
                + " Person, where:(name = 'n4')} return friend.name").toList();
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
                + " 'n1')}.out('Friend').out('Friend'){as:friend} return $matches)").toList();
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
                + " 'n1')}-Friend->{}-Friend->{as:friend} return $matches)").toList();
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
                + " $currentMatch)} return $matches)").toList();

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
                + " return $matches)").toList();

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
                + " friend)").toList();
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
                + " friend)").toList();
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
                + " return friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 4), where: ($depth=1) }"
                + " return friend)").toList();
    assertEquals(2, qResult.size());

    qResult = session.query(
        "select friend.name as name from (match {class:Person, where:(name ="
            + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend)").toList();
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
                + " 'n1')}-Friend->{as:friend, while: ($depth < 1)} return friend)").toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 2), where: ($depth=1) } return"
                + " friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 4), where: ($depth=1) } return"
                + " friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: (true) } return friend)").toList();
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
                + " friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1 } return friend)").toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 0 } return friend)").toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1, where: ($depth > 0) } return"
                + " friend)").toList();
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
                + " friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1 } return friend)").toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 0 } return friend)").toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1, where: ($depth > 0) } return"
                + " friend)").toList();
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
          (d.getEntity("friend1")).<Object>getProperty(
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
                + " person, b.name").toList();
    assertEquals(6, qResult.size());
    for (var doc : qResult) {
      assertEquals(2, doc.getPropertyNames().size());
      Identifiable personId = doc.getProperty("person");
      var transaction = session.getActiveTransaction();
      EntityImpl person = transaction.load(personId);
      String name = person.getProperty("name");
      assertTrue(!name.isEmpty() && name.charAt(0) == 'n');
    }
  }

  @Test
  public void testOptional2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person} --> {as:b, optional:true, where:(nonExisting ="
                + " 12)} return person, b.name").toList();
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
                + " AS b").toList();

    assertEquals(2, qResult.size());
    var sum = 0;
    for (var doc : qResult) {
      sum += ((Number) doc.getProperty("b")).intValue();
    }
    assertEquals(3, sum);
    qResult =
        session.query(
            "MATCH {class: testEvalInReturn, as: p} RETURN if(eval(\"p.name = 'foo'\"), 'foo',"
                + " 'foo') AS b").toList();

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
                + " testCheckClassAsCondition1, as: q} RETURN $elements").toList();

    assertEquals(2, qResult.size());
    session.commit();
  }

  @Test
  public void testInstanceof() {
    session.begin();
    var qResult =
        session.query(
            "MATCH {class: Person, as: p, where: ($currentMatch instanceof 'Person')} return"
                + " $elements limit 1").toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, as: p, where: ($currentMatch instanceof 'V')} return"
                + " $elements limit 1").toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, as: p, where: (not ($currentMatch instanceof 'Person'))}"
                + " return $elements limit 1").toList();
    assertEquals(0, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, where: (name = 'n1')}.out(){as:p, where: ($currentMatch"
                + " instanceof 'Person')} return $elements limit 1").toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, where: (name = 'n1')}.out(){as:p, where: ($currentMatch"
                + " instanceof 'Person' and '$currentMatch' <> '@this')} return $elements limit"
                + " 1").toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "MATCH {class: Person, where: (name = 'n1')}.out(){as:p, where: ( not"
                + " ($currentMatch instanceof 'Person'))} return $elements limit 1").toList();
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
                + " return $elements limit 1").toList();
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
                MATCH\s
                {class: testMatched1_Foo, as: foo}.out('testMatched1_Foo_Bar') {as: bar},\s
                {class: testMatched1_Bar,as: bar}.out('testMatched1_Bar_Baz') {as: baz},\s
                {class: testMatched1_Foo,as: foo}.out('testMatched1_Foo_Far') {where:\
                 ($matched.baz IS null),as: far}
                RETURN $matches""");
    assertFalse(result.hasNext());

    result =
        session.query(
            """
                MATCH\s
                {class: testMatched1_Foo, as: foo}.out('testMatched1_Foo_Bar') {as: bar},\s
                {class: testMatched1_Bar,as: bar}.out('testMatched1_Bar_Baz') {as: baz},\s
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
}
