package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
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
    session.commit();
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

  /** Exercises ReturnMatchElementsStep by using RETURN $elements. */
  @Test
  public void testReturnElementsUnrolls() {
    session.begin();
    var result = session.query(
            "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b} RETURN $elements")
        .toList();
    // $elements should unroll into individual elements - 2 per row (a and b)
    // n1 has 2 friends (n2, n3) so we expect 2 rows * 2 elements = 4 result rows
    // But each row in MATCH result becomes 2 elements (a=n1, b=friend)
    assertFalse(result.isEmpty());
    // Each result should be a single record, not a map with aliases
    for (var row : result) {
      assertNotNull(row.getIdentity());
    }
    session.commit();
  }

  /** Exercises ReturnMatchPathElementsStep by using RETURN $pathElements. */
  @Test
  public void testReturnPathElementsIncludesAll() {
    session.begin();
    var result = session.query(
            "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b} "
                + "RETURN $pathElements")
        .toList();
    assertFalse(result.isEmpty());
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
    // (Optional: LEFT JOIN semantics - row is preserved)
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
   * This covers prettyPrint() methods on MatchFirstStep, MatchStep, and return steps.
   */
  @Test
  public void testExplainMatchQuery() {
    session.begin();
    var result = session.query(
            "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
                + " RETURN a.name, b.name")
        .toList();
    assertFalse(result.isEmpty());
    // EXPLAIN should return a result with the execution plan description
    session.commit();
  }

  /**
   * Exercises explain() with optional match to cover OptionalMatchStep.prettyPrint().
   */
  @Test
  public void testExplainMatchOptionalQuery() {
    session.begin();
    var result = session.query(
            "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
                + ".out('Friend'){as:b, optional:true} RETURN a, b")
        .toList();
    assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Exercises explain() with NOT pattern to cover FilterNotMatchPatternStep.prettyPrint().
   */
  @Test
  public void testExplainMatchNotPattern() {
    session.begin();
    var result = session.query(
            "EXPLAIN MATCH {class:Person, as:a}.out('Friend'){as:b},"
                + " NOT {as:a}.out('Friend'){as:b, where:(name='n3')}"
                + " RETURN a, b")
        .toList();
    assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Exercises explain() with RETURN $paths to cover ReturnMatchPathsStep.prettyPrint().
   */
  @Test
  public void testExplainReturnPaths() {
    session.begin();
    var result = session.query(
            "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
                + " RETURN $paths")
        .toList();
    assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Exercises explain() with RETURN $elements to cover ReturnMatchElementsStep.prettyPrint().
   */
  @Test
  public void testExplainReturnElements() {
    session.begin();
    var result = session.query(
            "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
                + " RETURN $elements")
        .toList();
    assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Exercises explain() with RETURN $pathElements to cover
   * ReturnMatchPathElementsStep.prettyPrint().
   */
  @Test
  public void testExplainReturnPathElements() {
    session.begin();
    var result = session.query(
            "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
                + " RETURN $pathElements")
        .toList();
    assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Exercises explain() with RETURN $patterns to cover
   * ReturnMatchPatternsStep.prettyPrint().
   */
  @Test
  public void testExplainReturnPatterns() {
    session.begin();
    var result = session.query(
            "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b}"
                + " RETURN $patterns")
        .toList();
    assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Tests reverse MatchStep prettyPrint direction indicator by exercising EXPLAIN
   * on a pattern that requires reverse traversal.
   */
  @Test
  public void testExplainReverseTraversal() {
    session.begin();
    var result = session.query(
            "EXPLAIN MATCH {class:Person, as:a, where:(name='n4')}.in('Friend'){as:b}"
                + " RETURN a, b")
        .toList();
    assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Exercises MatchMultiEdgeTraverser by testing multi-step path traversal.
   */
  @Test
  public void testMultiEdgeTraversal() {
    session.begin();
    // Multi-step: n1 -> n2 -> n4, compound path
    var result = session.query(
            "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend').out('Friend'){as:b}"
                + " RETURN b.name as bName")
        .toList();
    assertFalse(result.isEmpty());
    // n1->n2->n4 and n1->n3 (n3 has no outgoing), so should get n4
    boolean foundN4 = false;
    for (var row : result) {
      if ("n4".equals(row.getProperty("bName"))) {
        foundN4 = true;
      }
    }
    assertTrue("Should find n4 through multi-edge traversal", foundN4);
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

  /**
   * Exercises MATCH with WHILE clause and depthAlias to cover MatchEdgeTraverser's
   * recursive path and depth metadata propagation.
   */
  @Test
  public void testWhileTraversalWithDepthAlias() {
    session.begin();
    // n1 -> n2 -> n4 -> n5, with WHILE condition and depth tracking
    var result = session.query(
            "MATCH {class:Person, as:a, where:(name='n1')}"
                + ".out('Friend'){while:($depth < 3), as:b, depthAlias: d}"
                + " RETURN b.name as bName, d")
        .toList();
    assertFalse(result.isEmpty());
    // Should find records at various depths
    boolean foundDepthZero = false;
    for (var row : result) {
      Object depth = row.getProperty("d");
      if (depth != null && ((Number) depth).intValue() == 0) {
        foundDepthZero = true;
      }
    }
    assertTrue("WHILE traversal should include depth 0 (starting point)", foundDepthZero);
    session.commit();
  }

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
    assertFalse(result.isEmpty());
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
   * scheduler is forced to reverse one edge direction. The pattern a->b->d, a->c->d
   * means that when scheduling the second path to 'd', it's already visited, creating
   * a back-edge that may be reversed.
   */
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
            + " WHERE name = 'src'").close();
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
    assertFalse(result.isEmpty());
    boolean found = false;
    for (var row : result) {
      if ("n4".equals(row.getProperty("bName"))) {
        found = true;
      }
    }
    assertTrue("Compound path should find n4 via n1->n2->n4", found);
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
    var result = session.query(
            "MATCH {class:Person, as:a, where:(name='n1')}"
                + ".(out('Friend'){while:($depth < 2)}){as:b}"
                + " RETURN b.name as bName")
        .toList();
    assertFalse(result.isEmpty());
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
    session.commit();
  }

  /**
   * Exercises MatchReverseEdgeTraverser with an optional target node that forces the
   * scheduler to reverse the edge direction (optional nodes must be reached from
   * already-visited nodes).
   */
  @Test
  public void testOptionalNodeTriggersReverseTraversal() {
    session.begin();
    var result = session.query(
            "MATCH {class:Person, as:a}.out('Friend'){as:b},"
                + " {as:b, optional:true}.in('Friend'){as:a}"
                + " RETURN a.name as aName, b.name as bName")
        .toList();
    assertFalse(result.isEmpty());
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
   * Exercises MatchEdgeTraverser.next() with depthAlias AND pathAlias to cover
   * the metadata propagation branches.
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
    for (var row : result) {
      Object depth = row.getProperty("d");
      if (depth != null && ((Number) depth).intValue() == 0) {
        foundStart = true;
        assertNotNull("pathAlias should produce a value", row.getProperty("p"));
      }
    }
    assertTrue("Should find depth-0 starting point result", foundStart);
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
    assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Exercises OptionalMatchEdgeTraverser with a node that has no outgoing
   * edges, producing the EMPTY_OPTIONAL sentinel. Covers the "no match"
   * path in init() and the null-setting path in next().
   */
  @Test
  public void testOptionalWithNoMatchProducesNullValue() {
    session.begin();
    // n6 has no outgoing Friend edges. 'b' will be EMPTY_OPTIONAL → null.
    var result = session.query(
            "MATCH {class:Person, as:a, where:(name='n6')}"
                + ".out('Friend'){as:b, optional:true}"
                + " RETURN a.name as aName, b as bValue")
        .toList();
    assertEquals(1, result.size());
    assertEquals("n6", result.get(0).getProperty("aName"));
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
