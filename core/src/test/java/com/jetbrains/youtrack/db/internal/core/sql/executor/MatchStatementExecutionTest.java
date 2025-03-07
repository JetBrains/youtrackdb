package com.jetbrains.youtrack.db.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class MatchStatementExecutionTest extends DbTestBase {

  public void beforeTest() throws Exception {
    super.beforeTest();

    db.command("CREATE class Person extends V").close();
    db.command("CREATE class Friend extends E").close();

    db.begin();
    db.command("CREATE VERTEX Person set name = 'n1'").close();
    db.command("CREATE VERTEX Person set name = 'n2'").close();
    db.command("CREATE VERTEX Person set name = 'n3'").close();
    db.command("CREATE VERTEX Person set name = 'n4'").close();
    db.command("CREATE VERTEX Person set name = 'n5'").close();
    db.command("CREATE VERTEX Person set name = 'n6'").close();

    String[][] friendList =
        new String[][]{{"n1", "n2"}, {"n1", "n3"}, {"n2", "n4"}, {"n4", "n5"}, {"n4", "n6"}};

    for (String[] pair : friendList) {
      db.command(
              "CREATE EDGE Friend from (select from Person where name = ?) to (select from Person"
                  + " where name = ?)",
              pair[0],
              pair[1])
          .close();
    }

    db.commit();

    db.command("CREATE class MathOp extends V").close();

    db.begin();
    db.command("CREATE VERTEX MathOp set a = 1, b = 3, c = 2").close();
    db.command("CREATE VERTEX MathOp set a = 5, b = 3, c = 2").close();
    db.commit();

    initOrgChart();

    initTriangleTest();

    initEdgeIndexTest();

    initDiamondTest();
  }

  private void initEdgeIndexTest() {
    db.command("CREATE class IndexedVertex extends V").close();
    db.command("CREATE property IndexedVertex.uid INTEGER").close();
    db.command("CREATE index IndexedVertex_uid on IndexedVertex (uid) NOTUNIQUE").close();

    db.command("CREATE class IndexedEdge extends E").close();
    db.command("CREATE property IndexedEdge.out LINK").close();
    db.command("CREATE property IndexedEdge.in LINK").close();
    db.command("CREATE index IndexedEdge_out_in on IndexedEdge (out, in) NOTUNIQUE").close();

    int nodes = 1000;
    for (int i = 0; i < nodes; i++) {
      db.begin();
      EntityImpl doc = new EntityImpl("IndexedVertex");
      doc.field("uid", i);
      doc.save();
      db.commit();
    }

    db.begin();
    for (int i = 0; i < 100; i++) {
      db.command(
              "CREATE EDGE IndexedEDGE FROM (SELECT FROM IndexedVertex WHERE uid = 0) TO (SELECT"
                  + " FROM IndexedVertex WHERE uid > "
                  + (i * nodes / 100)
                  + " and uid <"
                  + ((i + 1) * nodes / 100)
                  + ")")
          .close();
    }

    for (int i = 0; i < 100; i++) {
      db.command(
              "CREATE EDGE IndexedEDGE FROM (SELECT FROM IndexedVertex WHERE uid > "
                  + ((i * nodes / 100) + 1)
                  + " and uid < "
                  + (((i + 1) * nodes / 100) + 1)
                  + ") TO (SELECT FROM IndexedVertex WHERE uid = 1)")
          .close();
    }
    db.commit();
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

    db.command("CREATE class Employee extends V").close();
    db.command("CREATE class Department extends V").close();
    db.command("CREATE class ParentDepartment extends E").close();
    db.command("CREATE class WorksAt extends E").close();
    db.command("CREATE class ManagerOf extends E").close();

    int[][] deptHierarchy = new int[10][];
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

    String[] deptManagers = {"a", "b", "d", null, null, null, null, "c", null, null};

    String[][] employees = new String[10][];
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

    db.begin();
    for (int i = 0; i < deptHierarchy.length; i++) {
      db.command("CREATE VERTEX Department set name = 'department" + i + "' ").close();
    }

    for (int parent = 0; parent < deptHierarchy.length; parent++) {
      int[] children = deptHierarchy[parent];
      for (int child : children) {
        db.command(
                "CREATE EDGE ParentDepartment from (select from Department where name = 'department"
                    + child
                    + "') to (select from Department where name = 'department"
                    + parent
                    + "') ")
            .close();
      }
    }

    for (int dept = 0; dept < deptManagers.length; dept++) {
      String manager = deptManagers[dept];
      if (manager != null) {
        db.command("CREATE Vertex Employee set name = '" + manager + "' ").close();

        db.command(
                "CREATE EDGE ManagerOf from (select from Employee where name = '"
                    + manager
                    + "') to (select from Department where name = 'department"
                    + dept
                    + "') ")
            .close();
      }
    }

    for (int dept = 0; dept < employees.length; dept++) {
      String[] employeesForDept = employees[dept];
      for (String employee : employeesForDept) {
        db.command("CREATE Vertex Employee set name = '" + employee + "' ").close();

        db.command(
                "CREATE EDGE WorksAt from (select from Employee where name = '"
                    + employee
                    + "') to (select from Department where name = 'department"
                    + dept
                    + "') ")
            .close();
      }
    }
    db.commit();
  }

  private void initTriangleTest() {
    db.command("CREATE class TriangleV extends V").close();
    db.command("CREATE property TriangleV.uid INTEGER").close();
    db.command("CREATE index TriangleV_uid on TriangleV (uid) UNIQUE_HASH_INDEX").close();
    db.command("CREATE class TriangleE extends E").close();

    db.begin();
    for (int i = 0; i < 10; i++) {
      db.command("CREATE VERTEX TriangleV set uid = ?", i).close();
    }
    int[][] edges = {
        {0, 1}, {0, 2}, {1, 2}, {1, 3}, {2, 4}, {3, 4}, {3, 5}, {4, 0}, {4, 7}, {6, 7}, {7, 8},
        {7, 9}, {8, 9}, {9, 1}, {8, 3}, {8, 4}
    };
    for (int[] edge : edges) {
      db.command(
              "CREATE EDGE TriangleE from (select from TriangleV where uid = ?) to (select from"
                  + " TriangleV where uid = ?)",
              edge[0],
              edge[1])
          .close();
    }
    db.commit();
  }

  private void initDiamondTest() {
    db.command("CREATE class DiamondV extends V").close();
    db.command("CREATE class DiamondE extends E").close();

    db.begin();
    for (int i = 0; i < 4; i++) {
      db.command("CREATE VERTEX DiamondV set uid = ?", i).close();
    }
    int[][] edges = {{0, 1}, {0, 2}, {1, 3}, {2, 3}};
    for (int[] edge : edges) {
      db.command(
              "CREATE EDGE DiamondE from (select from DiamondV where uid = ?) to (select from"
                  + " DiamondV where uid = ?)",
              edge[0],
              edge[1])
          .close();
    }
    db.commit();
  }

  @Test
  public void testSimple() throws Exception {
    List<EntityImpl> qResult = collect(
        db.command("match {class:Person, as: person} return person"));
    assertEquals(6, qResult.size());
    for (EntityImpl doc : qResult) {
      assertEquals(1, doc.fieldNames().length);
      Identifiable personId = doc.field("person");
      EntityImpl person = personId.getRecord();
      String name = person.field("name");
      assertTrue(name.startsWith("n"));
    }
  }

  @Test
  public void testSimpleWhere() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
                    + " person"));

    assertEquals(2, qResult.size());
    for (EntityImpl doc : qResult) {
      assertEquals(1, doc.fieldNames().length);
      Identifiable personId = doc.field("person");
      EntityImpl person = personId.getRecord();
      String name = person.field("name");
      assertTrue(name.equals("n1") || name.equals("n2"));
    }
  }

  @Test
  public void testSimpleLimit() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
                    + " person limit 1"));

    assertEquals(1, qResult.size());
  }

  @Test
  public void testSimpleLimit2() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
                    + " person limit -1"));

    assertEquals(2, qResult.size());
  }

  @Test
  public void testSimpleLimit3() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return"
                    + " person limit 3"));

    assertEquals(2, qResult.size());
  }

  @Test
  public void testSimpleUnnamedParams() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, as: person, where: (name = ? or name = ?)} return person",
                "n1",
                "n2"));

    assertEquals(2, qResult.size());
    for (EntityImpl doc : qResult) {
      assertEquals(1, doc.fieldNames().length);
      Identifiable personId = doc.field("person");
      EntityImpl person = personId.getRecord();
      String name = person.field("name");
      assertTrue(name.equals("n1") || name.equals("n2"));
    }
  }

  @Test
  public void testCommonFriends() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                    + " = 'n4')} return $matches)"));
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.get(0).field("name"));
  }

  @Test
  public void testCommonFriendsArrows() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend-{as:friend}-Friend-{class: Person, where:(name = 'n4')}"
                    + " return $matches)"));
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.get(0).field("name"));
  }

  @Test
  public void testCommonFriends2() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, where:(name ="
                    + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                    + " = 'n4')} return friend.name as name"));
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.get(0).field("name"));
  }

  @Test
  public void testCommonFriends2Arrows() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class:"
                    + " Person, where:(name = 'n4')} return friend.name as name"));
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.get(0).field("name"));
  }

  @Test
  public void testReturnMethod() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, where:(name ="
                    + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                    + " = 'n4')} return friend.name.toUpperCase(Locale.ENGLISH) as name"));
    assertEquals(1, qResult.size());
    assertEquals("N2", qResult.get(0).field("name"));
  }

  @Test
  public void testReturnMethodArrows() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class:"
                    + " Person, where:(name = 'n4')} return friend.name.toUpperCase(Locale.ENGLISH)"
                    + " as name"));
    assertEquals(1, qResult.size());
    assertEquals("N2", qResult.get(0).field("name"));
  }

  @Test
  public void testReturnExpression() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, where:(name ="
                    + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                    + " = 'n4')} return friend.name + ' ' +friend.name as name"));
    assertEquals(1, qResult.size());
    assertEquals("n2 n2", qResult.get(0).field("name"));
  }

  @Test
  public void testReturnExpressionArrows() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class:"
                    + " Person, where:(name = 'n4')} return friend.name + ' ' +friend.name as"
                    + " name"));
    assertEquals(1, qResult.size());
    assertEquals("n2 n2", qResult.get(0).field("name"));
  }

  @Test
  public void testReturnDefaultAlias() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, where:(name ="
                    + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name"
                    + " = 'n4')} return friend.name"));
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.get(0).getProperty("friend.name"));
  }

  @Test
  public void testReturnDefaultAliasArrows() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class:"
                    + " Person, where:(name = 'n4')} return friend.name"));
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.get(0).getProperty("friend.name"));
  }

  @Test
  public void testFriendsOfFriends() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend').out('Friend'){as:friend} return $matches)"));
    assertEquals(1, qResult.size());
    assertEquals("n4", qResult.get(0).field("name"));
  }

  @Test
  public void testFriendsOfFriendsArrows() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend->{}-Friend->{as:friend} return $matches)"));
    assertEquals(1, qResult.size());
    assertEquals("n4", qResult.get(0).field("name"));
  }

  @Test
  public void testFriendsOfFriends2() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                    + " me}.both('Friend').both('Friend'){as:friend, where: ($matched.me !="
                    + " $currentMatch)} return $matches)"));

    for (EntityImpl doc : qResult) {
      assertNotEquals(doc.field("name"), "n1");
    }
  }

  @Test
  public void testFriendsOfFriends2Arrows() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                    + " me}-Friend-{}-Friend-{as:friend, where: ($matched.me != $currentMatch)}"
                    + " return $matches)"));

    for (EntityImpl doc : qResult) {
      assertNotEquals(doc.field("name"), "n1");
    }
  }

  @Test
  public void testFriendsWithName() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1"
                    + " = 2)}.out('Friend'){as:friend, where:(name = 'n2' and 1 + 1 = 2)} return"
                    + " friend)"));
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.get(0).field("name"));
  }

  @Test
  public void testFriendsWithNameArrows() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1"
                    + " = 2)}-Friend->{as:friend, where:(name = 'n2' and 1 + 1 = 2)} return"
                    + " friend)"));
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.get(0).field("name"));
  }

  @Test
  public void testWhile() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, while: ($depth < 1)} return friend)"));
    assertEquals(3, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, while: ($depth < 2), where: ($depth=1) }"
                    + " return friend)"));
    assertEquals(2, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, while: ($depth < 4), where: ($depth=1) }"
                    + " return friend)"));
    assertEquals(2, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend)"));
    assertEquals(6, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend limit 3)"));
    assertEquals(3, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend) limit 3"));
    assertEquals(3, qResult.size());
  }

  @Test
  public void testWhileArrows() throws Exception {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend->{as:friend, while: ($depth < 1)} return friend)"));
    assertEquals(3, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend->{as:friend, while: ($depth < 2), where: ($depth=1) } return"
                    + " friend)"));
    assertEquals(2, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend->{as:friend, while: ($depth < 4), where: ($depth=1) } return"
                    + " friend)"));
    assertEquals(2, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend->{as:friend, while: (true) } return friend)"));
    assertEquals(6, qResult.size());
  }

  @Test
  public void testMaxDepth() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, maxDepth: 1, where: ($depth=1) } return"
                    + " friend)"));
    assertEquals(2, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, maxDepth: 1 } return friend)"));
    assertEquals(3, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, maxDepth: 0 } return friend)"));
    assertEquals(1, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('Friend'){as:friend, maxDepth: 1, where: ($depth > 0) } return"
                    + " friend)"));
    assertEquals(2, qResult.size());
  }

  @Test
  public void testMaxDepthArrow() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend->{as:friend, maxDepth: 1, where: ($depth=1) } return"
                    + " friend)"));
    assertEquals(2, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend->{as:friend, maxDepth: 1 } return friend)"));
    assertEquals(3, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend->{as:friend, maxDepth: 0 } return friend)"));
    assertEquals(1, qResult.size());

    qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}-Friend->{as:friend, maxDepth: 1, where: ($depth > 0) } return"
                    + " friend)"));
    assertEquals(2, qResult.size());
  }

  @Test
  public void testManager() {
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one
    assertEquals("c", getManager("p10").field("name"));
    assertEquals("c", getManager("p12").field("name"));
    assertEquals("b", getManager("p6").field("name"));
    assertEquals("b", getManager("p11").field("name"));

    assertEquals("c", getManagerArrows("p10").field("name"));
    assertEquals("c", getManagerArrows("p12").field("name"));
    assertEquals("b", getManagerArrows("p6").field("name"));
    assertEquals("b", getManagerArrows("p11").field("name"));
  }

  private EntityImpl getManager(String personName) {
    String query =
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

    List<Identifiable> qResult = collectIdentifiable(db.command(query));
    assertEquals(1, qResult.size());
    return qResult.get(0).getRecord();
  }

  private EntityImpl getManagerArrows(String personName) {
    String query =
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

    List<Identifiable> qResult = collectIdentifiable(db.command(query));
    assertEquals(1, qResult.size());
    return qResult.get(0).getRecord();
  }

  @Test
  public void testManager2() {
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one
    assertEquals("c", getManager2("p10").field("name"));
    assertEquals("c", getManager2("p12").field("name"));
    assertEquals("b", getManager2("p6").field("name"));
    assertEquals("b", getManager2("p11").field("name"));

    assertEquals("c", getManager2Arrows("p10").field("name"));
    assertEquals("c", getManager2Arrows("p12").field("name"));
    assertEquals("b", getManager2Arrows("p6").field("name"));
    assertEquals("b", getManager2Arrows("p11").field("name"));
  }

  private EntityImpl getManager2(String personName) {
    String query =
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

    List<Identifiable> qResult = collectIdentifiable(db.command(query));
    assertEquals(1, qResult.size());
    return qResult.get(0).getRecord();
  }

  private EntityImpl getManager2Arrows(String personName) {
    String query =
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

    List<Identifiable> qResult = collectIdentifiable(db.command(query));
    assertEquals(1, qResult.size());
    return qResult.get(0).getRecord();
  }

  @Test
  public void testManaged() {
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    List<Identifiable> managedByA = getManagedBy("a");
    assertEquals(1, managedByA.size());
    assertEquals("p1", ((EntityImpl) managedByA.get(0).getRecord()).field("name"));

    List<Identifiable> managedByB = getManagedBy("b");
    assertEquals(5, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (Identifiable id : managedByB) {
      EntityImpl doc = id.getRecord();
      String name = doc.field("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
  }

  private List<Identifiable> getManagedBy(String managerName) {
    String query =
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

    return collectIdentifiable(db.command(query));
  }

  @Test
  public void testManagedArrows() {
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    List<Identifiable> managedByA = getManagedByArrows("a");
    assertEquals(1, managedByA.size());
    assertEquals("p1", ((EntityImpl) managedByA.get(0).getRecord()).field("name"));

    List<Identifiable> managedByB = getManagedByArrows("b");
    assertEquals(5, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (Identifiable id : managedByB) {
      EntityImpl doc = id.getRecord();
      String name = doc.field("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
  }

  private List<Identifiable> getManagedByArrows(String managerName) {
    String query =
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

    return collectIdentifiable(db.command(query));
  }

  @Test
  public void testManaged2() {
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    List<Identifiable> managedByA = getManagedBy2("a");
    assertEquals(1, managedByA.size());
    assertEquals("p1", ((EntityImpl) managedByA.get(0).getRecord()).field("name"));

    List<Identifiable> managedByB = getManagedBy2("b");
    assertEquals(5, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (Identifiable id : managedByB) {
      EntityImpl doc = id.getRecord();
      String name = doc.field("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
  }

  private List<Identifiable> getManagedBy2(String managerName) {
    String query =
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

    return collectIdentifiable(db.command(query));
  }

  @Test
  public void testManaged2Arrows() {
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    List<Identifiable> managedByA = getManagedBy2Arrows("a");
    assertEquals(1, managedByA.size());
    assertEquals("p1", ((EntityImpl) managedByA.get(0).getRecord()).field("name"));

    List<Identifiable> managedByB = getManagedBy2Arrows("b");
    assertEquals(5, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (Identifiable id : managedByB) {
      EntityImpl doc = id.getRecord();
      String name = doc.field("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
  }

  private List<Identifiable> getManagedBy2Arrows(String managerName) {
    String query =
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

    return collectIdentifiable(db.command(query));
  }

  @Test
  public void testTriangle1() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "  .out('TriangleE'){as: friend2}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
  }

  @Test
  public void testTriangle1Arrows() {
    String query =
        "match {class:TriangleV, as: friend1, where: (uid = 0)} -TriangleE-> {as: friend2}"
            + " -TriangleE-> {as: friend3},{class:TriangleV, as: friend1} -TriangleE-> {as:"
            + " friend3}return $matches";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
  }

  @Test
  public void testTriangle2Old() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    List<EntityImpl> result = collect(db.command(query));
    assertEquals(1, result.size());
    EntityImpl doc = result.get(0);
    EntityImpl friend1 = ((Identifiable) doc.field("friend1")).getRecord();
    EntityImpl friend2 = ((Identifiable) doc.field("friend2")).getRecord();
    EntityImpl friend3 = ((Identifiable) doc.field("friend3")).getRecord();
    assertEquals(0, friend1.<Object>field("uid"));
    assertEquals(1, friend2.<Object>field("uid"));
    assertEquals(2, friend3.<Object>field("uid"));
  }

  @Test
  public void testTriangle2() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $patterns";

    List<EntityImpl> result = collect(db.command(query));
    assertEquals(1, result.size());
    EntityImpl doc = result.get(0);
    EntityImpl friend1 = ((Identifiable) doc.field("friend1")).getRecord();
    EntityImpl friend2 = ((Identifiable) doc.field("friend2")).getRecord();
    EntityImpl friend3 = ((Identifiable) doc.field("friend3")).getRecord();
    assertEquals(0, friend1.<Object>field("uid"));
    assertEquals(1, friend2.<Object>field("uid"));
    assertEquals(2, friend3.<Object>field("uid"));
  }

  @Test
  public void testTriangle2Arrows() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  -TriangleE->{as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    List<EntityImpl> result = collect(db.command(query));
    assertEquals(1, result.size());
    EntityImpl doc = result.get(0);
    EntityImpl friend1 = ((Identifiable) doc.field("friend1")).getRecord();
    EntityImpl friend2 = ((Identifiable) doc.field("friend2")).getRecord();
    EntityImpl friend3 = ((Identifiable) doc.field("friend3")).getRecord();
    assertEquals(0, friend1.<Object>field("uid"));
    assertEquals(1, friend2.<Object>field("uid"));
    assertEquals(2, friend3.<Object>field("uid"));
  }

  @Test
  public void testTriangle3() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend2}"
            + "  -TriangleE->{as: friend3, where: (uid = 2)},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    List<EntityImpl> result = collect(db.command(query));
    assertEquals(1, result.size());
  }

  @Test
  public void testTriangle4() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
  }

  @Test
  public void testTriangle4Arrows() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend2, where: (uid = 1)}"
            + "  -TriangleE->{as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
  }

  @Test
  public void testTriangleWithEdges4() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .outE('TriangleE').inV(){as: friend2, where: (uid = 1)}"
            + "  .outE('TriangleE').inV(){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .outE('TriangleE').inV(){as: friend3}"
            + "return $matches";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
  }

  @Test
  public void testCartesianProduct() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where:(uid = 1)},"
            + "{class:TriangleV, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches";

    List<Identifiable> result = collectIdentifiable(db.command(query));
    assertEquals(2, result.size());
    for (Identifiable d : result) {
      assertEquals(
          ((EntityImpl) ((EntityImpl) d.getRecord()).field("friend1")).<Object>field("uid"), 1);
    }
  }

  @Test
  public void testCartesianProductLimit() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where:(uid = 1)},"
            + "{class:TriangleV, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches LIMIT 1";

    List<Identifiable> result = collectIdentifiable(db.command(query));
    assertEquals(1, result.size());
    for (Identifiable d : result) {
      assertEquals(
          ((EntityImpl) ((EntityImpl) d.getRecord()).field("friend1")).<Object>field("uid"), 1);
    }
  }

  @Test
  public void testArrayNumber() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0] as foo";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
    EntityImpl doc = (EntityImpl) result.get(0);
    Object foo = doc.field("foo");
    assertNotNull(foo);
    Assert.assertTrue(((Entity) foo).isVertex());
  }

  @Test
  public void testArraySingleSelectors2() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0,1] as foo";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
    EntityImpl doc = (EntityImpl) result.get(0);
    Object foo = doc.field("foo");
    assertNotNull(foo);
    assertTrue(foo instanceof List);
    assertEquals(2, ((List) foo).size());
  }

  @Test
  public void testArrayRangeSelectors1() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..1] as foo";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
    EntityImpl doc = (EntityImpl) result.get(0);
    Object foo = doc.field("foo");
    assertNotNull(foo);
    assertTrue(foo instanceof List);
    assertEquals(1, ((List) foo).size());
  }

  @Test
  public void testArrayRange2() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..2] as foo";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
    EntityImpl doc = (EntityImpl) result.get(0);
    Object foo = doc.field("foo");
    assertNotNull(foo);
    assertTrue(foo instanceof List);
    assertEquals(2, ((List) foo).size());
  }

  @Test
  public void testArrayRange3() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..3] as foo";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
    EntityImpl doc = (EntityImpl) result.get(0);
    Object foo = doc.field("foo");
    assertNotNull(foo);
    assertTrue(foo instanceof List);
    assertEquals(2, ((List) foo).size());
  }

  @Test
  public void testConditionInSquareBrackets() {
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[uid = 2] as foo";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
    EntityImpl doc = (EntityImpl) result.get(0);
    Object foo = doc.field("foo");
    assertNotNull(foo);
    assertTrue(foo instanceof List);
    assertEquals(1, ((List) foo).size());
    Vertex resultVertex = (Vertex) ((List) foo).get(0);
    assertEquals(2, resultVertex.<Object>getProperty("uid"));
  }

  @Test
  public void testIndexedEdge() {
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)}"
            + ".out('IndexedEdge'){class:IndexedVertex, as: two, where: (uid = 1)}"
            + "return one, two";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
  }

  @Test
  public void testIndexedEdgeArrows() {
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)}"
            + "-IndexedEdge->{class:IndexedVertex, as: two, where: (uid = 1)}"
            + "return one, two";

    List<?> result = collect(db.command(query));
    assertEquals(1, result.size());
  }

  @Test
  public void testJson() {
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'uuid':one.uid}";

    List<EntityImpl> result = collect(db.command(query));
    assertEquals(1, result.size());
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("uuid"));
  }

  @Test
  public void testJson2() {
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'sub': {'uuid':one.uid}}";

    List<EntityImpl> result = collect(db.command(query));
    assertEquals(1, result.size());
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub.uuid"));
  }

  @Test
  public void testJson3() {
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'sub': [{'uuid':one.uid}]}";

    List<EntityImpl> result = collect(db.command(query));
    assertEquals(1, result.size());
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub[0].uuid"));
  }

  @Test
  public void testUnique() {
    StringBuilder query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return one, two");

    List<EntityImpl> result = db.command(new CommandSQL(query.toString())).execute(db);
    assertEquals(1, result.size());

    query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return one.uid, two.uid");

    result = db.command(new CommandSQL(query.toString())).execute(db);
    assertEquals(1, result.size());
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub[0].uuid"));
  }

  @Test
  public void testManagedElements() {
    List<Identifiable> managedByB = getManagedElements("b");
    assertEquals(6, managedByB.size());
    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("b");
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (Identifiable id : managedByB) {
      EntityImpl doc = id.getRecord();
      String name = doc.field("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
  }

  private List<Identifiable> getManagedElements(String managerName) {
    String query =
        "  match {class:Employee, as:boss, where: (name = '"
            + managerName
            + "')}"
            + "  -ManagerOf->{}<-ParentDepartment-{"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return $elements";

    return db.command(new CommandSQL(query)).execute(db);
  }

  @Test
  public void testManagedPathElements() {
    List<Identifiable> managedByB = getManagedPathElements("b");
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
    for (Identifiable id : managedByB) {
      EntityImpl doc = id.getRecord();
      String name = doc.field("name");
      names.add(name);
    }
    assertEquals(expectedNames, names);
  }

  @Test
  public void testOptional() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, as: person} -NonExistingEdge-> {as:b, optional:true} return"
                    + " person, b.name"));
    assertEquals(6, qResult.size());
    for (EntityImpl doc : qResult) {
      assertEquals(2, doc.fieldNames().length);
      Identifiable personId = doc.field("person");
      EntityImpl person = personId.getRecord();
      String name = person.field("name");
      assertTrue(name.startsWith("n"));
    }
  }

  @Test
  public void testOptional2() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "match {class:Person, as: person} --> {as:b, optional:true, where:(nonExisting ="
                    + " 12)} return person, b.name"));
    assertEquals(6, qResult.size());
    for (EntityImpl doc : qResult) {
      assertEquals(2, doc.fieldNames().length);
      Identifiable personId = doc.field("person");
      EntityImpl person = personId.getRecord();
      String name = person.field("name");
      assertTrue(name.startsWith("n"));
    }
  }

  @Test
  public void testOptional3() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "select friend.name as name from (match {class:Person, as:a, where:(name = 'n1' and"
                    + " 1 + 1 = 2)}.out('Friend'){as:friend, where:(name = 'n2' and 1 + 1 ="
                    + " 2)},{as:a}.out(){as:b, where:(nonExisting = 12),"
                    + " optional:true},{as:friend}.out(){as:b, optional:true} return friend)"));
    assertEquals(1, qResult.size());
    assertEquals("n2", qResult.get(0).field("name"));
  }

  @Test
  public void testAliasesWithSubquery() throws Exception {
    List<EntityImpl> qResult =
        collect(
            db.command(
                "select from ( match {class:Person, as:A} return A.name as namexx ) limit 1"));
    assertEquals(1, qResult.size());
    assertNotNull(qResult.get(0).field("namexx"));
    assertTrue(qResult.get(0).field("namexx").toString().startsWith("n"));
  }

  @Test
  public void testEvalInReturn() {
    // issue #6606
    db.command("CREATE CLASS testEvalInReturn EXTENDS V").close();
    db.command("CREATE PROPERTY testEvalInReturn.name String").close();

    db.begin();
    db.command("CREATE VERTEX testEvalInReturn SET name = 'foo'").close();
    db.command("CREATE VERTEX testEvalInReturn SET name = 'bar'").close();
    db.commit();

    List<EntityImpl> qResult =
        collect(
            db.command(
                "MATCH {class: testEvalInReturn, as: p} RETURN if(eval(\"p.name = 'foo'\"), 1, 2)"
                    + " AS b"));

    assertEquals(2, qResult.size());
    int sum = 0;
    for (EntityImpl doc : qResult) {
      sum += ((Number) doc.field("b")).intValue();
    }
    assertEquals(3, sum);

    qResult =
        collect(
            db.command(
                "MATCH {class: testEvalInReturn, as: p} RETURN if(eval(\"p.name = 'foo'\"), 'foo',"
                    + " 'foo') AS b"));

    assertEquals(2, qResult.size());
  }

  @Test
  public void testCheckClassAsCondition() {

    db.command("CREATE CLASS testCheckClassAsCondition EXTENDS V").close();
    db.command("CREATE CLASS testCheckClassAsCondition1 EXTENDS V").close();
    db.command("CREATE CLASS testCheckClassAsCondition2 EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX testCheckClassAsCondition SET name = 'foo'").close();
    db.command("CREATE VERTEX testCheckClassAsCondition1 SET name = 'bar'").close();
    for (int i = 0; i < 5; i++) {
      db.command("CREATE VERTEX testCheckClassAsCondition2 SET name = 'baz'").close();
    }
    db.command(
            "CREATE EDGE E FROM (select from testCheckClassAsCondition where name = 'foo') to"
                + " (select from testCheckClassAsCondition1)")
        .close();
    db.command(
            "CREATE EDGE E FROM (select from testCheckClassAsCondition where name = 'foo') to"
                + " (select from testCheckClassAsCondition2)")
        .close();
    db.commit();

    List<EntityImpl> qResult =
        collect(
            db.command(
                "MATCH {class: testCheckClassAsCondition, as: p} -E- {class:"
                    + " testCheckClassAsCondition1, as: q} RETURN $elements"));

    assertEquals(2, qResult.size());
  }

  @Test
  public void testInstanceof() {

    List<EntityImpl> qResult =
        collect(
            db.command(
                "MATCH {class: Person, as: p, where: ($currentMatch instanceof 'Person')} return"
                    + " $elements limit 1"));
    assertEquals(1, qResult.size());

    qResult =
        collect(
            db.command(
                "MATCH {class: Person, as: p, where: ($currentMatch instanceof 'V')} return"
                    + " $elements limit 1"));
    assertEquals(1, qResult.size());

    qResult =
        collect(
            db.command(
                "MATCH {class: Person, as: p, where: (not ($currentMatch instanceof 'Person'))}"
                    + " return $elements limit 1"));
    assertEquals(0, qResult.size());

    qResult =
        collect(
            db.command(
                "MATCH {class: Person, where: (name = 'n1')}.out(){as:p, where: ($currentMatch"
                    + " instanceof 'Person')} return $elements limit 1"));
    assertEquals(1, qResult.size());

    qResult =
        collect(
            db.command(
                "MATCH {class: Person, where: (name = 'n1')}.out(){as:p, where: ($currentMatch"
                    + " instanceof 'Person' and '$currentMatch' <> '@this')} return $elements limit"
                    + " 1"));
    assertEquals(1, qResult.size());

    qResult =
        collect(
            db.command(
                "MATCH {class: Person, where: (name = 'n1')}.out(){as:p, where: ( not"
                    + " ($currentMatch instanceof 'Person'))} return $elements limit 1"));
    assertEquals(0, qResult.size());
  }

  @Test
  public void testBigEntryPoint() {
    // issue #6890

    Schema schema = db.getMetadata().getSchema();
    schema.createClass("testBigEntryPoint1");
    schema.createClass("testBigEntryPoint2");

    for (int i = 0; i < 1000; i++) {
      db.begin();
      EntityImpl doc = db.newInstance("testBigEntryPoint1");
      doc.field("a", i);
      doc.save();
      db.commit();
    }

    db.begin();
    EntityImpl doc = db.newInstance("testBigEntryPoint2");
    doc.field("b", "b");
    doc.save();
    db.commit();

    List<EntityImpl> qResult =
        collect(
            db.command(
                "MATCH {class: testBigEntryPoint1, as: a}, {class: testBigEntryPoint2, as: b}"
                    + " return $elements limit 1"));
    assertEquals(1, qResult.size());
  }

  @Test
  public void testMatched1() {
    // issue #6931
    db.command("CREATE CLASS testMatched1_Foo EXTENDS V").close();
    db.command("CREATE CLASS testMatched1_Bar EXTENDS V").close();
    db.command("CREATE CLASS testMatched1_Baz EXTENDS V").close();
    db.command("CREATE CLASS testMatched1_Far EXTENDS V").close();
    db.command("CREATE CLASS testMatched1_Foo_Bar EXTENDS E").close();
    db.command("CREATE CLASS testMatched1_Bar_Baz EXTENDS E").close();
    db.command("CREATE CLASS testMatched1_Foo_Far EXTENDS E").close();

    db.begin();
    db.command("CREATE VERTEX testMatched1_Foo SET name = 'foo'").close();
    db.command("CREATE VERTEX testMatched1_Bar SET name = 'bar'").close();
    db.command("CREATE VERTEX testMatched1_Baz SET name = 'baz'").close();
    db.command("CREATE VERTEX testMatched1_Far SET name = 'far'").close();

    db.command(
            "CREATE EDGE testMatched1_Foo_Bar FROM (SELECT FROM testMatched1_Foo) TO (SELECT FROM"
                + " testMatched1_Bar)")
        .close();
    db.command(
            "CREATE EDGE testMatched1_Bar_Baz FROM (SELECT FROM testMatched1_Bar) TO (SELECT FROM"
                + " testMatched1_Baz)")
        .close();
    db.command(
            "CREATE EDGE testMatched1_Foo_Far FROM (SELECT FROM testMatched1_Foo) TO (SELECT FROM"
                + " testMatched1_Far)")
        .close();
    db.commit();

    ResultSet result =
        db.query(
            "MATCH \n"
                + "{class: testMatched1_Foo, as: foo}.out('testMatched1_Foo_Bar') {as: bar}, \n"
                + "{class: testMatched1_Bar,as: bar}.out('testMatched1_Bar_Baz') {as: baz}, \n"
                + "{class: testMatched1_Foo,as: foo}.out('testMatched1_Foo_Far') {where:"
                + " ($matched.baz IS null),as: far}\n"
                + "RETURN $matches");
    assertFalse(result.hasNext());

    result =
        db.query(
            "MATCH \n"
                + "{class: testMatched1_Foo, as: foo}.out('testMatched1_Foo_Bar') {as: bar}, \n"
                + "{class: testMatched1_Bar,as: bar}.out('testMatched1_Bar_Baz') {as: baz}, \n"
                + "{class: testMatched1_Foo,as: foo}.out('testMatched1_Foo_Far') {where:"
                + " ($matched.baz IS not null),as: far}\n"
                + "RETURN $matches");
    assertEquals(1, result.stream().count());
  }

  @Test
  public void testDependencyOrdering1() {
    // issue #6931
    db.command("CREATE CLASS testDependencyOrdering1_Foo EXTENDS V").close();
    db.command("CREATE CLASS testDependencyOrdering1_Bar EXTENDS V").close();
    db.command("CREATE CLASS testDependencyOrdering1_Baz EXTENDS V").close();
    db.command("CREATE CLASS testDependencyOrdering1_Far EXTENDS V").close();
    db.command("CREATE CLASS testDependencyOrdering1_Foo_Bar EXTENDS E").close();
    db.command("CREATE CLASS testDependencyOrdering1_Bar_Baz EXTENDS E").close();
    db.command("CREATE CLASS testDependencyOrdering1_Foo_Far EXTENDS E").close();

    db.begin();
    db.command("CREATE VERTEX testDependencyOrdering1_Foo SET name = 'foo'").close();
    db.command("CREATE VERTEX testDependencyOrdering1_Bar SET name = 'bar'").close();
    db.command("CREATE VERTEX testDependencyOrdering1_Baz SET name = 'baz'").close();
    db.command("CREATE VERTEX testDependencyOrdering1_Far SET name = 'far'").close();

    db.command(
            "CREATE EDGE testDependencyOrdering1_Foo_Bar FROM (SELECT FROM"
                + " testDependencyOrdering1_Foo) TO (SELECT FROM testDependencyOrdering1_Bar)")
        .close();
    db.command(
            "CREATE EDGE testDependencyOrdering1_Bar_Baz FROM (SELECT FROM"
                + " testDependencyOrdering1_Bar) TO (SELECT FROM testDependencyOrdering1_Baz)")
        .close();
    db.command(
            "CREATE EDGE testDependencyOrdering1_Foo_Far FROM (SELECT FROM"
                + " testDependencyOrdering1_Foo) TO (SELECT FROM testDependencyOrdering1_Far)")
        .close();
    db.commit();

    // The correct but non-obvious execution order here is:
    // foo, bar, far, baz
    // This is a test to ensure that the query scheduler resolves dependencies correctly,
    // even if they are unusual or contrived.
    List result =
        db.query(
            new SQLSynchQuery(
                "MATCH {\n"
                    + "    class: testDependencyOrdering1_Foo,\n"
                    + "    as: foo\n"
                    + "}.out('testDependencyOrdering1_Foo_Far') {\n"
                    + "    optional: true,\n"
                    + "    where: ($matched.bar IS NOT null),\n"
                    + "    as: far\n"
                    + "}, {\n"
                    + "    as: foo\n"
                    + "}.out('testDependencyOrdering1_Foo_Bar') {\n"
                    + "    where: ($matched.foo IS NOT null),\n"
                    + "    as: bar\n"
                    + "}.out('testDependencyOrdering1_Bar_Baz') {\n"
                    + "    where: ($matched.far IS NOT null),\n"
                    + "    as: baz\n"
                    + "} RETURN $matches"));
    assertEquals(1, result.size());
  }

  @Test
  public void testCircularDependency() {
    // issue #6931
    db.command("CREATE CLASS testCircularDependency_Foo EXTENDS V").close();
    db.command("CREATE CLASS testCircularDependency_Bar EXTENDS V").close();
    db.command("CREATE CLASS testCircularDependency_Baz EXTENDS V").close();
    db.command("CREATE CLASS testCircularDependency_Far EXTENDS V").close();
    db.command("CREATE CLASS testCircularDependency_Foo_Bar EXTENDS E").close();
    db.command("CREATE CLASS testCircularDependency_Bar_Baz EXTENDS E").close();
    db.command("CREATE CLASS testCircularDependency_Foo_Far EXTENDS E").close();

    db.begin();
    db.command("CREATE VERTEX testCircularDependency_Foo SET name = 'foo'").close();
    db.command("CREATE VERTEX testCircularDependency_Bar SET name = 'bar'").close();
    db.command("CREATE VERTEX testCircularDependency_Baz SET name = 'baz'").close();
    db.command("CREATE VERTEX testCircularDependency_Far SET name = 'far'").close();

    db.command(
            "CREATE EDGE testCircularDependency_Foo_Bar FROM (SELECT FROM"
                + " testCircularDependency_Foo) TO (SELECT FROM testCircularDependency_Bar)")
        .close();
    db.command(
            "CREATE EDGE testCircularDependency_Bar_Baz FROM (SELECT FROM"
                + " testCircularDependency_Bar) TO (SELECT FROM testCircularDependency_Baz)")
        .close();
    db.command(
            "CREATE EDGE testCircularDependency_Foo_Far FROM (SELECT FROM"
                + " testCircularDependency_Foo) TO (SELECT FROM testCircularDependency_Far)")
        .close();
    db.commit();

    // The circular dependency here is:
    // - far depends on baz
    // - baz depends on bar
    // - bar depends on far
    SQLSynchQuery query =
        new SQLSynchQuery(
            "MATCH {\n"
                + "    class: testCircularDependency_Foo,\n"
                + "    as: foo\n"
                + "}.out('testCircularDependency_Foo_Far') {\n"
                + "    where: ($matched.baz IS NOT null),\n"
                + "    as: far\n"
                + "}, {\n"
                + "    as: foo\n"
                + "}.out('testCircularDependency_Foo_Bar') {\n"
                + "    where: ($matched.far IS NOT null),\n"
                + "    as: bar\n"
                + "}.out('testCircularDependency_Bar_Baz') {\n"
                + "    where: ($matched.bar IS NOT null),\n"
                + "    as: baz\n"
                + "} RETURN $matches");

    try {
      db.query(query);
      fail();
    } catch (CommandExecutionException x) {
      // passed the test
    }
  }

  @Test
  public void testUndefinedAliasDependency() {
    // issue #6931
    db.command("CREATE CLASS testUndefinedAliasDependency_Foo EXTENDS V").close();
    db.command("CREATE CLASS testUndefinedAliasDependency_Bar EXTENDS V").close();
    db.command("CREATE CLASS testUndefinedAliasDependency_Foo_Bar EXTENDS E").close();

    db.begin();
    db.command("CREATE VERTEX testUndefinedAliasDependency_Foo SET name = 'foo'").close();
    db.command("CREATE VERTEX testUndefinedAliasDependency_Bar SET name = 'bar'").close();

    db.command(
            "CREATE EDGE testUndefinedAliasDependency_Foo_Bar FROM (SELECT FROM"
                + " testUndefinedAliasDependency_Foo) TO (SELECT FROM"
                + " testUndefinedAliasDependency_Bar)")
        .close();
    db.commit();

    // "bar" in the following query declares a dependency on the alias "baz", which doesn't exist.
    SQLSynchQuery query =
        new SQLSynchQuery(
            "MATCH {\n"
                + "    class: testUndefinedAliasDependency_Foo,\n"
                + "    as: foo\n"
                + "}.out('testUndefinedAliasDependency_Foo_Bar') {\n"
                + "    where: ($matched.baz IS NOT null),\n"
                + "    as: bar\n"
                + "} RETURN $matches");

    try {
      db.query(query);
      fail();
    } catch (CommandExecutionException x) {
      // passed the test
    }
  }

  @Test
  public void testCyclicDeepTraversal() {
    db.command("CREATE CLASS testCyclicDeepTraversalV EXTENDS V").close();
    db.command("CREATE CLASS testCyclicDeepTraversalE EXTENDS E").close();

    db.begin();
    db.command("CREATE VERTEX testCyclicDeepTraversalV SET name = 'a'").close();
    db.command("CREATE VERTEX testCyclicDeepTraversalV SET name = 'b'").close();
    db.command("CREATE VERTEX testCyclicDeepTraversalV SET name = 'c'").close();
    db.command("CREATE VERTEX testCyclicDeepTraversalV SET name = 'z'").close();

    // a -> b -> z
    // z -> c -> a
    db.command(
            "CREATE EDGE testCyclicDeepTraversalE from(select from testCyclicDeepTraversalV where"
                + " name = 'a') to (select from testCyclicDeepTraversalV where name = 'b')")
        .close();

    db.command(
            "CREATE EDGE testCyclicDeepTraversalE from(select from testCyclicDeepTraversalV where"
                + " name = 'b') to (select from testCyclicDeepTraversalV where name = 'z')")
        .close();

    db.command(
            "CREATE EDGE testCyclicDeepTraversalE from(select from testCyclicDeepTraversalV where"
                + " name = 'z') to (select from testCyclicDeepTraversalV where name = 'c')")
        .close();

    db.command(
            "CREATE EDGE testCyclicDeepTraversalE from(select from testCyclicDeepTraversalV where"
                + " name = 'c') to (select from testCyclicDeepTraversalV where name = 'a')")
        .close();
    db.commit();

    String query =
        "MATCH {\n"
            + "    class: testCyclicDeepTraversalV,\n"
            + "    as: foo,\n"
            + "    where: (name = 'a')\n"
            + "}.out() {\n"
            + "    while: ($depth < 2),\n"
            + "    where: (name = 'z'),\n"
            + "    as: bar\n"
            + "}, {\n"
            + "    as: bar\n"
            + "}.out() {\n"
            + "    while: ($depth < 2),\n"
            + "    as: foo\n"
            + "} RETURN $patterns";

    ResultSet result = db.query(query);
    assertEquals(1, result.stream().count());
  }

  private List<Identifiable> getManagedPathElements(String managerName) {
    String query =
        "  match {class:Employee, as:boss, where: (name = '"
            + managerName
            + "')}"
            + "  -ManagerOf->{}<-ParentDepartment-{"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return $pathElements";

    return db.command(new CommandSQL(query)).execute(db);
  }

  private List<EntityImpl> collect(ResultSet set) {
    return set.stream()
        .map(
            (r) -> {
              if (r.isEntity()) {
                return (EntityImpl) r.getEntity().get();
              } else {
                return (EntityImpl) r.toEntity();
              }
            })
        .collect(Collectors.toList());
  }

  private List<Identifiable> collectIdentifiable(ResultSet set) {
    return set.stream().map((r) -> (EntityImpl) r.toEntity()).collect(Collectors.toList());
  }
}
