package com.jetbrains.youtrack.db.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Test;

public class LinkSetMatchStatementExecutionTest extends DbTestBase {

  public void beforeTest() throws Exception {
    super.beforeTest();
    session.execute("CREATE class Person").close();
    session.begin();

    session.execute("INSERT INTO Person set name = 'n1'").close();
    session.execute("INSERT INTO Person set name = 'n2'").close();
    session.execute("INSERT INTO Person set name = 'n3'").close();
    session.execute("INSERT INTO Person set name = 'n4'").close();
    session.execute("INSERT INTO Person set name = 'n5'").close();
    session.execute("INSERT INTO Person set name = 'n6'").close();

    var friendList =
        new String[][]{{"n1", "n2"}, {"n1", "n3"}, {"n2", "n4"}, {"n4", "n5"}, {"n4", "n6"}};

    for (var pair : friendList) {
      var fromPersons = session.query("select from Person where name = ?", pair[0]).entityStream()
          .toList();

      for (var fromPerson : fromPersons) {
        var toPersons = session.query("select from Person where name = ?", pair[1]).toRidList();
        fromPerson.getOrCreateLinkSet("friends").addAll(toPersons);
      }
    }

    session.commit();

    session.execute("CREATE class MathOp").close();

    session.begin();
    session.execute("INSERT INTO MathOp set a = 1, b = 3, c = 2").close();
    session.execute("INSERT INTO MathOp set a = 5, b = 3, c = 2").close();
    session.commit();

    initOrgChart();
//
//    initTriangleTest();
//
//    initEdgeIndexTest();
//
//    initDiamondTest();
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
      var person = transaction.loadEntity(personId);
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
  public void testCommonFriends() {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name"
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
                + " 'n1')}-friends-{as:friend}-friends-{class: Person, where:(name = 'n4')}"
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
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name"
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
            "match {class:Person, where:(name = 'n1')}-friends-{as:friend}-friends-{class:"
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
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name"
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
            "match {class:Person, where:(name = 'n1')}-friends-{as:friend}-friends-{class:"
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
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name"
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
            "match {class:Person, where:(name = 'n1')}-friends-{as:friend}-friends-{class:"
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
                + " 'n1')}.both('friends'){as:friend}.both('friends'){class: Person, where:(name"
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
            "match {class:Person, where:(name = 'n1')}-friends-{as:friend}-friends-{class:"
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
                + " 'n1')}.out('friends').out('friends'){as:friend} return $matches)").toList();
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
                + " 'n1')}-friends->{}-friends->{as:friend} return $matches)").toList();
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
                + " me}.both('friends').both('friends'){as:friend, where: ($matched.me !="
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
                + " me}-friends-{}-friends-{as:friend, where: ($matched.me != $currentMatch)}"
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
                + " = 2)}.out('friends'){as:friend, where:(name = 'n2' and 1 + 1 = 2)} return"
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
                + " = 2)}-friends->{as:friend, where:(name = 'n2' and 1 + 1 = 2)} return"
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
                    + " 'n1')}.out('friends'){as:friend, while: ($depth < 1)} return friend)")
            .toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, while: ($depth < 2), where: ($depth=1) }"
                + " return friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, while: ($depth < 4), where: ($depth=1) }"
                + " return friend)").toList();
    assertEquals(2, qResult.size());

    qResult = session.query(
        "select friend.name as name from (match {class:Person, where:(name ="
            + " 'n1')}.out('friends'){as:friend, while: (true) } return friend)").toList();
    assertEquals(6, qResult.size());

    qResult =
        session.query(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('friends'){as:friend, while: (true) } return friend limit 3)")
            .toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
                "select friend.name as name from (match {class:Person, where:(name ="
                    + " 'n1')}.out('friends'){as:friend, while: (true) } return friend) limit 3")
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
                + " 'n1')}-friends->{as:friend, while: ($depth < 1)} return friend)").toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, while: ($depth < 2), where: ($depth=1) } return"
                + " friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, while: ($depth < 4), where: ($depth=1) } return"
                + " friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, while: (true) } return friend)").toList();
    assertEquals(6, qResult.size());
    session.commit();
  }

  @Test
  public void testMaxDepth() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, maxDepth: 1, where: ($depth=1) } return"
                + " friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, maxDepth: 1 } return friend)").toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, maxDepth: 0 } return friend)").toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('friends'){as:friend, maxDepth: 1, where: ($depth > 0) } return"
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
                + " 'n1')}-friends->{as:friend, maxDepth: 1, where: ($depth=1) } return"
                + " friend)").toList();
    assertEquals(2, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, maxDepth: 1 } return friend)").toList();
    assertEquals(3, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, maxDepth: 0 } return friend)").toList();
    assertEquals(1, qResult.size());

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-friends->{as:friend, maxDepth: 1, where: ($depth > 0) } return"
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
            + "  .out('worksAt')"
            + "  .out('parentDepartment'){"
            + "      while: (in('managerOf').size() == 0),"
            + "      where: (in('managerOf').size() > 0)"
            + "  }"
            + "  .in('managerOf'){as: manager}"
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
            + "  -worksAt->{}-parentDepartment->{"
            + "      while: (in('managerOf').size() == 0),"
            + "      where: (in('managerOf').size() > 0)"
            + "  }<-managerOf-{as: manager}"
            + "  return manager"
            + ")";

    session.begin();
    var qResult = session.query(query).toList();
    assertEquals(1, qResult.size());
    return qResult.getFirst().asEntity();
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

    session.execute("CREATE class Employee").close();
    session.execute("CREATE class Department").close();

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
      session.execute("INSERT INTO Department set name = 'department" + i + "' ").close();
    }

    for (var parent = 0; parent < deptHierarchy.length; parent++) {
      var children = deptHierarchy[parent];
      for (var child : children) {
        var childDepartment = session.query(
                "select from Department where name = 'department" + child + "'")
            .entityStream().findFirst().orElseThrow();
        var parentDepartment = session.query(
                "select from Department where name = 'department" + parent + "'")
            .entityStream().findFirst().orElseThrow();
        childDepartment.setLink("parentDepartment", parentDepartment);
      }
    }

    for (var dept = 0; dept < deptManagers.length; dept++) {
      var managerName = deptManagers[dept];
      if (managerName != null) {
        var manager =
            session.execute("INSERT INTO Employee set name = '" + managerName + "' ").
                entityStream().findFirst().orElseThrow();
        var department = session.query(
                "select from Department where name = 'department" + dept + "'")
            .entityStream().findFirst().orElseThrow();
        manager.setLink("managerOf", department);
      }
    }

    for (var dept = 0; dept < employees.length; dept++) {
      var employeesForDept = employees[dept];
      for (var employeeName : employeesForDept) {
        var employee = session.execute("INSERT INTO Employee set name = '" + employeeName + "' ")
            .entityStream().findFirst().orElseThrow();
        var department = session.query(
                "select from Department where name = 'department" + dept + "'").entityStream()
            .findFirst()
            .orElseThrow();
        employee.setLink("worksAt", department);
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

}
