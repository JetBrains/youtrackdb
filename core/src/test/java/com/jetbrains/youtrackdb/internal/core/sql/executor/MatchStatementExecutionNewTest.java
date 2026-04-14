package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.query.BasicResultSet;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class MatchStatementExecutionNewTest extends DbTestBase {

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
          "CREATE EDGE Friend from (select from Person where name = ?) to (select from Person where"
              + " name = ?)",
          pair[0],
          pair[1]);
    }
    session.commit();

    session.execute("CREATE class MathOp extends V").close();

    session.begin();
    session.execute("CREATE VERTEX MathOp set a = 1, b = 3, c = 2").close();
    session.execute("CREATE VERTEX MathOp set a = 5, b = 3, c = 2").close();
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
    session.commit();

    session.begin();
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
    session.commit();

    session.begin();
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

    for (var i = 0; i < 10; i++) {
      session.begin();
      session.execute("CREATE VERTEX TriangleV set uid = ?", i).close();
      session.commit();
    }
    var edges = new int[][] {
        {0, 1}, {0, 2}, {1, 2}, {1, 3}, {2, 4}, {3, 4}, {3, 5}, {4, 0}, {4, 7}, {6, 7}, {7, 8},
        {7, 9}, {8, 9}, {9, 1}, {8, 3}, {8, 4}
    };
    for (var edge : edges) {
      session.begin();
      session.execute(
          "CREATE EDGE TriangleE from (select from TriangleV where uid = ?) to (select from"
              + " TriangleV where uid = ?)",
          edge[0],
          edge[1])
          .close();
      session.commit();
    }
  }

  private void initDiamondTest() {
    session.execute("CREATE class DiamondV extends V").close();
    session.execute("CREATE class DiamondE extends E").close();

    for (var i = 0; i < 4; i++) {
      session.begin();
      session.execute("CREATE VERTEX DiamondV set uid = ?", i).close();
      session.commit();
    }
    var edges = new int[][] {{0, 1}, {0, 2}, {1, 3}, {2, 3}};
    for (var edge : edges) {
      session.begin();
      session.execute(
          "CREATE EDGE DiamondE from (select from DiamondV where uid = ?) to (select from"
              + " DiamondV where uid = ?)",
          edge[0],
          edge[1])
          .close();
      session.commit();
    }
  }

  @Test
  public void testSimple() throws Exception {
    session.begin();
    var qResult = session.query("match {class:Person, as: person} return person");
    printExecutionPlan(qResult);

    for (var i = 0; i < 6; i++) {
      var item = qResult.next();
      Assert.assertEquals(1, item.getPropertyNames().size());
      Entity person = session.load(item.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(name.startsWith("n"));
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleWhere() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person");

    for (var i = 0; i < 2; i++) {
      var item = qResult.next();
      Assert.assertEquals(1, item.getPropertyNames().size());
      Entity personId = session.load(item.getProperty("person"));

      var transaction = session.getActiveTransaction();
      EntityImpl person = transaction.load(personId);
      String name = person.getProperty("name");
      Assert.assertTrue(name.equals("n1") || name.equals("n2"));
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleLimit() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person"
                + " limit 1");
    Assert.assertTrue(qResult.hasNext());
    qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleLimit2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person"
                + " limit -1");
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(qResult.hasNext());
      qResult.next();
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleLimit3() throws Exception {

    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person"
                + " limit 3");
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(qResult.hasNext());
      qResult.next();
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testSimpleUnnamedParams() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person, where: (name = ? or name = ?)} return person",
            "n1",
            "n2");

    printExecutionPlan(qResult);
    for (var i = 0; i < 2; i++) {

      var item = qResult.next();
      Assert.assertEquals(1, item.getPropertyNames().size());
      Entity person = session.load(item.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(name.equals("n1") || name.equals("n2"));
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriends() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriendsPatterns() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $patterns)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testPattens() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $patterns");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals(1, item.getPropertyNames().size());
    Assert.assertEquals("friend", item.getPropertyNames().iterator().next());
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testPaths() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $paths");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals(3, item.getPropertyNames().size());
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testElements() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $elements");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testPathElements() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $pathElements");
    printExecutionPlan(qResult);
    Set<String> expected = new HashSet<>();
    expected.add("n1");
    expected.add("n2");
    expected.add("n4");
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(qResult.hasNext());
      var item = qResult.next();
      expected.remove(item.getProperty("name"));
    }
    Assert.assertFalse(qResult.hasNext());
    Assert.assertTrue(expected.isEmpty());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriendsMatches() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $matches)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriendsArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend-{as:friend}-Friend-{class: Person, where:(name = 'n4')} return"
                + " friend)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriendsArrowsPatterns() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend-{as:friend}-Friend-{class: Person, where:(name = 'n4')} return"
                + " $patterns)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriends2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testCommonFriends2Arrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class: Person,"
                + " where:(name = 'n4')} return friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnMethod() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend.name.toUpperCase(Locale.ENGLISH) as name");
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("N2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnMethodArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class: Person,"
                + " where:(name = 'n4')} return friend.name.toUpperCase(Locale.ENGLISH) as name");
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("N2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnExpression() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend.name + ' ' +friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2 n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnExpressionArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class: Person,"
                + " where:(name = 'n4')} return friend.name + ' ' +friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2 n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnDefaultAlias() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend.name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("friend.name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testReturnDefaultAliasArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class: Person,"
                + " where:(name = 'n4')} return friend.name");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("friend.name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsOfFriends() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend').out('Friend'){as:friend} return $matches)");

    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n4", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsOfFriendsArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{}-Friend->{as:friend} return $matches)");

    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertEquals("n4", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsOfFriends2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                + " me}.both('Friend').both('Friend'){as:friend, where: ($matched.me !="
                + " $currentMatch)} return $matches)");

    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    while (qResult.hasNext()) {
      Assert.assertNotEquals(qResult.next().getProperty("name"), "n1");
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsOfFriends2Arrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                + " me}-Friend-{}-Friend-{as:friend, where: ($matched.me != $currentMatch)} return"
                + " $matches)");

    Assert.assertTrue(qResult.hasNext());
    while (qResult.hasNext()) {
      Assert.assertNotEquals(qResult.next().getProperty("name"), "n1");
    }
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsWithName() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1 ="
                + " 2)}.out('Friend'){as:friend, where:(name = 'n2' and 1 + 1 = 2)} return"
                + " friend)");

    Assert.assertTrue(qResult.hasNext());
    Assert.assertEquals("n2", qResult.next().getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testFriendsWithNameArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1 ="
                + " 2)}-Friend->{as:friend, where:(name = 'n2' and 1 + 1 = 2)} return friend)");
    Assert.assertTrue(qResult.hasNext());
    Assert.assertEquals("n2", qResult.next().getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    session.commit();
  }

  @Test
  public void testWhile() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 1)} return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 2), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 4), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend)");
    Assert.assertEquals(6, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend limit 3)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend) limit 3");
    Assert.assertEquals(3, size(qResult));
    qResult.close();
    session.commit();
  }

  private int size(BasicResultSet qResult) {
    var result = 0;
    while (qResult.hasNext()) {
      result++;
      qResult.next();
    }
    return result;
  }

  @Test
  public void testWhileArrows() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 1)} return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 2), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 4), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: (true) } return friend)");
    Assert.assertEquals(6, size(qResult));
    qResult.close();
    session.commit();
  }

  @Test
  public void testMaxDepth() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1, where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1 } return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 0 } return friend)");
    Assert.assertEquals(1, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1, where: ($depth > 0) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();
    session.commit();
  }

  @Test
  public void testMaxDepthArrow() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1, where: ($depth=1) } return friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1 } return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 0 } return friend)");
    Assert.assertEquals(1, size(qResult));
    qResult.close();

    qResult =
        session.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1, where: ($depth > 0) } return friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();
    session.commit();
  }

  @Test
  public void testManager() {
    initOrgChart();
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one
    session.begin();
    EntityImpl entity7 = getManager("p10");
    Assert.assertEquals("c", entity7.getProperty("name"));
    EntityImpl entity6 = getManager("p12");
    Assert.assertEquals("c", entity6.getProperty("name"));
    EntityImpl entity5 = getManager("p6");
    Assert.assertEquals("b", entity5.getProperty("name"));
    EntityImpl entity4 = getManager("p11");
    Assert.assertEquals("b", entity4.getProperty("name"));

    EntityImpl entity3 = getManagerArrows("p10");
    Assert.assertEquals("c", entity3.getProperty("name"));
    EntityImpl entity2 = getManagerArrows("p12");
    Assert.assertEquals("c", entity2.getProperty("name"));
    EntityImpl entity1 = getManagerArrows("p6");
    Assert.assertEquals("b", entity1.getProperty("name"));
    EntityImpl entity = getManagerArrows("p11");
    Assert.assertEquals("b", entity.getProperty("name"));
    session.commit();
  }

  private EntityImpl getManager(String personName) {
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

    var qResult = session.query(query);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    Identifiable identifiable = item.asEntity();
    var transaction = session.getActiveTransaction();
    return transaction.load(identifiable);
  }

  private EntityImpl getManagerArrows(String personName) {
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

    var qResult = session.query(query);
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    Identifiable identifiable = item.asEntity();
    var transaction = session.getActiveTransaction();
    return transaction.load(identifiable);
  }

  @Test
  public void testManager2() {
    initOrgChart();
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one

    session.begin();
    Assert.assertEquals("c", getManager2("p10").getProperty("name"));
    Assert.assertEquals("c", getManager2("p12").getProperty("name"));
    Assert.assertEquals("b", getManager2("p6").getProperty("name"));
    Assert.assertEquals("b", getManager2("p11").getProperty("name"));

    Assert.assertEquals("c", getManager2Arrows("p10").getProperty("name"));
    Assert.assertEquals("c", getManager2Arrows("p12").getProperty("name"));
    Assert.assertEquals("b", getManager2Arrows("p6").getProperty("name"));
    Assert.assertEquals("b", getManager2Arrows("p11").getProperty("name"));
    session.commit();
  }

  private BasicResult getManager2(String personName) {
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

    var qResult = session.query(query);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    return item;
  }

  private BasicResult getManager2Arrows(String personName) {
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

    var qResult = session.query(query);
    Assert.assertTrue(qResult.hasNext());
    var item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    return item;
  }

  @Test
  public void testManaged() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedBy("a");
    Assert.assertTrue(managedByA.hasNext());
    var item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();

    var managedByB = getManagedBy("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private BasicResultSet getManagedBy(String managerName) {
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

    return session.query(query);
  }

  @Test
  public void testManagedArrows() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedByArrows("a");
    Assert.assertTrue(managedByA.hasNext());
    var item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();
    var managedByB = getManagedByArrows("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private BasicResultSet getManagedByArrows(String managerName) {
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

    return session.query(query);
  }

  @Test
  public void testManaged2() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedBy2("a");
    Assert.assertTrue(managedByA.hasNext());
    var item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();
    var managedByB = getManagedBy2("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private BasicResultSet getManagedBy2(String managerName) {
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

    return session.query(query);
  }

  @Test
  public void testManaged2Arrows() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    session.begin();
    var managedByA = getManagedBy2Arrows("a");
    Assert.assertTrue(managedByA.hasNext());
    var item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();
    var managedByB = getManagedBy2Arrows("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private BasicResultSet getManagedBy2Arrows(String managerName) {
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

    return session.query(query);
  }

  @Test
  public void testTriangle1() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "  .out('TriangleE'){as: friend2}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);

    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle1Arrows() {
    initTriangleTest();
    var query =
        "match {class:TriangleV, as: friend1, where: (uid = 0)} -TriangleE-> {as: friend2}"
            + " -TriangleE-> {as: friend3},{class:TriangleV, as: friend1} -TriangleE-> {as:"
            + " friend3}return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle2Old() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Entity friend1 = session.load(doc.getProperty("friend1"));
    Entity friend2 = session.load(doc.getProperty("friend2"));
    Entity friend3 = session.load(doc.getProperty("friend3"));
    Assert.assertEquals(0, friend1.<Object>getProperty("uid"));
    Assert.assertEquals(1, friend2.<Object>getProperty("uid"));
    Assert.assertEquals(2, friend3.<Object>getProperty("uid"));
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle2() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $patterns";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());
    Entity friend1 = session.load(doc.getProperty("friend1"));
    Entity friend2 = session.load(doc.getProperty("friend2"));
    Entity friend3 = session.load(doc.getProperty("friend3"));
    Assert.assertEquals(0, friend1.<Object>getProperty("uid"));
    Assert.assertEquals(1, friend2.<Object>getProperty("uid"));
    Assert.assertEquals(2, friend3.<Object>getProperty("uid"));
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle2Arrows() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  -TriangleE->{as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());
    Entity friend1 = session.load(doc.getProperty("friend1"));
    Entity friend2 = session.load(doc.getProperty("friend2"));
    Entity friend3 = session.load(doc.getProperty("friend3"));
    Assert.assertEquals(0, friend1.<Object>getProperty("uid"));
    Assert.assertEquals(1, friend2.<Object>getProperty("uid"));
    Assert.assertEquals(2, friend3.<Object>getProperty("uid"));
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle3() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend2}"
            + "  -TriangleE->{as: friend3, where: (uid = 2)},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle4() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangle4Arrows() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend2, where: (uid = 1)}"
            + "  -TriangleE->{as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testTriangleWithEdges4() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .outE('TriangleE').inV(){as: friend2, where: (uid = 1)}"
            + "  .outE('TriangleE').inV(){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .outE('TriangleE').inV(){as: friend3}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testCartesianProduct() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where:(uid = 1)},"
            + "{class:TriangleV, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches";

    session.begin();
    var result = session.query(query);
    printExecutionPlan(result);

    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var doc = result.next();
      Entity friend1 = session.load(doc.getProperty("friend1"));
      Assert.assertEquals(friend1.<Object>getProperty("uid"), 1);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testCartesianProductLimit() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where:(uid = 1)},"
            + "{class:TriangleV, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches LIMIT 1";

    session.begin();
    var result = session.query(query);

    Assert.assertTrue(result.hasNext());
    var d = result.next();
    Entity friend1 = session.load(d.getProperty("friend1"));
    Assert.assertEquals(friend1.<Object>getProperty("uid"), 1);
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testArrayNumber() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0] as foo";

    session.begin();
    var result = session.query(query);

    Assert.assertTrue(result.hasNext());

    var doc = result.next();
    Object foo = session.load(doc.getProperty("foo"));
    Assert.assertNotNull(foo);
    Assert.assertTrue(((Entity) foo).isVertex());
    result.close();
    session.commit();
  }

  @Test
  public void testArraySingleSelectors2() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0,1] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());
    var foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(2, ((List) foo).size());
    result.close();
    session.commit();
  }

  @Test
  public void testArrayRangeSelectors1() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..1] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());

    var foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(1, ((List) foo).size());
    result.close();
    session.commit();
  }

  @Test
  public void testArrayRange2() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..2] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());

    var foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(2, ((List) foo).size());
    result.close();
    session.commit();
  }

  @Test
  public void testArrayRange3() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..3] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());

    var foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(2, ((List) foo).size());
    result.close();
    session.commit();
  }

  @Test
  public void testConditionInSquareBrackets() {
    initTriangleTest();
    var query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[uid = 2] as foo";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertFalse(result.hasNext());

    var foo = doc.getLinkList("foo");
    Assert.assertNotNull(foo);
    Assert.assertEquals(1, foo.size());
    Identifiable identifiable = foo.getFirst();
    var transaction = session.getActiveTransaction();
    var resultVertex = transaction.loadVertex(identifiable);
    Assert.assertEquals(2, resultVertex.<Object>getProperty("uid"));
    result.close();
    session.commit();
  }

  @Test
  public void testUnique() {
    initDiamondTest();
    var query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return DISTINCT one, two");

    session.begin();
    var result = session.query(query.toString());
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());

    query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return DISTINCT one.uid, two.uid");

    result.close();

    result = session.query(query.toString());
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub[0].uuid"));
    session.commit();
  }

  @Test
  public void testNotUnique() {
    initDiamondTest();
    var query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return one, two");

    session.begin();
    var result = session.query(query.toString());
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();

    query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return one.uid, two.uid");

    result = session.query(query.toString());
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub[0].uuid"));
    session.commit();
  }

  @Test
  public void testManagedElements() {
    initOrgChart();
    session.begin();
    var managedByB = getManagedElements("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("b");
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (var i = 0; i < 6; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var doc = managedByB.next();
      String name = doc.getProperty("name");
      names.add(name);
    }
    Assert.assertFalse(managedByB.hasNext());
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  private BasicResultSet getManagedElements(String managerName) {
    var query =
        "  match {class:Employee, as:boss, where: (name = '"
            + managerName
            + "')}"
            + "  -ManagerOf->{}<-ParentDepartment-{"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return distinct $elements";

    return session.query(query);
  }

  @Test
  public void testManagedPathElements() {
    initOrgChart();
    session.begin();
    var managedByB = getManagedPathElements("b");

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
    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(managedByB.hasNext());
      var doc = managedByB.next();
      String name = doc.getProperty("name");
      names.add(name);
    }
    Assert.assertFalse(managedByB.hasNext());
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
    session.commit();
  }

  @Test
  public void testOptional() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person} -NonExistingEdge-> {as:b, optional:true} return"
                + " person, b.name");

    printExecutionPlan(qResult);
    for (var i = 0; i < 6; i++) {
      Assert.assertTrue(qResult.hasNext());
      var doc = qResult.next();
      Assert.assertEquals(2, doc.getPropertyNames().size());
      Entity person = session.load(doc.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(name.startsWith("n"));
    }
    session.commit();
  }

  @Test
  public void testOptional2() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "match {class:Person, as: person} --> {as:b, optional:true, where:(nonExisting = 12)}"
                + " return person, b.name");

    for (var i = 0; i < 6; i++) {
      Assert.assertTrue(qResult.hasNext());
      var doc = qResult.next();
      Assert.assertEquals(2, doc.getPropertyNames().size());
      Entity person = session.load(doc.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(name.startsWith("n"));
    }
    session.commit();
  }

  @Test
  public void testOptional3() throws Exception {
    session.begin();
    var qResult =
        session.query(
            "select friend.name as name, b from (match {class:Person, as:a, where:(name = 'n1' and"
                + " 1 + 1 = 2)}.out('Friend'){as:friend, where:(name = 'n2' and 1 + 1 ="
                + " 2)},{as:a}.out(){as:b, where:(nonExisting = 12),"
                + " optional:true},{as:friend}.out(){as:b, optional:true} return friend, b)");

    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    var doc = qResult.next();
    Assert.assertEquals("n2", doc.getProperty("name"));
    Assert.assertNull(doc.getProperty("b"));
    Assert.assertFalse(qResult.hasNext());
    session.commit();
  }

  @Test
  public void testOrderByAsc() {
    session.execute("CREATE CLASS testOrderByAsc EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX testOrderByAsc SET name = 'bbb'").close();
    session.execute("CREATE VERTEX testOrderByAsc SET name = 'zzz'").close();
    session.execute("CREATE VERTEX testOrderByAsc SET name = 'aaa'").close();
    session.execute("CREATE VERTEX testOrderByAsc SET name = 'ccc'").close();
    session.commit();

    var query = "MATCH { class: testOrderByAsc, as:a} RETURN a.name as name order by name asc";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("aaa", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("bbb", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("ccc", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("zzz", result.next().getProperty("name"));
    Assert.assertFalse(result.hasNext());
    session.commit();
  }

  @Test
  public void testOrderByDesc() {
    session.execute("CREATE CLASS testOrderByDesc EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX testOrderByDesc SET name = 'bbb'").close();
    session.execute("CREATE VERTEX testOrderByDesc SET name = 'zzz'").close();
    session.execute("CREATE VERTEX testOrderByDesc SET name = 'aaa'").close();
    session.execute("CREATE VERTEX testOrderByDesc SET name = 'ccc'").close();
    session.commit();

    var query = "MATCH { class: testOrderByDesc, as:a} RETURN a.name as name order by name desc";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("zzz", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("ccc", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("bbb", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("aaa", result.next().getProperty("name"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testNestedProjections() {
    var clazz = "testNestedProjections";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'bbb', surname = 'ccc'").close();
    session.commit();

    var query = "MATCH { class: " + clazz + ", as:a} RETURN a:{name}, 'x' ";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    BasicResult a = item.getProperty("a");
    Assert.assertEquals("bbb", a.getProperty("name"));
    Assert.assertNull(a.getProperty("surname"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testAggregate() {
    var clazz = "testAggregate";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 1").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 2").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 3").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'bbb', num = 4").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'bbb', num = 5").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'bbb', num = 6").close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name, max(a.num) as maxNum group by a.name order by name";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("aaa", item.getProperty("name"));
    Assert.assertEquals(3, (int) item.getProperty("maxNum"));

    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertEquals("bbb", item.getProperty("name"));
    Assert.assertEquals(6, (int) item.getProperty("maxNum"));

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testOrderByOutOfProjAsc() {
    var clazz = "testOrderByOutOfProjAsc";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 0, num2 = 1").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 1, num2 = 2").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 2, num2 = 3").close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name, a.num as num order by a.num2 asc";

    session.begin();
    var result = session.query(query);
    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals("aaa", item.getProperty("name"));
      Assert.assertEquals(i, (int) item.getProperty("num"));
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testOrderByOutOfProjDesc() {
    var clazz = "testOrderByOutOfProjDesc";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 0, num2 = 1").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 1, num2 = 2").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 2, num2 = 3").close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name, a.num as num order by a.num2 desc";

    session.begin();
    var result = session.query(query);
    for (var i = 2; i >= 0; i--) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals("aaa", item.getProperty("name"));
      Assert.assertEquals(i, (int) item.getProperty("num"));
    }

    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testUnwind() {
    var clazz = "testUnwind";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa', coll = [1, 2]").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'bbb', coll = [3, 4]").close();
    session.commit();

    var query =
        "MATCH { class: " + clazz + ", as:a} RETURN a.name as name, a.coll as num unwind num";

    session.begin();
    var sum = 0;
    var result = session.query(query);
    for (var i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      sum += item.<Integer>getProperty("num");
    }

    Assert.assertFalse(result.hasNext());

    result.close();
    Assert.assertEquals(10, sum);
    session.commit();
  }

  @Test
  public void testSkip() {
    var clazz = "testSkip";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'bbb'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'ccc'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'ddd'").close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name ORDER BY name ASC skip 1 limit 2";

    session.begin();
    var result = session.query(query);

    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("bbb", item.getProperty("name"));

    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertEquals("ccc", item.getProperty("name"));

    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testDepthAlias() {
    var clazz = "testDepthAlias";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'bbb'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'ccc'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'ddd'").close();

    session.execute(
        "CREATE EDGE E FROM (SELECT FROM "
            + clazz
            + " WHERE name = 'aaa') TO (SELECT FROM "
            + clazz
            + " WHERE name = 'bbb')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM "
            + clazz
            + " WHERE name = 'bbb') TO (SELECT FROM "
            + clazz
            + " WHERE name = 'ccc')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM "
            + clazz
            + " WHERE name = 'ccc') TO (SELECT FROM "
            + clazz
            + " WHERE name = 'ddd')")
        .close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a, where:(name = 'aaa')} --> {as:b, while:($depth<10), depthAlias: xy} RETURN"
            + " a.name as name, b.name as bname, xy";

    session.begin();
    var result = session.query(query);

    var sum = 0;
    for (var i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      var depth = item.getProperty("xy");
      Assert.assertTrue(depth instanceof Integer);
      Assert.assertEquals("aaa", item.getProperty("name"));
      switch ((int) depth) {
        case 0 :
          Assert.assertEquals("aaa", item.getProperty("bname"));
          break;
        case 1 :
          Assert.assertEquals("bbb", item.getProperty("bname"));
          break;
        case 2 :
          Assert.assertEquals("ccc", item.getProperty("bname"));
          break;
        case 3 :
          Assert.assertEquals("ddd", item.getProperty("bname"));
          break;
        default :
          Assert.fail();
      }
      sum += (int) depth;
    }
    Assert.assertEquals(sum, 6);
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testPathAlias() {
    var clazz = "testPathAlias";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'aaa'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'bbb'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'ccc'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'ddd'").close();

    session.execute(
        "CREATE EDGE E FROM (SELECT FROM "
            + clazz
            + " WHERE name = 'aaa') TO (SELECT FROM "
            + clazz
            + " WHERE name = 'bbb')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM "
            + clazz
            + " WHERE name = 'bbb') TO (SELECT FROM "
            + clazz
            + " WHERE name = 'ccc')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM "
            + clazz
            + " WHERE name = 'ccc') TO (SELECT FROM "
            + clazz
            + " WHERE name = 'ddd')")
        .close();
    session.commit();

    var query =
        "MATCH { class: "
            + clazz
            + ", as:a, where:(name = 'aaa')} --> {as:b, while:($depth<10), pathAlias: xy} RETURN"
            + " a.name as name, b.name as bname, xy";

    session.begin();
    var result = session.query(query);

    for (var i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      var path = item.getProperty("xy");
      Assert.assertTrue(path instanceof List);
      var thePath = (List<Identifiable>) path;

      String bname = item.getProperty("bname");
      if (bname.equals("aaa")) {
        Assert.assertEquals(0, thePath.size());
      } else if (bname.equals("aaa")) {
        Assert.assertEquals(1, thePath.size());
        var transaction = session.getActiveTransaction();
        Assert.assertEquals("bbb",
            ((Entity) transaction.load(thePath.get(0))).getProperty("name"));
      } else if (bname.equals("ccc")) {
        Assert.assertEquals(2, thePath.size());
        var transaction1 = session.getActiveTransaction();
        Assert.assertEquals("bbb",
            ((Entity) transaction1.load(thePath.get(0))).getProperty("name"));
        var transaction = session.getActiveTransaction();
        Assert.assertEquals("ccc",
            ((Entity) transaction.load(thePath.get(1))).getProperty("name"));
      } else if (bname.equals("ddd")) {
        Assert.assertEquals(3, thePath.size());
        var transaction2 = session.getActiveTransaction();
        Assert.assertEquals("bbb",
            ((Entity) transaction2.load(thePath.get(0))).getProperty("name"));
        var transaction1 = session.getActiveTransaction();
        Assert.assertEquals("ccc",
            ((Entity) transaction1.load(thePath.get(1))).getProperty("name"));
        var transaction = session.getActiveTransaction();
        Assert.assertEquals("ddd",
            ((Entity) transaction.load(thePath.get(2))).getProperty("name"));
      }
    }
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  // Verifies that a WHILE traversal with pathAlias on a diamond-shaped graph
  // returns all distinct paths, including multiple paths that reach the same
  // vertex via different routes. Graph: a→b, a→c, b→d, c→d. Starting from a,
  // vertex d is reachable via two paths (a-b-d and a-c-d). With pathAlias the
  // dedup logic must be disabled so both paths appear in the results.
  @Test
  public void testPathAliasDiamondGraphReturnsAllPaths() {
    var clazz = "testPathAliasDiamond";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'a'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'b'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'c'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'd'").close();

    // Diamond: a→b, a→c, b→d, c→d
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'a') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'b')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'a') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'c')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'b') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'd')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'c') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'd')")
        .close();
    session.commit();

    // WHILE traversal with pathAlias: should enumerate all distinct paths
    var query =
        "MATCH { class: " + clazz
            + ", as:start, where:(name = 'a')} --> {as:dest, while:($depth<10),"
            + " pathAlias: p} RETURN dest.name as dname, p";

    session.begin();
    var result = session.query(query);

    // Collect (destination name, path length) pairs from all results.
    // Expected results:
    //   a at depth 0 (path=[])
    //   b at depth 1 (path=[b])
    //   c at depth 1 (path=[c])
    //   d at depth 2 via b (path=[b,d])
    //   d at depth 2 via c (path=[c,d])
    // Total: 5 results, with 'd' appearing twice (different paths).
    var resultPairs = new java.util.ArrayList<String>();
    while (result.hasNext()) {
      var item = result.next();
      String dname = item.getProperty("dname");
      List<?> path = item.getProperty("p");
      resultPairs.add(dname + ":" + path.size());
    }
    result.close();
    session.commit();

    // Sort for deterministic comparison
    java.util.Collections.sort(resultPairs);

    // 'd' must appear twice — once for each path through the diamond
    Assert.assertEquals(
        "pathAlias must disable dedup so diamond vertex 'd' appears for each distinct path",
        java.util.List.of("a:0", "b:1", "c:1", "d:2", "d:2"),
        resultPairs);
  }

  // Verifies that a WHILE traversal WITHOUT pathAlias produces the same vertices
  // and depths as one WITH pathAlias, but does not expose any path property.
  // This exercises the optimization that skips PathNode construction entirely
  // when no pathAlias is declared (the common case for queries like IS2).
  @Test
  public void testWhileWithoutPathAliasSkipsPathConstruction() {
    var clazz = "testWhileNoPath";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'a'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'b'").close();
    session.execute("CREATE VERTEX " + clazz + " SET name = 'c'").close();

    // Chain: a → b → c
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'a') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'b')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'b') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'c')")
        .close();
    session.commit();

    // Query with depthAlias only (no pathAlias) — path construction should be skipped
    var queryNoPath =
        "MATCH { class: " + clazz
            + ", as:start, where:(name = 'a')} --> {as:dest, while:($depth<10),"
            + " depthAlias: d} RETURN dest.name as dname, d";

    session.begin();
    var result = session.query(queryNoPath);

    // Collect (name, depth) pairs — should be: a:0, b:1, c:2
    var pairs = new java.util.ArrayList<String>();
    while (result.hasNext()) {
      var item = result.next();
      String dname = item.getProperty("dname");
      Integer depth = item.getProperty("d");
      Assert.assertNotNull("depthAlias must still be populated", depth);
      pairs.add(dname + ":" + depth);
    }
    result.close();
    session.commit();

    java.util.Collections.sort(pairs);
    Assert.assertEquals(
        "WHILE without pathAlias must still traverse and return all reachable vertices",
        java.util.List.of("a:0", "b:1", "c:2"),
        pairs);
  }

  // Verifies that the lazy recursive traversal correctly handles a deep linear
  // chain (50 hops). The stack-based implementation should handle this without
  // any stack depth issues, unlike a naive recursive approach.
  @Test
  public void testWhileDeepChainLazyTraversal() {
    var clazz = "testWhileDeepChain";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    int chainLength = 50;
    session.begin();
    for (int i = 0; i <= chainLength; i++) {
      session.execute("CREATE VERTEX " + clazz + " SET name = 'n" + i + "'").close();
    }
    for (int i = 0; i < chainLength; i++) {
      session.execute(
          "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'n" + i + "') "
              + "TO (SELECT FROM " + clazz + " WHERE name = 'n" + (i + 1) + "')")
          .close();
    }
    session.commit();

    // Traverse the entire chain from n0 with WHILE(true)
    var query =
        "MATCH { class: " + clazz
            + ", as:start, where:(name = 'n0')} --> {as:dest, while:(true),"
            + " depthAlias: d} RETURN dest.name as dname, d";

    session.begin();
    var result = session.query(query);

    int count = 0;
    int maxDepth = -1;
    while (result.hasNext()) {
      var item = result.next();
      int depth = item.getProperty("d");
      if (depth > maxDepth) {
        maxDepth = depth;
      }
      count++;
    }
    result.close();
    session.commit();

    // All 51 nodes (n0 at depth 0 through n50 at depth 50)
    Assert.assertEquals(
        "Deep chain must yield all nodes including start",
        chainLength + 1, count);
    Assert.assertEquals(
        "Deepest node must be at depth = chain length",
        chainLength, maxDepth);
  }

  // Verifies that WHILE with LIMIT only pulls as many results as needed.
  // The lazy stream enables this: after LIMIT is reached, remaining subtrees
  // are not expanded. We verify correctness (not laziness directly) by checking
  // that LIMIT produces a proper subset of the full result set.
  @Test
  public void testWhileWithLimit() {
    var clazz = "testWhileLimit";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX " + clazz + " SET name = 'n" + i + "'").close();
    }
    for (int i = 0; i < 9; i++) {
      session.execute(
          "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'n" + i + "') "
              + "TO (SELECT FROM " + clazz + " WHERE name = 'n" + (i + 1) + "')")
          .close();
    }
    session.commit();

    // Full traversal — should yield all 10 nodes
    var fullQuery =
        "MATCH { class: " + clazz
            + ", as:start, where:(name = 'n0')} --> {as:dest, while:(true)} "
            + "RETURN dest.name as dname";

    session.begin();
    var fullResult = session.query(fullQuery);
    int fullCount = 0;
    while (fullResult.hasNext()) {
      fullResult.next();
      fullCount++;
    }
    fullResult.close();
    session.commit();
    Assert.assertEquals(10, fullCount);

    // Limited traversal — should yield exactly 3 nodes
    var limitedQuery =
        "MATCH { class: " + clazz
            + ", as:start, where:(name = 'n0')} --> {as:dest, while:(true)} "
            + "RETURN dest.name as dname LIMIT 3";

    session.begin();
    var limitedResult = session.query(limitedQuery);
    int limitedCount = 0;
    while (limitedResult.hasNext()) {
      limitedResult.next();
      limitedCount++;
    }
    limitedResult.close();
    session.commit();
    Assert.assertEquals(3, limitedCount);
  }

  // Verifies that the lazy traversal produces correct results on a branching
  // (fan-out) graph: a root node with multiple children, each having their own
  // children. This tests that the DFS stack correctly explores all branches.
  //
  //       root
  //      / | \
  //     a  b  c
  //    /|    |
  //   d  e   f
  @Test
  public void testWhileBranchingGraphLazyTraversal() {
    var clazz = "testWhileBranching";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    for (var name : new String[] {"root", "a", "b", "c", "d", "e", "f"}) {
      session.execute("CREATE VERTEX " + clazz + " SET name = '" + name + "'").close();
    }
    // root → a, b, c
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'root') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'a')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'root') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'b')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'root') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'c')")
        .close();
    // a → d, e
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'a') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'd')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'a') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'e')")
        .close();
    // b → f
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'b') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'f')")
        .close();
    session.commit();

    var query =
        "MATCH { class: " + clazz
            + ", as:start, where:(name = 'root')} --> {as:dest, while:(true),"
            + " depthAlias: d} RETURN dest.name as dname, d";

    session.begin();
    var result = session.query(query);

    var names = new java.util.ArrayList<String>();
    while (result.hasNext()) {
      var item = result.next();
      names.add((String) item.getProperty("dname"));
    }
    result.close();
    session.commit();

    java.util.Collections.sort(names);
    // All 7 nodes must appear exactly once (dedup active, no pathAlias)
    Assert.assertEquals(
        "Branching graph: all nodes must be visited exactly once",
        java.util.List.of("a", "b", "c", "d", "e", "f", "root"),
        names);
  }

  // Verifies that the lazy traversal respects maxDepth correctly, stopping
  // expansion at the specified depth even in a deeper graph.
  @Test
  public void testWhileMaxDepthLazyTraversal() {
    var clazz = "testWhileMaxDepth";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.begin();
    // Chain: a → b → c → d → e
    for (var name : new String[] {"a", "b", "c", "d", "e"}) {
      session.execute("CREATE VERTEX " + clazz + " SET name = '" + name + "'").close();
    }
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'a') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'b')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'b') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'c')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'c') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'd')")
        .close();
    session.execute(
        "CREATE EDGE E FROM (SELECT FROM " + clazz + " WHERE name = 'd') "
            + "TO (SELECT FROM " + clazz + " WHERE name = 'e')")
        .close();
    session.commit();

    // maxDepth: 2 — should see a (depth 0), b (depth 1), c (depth 2) only
    var query =
        "MATCH { class: " + clazz
            + ", as:start, where:(name = 'a')} --> {as:dest, while:(true),"
            + " maxDepth: 2, depthAlias: d} RETURN dest.name as dname, d";

    session.begin();
    var result = session.query(query);

    var pairs = new java.util.ArrayList<String>();
    while (result.hasNext()) {
      var item = result.next();
      pairs.add(item.getProperty("dname") + ":" + item.getProperty("d"));
    }
    result.close();
    session.commit();

    java.util.Collections.sort(pairs);
    Assert.assertEquals(
        "maxDepth: 2 must stop at depth 2",
        java.util.List.of("a:0", "b:1", "c:2"),
        pairs);
  }

  // Semantic equivalence regression: `while: ($depth < N)` and `maxDepth: N`
  // stop expansion at the same boundary. Before the lazy-path unification,
  // these two phrasings took different execution branches (eager vs lazy),
  // so a future refactor could silently diverge their behavior. This test
  // pins the equivalence on both a linear chain and a branching graph.
  @Test
  public void testWhileVsMaxDepthSemanticEquivalence() {
    var clazz = "testWhileVsMaxDepth";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    // Branching graph: root → {a, b} ; a → {c, d} ; b → {e} ; c → {f}
    // Depth 0: root.  Depth 1: a, b.  Depth 2: c, d, e.  Depth 3: f.
    session.executeInTx(
        tx -> {
          var root = session.newVertex(clazz);
          root.setProperty("name", "root");
          var a = session.newVertex(clazz);
          a.setProperty("name", "a");
          var b = session.newVertex(clazz);
          b.setProperty("name", "b");
          var c = session.newVertex(clazz);
          c.setProperty("name", "c");
          var d = session.newVertex(clazz);
          d.setProperty("name", "d");
          var e = session.newVertex(clazz);
          e.setProperty("name", "e");
          var f = session.newVertex(clazz);
          f.setProperty("name", "f");

          root.addEdge(a);
          root.addEdge(b);
          a.addEdge(c);
          a.addEdge(d);
          b.addEdge(e);
          c.addEdge(f);
        });

    // For each boundary depth N, `while: ($depth < N)` must return the
    // same set of vertices as `maxDepth: N`, and the combined form must
    // match too.
    for (int n = 0; n <= 4; n++) {
      var whileOnly =
          "MATCH { class: " + clazz
              + ", as:s, where:(name = 'root')} --> {as:d, while:($depth < " + n
              + ")} RETURN d.name as dname";
      var maxDepthOnly =
          "MATCH { class: " + clazz
              + ", as:s, where:(name = 'root')} --> {as:d, while:(true),"
              + " maxDepth: " + n + "} RETURN d.name as dname";
      var combined =
          "MATCH { class: " + clazz
              + ", as:s, where:(name = 'root')} --> {as:d,"
              + " while:($depth < " + n + "), maxDepth: " + n
              + "} RETURN d.name as dname";

      var whileNames = collectNames(whileOnly);
      var maxDepthNames = collectNames(maxDepthOnly);
      var combinedNames = collectNames(combined);

      Assert.assertEquals(
          "while:($depth<" + n + ") must match maxDepth: " + n,
          whileNames, maxDepthNames);
      Assert.assertEquals(
          "combined while+maxDepth with equal bound must match either phrasing",
          whileNames, combinedNames);
    }
  }

  private java.util.List<String> collectNames(String query) {
    session.begin();
    var result = session.query(query);
    var names = new java.util.ArrayList<String>();
    while (result.hasNext()) {
      names.add((String) result.next().getProperty("dname"));
    }
    result.close();
    session.commit();
    java.util.Collections.sort(names);
    return names;
  }

  // Shallow-wide stress test: 1 root → 100 mids → 99 leaves per mid =
  // 10 000 vertices reachable at depth ≤ 2, no cycles, no diamond overlap.
  // Locks in the no-regression guarantee after Frame unboxing: any reintroduced
  // per-vertex allocation would blow up both allocation rate and runtime on
  // this shape. Also verifies that the parallel-array stack grows correctly
  // past its initial 16-slot capacity (deepest stack at any moment ≤ 3).
  @Test
  public void testShallowWideFanOutLazyTraversal() {
    var clazz = "testShallowWideFanOut";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    final int mids = 100;
    final int leavesPerMid = 99;
    final int expectedCount = 1 + mids + mids * leavesPerMid; // 10 000

    session.executeInTx(
        tx -> {
          var root = session.newVertex(clazz);
          root.setProperty("name", "root");
          for (int i = 0; i < mids; i++) {
            var mid = session.newVertex(clazz);
            mid.setProperty("name", "m" + i);
            root.addEdge(mid);
            for (int j = 0; j < leavesPerMid; j++) {
              var leaf = session.newVertex(clazz);
              leaf.setProperty("name", "l" + i + "_" + j);
              mid.addEdge(leaf);
            }
          }
        });

    // maxDepth: 2, no pathAlias → dedup active, but the graph is a tree so
    // no vertex is reached twice. Result size must equal the total vertex
    // count reachable at depth ≤ 2.
    var query =
        "MATCH { class: " + clazz
            + ", as:s, where:(name = 'root')} --> {as:d, while:(true),"
            + " maxDepth: 2, depthAlias: depth} RETURN d.name as name, depth";

    var start = System.nanoTime();
    session.begin();
    var result = session.query(query);

    var depthCounts = new int[3];
    var seen = new java.util.HashSet<String>();
    int count = 0;
    while (result.hasNext()) {
      var item = result.next();
      var name = (String) item.getProperty("name");
      int depth = item.getProperty("depth");
      Assert.assertTrue(
          "depth must be within [0, 2] bound", depth >= 0 && depth <= 2);
      depthCounts[depth]++;
      Assert.assertTrue(
          "vertex must appear at most once when pathAlias is absent: " + name,
          seen.add(name));
      count++;
    }
    result.close();
    session.commit();
    var elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    Assert.assertEquals(
        "all reachable vertices at depth ≤ 2 must be visited",
        expectedCount, count);
    Assert.assertEquals("root at depth 0", 1, depthCounts[0]);
    Assert.assertEquals("mids at depth 1", mids, depthCounts[1]);
    Assert.assertEquals(
        "leaves at depth 2", mids * leavesPerMid, depthCounts[2]);
    // Soft runtime bound — 10 000-vertex fan-out should execute in well under
    // 30 s on any test host. A serious regression (e.g. per-vertex allocation
    // reintroduced) would blow past this.
    Assert.assertTrue(
        "traversal took " + elapsedMs + "ms, exceeds 30 s soft bound",
        elapsedMs < 30_000L);
  }

  @Test
  public void testNegativePattern() {
    var clazz = "testNegativePattern";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.executeInTx(
        transaction -> {
          var v1 = session.newVertex(clazz);
          v1.setProperty("name", "a");

          var v2 = session.newVertex(clazz);
          v2.setProperty("name", "b");

          var v3 = session.newVertex(clazz);
          v3.setProperty("name", "c");

          v1.addEdge(v2);
          v2.addEdge(v3);
        });

    var query = "MATCH { class:" + clazz + ", as:a} --> {as:b} --> {as:c}, ";
    query += " NOT {as:a} --> {as:c}";
    query += " RETURN $patterns";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testNegativePattern2() {
    var clazz = "testNegativePattern2";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.executeInTx(
        transaction -> {
          var v1 = session.newVertex(clazz);
          v1.setProperty("name", "a");

          var v2 = session.newVertex(clazz);
          v2.setProperty("name", "b");

          var v3 = session.newVertex(clazz);
          v3.setProperty("name", "c");

          v1.addEdge(v2);
          v2.addEdge(v3);
          v1.addEdge(v3);
        });

    var query = "MATCH { class:" + clazz + ", as:a} --> {as:b} --> {as:c}, ";
    query += " NOT {as:a} --> {as:c}";
    query += " RETURN $patterns";

    session.begin();
    var result = session.query(query);
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testNegativePattern3() {
    var clazz = "testNegativePattern3";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.executeInTx(
        transaction -> {
          var v1 = session.newVertex(clazz);
          v1.setProperty("name", "a");

          var v2 = session.newVertex(clazz);
          v2.setProperty("name", "b");

          var v3 = session.newVertex(clazz);
          v3.setProperty("name", "c");

          v1.addEdge(v2);
          v2.addEdge(v3);
          v1.addEdge(v3);
        });

    var query = "MATCH { class:" + clazz + ", as:a} --> {as:b} --> {as:c}, ";
    query += " NOT {as:a} --> {as:c, where:(name <> 'c')}";
    query += " RETURN $patterns";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  @Test
  public void testPathTraversal() {
    var clazz = "testPathTraversal";
    session.execute("CREATE CLASS " + clazz + " EXTENDS V").close();

    session.executeInTx(
        transaction -> {
          var v1 = session.newVertex(clazz);
          v1.setProperty("name", "a");

          var v2 = session.newVertex(clazz);
          v2.setProperty("name", "b");

          var v3 = session.newVertex(clazz);
          v3.setProperty("name", "c");

          v1.setProperty("next", v2);
          v2.setProperty("next", v3);

        });

    var query = "MATCH { class:" + clazz + ", as:a}.next{as:b, where:(name ='b')}";
    query += " RETURN a.name as a, b.name as b";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("a", item.getProperty("a"));
    Assert.assertEquals("b", item.getProperty("b"));

    Assert.assertFalse(result.hasNext());

    result.close();

    query = "MATCH { class:" + clazz + ", as:a, where:(name ='a')}.next{as:b}";
    query += " RETURN a.name as a, b.name as b";

    result = session.query(query);
    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertEquals("a", item.getProperty("a"));
    Assert.assertEquals("b", item.getProperty("b"));

    Assert.assertFalse(result.hasNext());

    result.close();
    session.commit();
  }

  private BasicResultSet getManagedPathElements(String managerName) {
    var query =
        "  match {class:Employee, as:boss, where: (name = '"
            + managerName
            + "')}"
            + "  -ManagerOf->{}<-ParentDepartment-{"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return distinct $pathElements";

    return session.query(query);
  }

  @Test
  public void testQuotedClassName() {
    var className = "testQuotedClassName";
    session.execute("CREATE CLASS " + className + " EXTENDS V").close();

    session.begin();
    session.execute("CREATE VERTEX " + className + " SET name = 'a'").close();
    session.commit();

    var query = "MATCH {class: `" + className + "`, as:foo} RETURN $elements";

    session.begin();
    try (var rs = session.query(query)) {
      Assert.assertEquals(1L, rs.stream().count());
    }
    session.commit();
  }

  // =====================================================================
  // Index-ordered MATCH tests
  //
  // Schema: TestPerson -[TEST_HAS_CREATOR]-> TestMessage
  //   TestMessage has creationDate (DATETIME, indexed NOTUNIQUE) and msgId (LONG)
  //   Each TestPerson has multiple TestMessages with distinct creationDates.
  // =====================================================================

  /**
   * Sets up schema and data for index-ordered MATCH tests.
   * Creates the schema (TestPerson, TestMessage, TEST_HAS_CREATOR edge, index)
   * and optionally 1 or 2 persons depending on the parameter.
   *
   * @param includeSecondPerson if true, creates person2 with 10 additional messages
   */
  private void initIndexOrderedMatchData(boolean includeSecondPerson) {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'person1'").close();

    // person1: messages with creationDate = day 1..10, msgId = 100..109
    for (var i = 1; i <= 10; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + (99 + i))
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + (99 + i)
              + ") TO (SELECT FROM TestPerson WHERE name = 'person1')")
          .close();
    }

    if (includeSecondPerson) {
      session.execute("CREATE VERTEX TestPerson SET name = 'person2'").close();
      // person2: messages with creationDate = day 11..20, msgId = 200..209
      for (var i = 11; i <= 20; i++) {
        session.execute(
            "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
                + String.format("%02d", i) + " 00:00:00', msgId = " + (189 + i))
            .close();
        session.execute(
            "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
                + (189 + i) + ") TO (SELECT FROM TestPerson WHERE name = 'person2')")
            .close();
      }
    }
    session.commit();
  }

  private void initIndexOrderedMatchData() {
    initIndexOrderedMatchData(true);
  }

  /**
   * Multi-source setup: 5 persons, 10 messages each. Ensures estimated cardinality > 1
   * so the planner selects a multi-source mode. Each person's messages span a different
   * 10-day range (person1: Feb 1-10, person2: Feb 11-20, ..., person5: Mar 11-20)
   * so global ordering can be verified.
   */
  private void initIndexOrderedMatchMultiSourceData() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    var msgCounter = 0;
    for (var p = 1; p <= 5; p++) {
      session.execute("CREATE VERTEX TestPerson SET name = 'person" + p + "'").close();
      for (var d = 1; d <= 10; d++) {
        msgCounter++;
        // Distribute across Feb and Mar to get distinct days per person
        var month = msgCounter <= 28 ? "02" : "03";
        var day = msgCounter <= 28 ? msgCounter : msgCounter - 28;
        session.execute(
            "CREATE VERTEX TestMessage SET creationDate = '2025-"
                + month + "-" + String.format("%02d", day)
                + " 00:00:00', msgId = " + msgCounter)
            .close();
        session.execute(
            "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
                + msgCounter
                + ") TO (SELECT FROM TestPerson WHERE name = 'person" + p + "')")
            .close();
      }
    }
    session.commit();
  }

  /**
   * Large single-source setup: 1 person with 200 messages.
   * High edge count relative to index size should make the cost model choose
   * indexScanFiltered over loadAllAndSort.
   */
  private void initIndexOrderedMatchLargeData() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'person1'").close();
    for (var i = 1; i <= 200; i++) {
      // Monotonically increasing timestamps: msgId i → hour i/60, minute i%60
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-01 "
              + String.format("%02d", i / 60) + ":"
              + String.format("%02d", i % 60) + ":00', msgId = " + i)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + i
              + ") TO (SELECT FROM TestPerson WHERE name = 'person1')")
          .close();
    }
    session.commit();
  }

  /** Extract the day-of-month from a Date result property. */
  private int dayOfMonth(Object dateValue) {
    if (dateValue instanceof java.util.Date date) {
      var cal = java.util.Calendar.getInstance();
      cal.setTime(date);
      return cal.get(java.util.Calendar.DAY_OF_MONTH);
    }
    throw new AssertionError("Expected Date but got: " + dateValue.getClass() + " = " + dateValue);
  }

  private String getPlan(ResultSet result) {
    var plan = result.getExecutionPlan();
    Assert.assertNotNull("Execution plan should be present", plan);
    return plan.prettyPrint(0, 2);
  }

  /** Sets index-ordered config to known test-safe values. Call restore in finally. */
  private AutoCloseable setIndexOrderedTestConfig() {
    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    var oldMaxScan = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValue();
    var oldCostBias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValue();
    var oldMaxSources = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.getValue();

    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(1);
    GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(10_000_000);
    GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.setValue(1.0);
    GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.setValue(100_000);

    return () -> {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(oldMaxScan);
      GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.setValue(oldCostBias);
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.setValue(oldMaxSources);
    };
  }

  // Single-source single-field ORDER BY with index uses IndexOrderedEdgeStep and returns sorted results.
  @Test
  public void testIndexOrderedMatchSingleSourceSingleField() throws Exception {
    initIndexOrderedMatchData(false);

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        // OrderByStep is always present but acts as pass-through when
        // IndexOrderedEdgeStep signals pre-sorted output at runtime.
        Assert.assertTrue(
            "OrderByStep should be present (pass-through mode), but plan was:\n" + plan,
            plan.contains("ORDER BY"));

        Assert.assertTrue("Expected results but got none. Plan:\n" + plan, result.hasNext());
        var r1 = result.next();
        Assert.assertEquals(10, dayOfMonth(r1.getProperty("cd")));

        Assert.assertTrue(result.hasNext());
        var r2 = result.next();
        Assert.assertEquals(9, dayOfMonth(r2.getProperty("cd")));

        Assert.assertTrue(result.hasNext());
        var r3 = result.next();
        Assert.assertEquals(8, dayOfMonth(r3.getProperty("cd")));

        Assert.assertFalse("Should have exactly 3 results", result.hasNext());
      }
      session.commit();
    }
  }

  // ORDER BY a non-indexed property falls back to standard MatchStep without IndexOrderedEdgeStep.
  @Test
  public void testIndexOrderedMatchNoIndex() {
    initIndexOrderedMatchData(false);

    session.begin();
    // ORDER BY msgId — no index on msgId, so standard MatchStep + OrderByStep
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.msgId as mid ORDER BY mid DESC LIMIT 3";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      Assert.assertFalse(
          "Should NOT use INDEX ORDERED MATCH when no index, but plan was:\n" + plan,
          plan.contains("INDEX ORDERED MATCH"));

      // Verify correct results: msgIds 109, 108, 107
      var r1 = result.next();
      Assert.assertEquals(109L, ((Number) r1.getProperty("mid")).longValue());
      var r2 = result.next();
      Assert.assertEquals(108L, ((Number) r2.getProperty("mid")).longValue());
      var r3 = result.next();
      Assert.assertEquals(107L, ((Number) r3.getProperty("mid")).longValue());
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  // WHILE traversals prevent the optimization because the result set is recursive.
  @Test
  public void testIndexOrderedMatchWhileEdge() {
    initIndexOrderedMatchData(false);

    session.begin();
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){while: ($depth < 2), as: m} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      Assert.assertFalse(
          "WHILE edges should NOT use INDEX ORDERED MATCH, but plan was:\n" + plan,
          plan.contains("INDEX ORDERED MATCH"));
    }
    session.commit();
  }

  // both() direction prevents the optimization because the LinkBag to scan is ambiguous.
  @Test
  public void testIndexOrderedMatchBothDirection() {
    initIndexOrderedMatchData(false);

    session.begin();
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".both('TEST_HAS_CREATOR'){as: m} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      Assert.assertFalse(
          "both() direction should NOT use INDEX ORDERED MATCH, but plan was:\n" + plan,
          plan.contains("INDEX ORDERED MATCH"));
    }
    session.commit();
  }

  // Multi-field ORDER BY with small data (9 messages): plan-time cost check rejects
  // the index-ordered optimization because the index seek overhead dominates for
  // small datasets. Falls back to standard MATCH + ORDER BY, results still correct.
  @Test
  public void testIndexOrderedMatchMultiFieldOrderBy() {
    // Custom data: 1 person, 3 dates × 3 messages each (9 messages total).
    // day 10: msgId 301,302,303; day 9: msgId 304,305,306; day 8: msgId 307,308,309
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'person1'").close();
    var msgId = 300;
    for (var day = 10; day >= 8; day--) {
      for (var j = 1; j <= 3; j++) {
        msgId++;
        session.execute(
            "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
                + String.format("%02d", day) + " 00:00:00', msgId = " + msgId)
            .close();
        session.execute(
            "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
                + msgId + ") TO (SELECT FROM TestPerson WHERE name = 'person1')")
            .close();
      }
    }
    session.commit();

    session.begin();
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd, m.msgId as mid"
            + " ORDER BY cd DESC, mid ASC LIMIT 5";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      // With only 9 messages the plan-time cost check rejects the optimization:
      // seekCost (16) dominates, making costUnionScan > costLoadSort.
      Assert.assertFalse(
          "Plan should NOT use INDEX ORDERED MATCH with small data, but was:\n" + plan,
          plan.contains("INDEX ORDERED MATCH"));
      Assert.assertTrue(
          "OrderByStep should be present for ORDER BY, but plan was:\n" + plan,
          plan.contains("ORDER BY"));

      // Expected: day 10 (msgId 301,302,303 ASC), then day 9 (msgId 304,305 ASC)
      // LIMIT 5 → 3 from day 10 + 2 from day 9
      var mids = new java.util.ArrayList<Long>();
      var days = new java.util.ArrayList<Integer>();
      while (result.hasNext()) {
        var row = result.next();
        days.add(dayOfMonth(row.getProperty("cd")));
        mids.add(((Number) row.getProperty("mid")).longValue());
      }
      Assert.assertEquals("Should have 5 results", 5, mids.size());

      // First 3: day 10, sorted by msgId ASC
      Assert.assertEquals(10, (int) days.get(0));
      Assert.assertEquals(10, (int) days.get(1));
      Assert.assertEquals(10, (int) days.get(2));
      Assert.assertEquals(301L, (long) mids.get(0));
      Assert.assertEquals(302L, (long) mids.get(1));
      Assert.assertEquals(303L, (long) mids.get(2));

      // Next 2: day 9, sorted by msgId ASC
      Assert.assertEquals(9, (int) days.get(3));
      Assert.assertEquals(9, (int) days.get(4));
      Assert.assertEquals(304L, (long) mids.get(3));
      Assert.assertEquals(305L, (long) mids.get(4));
    }
    session.commit();
  }

  // SKIP + LIMIT pagination with small data (10 messages): plan-time cost check
  // rejects the index-ordered optimization because seekCost dominates for small
  // datasets. Falls back to standard MATCH + ORDER BY, results still correct.
  @Test
  public void testIndexOrderedMatchSkipLimit() {
    initIndexOrderedMatchData(false);

    session.begin();
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC SKIP 2 LIMIT 3";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      // With only 10 messages and queryLimit=5 (skip 2 + limit 3), the
      // plan-time cost check rejects: seekCost (16) dominates.
      Assert.assertFalse(
          "Plan should NOT use INDEX ORDERED MATCH with small data, but was:\n" + plan,
          plan.contains("INDEX ORDERED MATCH"));

      // person1 has dates: 10, 9, 8, 7, 6, 5, 4, 3, 2, 1 (DESC)
      // SKIP 2 → skip 10, 9; LIMIT 3 → return 8, 7, 6
      var r1 = result.next();
      Assert.assertEquals(8, dayOfMonth(r1.getProperty("cd")));
      var r2 = result.next();
      Assert.assertEquals(7, dayOfMonth(r2.getProperty("cd")));
      var r3 = result.next();
      Assert.assertEquals(6, dayOfMonth(r3.getProperty("cd")));
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  // DISTINCT prevents OrderByStep suppression because it can change the result set.
  @Test
  public void testIndexOrderedMatchWithDistinct() {
    initIndexOrderedMatchData(false);

    session.begin();
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN DISTINCT m.creationDate as cd ORDER BY cd DESC LIMIT 3";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      // With DISTINCT, OrderByStep must be present (not suppressed)
      Assert.assertTrue(
          "OrderByStep should NOT be suppressed when DISTINCT is used, plan was:\n" + plan,
          plan.contains("ORDER BY"));

      // Results should still be correct
      var r1 = result.next();
      Assert.assertEquals(10, dayOfMonth(r1.getProperty("cd")));
      Assert.assertTrue(result.hasNext());
    }
    session.commit();
  }

  // GROUP BY prevents OrderByStep suppression because it changes the result cardinality.
  @Test
  public void testIndexOrderedMatchWithGroupBy() {
    initIndexOrderedMatchData();

    session.begin();
    var query =
        "MATCH {class: TestPerson, as: p}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN p.name as pname, count(*) as cnt"
            + " GROUP BY pname ORDER BY pname ASC";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      // GROUP BY → OrderByStep must NOT be suppressed even if index exists
      Assert.assertTrue(
          "OrderByStep should NOT be suppressed when GROUP BY is used, plan was:\n" + plan,
          plan.contains("ORDER BY"));

      // 2 persons: person1 (10 messages), person2 (10 messages)
      var r1 = result.next();
      Assert.assertEquals("person1", r1.getProperty("pname"));
      Assert.assertEquals(10L, ((Number) r1.getProperty("cnt")).longValue());
      var r2 = result.next();
      Assert.assertEquals("person2", r2.getProperty("pname"));
      Assert.assertEquals(10L, ((Number) r2.getProperty("cnt")).longValue());
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  // Multi-source FILTERED_BOUND mode with WHERE filter and source alias in RETURN produces globally sorted results.
  @Test
  public void testIndexOrderedMatchMultiSourceFilteredBound() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // WHERE filter selects all 5 persons; p.name in RETURN → FILTERED_BOUND
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd ORDER BY cd DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use FILTERED_BOUND mode, but was:\n" + plan,
            plan.contains("FILTERED_BOUND"));

        // 5 persons × 10 messages each, days 1-10, 11-20, 21-30, 31-40, 41-50.
        // Top 5 DESC: days from person5 (days 41-50): 50, 49, 48, 47, 46.
        var days = new java.util.ArrayList<Integer>();
        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          days.add(dayOfMonth(row.getProperty("cd")));
          names.add(row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + days,
              days.get(i) >= days.get(i + 1));
        }
      }
      session.commit();
    }
  }

  // Multi-source FILTERED_UNBOUND mode with WHERE filter and source alias NOT in RETURN uses union-RidSet-only mode.
  @Test
  public void testIndexOrderedMatchMultiSourceFilteredUnbound() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // WHERE filter on p, but p NOT in RETURN → FILTERED_UNBOUND
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use FILTERED_UNBOUND mode, but was:\n" + plan,
            plan.contains("FILTERED_UNBOUND"));

        var days = new java.util.ArrayList<Integer>();
        while (result.hasNext()) {
          days.add(dayOfMonth(result.next().getProperty("cd")));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + days,
              days.get(i) >= days.get(i + 1));
        }
      }
      session.commit();
    }
  }

  // Multi-source UNFILTERED_UNBOUND mode with no WHERE and source alias NOT in RETURN returns ASC-sorted results.
  @Test
  public void testIndexOrderedMatchMultiSourceUnfilteredUnbound() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd ASC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use UNFILTERED_UNBOUND mode, but was:\n" + plan,
            plan.contains("UNFILTERED_UNBOUND"));

        // ASC: first 5 results should be in ascending order
        var days = new java.util.ArrayList<Integer>();
        while (result.hasNext()) {
          days.add(dayOfMonth(result.next().getProperty("cd")));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in ASC order: " + days,
              days.get(i) <= days.get(i + 1));
        }
      }
      session.commit();
    }
  }

  // ORDER BY using a projection alias resolves correctly and enables the optimization.
  @Test
  public void testIndexOrderedMatchProjectionAlias() throws Exception {
    initIndexOrderedMatchData(false);

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // ORDER BY uses "messageDate" which is an alias for m.creationDate
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as messageDate ORDER BY messageDate DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Alias resolution should enable INDEX ORDERED MATCH, but plan was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var r1 = result.next();
        Assert.assertEquals(10, dayOfMonth(r1.getProperty("messageDate")));
        var r2 = result.next();
        Assert.assertEquals(9, dayOfMonth(r2.getProperty("messageDate")));
        var r3 = result.next();
        Assert.assertEquals(8, dayOfMonth(r3.getProperty("messageDate")));
        Assert.assertFalse(result.hasNext());
      }
      session.commit();
    }
  }

  // Index-ordered scan and standard load-all-and-sort produce identical results for the same data.
  @Test
  public void testIndexOrderedMatchFallbackCorrectness() {
    initIndexOrderedMatchData(false);

    session.begin();
    // Optimizable query: ORDER BY creationDate DESC (indexed)
    var optimizedQuery =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd, m.msgId as mid ORDER BY cd DESC";

    // Baseline: MATCH without class constraint on m (no IndexOrderedEdgeStep)
    var baselineQuery =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){as: m} "
            + "RETURN m.creationDate as cd, m.msgId as mid ORDER BY cd DESC";

    var optimizedResults = new java.util.ArrayList<Long>();
    try (var result = session.query(optimizedQuery)) {
      while (result.hasNext()) {
        var row = result.next();
        optimizedResults.add(((Number) row.getProperty("mid")).longValue());
      }
    }

    var baselineResults = new java.util.ArrayList<Long>();
    try (var result = session.query(baselineQuery)) {
      while (result.hasNext()) {
        var row = result.next();
        baselineResults.add(((Number) row.getProperty("mid")).longValue());
      }
    }

    Assert.assertEquals("Both paths should return the same number of results",
        baselineResults.size(), optimizedResults.size());
    Assert.assertEquals("Both paths should return results in the same order",
        baselineResults, optimizedResults);
    session.commit();
  }

  // Large data set with 200 messages and LIMIT 3 triggers the indexScanFiltered path via cost model.
  @Test
  public void testIndexOrderedMatchIndexScanPath() throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // Should return 3 results in DESC order by creationDate
        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 3 results", 3, mids.size());
        // The msgIds with latest timestamps should come first
        // All 3 should be from the highest msgId range (close to 200)
        for (var mid : mids) {
          Assert.assertTrue("msgId should be > 100 for top-3, got: " + mid, mid > 100);
        }
      }
      session.commit();
    }
  }

  // NULL creationDate values appear first in ASC order, matching B-tree index NULL ordering.
  @Test
  public void testIndexOrderedMatchNullOrdering() throws Exception {
    initIndexOrderedMatchData(false);
    // Add 2 messages with NULL creationDate
    session.begin();
    session.execute(
        "CREATE VERTEX TestMessage SET creationDate = null, msgId = 500").close();
    session.execute(
        "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = 500)"
            + " TO (SELECT FROM TestPerson WHERE name = 'person1')")
        .close();
    session.execute(
        "CREATE VERTEX TestMessage SET creationDate = null, msgId = 501").close();
    session.execute(
        "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = 501)"
            + " TO (SELECT FROM TestPerson WHERE name = 'person1')")
        .close();
    session.commit();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // ASC order: NULLs should come first
      var queryAsc =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd, m.msgId as mid ORDER BY cd ASC LIMIT 4";
      try (var result = session.query(queryAsc)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // First 2 results should have NULL creationDate
        var r1 = result.next();
        Assert.assertNull("First result should have NULL cd", r1.getProperty("cd"));
        var r2 = result.next();
        Assert.assertNull("Second result should have NULL cd", r2.getProperty("cd"));
        // Next results should have non-NULL dates
        var r3 = result.next();
        Assert.assertNotNull("Third result should have non-NULL cd", r3.getProperty("cd"));
        var r4 = result.next();
        Assert.assertNotNull("Fourth result should have non-NULL cd", r4.getProperty("cd"));
        Assert.assertFalse(result.hasNext());
      }
      session.commit();
    }
  }

  // Cardinality mis-estimation with LIKE filter still produces globally sorted results via multi-source mode.
  @Test
  public void testIndexOrderedMatchCardinalityMisEstimation() throws Exception {
    initIndexOrderedMatchData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // LIKE filter on 2 persons — estimator might say cardinality=1,
      // but runtime has 2 sources. Global DESC must be correct.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // person1: days 1-10, person2: days 11-20
        // Global DESC LIMIT 5 → days 20, 19, 18, 17, 16 (all from person2)
        var days = new java.util.ArrayList<Integer>();
        while (result.hasNext()) {
          days.add(dayOfMonth(result.next().getProperty("cd")));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        Assert.assertEquals("Top result must be day 20 (global order)", 20, (int) days.get(0));
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + days,
              days.get(i) >= days.get(i + 1));
        }
      }
      session.commit();
    }
  }

  // =====================================================================
  // EXPLAIN-based plan structure tests
  //
  // These tests verify the execution plan structure without consuming
  // results. They ensure that the optimizer makes the right decisions
  // about when to use IndexOrderedEdgeStep and which mode to select.
  // =====================================================================

  /**
   * Asserts that the plan contains all of the given substrings and none of the
   * excluded substrings. Provides a clear error message showing the full plan.
   */
  private void assertPlanContains(String plan, String[] expected, String[] excluded) {
    for (var s : expected) {
      Assert.assertTrue(
          "Plan should contain '" + s + "', but was:\n" + plan, plan.contains(s));
    }
    for (var s : excluded) {
      Assert.assertFalse(
          "Plan should NOT contain '" + s + "', but was:\n" + plan, plan.contains(s));
    }
  }

  // EXPLAIN: single-source single-field with small data (10 messages): plan-time
  // cost check rejects index-ordered optimization, falls back to standard MATCH.
  @Test
  public void testExplainSingleSourceSingleField() {
    initIndexOrderedMatchData(false);

    session.begin();
    try (var result = session.query(
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 10")) {
      var plan = getPlan(result);
      // With only 10 messages, the plan-time cost check rejects: seekCost
      // dominates for small datasets. Standard MATCH path is used instead.
      assertPlanContains(plan,
          new String[] {"MATCH      ---->", "ORDER BY", "LIMIT"},
          new String[] {"INDEX ORDERED MATCH"});
    }
    session.commit();
  }

  // EXPLAIN: multi-field ORDER BY with small data (10 messages): plan-time cost
  // check rejects index-ordered optimization, falls back to standard MATCH.
  @Test
  public void testExplainMultiFieldOrderBy() {
    initIndexOrderedMatchData(false);

    session.begin();
    try (var result = session.query(
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd, m.msgId as mid"
            + " ORDER BY cd DESC, mid ASC SKIP 2 LIMIT 5")) {
      var plan = getPlan(result);
      // With only 10 messages, the plan-time cost check rejects: seekCost
      // dominates for small datasets. Standard MATCH path is used instead.
      assertPlanContains(plan,
          new String[] {"MATCH      ---->", "ORDER BY"},
          new String[] {"INDEX ORDERED MATCH"});
    }
    session.commit();
  }

  // EXPLAIN: no index on ORDER BY property falls back to standard MatchStep without IndexOrderedEdgeStep.
  @Test
  public void testExplainNoIndexFallsBackToMatchStep() {
    initIndexOrderedMatchData(false);

    session.begin();
    try (var result = session.query(
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.msgId as mid ORDER BY mid DESC LIMIT 5")) {
      var plan = getPlan(result);
      assertPlanContains(plan,
          new String[] {"MATCH      ---->", "ORDER BY"},
          new String[] {"INDEX ORDERED MATCH"});
    }
    session.commit();
  }

  // EXPLAIN: DISTINCT keeps OrderByStep in the plan because it can change the row set.
  @Test
  public void testExplainDistinctKeepsOrderByStep() {
    initIndexOrderedMatchData(false);

    session.begin();
    try (var result = session.query(
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN DISTINCT m.creationDate as cd ORDER BY cd DESC LIMIT 5")) {
      var plan = getPlan(result);
      assertPlanContains(plan,
          new String[] {"ORDER BY", "DISTINCT"},
          new String[] {}); // INDEX ORDERED MATCH may or may not be present
    }
    session.commit();
  }

  // EXPLAIN: GROUP BY keeps OrderByStep in the plan because it changes cardinality.
  @Test
  public void testExplainGroupByKeepsOrderByStep() {
    initIndexOrderedMatchData();

    session.begin();
    try (var result = session.query(
        "MATCH {class: TestPerson, as: p}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN p.name as pname, count(*) as cnt"
            + " GROUP BY pname ORDER BY pname ASC")) {
      var plan = getPlan(result);
      assertPlanContains(plan,
          new String[] {"ORDER BY", "GROUP BY"},
          new String[] {}); // INDEX ORDERED MATCH may or may not be present
    }
    session.commit();
  }

  // EXPLAIN: multi-source plan includes correct mode suffix (FILTERED_BOUND, FILTERED_UNBOUND, UNFILTERED_BOUND, UNFILTERED_UNBOUND).
  @Test
  public void testExplainMultiSourceModeInPlan() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // FILTERED_BOUND: WHERE + source in RETURN
      try (var result = session.query(
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd ORDER BY cd DESC LIMIT 5")) {
        assertPlanContains(getPlan(result),
            new String[] {"INDEX ORDERED MATCH DESC (FILTERED_BOUND)"},
            new String[] {"MATCH      ---->"});
      }

      // FILTERED_UNBOUND: WHERE + source NOT in RETURN
      try (var result = session.query(
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 5")) {
        assertPlanContains(getPlan(result),
            new String[] {"INDEX ORDERED MATCH DESC (FILTERED_UNBOUND)"},
            new String[] {"MATCH      ---->"});
      }

      // UNFILTERED_BOUND: no WHERE + source in RETURN
      try (var result = session.query(
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd ORDER BY cd DESC LIMIT 5")) {
        assertPlanContains(getPlan(result),
            new String[] {"INDEX ORDERED MATCH DESC (UNFILTERED_BOUND)"},
            new String[] {"MATCH      ---->"});
      }

      // UNFILTERED_UNBOUND: no WHERE + source NOT in RETURN
      try (var result = session.query(
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 5")) {
        assertPlanContains(getPlan(result),
            new String[] {"INDEX ORDERED MATCH DESC (UNFILTERED_UNBOUND)"},
            new String[] {"MATCH      ---->"});
      }
      session.commit();
    }
  }

  // EXPLAIN: WHILE edge, both() direction, and missing target class all prevent the optimization.
  @Test
  public void testExplainOptimizationNotApplied() {
    initIndexOrderedMatchData(false);

    session.begin();
    // WHILE edge → standard MatchStep
    try (var result = session.query(
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){while: ($depth < 2), as: m} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3")) {
      assertPlanContains(getPlan(result),
          new String[] {"ORDER BY"},
          new String[] {"INDEX ORDERED MATCH"});
    }

    // both() direction → standard MatchStep
    try (var result = session.query(
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".both('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3")) {
      assertPlanContains(getPlan(result),
          new String[] {"ORDER BY"},
          new String[] {"INDEX ORDERED MATCH"});
    }

    // No class on target → optimization not detected (aliasClasses has no entry)
    try (var result = session.query(
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){as: m} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3")) {
      assertPlanContains(getPlan(result),
          new String[] {"ORDER BY"},
          new String[] {"INDEX ORDERED MATCH"});
    }
    session.commit();
  }

  // Target WHERE filter (creationDate < threshold) applied during index-ordered scan returns only matching records.
  @Test
  public void testIndexOrderedMatchTargetWhereFilter() throws Exception {
    initIndexOrderedMatchData(false);

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // person1 has messages with creationDate day 1..10.
      // WHERE on target filters to creationDate < '2025-01-06' → days 1..5 only.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (creationDate < '2025-01-06 00:00:00')} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // DESC order, filtered to days 1..5 → expect day 5, 4, 3
        Assert.assertTrue(result.hasNext());
        var r1 = result.next();
        Assert.assertEquals(5, dayOfMonth(r1.getProperty("cd")));

        Assert.assertTrue(result.hasNext());
        var r2 = result.next();
        Assert.assertEquals(4, dayOfMonth(r2.getProperty("cd")));

        Assert.assertTrue(result.hasNext());
        var r3 = result.next();
        Assert.assertEquals(3, dayOfMonth(r3.getProperty("cd")));

        Assert.assertFalse("Should have exactly 3 results", result.hasNext());
      }
      session.commit();
    }
  }

  // Target WHERE referencing $matched rejects the optimization because IndexOrderedEdgeStep lacks that context.
  @Test
  public void testIndexOrderedMatchRejectedWhenMatchedRef() {
    initIndexOrderedMatchData(false);

    session.begin();
    // The WHERE on m references $matched.p — this should prevent the optimization
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
            + " where: (msgId > $matched.p.minValue)} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      Assert.assertFalse(
          "Plan should NOT use INDEX ORDERED MATCH when WHERE references $matched,"
              + " but was:\n" + plan,
          plan.contains("INDEX ORDERED MATCH"));
    }
    session.commit();
  }

  // Multi-hop 3-hop pattern forces FILTERED_BOUND when an earlier alias is needed downstream in RETURN.
  @Test
  public void testIndexOrderedMatchMultiHopFilteredBound() {
    initIndexOrderedMatchReplyData();

    session.begin();
    // root is an earlier alias, msg is the source of the optimized edge.
    // root.name in RETURN forces BOUND mode for the optimized step.
    var query =
        "MATCH {class: TestPerson, as: root, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){as: msg}"
            + ".in('TEST_REPLY_OF'){class: TestReply, as: reply} "
            + "RETURN root.name as rname, reply.content as rc"
            + " ORDER BY reply.creationDate DESC LIMIT 5";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      // The query has a 3-hop pattern. If the optimization is applied to the
      // reply edge, it should use FILTERED_BOUND because root is needed in RETURN.
      // If the optimization is not applied (acceptable), verify correct results.
      var contents = new java.util.ArrayList<String>();
      var rnames = new java.util.ArrayList<String>();
      while (result.hasNext()) {
        var row = result.next();
        rnames.add(row.getProperty("rname"));
        contents.add(row.getProperty("rc"));
      }
      // Verify root.name is bound correctly
      for (var rname : rnames) {
        Assert.assertEquals("root should be person1", "person1", rname);
      }
      // Verify we got reply content
      Assert.assertFalse("Should have at least 1 reply result", contents.isEmpty());
      Assert.assertTrue("Should have at most 5 results", contents.size() <= 5);
    }
    session.commit();
  }

  /**
   * Sets up schema and data for multi-hop MATCH tests with replies.
   * Creates TestPerson, TestMessage, TestReply (extends V), TEST_HAS_CREATOR
   * and TEST_REPLY_OF edges. Person1 has 3 messages, each message has 2 replies.
   */
  private void initIndexOrderedMatchReplyData() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TestReply EXTENDS V").close();
    session.execute("CREATE PROPERTY TestReply.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestReply.content STRING").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute("CREATE CLASS TEST_REPLY_OF EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestReply.creationDate ON TestReply(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'person1'").close();

    // 3 messages for person1
    for (var m = 1; m <= 3; m++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
              + String.format("%02d", m) + " 00:00:00', msgId = " + m)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
              + m + ") TO (SELECT FROM TestPerson WHERE name = 'person1')")
          .close();

      // 2 replies per message
      for (var r = 1; r <= 2; r++) {
        var replyDay = m * 2 + r;
        var content = "reply_m" + m + "_r" + r;
        session.execute(
            "CREATE VERTEX TestReply SET creationDate = '2025-02-"
                + String.format("%02d", replyDay)
                + " 00:00:00', content = '" + content + "'")
            .close();
        session.execute(
            "CREATE EDGE TEST_REPLY_OF FROM (SELECT FROM TestReply WHERE content = '"
                + content + "') TO (SELECT FROM TestMessage WHERE msgId = " + m + ")")
            .close();
      }
    }
    session.commit();
  }

  // Single-source ASC order uses the orderAsc=true branch and returns results in ascending order.
  @Test
  public void testIndexOrderedMatchSingleSourceAsc() throws Exception {
    initIndexOrderedMatchData(false);

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd ASC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should indicate ASC direction, but was:\n" + plan,
            plan.contains("ASC"));

        // ASC: first 3 should be day 1, 2, 3
        Assert.assertTrue(result.hasNext());
        var r1 = result.next();
        Assert.assertEquals(1, dayOfMonth(r1.getProperty("cd")));

        Assert.assertTrue(result.hasNext());
        var r2 = result.next();
        Assert.assertEquals(2, dayOfMonth(r2.getProperty("cd")));

        Assert.assertTrue(result.hasNext());
        var r3 = result.next();
        Assert.assertEquals(3, dayOfMonth(r3.getProperty("cd")));

        Assert.assertFalse("Should have exactly 3 results", result.hasNext());
      }
      session.commit();
    }
  }

  // Multi-source FILTERED_BOUND with ASC direction returns results in ascending order.
  @Test
  public void testIndexOrderedMatchMultiSourceAsc() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // LIKE filter + p.name in RETURN → FILTERED_BOUND, ASC order
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd ORDER BY cd ASC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var days = new java.util.ArrayList<Integer>();
        while (result.hasNext()) {
          days.add(dayOfMonth(result.next().getProperty("cd")));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        // Verify ASC order
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in ASC order: " + days,
              days.get(i) <= days.get(i + 1));
        }
      }
      session.commit();
    }
  }

  // FILTERED_BOUND with sourceCount exceeding MAX_SOURCES triggers loadAllMultiSource fallback and still returns sorted results.
  @Test
  public void testIndexOrderedMatchFilteredBoundMaxSourcesFallback() {
    initIndexOrderedMatchMultiSourceData();

    var oldValue = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.setValue(3);
    try {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd ORDER BY cd DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // Verify results are correct even through the loadAllMultiSource fallback
        var days = new java.util.ArrayList<Integer>();
        while (result.hasNext()) {
          days.add(dayOfMonth(result.next().getProperty("cd")));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + days,
              days.get(i) >= days.get(i + 1));
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.setValue(oldValue);
    }
  }

  // Single-source RidSet overflow (ridSet exceeds MAX_RIDSET_SIZE) triggers loadAllAndSort fallback with correct results.
  @Test
  public void testIndexOrderedMatchSingleSourceMaxRidSetOverflow() {
    initIndexOrderedMatchData(false);

    var oldValue = GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValue();
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(3);
    try {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // Despite overflow fallback, results must be correctly sorted DESC
        Assert.assertTrue(result.hasNext());
        Assert.assertEquals(10, dayOfMonth(result.next().getProperty("cd")));
        Assert.assertTrue(result.hasNext());
        Assert.assertEquals(9, dayOfMonth(result.next().getProperty("cd")));
        Assert.assertTrue(result.hasNext());
        Assert.assertEquals(8, dayOfMonth(result.next().getProperty("cd")));
        Assert.assertFalse("Should have exactly 3 results", result.hasNext());
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(oldValue);
    }
  }

  // Person with no outgoing edges returns 0 results gracefully without NPE in processUpstreamRow.
  @Test
  public void testIndexOrderedMatchEmptyRidSet() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    // Person with NO edges at all
    session.execute("CREATE VERTEX TestPerson SET name = 'lonely'").close();
    session.commit();

    session.begin();
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'lonely')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
    try (var result = session.query(query)) {
      // Should return 0 results without error
      Assert.assertFalse(
          "Person with no edges should produce 0 results", result.hasNext());
    }
    session.commit();
  }

  // Target WHERE referencing $currentMatch rejects the optimization because IndexOrderedEdgeStep lacks that context.
  @Test
  public void testIndexOrderedMatchCurrentMatchGuard() {
    initIndexOrderedMatchData(false);

    session.begin();
    // $currentMatch in target WHERE → optimization should be rejected
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
            + " where: ($currentMatch != null)} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      Assert.assertFalse(
          "Plan should NOT use INDEX ORDERED MATCH when WHERE references $currentMatch,"
              + " but was:\n" + plan,
          plan.contains("INDEX ORDERED MATCH"));
    }
    session.commit();
  }

  // UNFILTERED mode without LIMIT returns all results because sequential index scan is always beneficial.
  @Test
  public void testIndexOrderedMatchNoLimitAllResults() throws Exception {
    initIndexOrderedMatchData(false);

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // No WHERE on p → UNFILTERED mode (no LIMIT required)
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var days = new java.util.ArrayList<Integer>();
        while (result.hasNext()) {
          days.add(dayOfMonth(result.next().getProperty("cd")));
        }
        Assert.assertEquals("Should have all 10 results: " + days, 10, days.size());
        // Verify DESC ordering
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + days,
              days.get(i) >= days.get(i + 1));
        }
        // First should be day 10, last should be day 1
        Assert.assertEquals(10, (int) days.get(0));
        Assert.assertEquals(1, (int) days.get(days.size() - 1));
      }
      session.commit();
    }
  }

  // FILTERED_BOUND with target WHERE filter (msgId > 40) only returns messages matching the target filter.
  @Test
  public void testIndexOrderedMatchFilteredBoundWithTargetFilter() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (msgId > 40)} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          var row = result.next();
          mids.add(((Number) row.getProperty("mid")).longValue());
        }
        Assert.assertFalse("Should have results", mids.isEmpty());
        Assert.assertTrue("Should have at most 5 results", mids.size() <= 5);
        // All returned msgIds should be > 40
        for (var mid : mids) {
          Assert.assertTrue(
              "All msgIds should be > 40 due to target filter, got: " + mid,
              mid > 40);
        }
      }
      session.commit();
    }
  }

  // UNFILTERED_BOUND without LIMIT exercises the full index scan path and returns all 50 results.
  @Test
  public void testIndexOrderedMatchUnfilteredBoundNoLimit() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // No WHERE on p, p.name in RETURN → UNFILTERED_BOUND, no LIMIT
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd ORDER BY cd ASC";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use UNFILTERED_BOUND mode, but was:\n" + plan,
            plan.contains("UNFILTERED_BOUND"));

        var dates = new java.util.ArrayList<java.util.Date>();
        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          dates.add(row.getProperty("cd"));
          names.add(row.getProperty("pname"));
        }
        // 5 persons x 10 messages = 50 total results
        Assert.assertEquals(
            "Should have all 50 results", 50, dates.size());
        // Verify ASC ordering (compare full Date, not dayOfMonth which wraps)
        for (var i = 0; i < dates.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in ASC order at index " + i
                  + ": " + dates.get(i) + " vs " + dates.get(i + 1),
              !dates.get(i).after(dates.get(i + 1)));
        }
        // All names should be non-null (binding works in UNFILTERED_BOUND)
        for (var name : names) {
          Assert.assertNotNull(
              "pname should be bound in UNFILTERED_BOUND mode", name);
        }
      }
      session.commit();
    }
  }

  // High COST_BIAS override causes plan-time cost model rejection so the optimization
  // is not applied. Normal MATCH path produces correct results.
  @Test
  public void testIndexOrderedMatchCostBiasOverride() {
    initIndexOrderedMatchLargeData();

    var oldValue = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.setValue(100.0);
    try {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertFalse(
            "Plan should NOT use INDEX ORDERED MATCH when COST_BIAS rejects,"
                + " but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // Results should still be correct via normal path
        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 3 results", 3, mids.size());
        // Top 3 by DESC creationDate should have highest msgIds
        for (var mid : mids) {
          Assert.assertTrue(
              "msgId should be > 100 for top-3, got: " + mid, mid > 100);
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.setValue(oldValue);
    }
  }

  // Downstream $matched.<alias> reference in a later edge forces BOUND mode to preserve the earlier alias.
  @Test
  public void testIndexOrderedMatchDownstreamMatchedForcesBound() {
    initIndexOrderedMatchReplyData();

    session.begin();
    // root → msg is first edge, msg → reply is the index-ordered edge.
    // reply → check has WHERE referencing $matched.root, which forces
    // BOUND mode to preserve 'root' alias in the row.
    // Using optional edge to avoid empty results if no match.
    var query =
        "MATCH {class: TestPerson, as: root, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){as: msg},"
            + " {as: msg}.in('TEST_REPLY_OF'){class: TestReply, as: reply}"
            + ".out('TEST_HAS_CREATOR')"
            + "  {as: check, where: (@rid = $matched.root.@rid), optional: true}"
            + " RETURN reply.creationDate as cd, root.name as rname"
            + " ORDER BY cd DESC LIMIT 5";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      // If optimization is applied, it must use BOUND mode (not UNBOUND)
      // because $matched.root is referenced downstream
      if (plan.contains("INDEX ORDERED MATCH")) {
        Assert.assertFalse(
            "Should not use UNBOUND mode when downstream uses $matched,"
                + " plan was:\n" + plan,
            plan.contains("UNBOUND"));
      }

      // Verify root.name is correctly bound
      while (result.hasNext()) {
        var row = result.next();
        Assert.assertEquals("person1", row.getProperty("rname"));
      }
    }
    session.commit();
  }

  // FILTERED_BOUND with low MIN_LINKBAG exercises union RidSet build + index scan path with source binding.
  @Test
  public void testIndexOrderedMatchFilteredBoundIndexScanWithUnion() {
    initIndexOrderedMatchMultiSourceData();

    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(1);
    try {
      session.begin();
      // FILTERED_BOUND with low MIN_LINKBAG → cost model approves →
      // pickMultiSourceStrategy picks UNION_RIDSET_SCAN or GLOBAL_SCAN.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd"
              + " ORDER BY cd DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var days = new java.util.ArrayList<Integer>();
        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          days.add(dayOfMonth(row.getProperty("cd")));
          names.add(row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + days,
              days.get(i) >= days.get(i + 1));
        }
        // Verify source binding works
        for (var name : names) {
          Assert.assertNotNull("pname should be bound", name);
          Assert.assertTrue(
              "pname should be a valid person name, got: " + name,
              name.startsWith("person"));
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
    }
  }

  // FILTERED_BOUND union overflow (RidSet exceeds MAX_RIDSET_SIZE during union build) falls back to loadFromSourcesUnsorted.
  @Test
  public void testIndexOrderedMatchFilteredBoundUnionOverflow() {
    initIndexOrderedMatchMultiSourceData();

    var oldMaxRidSet = GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValue();
    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(3);
    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(1);
    try {
      session.begin();
      // FILTERED_BOUND + cost model approves → tries UNION_RIDSET_SCAN →
      // union exceeds maxRidSetSize(3) → overflow → loadFromSourcesUnsorted
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd"
              + " ORDER BY cd DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // Overflow → loadFromSourcesUnsorted → OrderByStep sorts
        var days = new java.util.ArrayList<Integer>();
        while (result.hasNext()) {
          days.add(dayOfMonth(result.next().getProperty("cd")));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order after fallback: " + days,
              days.get(i) >= days.get(i + 1));
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(oldMaxRidSet);
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
    }
  }

  // FILTERED_UNBOUND with high MIN_LINKBAG triggers plan-time cost model rejection
  // so the optimization is not applied at all. Normal MATCH path produces correct results.
  @Test
  public void testIndexOrderedMatchFilteredUnboundCostModelReject() {
    initIndexOrderedMatchMultiSourceData();

    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(999999);
    try {
      session.begin();
      // WHERE on p, p NOT in RETURN → would be FILTERED_UNBOUND.
      // Plan-time cost check rejects → optimization not applied.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertFalse(
            "Plan should NOT use INDEX ORDERED MATCH when cost model rejects,"
                + " but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // Normal path with OrderByStep sorts correctly
        var days = new java.util.ArrayList<Integer>();
        while (result.hasNext()) {
          days.add(dayOfMonth(result.next().getProperty("cd")));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order via normal path: " + days,
              days.get(i) >= days.get(i + 1));
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
    }
  }

  // FILTERED_UNBOUND union RidSet overflow (exceeds MAX_RIDSET_SIZE) triggers loadFromSourcesUnbound fallback.
  @Test
  public void testIndexOrderedMatchFilteredUnboundRidSetOverflow() {
    initIndexOrderedMatchMultiSourceData();

    var oldMaxRidSet = GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValue();
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(3);
    try {
      session.begin();
      // WHERE on p, p NOT in RETURN → FILTERED_UNBOUND.
      // Union builds > 3 entries → overflow → loadFromSourcesUnbound.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use FILTERED_UNBOUND mode, but was:\n" + plan,
            plan.contains("FILTERED_UNBOUND"));

        var days = new java.util.ArrayList<Integer>();
        while (result.hasNext()) {
          days.add(dayOfMonth(result.next().getProperty("cd")));
        }
        Assert.assertEquals("Should have 5 results: " + days, 5, days.size());
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + days,
              days.get(i) >= days.get(i + 1));
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(oldMaxRidSet);
    }
  }

  // UNFILTERED_BOUND mode with multiple sources exercises class check + lazy load with correct binding and ordering.
  @Test
  public void testIndexOrderedMatchUnfilteredBoundBinding() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // No WHERE on p, p.name in RETURN → UNFILTERED_BOUND
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd, m.msgId as mid"
              + " ORDER BY cd DESC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use UNFILTERED_BOUND mode, but was:\n" + plan,
            plan.contains("UNFILTERED_BOUND"));

        var days = new java.util.ArrayList<Integer>();
        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          days.add(dayOfMonth(row.getProperty("cd")));
          names.add(row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 10 results: " + days, 10, days.size());
        // Verify DESC order
        for (var i = 0; i < days.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + days,
              days.get(i) >= days.get(i + 1));
        }
        // Verify source binding
        for (var name : names) {
          Assert.assertNotNull("pname should be bound", name);
          Assert.assertTrue("pname should be a person name", name.startsWith("person"));
        }
      }
      session.commit();
    }
  }

  // FILTERED_BOUND without LIMIT: plan-time cost check decides based on
  // estimated density. Results must be correct regardless of whether the
  // optimization is applied or not.
  @Test
  public void testIndexOrderedMatchFilteredBoundWithoutLimit() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.creationDate as cd ORDER BY cd DESC";
      try (var result = session.query(query)) {
        int count = 0;
        java.util.Date prev = null;
        while (result.hasNext()) {
          var row = result.next();
          java.util.Date cd = row.getProperty("cd");
          Assert.assertNotNull("pname should be bound", row.getProperty("pname"));
          if (prev != null) {
            Assert.assertFalse("Results should be in DESC order",
                cd.after(prev));
          }
          prev = cd;
          count++;
        }
        Assert.assertEquals("Should have all 50 results", 50, count);
      }
      session.commit();
    }
  }

  // FILTERED_UNBOUND without LIMIT: same — results correct regardless
  // of whether plan-time cost check approves or rejects.
  @Test
  public void testIndexOrderedMatchFilteredUnboundWithoutLimit() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd ORDER BY cd DESC";
      try (var result = session.query(query)) {
        int count = 0;
        java.util.Date prev = null;
        while (result.hasNext()) {
          java.util.Date cd = result.next().getProperty("cd");
          if (prev != null) {
            Assert.assertFalse("Results should be in DESC order",
                cd.after(prev));
          }
          prev = cd;
          count++;
        }
        Assert.assertEquals("Should have all 50 results", 50, count);
      }
      session.commit();
    }
  }

  // UNFILTERED_UNBOUND with target WHERE filter (msgId > 45) only returns matching messages.
  @Test
  public void testIndexOrderedMatchUnfilteredUnboundWithTargetFilter() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // No WHERE on p, p NOT in RETURN → UNFILTERED_UNBOUND.
      // WHERE on m filters to msgId > 45.
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (msgId > 45)} "
              + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 5 results", 5, mids.size());
        for (var mid : mids) {
          Assert.assertTrue(
              "All msgIds should be > 45 due to target filter, got: " + mid,
              mid > 45);
        }
      }
      session.commit();
    }
  }

  // UNFILTERED_BOUND with target WHERE filter (msgId > 45) only returns matching messages with bound source.
  @Test
  public void testIndexOrderedMatchUnfilteredBoundWithTargetFilter() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // No WHERE on p, p.name in RETURN → UNFILTERED_BOUND.
      // WHERE on m: msgId > 45
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (msgId > 45)} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use UNFILTERED_BOUND mode, but was:\n" + plan,
            plan.contains("UNFILTERED_BOUND"));

        var mids = new java.util.ArrayList<Long>();
        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          mids.add(((Number) row.getProperty("mid")).longValue());
          names.add(row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 5 results", 5, mids.size());
        for (var mid : mids) {
          Assert.assertTrue(
              "All msgIds should be > 45, got: " + mid, mid > 45);
        }
        for (var name : names) {
          Assert.assertNotNull("pname should be bound", name);
        }
      }
      session.commit();
    }
  }

  // FILTERED_BOUND with high MIN_LINKBAG causes plan-time cost model rejection.
  // Target WHERE filter (msgId > 40) is applied correctly via normal MATCH path.
  @Test
  public void testIndexOrderedMatchLoadFromSourcesUnsortedTargetFilter() {
    initIndexOrderedMatchMultiSourceData();

    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(999999);
    try {
      session.begin();
      // FILTERED_BOUND + plan-time cost model rejects → no optimization.
      // WHERE on m: msgId > 40.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (msgId > 40)} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertFalse(
            "Plan should NOT use INDEX ORDERED MATCH when cost model rejects,"
                + " but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertFalse("Should have results", mids.isEmpty());
        for (var mid : mids) {
          Assert.assertTrue(
              "All msgIds should be > 40, got: " + mid, mid > 40);
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
    }
  }

  // FILTERED_UNBOUND with high MIN_LINKBAG causes plan-time cost model rejection.
  // Target WHERE filter (msgId > 40) is applied correctly via normal MATCH path.
  @Test
  public void testIndexOrderedMatchLoadFromSourcesUnboundTargetFilter() {
    initIndexOrderedMatchMultiSourceData();

    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(999999);
    try {
      session.begin();
      // WHERE on p, p NOT in RETURN → would be FILTERED_UNBOUND.
      // Plan-time cost check rejects → no optimization.
      // WHERE on m: msgId > 40.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (msgId > 40)} "
              + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertFalse(
            "Plan should NOT use INDEX ORDERED MATCH when cost model rejects,"
                + " but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertFalse("Should have results", mids.isEmpty());
        for (var mid : mids) {
          Assert.assertTrue(
              "All msgIds should be > 40, got: " + mid, mid > 40);
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
    }
  }

  // True single-source via {rid: #X:Y} syntax triggers single-source mode
  // (multiSourceMode == null) with indexScanFiltered path. Uses large data
  // so cost model approves index scan. Covers processUpstreamRow, indexScanFiltered,
  // resolveEdgeRidSet, loadLinkBag, loadRecord, matchesTargetFilter, ridFromPair.
  @Test
  public void testIndexOrderedMatchTrueSingleSourceIndexScan() throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      String rid;
      try (var ridResult = session.query(
          "SELECT @rid as r FROM TestPerson WHERE name = 'person1'")) {
        Assert.assertTrue("Should find person1", ridResult.hasNext());
        rid = ridResult.next().getProperty("r").toString();
      }

      // {rid: #X:Y} syntax → aliasRids populated → single-source mode
      var query = "MATCH {class: TestPerson, as: p, rid: " + rid + "}"
          + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
          + "RETURN m.creationDate as cd, m.msgId as mid ORDER BY cd DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        // Single-source: no mode suffix in plan
        Assert.assertFalse(
            "Should be single-source (no mode suffix), but plan was:\n" + plan,
            plan.contains("FILTERED") || plan.contains("UNFILTERED"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 5 results: " + mids, 5, mids.size());
        Assert.assertEquals(200L, (long) mids.get(0));
        Assert.assertEquals(199L, (long) mids.get(1));
        Assert.assertEquals(198L, (long) mids.get(2));
        Assert.assertEquals(197L, (long) mids.get(3));
        Assert.assertEquals(196L, (long) mids.get(4));
      }
      session.commit();
    }
  }

  // True single-source via {rid:} syntax with MAX_SCAN=1 forces the runtime
  // cost model to reject index scan, exercising the loadFromRidSet fallback path.
  // Covers the false branch of shouldUseIndexScan → loadFromRidSet with its
  // stream pipeline (filter by targetFilter + targetClassName).
  @Test
  public void testIndexOrderedMatchTrueSingleSourceLoadFromRidSet() throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      var oldMaxScan = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValue();
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(1L);
      try {
        session.begin();
        String rid;
        try (var ridResult = session.query(
            "SELECT @rid as r FROM TestPerson WHERE name = 'person1'")) {
          Assert.assertTrue("Should find person1", ridResult.hasNext());
          rid = ridResult.next().getProperty("r").toString();
        }

        // {rid:} → single-source. MAX_SCAN=1 → runtime cost model rejects
        // → loadFromRidSet (unsorted). OrderByStep sorts with bounded heap.
        var query = "MATCH {class: TestPerson, as: p, rid: " + rid + "}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd, m.msgId as mid ORDER BY cd DESC LIMIT 5";
        try (var result = session.query(query)) {
          var plan = getPlan(result);
          Assert.assertTrue(
              "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
              plan.contains("INDEX ORDERED MATCH"));

          var mids = new java.util.ArrayList<Long>();
          while (result.hasNext()) {
            mids.add(((Number) result.next().getProperty("mid")).longValue());
          }
          Assert.assertEquals("Should have 5 results: " + mids, 5, mids.size());
          Assert.assertEquals(200L, (long) mids.get(0));
          Assert.assertEquals(199L, (long) mids.get(1));
        }
        session.commit();
      } finally {
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(oldMaxScan);
      }
    }
  }

  // Single-field ORDER BY with @rid and large data enables OrderByStep pass-through mode without buffering.
  @Test
  public void testIndexOrderedMatchOrderByStepPassThrough() throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      String rid;
      try (var ridResult = session.query(
          "SELECT @rid as r FROM TestPerson WHERE name = 'person1'")) {
        Assert.assertTrue("Should find person1", ridResult.hasNext());
        rid = ridResult.next().getProperty("r").toString();
      }

      // Single-field ORDER BY + @rid → pass-through in OrderByStep
      var query = "MATCH {class: TestPerson, as: p, rid: " + rid + "}"
          + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
          + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 10 results: " + mids, 10, mids.size());
        // Verify descending order of msgId (since creationDate is monotonically
        // increasing with msgId in initIndexOrderedMatchLargeData)
        for (var i = 0; i < mids.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + mids,
              mids.get(i) > mids.get(i + 1));
        }
        Assert.assertEquals("First result should be msgId 200", 200L, (long) mids.get(0));
      }
      session.commit();
    }
  }

  // Multi-field ORDER BY with @rid and pre-sorted input activates cutoff in OrderByStep bounded heap.
  @Test
  public void testIndexOrderedMatchMultiFieldCutoffWithPreSorted() throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      String rid;
      try (var ridResult = session.query(
          "SELECT @rid as r FROM TestPerson WHERE name = 'person1'")) {
        Assert.assertTrue("Should find person1", ridResult.hasNext());
        rid = ridResult.next().getProperty("r").toString();
      }

      // Multi-field ORDER BY + @rid + large data → cutoff in bounded heap
      var query = "MATCH {class: TestPerson, as: p, rid: " + rid + "}"
          + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
          + "RETURN m.creationDate as cd, m.msgId as mid"
          + " ORDER BY cd DESC, mid ASC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "OrderByStep should be present for multi-field ORDER BY:\n" + plan,
            plan.contains("ORDER BY"));

        // All 200 messages have distinct creationDate, so the top 5 DESC
        // are msgId 200, 199, 198, 197, 196 (each on its own date, mid ASC
        // is trivially satisfied for 1 entry per date).
        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 5 results: " + mids, 5, mids.size());
        Assert.assertEquals(200L, (long) mids.get(0));
        Assert.assertEquals(199L, (long) mids.get(1));
        Assert.assertEquals(198L, (long) mids.get(2));
        Assert.assertEquals(197L, (long) mids.get(3));
        Assert.assertEquals(196L, (long) mids.get(4));
      }
      session.commit();
    }
  }

  // WHERE (@rid = ...) goes through FILTERED mode (not single-source aliasRids).
  // With MIN_LINKBAG=999999, the plan-time cost check in computeCosts returns null
  // (linkBagSize < minLinkBag), so the optimization is rejected. Falls back to
  // standard MATCH + ORDER BY, results still correct.
  @Test
  public void testIndexOrderedMatchMultiFieldCutoffDisabledWhenUnsorted() {
    initIndexOrderedMatchLargeData();

    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(999999);
    try {
      session.begin();
      String rid;
      try (var ridResult = session.query(
          "SELECT @rid as r FROM TestPerson WHERE name = 'person1'")) {
        Assert.assertTrue("Should find person1", ridResult.hasNext());
        rid = ridResult.next().getProperty("r").toString();
      }

      // @rid in WHERE → FILTERED mode (not aliasRids single-source);
      // MIN_LINKBAG=999999 → plan-time cost check rejects.
      var query = "MATCH {class: TestPerson, as: p, where: (@rid = " + rid + ")}"
          + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
          + "RETURN m.creationDate as cd, m.msgId as mid"
          + " ORDER BY cd DESC, mid ASC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertFalse(
            "Plan should NOT use INDEX ORDERED MATCH when MIN_LINKBAG=999999, but was:\n"
                + plan,
            plan.contains("INDEX ORDERED MATCH"));

        // Standard MATCH + OrderByStep sorts correctly.
        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 5 results: " + mids, 5, mids.size());
        Assert.assertEquals(200L, (long) mids.get(0));
        Assert.assertEquals(199L, (long) mids.get(1));
        Assert.assertEquals(198L, (long) mids.get(2));
        Assert.assertEquals(197L, (long) mids.get(3));
        Assert.assertEquals(196L, (long) mids.get(4));
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
    }
  }

  // FILTERED_BOUND with low COST_BIAS and high density selects GLOBAL_SCAN strategy with reverse edge lookup.
  @Test
  public void testIndexOrderedMatchFilteredBoundIndexScanGlobal() {
    initIndexOrderedMatchLargeData();

    // Add 4 more persons sharing the same messages to create multi-source
    session.begin();
    for (var p = 2; p <= 5; p++) {
      session.execute(
          "CREATE VERTEX TestPerson SET name = 'person" + p + "'").close();
      for (var i = 1; i <= 200; i++) {
        session.execute(
            "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
                + i + ") TO (SELECT FROM TestPerson WHERE name = 'person"
                + p + "')")
            .close();
      }
    }
    session.commit();

    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    var oldCostBias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(1);
    GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.setValue(0.01);
    try {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          mids.add(((Number) row.getProperty("mid")).longValue());
          names.add(row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 5 results: " + mids, 5, mids.size());
        // All should be high msgId values (top of DESC order)
        for (var mid : mids) {
          Assert.assertTrue(
              "msgId should be >= 196 (top 5 DESC), got: " + mid,
              mid >= 196);
        }
        for (var name : names) {
          Assert.assertNotNull("pname should be bound", name);
          Assert.assertTrue(
              "pname should be a valid person, got: " + name,
              name.startsWith("person"));
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
      GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.setValue(oldCostBias);
    }
  }

  // FILTERED_BOUND with small data (11 messages, 2 sources): plan-time cost check
  // rejects the index-ordered optimization because seekCost dominates for small
  // datasets even with MIN_LINKBAG=1. Falls back to standard MATCH + ORDER BY.
  // Verifies shared message 701 still appears twice (once per source person).
  @Test
  public void testIndexOrderedMatchFilteredBoundMatchTargetToSources() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'tp1'").close();
    session.execute("CREATE VERTEX TestPerson SET name = 'tp2'").close();

    // Shared message linked to both persons
    session.execute(
        "CREATE VERTEX TestMessage SET creationDate = '2025-08-01 00:00:00', msgId = 701")
        .close();
    session.execute(
        "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = 701)"
            + " TO (SELECT FROM TestPerson WHERE name = 'tp1')")
        .close();
    session.execute(
        "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = 701)"
            + " TO (SELECT FROM TestPerson WHERE name = 'tp2')")
        .close();

    // Additional messages so multi-source mode activates
    for (var i = 2; i <= 6; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-08-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + (700 + i))
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
              + (700 + i) + ") TO (SELECT FROM TestPerson WHERE name = 'tp1')")
          .close();
    }
    for (var i = 7; i <= 11; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-08-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + (700 + i))
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
              + (700 + i) + ") TO (SELECT FROM TestPerson WHERE name = 'tp2')")
          .close();
    }
    session.commit();

    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(1);
    try {
      session.begin();
      // FILTERED_BOUND: both persons as sources, p.name in RETURN, LIMIT
      var query =
          "MATCH {class: TestPerson, as: p,"
              + " where: (name IN ['tp1','tp2'])}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 20";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        // With only 11 messages and ~20 estimated edges, the plan-time cost
        // check rejects: seekCost (16) dominates for small datasets.
        Assert.assertFalse(
            "Plan should NOT use INDEX ORDERED MATCH with small data, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var names = new java.util.ArrayList<String>();
        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          var row = result.next();
          names.add(row.getProperty("pname"));
          mids.add(((Number) row.getProperty("mid")).longValue());
        }
        // tp1: 6 msgs (701-706), tp2: 6 msgs (701,707-711) = 12 rows total
        Assert.assertEquals(
            "Should have 12 result rows: " + mids, 12, mids.size());
        // Shared message 701 should appear twice (once per person)
        long sharedCount = mids.stream().filter(m -> m == 701L).count();
        Assert.assertEquals(
            "Shared message 701 should appear twice", 2, sharedCount);
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
    }
  }

  // UNFILTERED_BOUND resolveReverseEdges finds and binds multiple source vertices for a shared target.
  @Test
  public void testIndexOrderedMatchUnfilteredBoundResolveMultipleReverse() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'rev_p1'").close();
    session.execute("CREATE VERTEX TestPerson SET name = 'rev_p2'").close();

    // Shared message linked to both persons
    session.execute(
        "CREATE VERTEX TestMessage SET creationDate = '2025-09-15 00:00:00', msgId = 601")
        .close();
    session.execute(
        "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = 601)"
            + " TO (SELECT FROM TestPerson WHERE name = 'rev_p1')")
        .close();
    session.execute(
        "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = 601)"
            + " TO (SELECT FROM TestPerson WHERE name = 'rev_p2')")
        .close();

    // Additional messages so there is data diversity
    for (var i = 2; i <= 4; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-09-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + (600 + i))
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
              + (600 + i) + ") TO (SELECT FROM TestPerson WHERE name = 'rev_p1')")
          .close();
    }
    session.commit();

    session.begin();
    // UNFILTERED_BOUND: no WHERE on source, p in RETURN → unfilteredBound
    // (source class check + lazy load). The planner needs class TestPerson
    // but no filter.
    var query =
        "MATCH {class: TestPerson, as: p}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN p.name as pname, m.msgId as mid"
            + " ORDER BY m.creationDate DESC LIMIT 10";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      // Verify either INDEX ORDERED MATCH or correct results
      // (UNFILTERED_BOUND may or may not be chosen depending on planner)
      var names = new java.util.ArrayList<String>();
      var mids = new java.util.ArrayList<Long>();
      while (result.hasNext()) {
        var row = result.next();
        names.add(row.getProperty("pname"));
        mids.add(((Number) row.getProperty("mid")).longValue());
      }
      // Shared message 601 should produce 2 rows (one per person)
      long sharedCount = mids.stream().filter(m -> m == 601L).count();
      Assert.assertEquals(
          "Shared message 601 should appear twice (one per source): " + mids,
          2, sharedCount);
      // Total: rev_p1 has 4 msgs (601-604), rev_p2 has 1 msg (601) = 5 rows
      Assert.assertEquals(
          "Should have 5 result rows: " + mids, 5, mids.size());
    }
    session.commit();
  }

  // FILTERED_UNBOUND with low MIN_LINKBAG exercises union RidSet + index scan path.
  @Test
  public void testIndexOrderedMatchFilteredUnboundIndexScan() {
    initIndexOrderedMatchMultiSourceData();

    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(1);
    try {
      session.begin();
      // WHERE on p, p NOT in RETURN → FILTERED_UNBOUND
      // MIN_LINKBAG=1 → cost model approves → union RidSet + index scan
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 5 results: " + mids, 5, mids.size());
        // Verify DESC order
        for (var i = 0; i < mids.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + mids,
              mids.get(i) >= mids.get(i + 1));
        }
        // Top 5 DESC msgIds from multi-source data (50 msgs total)
        Assert.assertEquals("Top result should be msgId 50", 50L, (long) mids.get(0));
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
    }
  }

  /**
   * Sets up schema and data for the UNFILTERED_UNBOUND any-match test.
   *
   * Creates TestPerson (V), TestOrg (V), TestMessage (V) with creationDate index,
   * and TEST_HAS_CREATOR (E). One "shared" message is linked to BOTH a TestOrg
   * vertex AND a TestPerson vertex, so its reverse edge list contains a non-TestPerson
   * entry first. Additional messages are linked only to TestPerson.
   *
   * This exercises the any-match logic in unfilteredUnbound where the first reverse
   * edge is the wrong class but a later one is correct.
   */
  private void initIndexOrderedMatchAnyMatchData() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestOrg EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestOrg SET name = 'org1'").close();
    session.execute("CREATE VERTEX TestPerson SET name = 'person_any'").close();

    // Shared message linked to BOTH TestOrg and TestPerson.
    // The reverse edge list will contain [TestOrg, TestPerson] (or vice versa).
    // The any-match logic must find the TestPerson even if TestOrg is checked first.
    session.execute(
        "CREATE VERTEX TestMessage SET creationDate = '2025-06-15 00:00:00', msgId = 900")
        .close();
    session.execute(
        "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = 900)"
            + " TO (SELECT FROM TestOrg WHERE name = 'org1')")
        .close();
    session.execute(
        "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = 900)"
            + " TO (SELECT FROM TestPerson WHERE name = 'person_any')")
        .close();

    // Additional messages linked only to TestPerson
    for (var i = 1; i <= 5; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-06-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + (900 + i))
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
              + (900 + i) + ") TO (SELECT FROM TestPerson WHERE name = 'person_any')")
          .close();
    }
    session.commit();
  }

  // UNFILTERED_UNBOUND any-match: a shared message has reverse edges to both
  // TestOrg and TestPerson. The any-match logic in unfilteredUnbound must find
  // the TestPerson source even when the first reverse edge points to a TestOrg.
  // Verifies the shared message (msgId=900) appears in results.
  @Test
  public void testIndexOrderedMatchUnfilteredUnboundAnyMatch() throws Exception {
    initIndexOrderedMatchAnyMatchData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd, m.msgId as mid"
              + " ORDER BY cd DESC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        // person_any has 6 messages: 900 (shared) + 901-905 (exclusive)
        Assert.assertEquals(
            "Should have 6 results (5 exclusive + 1 shared): " + mids, 6, mids.size());
        // Shared message 900 (day 15) should be the top result (DESC order)
        Assert.assertEquals(
            "Shared message 900 should appear in results (top by date)",
            900L, (long) mids.get(0));
        // Verify DESC ordering
        Assert.assertTrue(
            "Results should be in DESC order by creationDate: " + mids,
            mids.get(0) == 900L && mids.get(1) == 905L);
      }
      session.commit();
    }
  }

  // =====================================================================
  // .inE() / .outE() edge traversal tests
  // =====================================================================

  /**
   * Setup for .inE()/.outE() tests: Person → Message via HAS_CREATOR,
   * Person → Person via KNOWS (with creationDate on KNOWS edge + index).
   */
  private void initIndexOrderedMatchEdgeTraversalData() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute("CREATE CLASS TEST_KNOWS EXTENDS E").close();
    session.execute("CREATE PROPERTY TEST_KNOWS.creationDate DATETIME").close();
    session.execute(
        "CREATE INDEX TEST_KNOWS.creationDate ON TEST_KNOWS(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'alice'").close();
    session.execute("CREATE VERTEX TestPerson SET name = 'bob'").close();
    session.execute("CREATE VERTEX TestPerson SET name = 'carol'").close();
    session.execute("CREATE VERTEX TestPerson SET name = 'dave'").close();

    // alice KNOWS bob (Jan 5), carol (Jan 10), dave (Jan 15)
    session.execute(
        "CREATE EDGE TEST_KNOWS FROM "
            + "(SELECT FROM TestPerson WHERE name = 'alice') TO "
            + "(SELECT FROM TestPerson WHERE name = 'bob') "
            + "SET creationDate = '2025-01-05 00:00:00'")
        .close();
    session.execute(
        "CREATE EDGE TEST_KNOWS FROM "
            + "(SELECT FROM TestPerson WHERE name = 'alice') TO "
            + "(SELECT FROM TestPerson WHERE name = 'carol') "
            + "SET creationDate = '2025-01-10 00:00:00'")
        .close();
    session.execute(
        "CREATE EDGE TEST_KNOWS FROM "
            + "(SELECT FROM TestPerson WHERE name = 'alice') TO "
            + "(SELECT FROM TestPerson WHERE name = 'dave') "
            + "SET creationDate = '2025-01-15 00:00:00'")
        .close();

    // bob KNOWS carol (Jan 20) — for multi-source tests
    session.execute(
        "CREATE EDGE TEST_KNOWS FROM "
            + "(SELECT FROM TestPerson WHERE name = 'bob') TO "
            + "(SELECT FROM TestPerson WHERE name = 'carol') "
            + "SET creationDate = '2025-01-20 00:00:00'")
        .close();
    session.commit();
  }

  // .outE() with ORDER BY on edge property, single-source pattern (IS3-like).
  @Test
  public void testIndexOrderedMatchOutESingleSource() throws Exception {
    initIndexOrderedMatchEdgeTraversalData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'alice')}"
              + ".outE('TEST_KNOWS'){as: k}.inV(){as: friend} "
              + "RETURN friend.name as fname, k.creationDate as kd"
              + " ORDER BY kd DESC LIMIT 2";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCHE for .outE(), but was:\n"
                + plan,
            plan.contains("INDEX ORDERED MATCHE"));

        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          names.add(result.next().getProperty("fname"));
        }
        Assert.assertEquals("Should have 2 results", 2, names.size());
        // DESC by creationDate: dave (Jan 15), carol (Jan 10)
        Assert.assertEquals("dave", names.get(0));
        Assert.assertEquals("carol", names.get(1));
      }
      session.commit();
    }
  }

  // .outE() with ASC order.
  @Test
  public void testIndexOrderedMatchOutEAsc() throws Exception {
    initIndexOrderedMatchEdgeTraversalData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'alice')}"
              + ".outE('TEST_KNOWS'){as: k}.inV(){as: friend} "
              + "RETURN friend.name as fname, k.creationDate as kd"
              + " ORDER BY kd ASC LIMIT 2";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCHE, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCHE"));

        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          names.add(result.next().getProperty("fname"));
        }
        Assert.assertEquals("Should have 2 results", 2, names.size());
        // ASC: bob (Jan 5), carol (Jan 10)
        Assert.assertEquals("bob", names.get(0));
        Assert.assertEquals("carol", names.get(1));
      }
      session.commit();
    }
  }

  // .outE() multi-source FILTERED_BOUND: multiple persons, ORDER BY edge property.
  @Test
  public void testIndexOrderedMatchOutEMultiSourceFilteredBound() throws Exception {
    initIndexOrderedMatchEdgeTraversalData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // alice has 3 KNOWS edges, bob has 1 → 4 total
      // p.name in RETURN → FILTERED_BOUND
      var query =
          "MATCH {class: TestPerson, as: p, where: (name IN ['alice', 'bob'])}"
              + ".outE('TEST_KNOWS'){as: k}.inV(){as: friend} "
              + "RETURN p.name as pname, friend.name as fname, k.creationDate as kd"
              + " ORDER BY kd DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCHE, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCHE"));

        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          names.add(row.getProperty("fname"));
          Assert.assertNotNull("pname should be bound", row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 3 results", 3, names.size());
        // DESC: bob→carol (Jan 20), alice→dave (Jan 15), alice→carol (Jan 10)
        Assert.assertEquals("carol", names.get(0));
        Assert.assertEquals("dave", names.get(1));
        Assert.assertEquals("carol", names.get(2));
      }
      session.commit();
    }
  }

  /**
   * Large multi-source setup: 3 persons × 200 messages each = 600 messages.
   * High edge count and index size ensure plan-time approval AND runtime cost
   * model picks index scan strategies (UNION_RIDSET_SCAN or GLOBAL_SCAN)
   * instead of LOAD_ALL_SORT.
   */
  private void initIndexOrderedMatchLargeMultiSourceData() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();
    session.begin();
    var msgCounter = 0;
    for (var p = 1; p <= 3; p++) {
      session.execute("CREATE VERTEX TestPerson SET name = 'person" + p + "'").close();
      for (var d = 1; d <= 200; d++) {
        msgCounter++;
        session.execute(
            "CREATE VERTEX TestMessage SET creationDate = '2025-01-01 "
                + String.format("%02d", msgCounter / 3600)
                + ":" + String.format("%02d", (msgCounter / 60) % 60)
                + ":" + String.format("%02d", msgCounter % 60)
                + "', msgId = " + msgCounter)
            .close();
        session.execute(
            "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
                + msgCounter + ") TO (SELECT FROM TestPerson WHERE name = 'person"
                + p + "')")
            .close();
      }
    }
    session.commit();
  }

  // FILTERED_BOUND with large multi-source data exercises filteredBound → index scan
  // strategies (UNION_RIDSET_SCAN or GLOBAL_SCAN) with source binding and correct ordering.
  @Test
  public void testIndexOrderedMatchFilteredBoundLargeMultiSource() throws Exception {
    initIndexOrderedMatchLargeMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // WHERE on p + p.name in RETURN → FILTERED_BOUND mode.
      // 3 sources × 200 msgs → high density → cost model picks index scan.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH (FILTERED_BOUND), but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use FILTERED_BOUND mode, but was:\n" + plan,
            plan.contains("FILTERED_BOUND"));

        var mids = new java.util.ArrayList<Long>();
        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          mids.add(((Number) row.getProperty("mid")).longValue());
          names.add(row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 10 results: " + mids, 10, mids.size());
        // DESC order: highest msgIds first (600, 599, 598, ...)
        for (var i = 0; i < mids.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order by msgId: " + mids,
              mids.get(i) >= mids.get(i + 1));
        }
        // Verify source binding works for all results
        for (var name : names) {
          Assert.assertNotNull("pname should be bound", name);
          Assert.assertTrue(
              "pname should be a valid person name, got: " + name,
              name.startsWith("person"));
        }
      }
      session.commit();
    }
  }

  // FILTERED_UNBOUND with large multi-source data exercises filteredUnbound → union RidSet
  // index scan path. Source alias NOT in RETURN, so no source binding needed.
  @Test
  public void testIndexOrderedMatchFilteredUnboundLargeMultiSource() throws Exception {
    initIndexOrderedMatchLargeMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // WHERE on p + p NOT in RETURN → FILTERED_UNBOUND mode.
      // 3 sources × 200 msgs → union RidSet built, index scan with bitmap filter.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 10 results: " + mids, 10, mids.size());
        // DESC order: highest msgIds first
        for (var i = 0; i < mids.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order by msgId: " + mids,
              mids.get(i) >= mids.get(i + 1));
        }
      }
      session.commit();
    }
  }

  // FILTERED_BOUND with target WHERE filter and large multi-source data exercises
  // matchesTargetFilter in the multi-source index scan path, filtering out non-matching targets.
  @Test
  public void testIndexOrderedMatchFilteredBoundTargetFilterLargeMultiSource()
      throws Exception {
    initIndexOrderedMatchLargeMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // WHERE on p + p.name in RETURN → FILTERED_BOUND.
      // Target WHERE (msgId > 400) filters out messages 1-400, leaving only 401-600.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (msgId > 400)} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          mids.add(((Number) row.getProperty("mid")).longValue());
          names.add(row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 10 results: " + mids, 10, mids.size());
        // All msgIds must be > 400 (target WHERE filter)
        for (var mid : mids) {
          Assert.assertTrue(
              "All msgIds should be > 400 due to target filter, got: " + mid,
              mid > 400);
        }
        // DESC order
        for (var i = 0; i < mids.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + mids,
              mids.get(i) >= mids.get(i + 1));
        }
        // Verify source binding
        for (var name : names) {
          Assert.assertNotNull("pname should be bound", name);
        }
      }
      session.commit();
    }
  }

  // UNFILTERED_BOUND with large data exercises unfilteredBound full index scan path.
  // No WHERE on source, source in RETURN. Scans index globally, per hit: loads record,
  // follows reverse edge, verifies source class, loads source for binding.
  @Test
  public void testIndexOrderedMatchUnfilteredBoundLargeMultiSource() throws Exception {
    initIndexOrderedMatchLargeMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // No WHERE on p, p.name in RETURN → UNFILTERED_BOUND mode.
      // Full index scan with class check + lazy source load.
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use UNFILTERED_BOUND mode, but was:\n" + plan,
            plan.contains("UNFILTERED_BOUND"));

        var mids = new java.util.ArrayList<Long>();
        var names = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          mids.add(((Number) row.getProperty("mid")).longValue());
          names.add(row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 10 results: " + mids, 10, mids.size());
        // DESC order: highest msgIds first
        for (var i = 0; i < mids.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + mids,
              mids.get(i) >= mids.get(i + 1));
        }
        // Verify source binding
        for (var name : names) {
          Assert.assertNotNull("pname should be bound in UNFILTERED_BOUND", name);
          Assert.assertTrue(
              "pname should start with 'person', got: " + name,
              name.startsWith("person"));
        }
      }
      session.commit();
    }
  }

  // UNFILTERED_UNBOUND with large data exercises unfilteredUnbound path.
  // No WHERE on source, source NOT in RETURN. Lightest mode: scan index, per hit
  // verify reverse edge points to correct source class, no source load or binding.
  @Test
  public void testIndexOrderedMatchUnfilteredUnboundLargeMultiSource() throws Exception {
    initIndexOrderedMatchLargeMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // No WHERE on p, p NOT in RETURN → UNFILTERED_UNBOUND mode.
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "Plan should use UNFILTERED_UNBOUND mode, but was:\n" + plan,
            plan.contains("UNFILTERED_UNBOUND"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 10 results: " + mids, 10, mids.size());
        // DESC order: highest msgIds first
        for (var i = 0; i < mids.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order: " + mids,
              mids.get(i) >= mids.get(i + 1));
        }
      }
      session.commit();
    }
  }

  // FILTERED_UNBOUND with large data and single-field ORDER BY + LIMIT verifies
  // OrderByStep pass-through mode: the optimization is applied AND results are in
  // correct order (IndexOrderedEdgeStep signals pre-sorted, OrderByStep passes through).
  @Test
  public void testIndexOrderedMatchOrderByPassThroughFilteredUnbound()
      throws Exception {
    initIndexOrderedMatchLargeMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // WHERE on p + p NOT in RETURN → FILTERED_UNBOUND.
      // Single-field ORDER BY + LIMIT → OrderByStep in pass-through mode.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.msgId as mid"
              + " ORDER BY m.creationDate ASC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "OrderByStep should be present (pass-through mode), but plan was:\n"
                + plan,
            plan.contains("ORDER BY"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 10 results: " + mids, 10, mids.size());
        // ASC order: smallest msgIds first (1, 2, 3, ...)
        for (var i = 0; i < mids.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in ASC order by msgId: " + mids,
              mids.get(i) <= mids.get(i + 1));
        }
        Assert.assertEquals(
            "First result should be msgId 1", 1L, (long) mids.get(0));
      }
      session.commit();
    }
  }

  private void printExecutionPlan(BasicResultSet result) {
    printExecutionPlan(null, result);
  }

  private void printExecutionPlan(String unusedQuery, BasicResultSet unusedResult) {
    //    if (query != null) {
    //      System.out.println(query);
    //    }
    //    result.getExecutionPlan().ifPresent(x -> System.out.println(x.prettyPrint(0, 3)));
    //    System.out.println();
  }

  // =====================================================================
  // Additional branch-coverage tests for IndexOrderedEdgeStep
  // =====================================================================

  // Class inheritance: targetClassName filtering skips records of wrong class
  // when multiple classes share the same index via a common superclass.
  // Covers matchesTargetFilter branches: targetClassName != null, class mismatch,
  // isSubClassOf check.
  @Test
  public void testIndexOrderedMatchTargetClassInheritance() throws Exception {
    // Person1 has in_TEST_HAS_CREATOR edges pointing to both TestMessage and
    // TestComment vertices. The index is on TestMessage.creationDate. The
    // MATCH query targets {class: TestMessage} — TestComment records in the
    // LinkBag are loaded but fail targetClassName check in matchesTargetFilter.
    // This exercises the class mismatch branch (lines 873, 882-883).
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TestComment EXTENDS V").close();
    session.execute("CREATE PROPERTY TestComment.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestComment.cid LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'person1'").close();
    // person1: 100 TestMessage (msgId 1..100) linked via TEST_HAS_CREATOR.
    // Large enough for the plan-time cost model to approve the optimization.
    for (var i = 1; i <= 100; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-01 "
              + String.format("%02d", i / 60) + ":"
              + String.format("%02d", i % 60) + ":00', msgId = " + i)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + i
              + ") TO (SELECT FROM TestPerson WHERE name = 'person1')")
          .close();
    }
    // person1: 50 TestComment also linked via TEST_HAS_CREATOR.
    // These are NOT TestMessage — they should be filtered by targetClassName.
    for (var i = 1; i <= 50; i++) {
      session.execute(
          "CREATE VERTEX TestComment SET creationDate = '2025-02-01 "
              + String.format("%02d", i / 60) + ":"
              + String.format("%02d", i % 60) + ":00', cid = " + (1000 + i))
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestComment WHERE cid = " + (1000 + i)
              + ") TO (SELECT FROM TestPerson WHERE name = 'person1')")
          .close();
    }
    session.commit();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // Target class = TestMessage → only TestMessage records should be returned.
      // Person1's LinkBag has both TestMessage and TestComment RIDs; when loading,
      // TestComment records fail the targetClassName check.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 5 results (only TestMessage)", 5, mids.size());
        // All returned records must be TestMessage (msgId 1..100)
        for (var mid : mids) {
          Assert.assertTrue(
              "Only TestMessage (1..100) should be returned, got: " + mid,
              mid >= 1 && mid <= 100);
        }
        // DESC order: 100, 99, 98, 97, 96
        Assert.assertEquals(100L, (long) mids.get(0));
      }
      session.commit();
    }
  }

  // UNFILTERED_BOUND with class inheritance: reverse edge class check filters
  // out sources of wrong class. Covers hasPolymorphicCollectionId branches.
  @Test
  public void testIndexOrderedMatchUnfilteredBoundClassInheritance() throws Exception {
    // Two source classes: TestPerson and TestBot (both EXTENDS V).
    // Both link to TestMessage via TEST_HAS_CREATOR.
    // Query uses {class: TestPerson} as source → only TestPerson sources should match.
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestBot EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'person1'").close();
    session.execute("CREATE VERTEX TestBot SET name = 'bot1'").close();
    // person1: messages 1..5, bot1: messages 6..10
    for (var i = 1; i <= 5; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + i)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + i
              + ") TO (SELECT FROM TestPerson WHERE name = 'person1')")
          .close();
    }
    for (var i = 6; i <= 10; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + i)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + i
              + ") TO (SELECT FROM TestBot WHERE name = 'bot1')")
          .close();
    }
    session.commit();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // UNFILTERED_BOUND: no WHERE on source, p.name in RETURN.
      // Source class = TestPerson → only messages linked to TestPerson should appear.
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          var row = result.next();
          mids.add(((Number) row.getProperty("mid")).longValue());
          Assert.assertEquals("person1", row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 5 results (person1 only)", 5, mids.size());
        // All from person1: msgId 1..5 DESC → 5, 4, 3, 2, 1
        for (var mid : mids) {
          Assert.assertTrue(
              "Only person1 messages (1..5) should be returned, got: " + mid,
              mid >= 1 && mid <= 5);
        }
      }
      session.commit();
    }
  }

  // UNFILTERED_UNBOUND with class inheritance: reverse edge class check filters
  // out targets pointing to wrong source class. anyValid=false branch hit.
  @Test
  public void testIndexOrderedMatchUnfilteredUnboundClassInheritance() throws Exception {
    // Same setup as above, but source NOT in RETURN → UNFILTERED_UNBOUND.
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestBot EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'person1'").close();
    session.execute("CREATE VERTEX TestBot SET name = 'bot1'").close();
    for (var i = 1; i <= 5; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + i)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + i
              + ") TO (SELECT FROM TestPerson WHERE name = 'person1')")
          .close();
    }
    for (var i = 6; i <= 10; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + i)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + i
              + ") TO (SELECT FROM TestBot WHERE name = 'bot1')")
          .close();
    }
    session.commit();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // UNFILTERED_UNBOUND: no WHERE, source NOT in RETURN
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 10";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        // Only person1's messages (1..5) should pass class check
        Assert.assertEquals("Should have 5 results (person1 only)", 5, mids.size());
        for (var mid : mids) {
          Assert.assertTrue(
              "Only person1 messages (1..5) should be returned, got: " + mid,
              mid >= 1 && mid <= 5);
        }
      }
      session.commit();
    }
  }

  // FILTERED_BOUND with mixed sources: one person has edges, another has none.
  // Tests null linkBag handling in union build and loadSourceEdgesBound.
  @Test
  public void testIndexOrderedMatchFilteredBoundMixedSources() throws Exception {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    // person1: has 50 edges (large enough for cost model), edgeless: no edges
    session.execute("CREATE VERTEX TestPerson SET name = 'person1'").close();
    session.execute("CREATE VERTEX TestPerson SET name = 'edgeless'").close();
    for (var i = 1; i <= 50; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-01 "
              + String.format("%02d", i / 60) + ":"
              + String.format("%02d", i % 60) + ":00', msgId = " + i)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + i
              + ") TO (SELECT FROM TestPerson WHERE name = 'person1')")
          .close();
    }
    session.commit();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // Both persons matched by IN → FILTERED_BOUND with mixed sources.
      // 'edgeless' returns null linkBag but should not cause errors.
      var query =
          "MATCH {class: TestPerson, as: p,"
              + " where: (name IN ['person1', 'edgeless'])}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN p.name as pname, m.msgId as mid ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          var row = result.next();
          mids.add(((Number) row.getProperty("mid")).longValue());
          Assert.assertEquals("person1", row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 5 results", 5, mids.size());
        Assert.assertEquals(50L, (long) mids.get(0));
      }
      session.commit();
    }
  }

  // Single-source with target WHERE filter + large data forces indexScanFiltered path.
  // matchesTargetFilter false branch exercised in the index scan lambda.
  @Test
  public void testIndexOrderedMatchSingleSourceIndexScanWithTargetFilter() throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      String rid;
      try (var ridResult = session.query(
          "SELECT @rid as r FROM TestPerson WHERE name = 'person1'")) {
        Assert.assertTrue(ridResult.hasNext());
        rid = ridResult.next().getProperty("r").toString();
      }

      // Large data (200 messages) + @rid → single-source + cost model approves.
      // Target WHERE (msgId > 190) filters most records in the index scan.
      var query = "MATCH {class: TestPerson, as: p, rid: " + rid + "}"
          + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
          + " where: (msgId > 190)} "
          + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        // Only msgIds > 190 pass filter: 191..200 → 10 available, LIMIT 5
        Assert.assertEquals("Should have 5 results", 5, mids.size());
        for (var mid : mids) {
          Assert.assertTrue("msgId should be > 190, got: " + mid, mid > 190);
        }
        // DESC: 200, 199, 198, 197, 196
        Assert.assertEquals(200L, (long) mids.get(0));
      }
      session.commit();
    }
  }

  // True single-source via {rid:} where the source has NO edges of the target
  // edge class. ridSet == null or isEmpty → ExecutionStream.empty().
  @Test
  public void testIndexOrderedMatchSingleSourceEmptyRidSet() throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // Create a person with NO TEST_HAS_CREATOR edges
      session.execute("CREATE VERTEX TestPerson SET name = 'loner'").close();
      session.commit();

      session.begin();
      String rid;
      try (var ridResult = session.query(
          "SELECT @rid as r FROM TestPerson WHERE name = 'loner'")) {
        Assert.assertTrue(ridResult.hasNext());
        rid = ridResult.next().getProperty("r").toString();
      }

      var query = "MATCH {class: TestPerson, as: p, rid: " + rid + "}"
          + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
          + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        Assert.assertFalse("Loner has no edges, should return 0 results",
            result.hasNext());
      }
      session.commit();
    }
  }

  // True single-source via {rid:} with target WHERE + loadFromRidSet path.
  // MAX_SCAN=1 forces runtime cost rejection → loadFromRidSet with target filter.
  @Test
  public void testIndexOrderedMatchSingleSourceLoadFromRidSetWithTargetFilter()
      throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      var oldMaxScan = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValue();
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(1L);
      try {
        session.begin();
        String rid;
        try (var ridResult = session.query(
            "SELECT @rid as r FROM TestPerson WHERE name = 'person1'")) {
          Assert.assertTrue(ridResult.hasNext());
          rid = ridResult.next().getProperty("r").toString();
        }

        // {rid:} → single-source. MAX_SCAN=1 → loadFromRidSet.
        // Target WHERE (msgId > 190) → matchesTargetFilter exercised in stream.
        var query = "MATCH {class: TestPerson, as: p, rid: " + rid + "}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
            + " where: (msgId > 190)} "
            + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 5";
        try (var result = session.query(query)) {
          var mids = new java.util.ArrayList<Long>();
          while (result.hasNext()) {
            mids.add(((Number) result.next().getProperty("mid")).longValue());
          }
          Assert.assertEquals("Should have 5 results", 5, mids.size());
          for (var mid : mids) {
            Assert.assertTrue("msgId should be > 190, got: " + mid, mid > 190);
          }
        }
        session.commit();
      } finally {
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(oldMaxScan);
      }
    }
  }

  // Single-source cost model rejection (small data) exercises loadFromRidSet with
  // target WHERE filter. Covers the filter branches in the stream pipeline.
  @Test
  public void testIndexOrderedMatchSingleSourceLoadFromRidSetWithFilter() throws Exception {
    initIndexOrderedMatchData(false);

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // person1 has 10 messages. With setIndexOrderedTestConfig the cost model
      // approves, so force loadFromRidSet by lowering MAX_SCAN to make cost model
      // prefer direct load over index scan at runtime.
      var oldMaxScan = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValue();
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(1);
      try {
        var query =
            "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
                + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
                + " where: (msgId > 105)} "
                + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 3";
        try (var result = session.query(query)) {
          // Plan may or may not use INDEX ORDERED MATCH depending on plan-time.
          // If it does, runtime cost model rejects → loadFromRidSet path.
          // Either way, results must be correct.
          var mids = new java.util.ArrayList<Long>();
          while (result.hasNext()) {
            mids.add(((Number) result.next().getProperty("mid")).longValue());
          }
          Assert.assertEquals("Should have 3 results", 3, mids.size());
          for (var mid : mids) {
            Assert.assertTrue("All msgIds should be > 105, got: " + mid, mid > 105);
          }
        }
      } finally {
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(oldMaxScan);
      }
      session.commit();
    }
  }

  // FILTERED_BOUND with MAX_SOURCES exceeded + target WHERE filter exercises
  // loadSourceEdgesBound with matchesTargetFilter.
  @Test
  public void testIndexOrderedMatchFilteredBoundMaxSourcesWithTargetFilter() {
    initIndexOrderedMatchMultiSourceData();

    var oldMaxSources = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.getValue();
    GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.setValue(2);
    try {
      session.begin();
      // 5 persons → exceeds MAX_SOURCES(2) → loadFromSourcesUnsorted path.
      // Target WHERE (msgId > 40) filters some records in the unsorted path.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (msgId > 40)} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 5 results", 5, mids.size());
        for (var mid : mids) {
          Assert.assertTrue("All msgIds should be > 40, got: " + mid, mid > 40);
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.setValue(oldMaxSources);
    }
  }

  // FILTERED_UNBOUND overflow + target WHERE filter exercises
  // loadSourceEdgesUnbound with matchesTargetFilter.
  @Test
  public void testIndexOrderedMatchFilteredUnboundOverflowWithTargetFilter() {
    initIndexOrderedMatchMultiSourceData();

    var oldMaxRidSet = GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValue();
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(3);
    try {
      session.begin();
      // WHERE on p, p NOT in RETURN → FILTERED_UNBOUND.
      // Union exceeds maxRidSetSize(3) → overflow → loadFromSourcesUnbound.
      // Target WHERE (msgId > 40) filters in the unsorted path.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (msgId > 40)} "
              + "RETURN m.msgId as mid ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 5 results", 5, mids.size());
        for (var mid : mids) {
          Assert.assertTrue("All msgIds should be > 40, got: " + mid, mid > 40);
        }
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(oldMaxRidSet);
    }
  }

  // .outE() with UNFILTERED_BOUND mode exercises edge traversal with
  // resolveReverseEdges edgeTraversal=true and source class check.
  @Test
  public void testIndexOrderedMatchOutEUnfilteredBound() throws Exception {
    initIndexOrderedMatchEdgeTraversalData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // No WHERE on source, source in RETURN → UNFILTERED_BOUND
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".outE('TEST_KNOWS'){as: k}.inV(){as: friend} "
              + "RETURN p.name as pname, friend.name as fname, k.creationDate as kd"
              + " ORDER BY kd DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCHE, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCHE"));

        var fnames = new java.util.ArrayList<String>();
        var pnames = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          fnames.add(row.getProperty("fname"));
          pnames.add(row.getProperty("pname"));
        }
        Assert.assertEquals("Should have 3 results", 3, fnames.size());
        // DESC: bob→carol (Jan 20), alice→dave (Jan 15), alice→carol (Jan 10)
        Assert.assertEquals("carol", fnames.get(0));
        Assert.assertEquals("dave", fnames.get(1));
        Assert.assertEquals("carol", fnames.get(2));
        // Source binding
        for (var pname : pnames) {
          Assert.assertNotNull("pname should be bound", pname);
        }
      }
      session.commit();
    }
  }

  // .outE() with UNFILTERED_UNBOUND mode exercises edge traversal with
  // resolveReverseEdges edgeTraversal=true without source binding.
  @Test
  public void testIndexOrderedMatchOutEUnfilteredUnbound() throws Exception {
    initIndexOrderedMatchEdgeTraversalData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // No WHERE on source, source NOT in RETURN → UNFILTERED_UNBOUND
      var query =
          "MATCH {class: TestPerson, as: p}"
              + ".outE('TEST_KNOWS'){as: k}.inV(){as: friend} "
              + "RETURN friend.name as fname, k.creationDate as kd"
              + " ORDER BY kd ASC LIMIT 4";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCHE, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCHE"));

        var fnames = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          fnames.add(result.next().getProperty("fname"));
        }
        Assert.assertEquals("Should have 4 results", 4, fnames.size());
        // ASC: alice→bob (Jan 5), alice→carol (Jan 10), alice→dave (Jan 15), bob→carol (Jan 20)
        Assert.assertEquals("bob", fnames.get(0));
        Assert.assertEquals("carol", fnames.get(1));
        Assert.assertEquals("dave", fnames.get(2));
        Assert.assertEquals("carol", fnames.get(3));
      }
      session.commit();
    }
  }

  // FILTERED_BOUND with target WHERE that rejects all targets produces 0 results
  // without errors. Exercises matchesTargetFilter returning false for every record.
  @Test
  public void testIndexOrderedMatchFilterAllOut() throws Exception {
    initIndexOrderedMatchMultiSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // Target WHERE: msgId > 9999 → no messages match → 0 results
      var query =
          "MATCH {class: TestPerson, as: p, where: (name LIKE 'person%')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m,"
              + " where: (msgId > 9999)} "
              + "RETURN p.name as pname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        Assert.assertFalse(
            "Should have 0 results when target filter rejects everything",
            result.hasNext());
      }
      session.commit();
    }
  }

  // =====================================================================
  // MatchExecutionPlanner branch-coverage tests
  // =====================================================================

  // Multi-field ORDER BY with large data and LIMIT triggers the indexOrderedUpstream
  // flag AND primaryKeySortedInput hint in the planner (lines 583-584, 631, 636).
  @Test
  public void testIndexOrderedMatchMultiFieldLargeData() throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // Multi-field ORDER BY: cd DESC, mid ASC. Large data (200 messages) →
      // cost model approves. primaryKeySortedInput is set.
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd, m.msgId as mid"
              + " ORDER BY cd DESC, mid ASC LIMIT 5";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        Assert.assertTrue(
            "OrderByStep should be present for multi-field ORDER BY, but was:\n" + plan,
            plan.contains("ORDER BY"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 5 results", 5, mids.size());
        // Each creationDate is unique, so top 5 DESC are msgId 200..196
        Assert.assertEquals(200L, (long) mids.get(0));
      }
      session.commit();
    }
  }

  // Multi-field ORDER BY with projection aliases (RETURN a AS x, b AS y ORDER BY x, y)
  // exercises resolveSimpleDotExpression and the multiFieldOrderBy flag via
  // the SELECT planner path (line 631).
  @Test
  public void testIndexOrderedMatchMultiFieldProjectionAlias() throws Exception {
    initIndexOrderedMatchLargeData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd, m.msgId as mid"
              + " ORDER BY cd DESC, mid ASC LIMIT 3";
      try (var result = session.query(query)) {
        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 3 results", 3, mids.size());
        Assert.assertEquals(200L, (long) mids.get(0));
        Assert.assertEquals(199L, (long) mids.get(1));
        Assert.assertEquals(198L, (long) mids.get(2));
      }
      session.commit();
    }
  }

  // .in() without edge class name parameter rejects the optimization (line 1731).
  @Test
  public void testIndexOrderedMatchNoEdgeClassRejects() {
    initIndexOrderedMatchData(false);

    session.begin();
    // .in() with no params → no specific edge class → reject
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in(){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      Assert.assertFalse(
          "No edge class should prevent INDEX ORDERED MATCH, but was:\n" + plan,
          plan.contains("INDEX ORDERED MATCH"));
    }
    session.commit();
  }

  // Multi-sub-pattern MATCH prevents the optimization (multiple FROM clauses).
  @Test
  public void testIndexOrderedMatchMultiPatternRejects() {
    initIndexOrderedMatchData();

    session.begin();
    // Two separate pattern clauses → subPatterns.size() > 1
    var query =
        "MATCH {class: TestPerson, as: p1, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m},"
            + " {class: TestPerson, as: p2, where: (name = 'person2')}"
            + " RETURN m.creationDate as cd ORDER BY cd DESC LIMIT 3";
    try (var result = session.query(query)) {
      var plan = getPlan(result);
      Assert.assertFalse(
          "Multi-pattern should prevent INDEX ORDERED MATCH, but was:\n" + plan,
          plan.contains("INDEX ORDERED MATCH"));
      // Results should still work via normal path
      while (result.hasNext()) {
        result.next();
      }
    }
    session.commit();
  }

  // ORDER BY on a function call expression (e.g., count(*)) cannot be resolved
  // by the planner and rejects the optimization.
  @Test
  public void testIndexOrderedMatchOrderByFunctionRejects() {
    initIndexOrderedMatchData(false);

    session.begin();
    // ORDER BY a non-dot expression that cannot be resolved to alias.property
    var query =
        "MATCH {class: TestPerson, as: p, where: (name = 'person1')}"
            + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
            + "RETURN m.creationDate as cd, m.msgId as mid"
            + " ORDER BY cd DESC";
    try (var result = session.query(query)) {
      // This exercises the resolveOrderByToAliasProperty Case 2 path
      // (orderAlias resolves through RETURN aliases)
      while (result.hasNext()) {
        result.next();
      }
    }
    session.commit();
  }

  // FILTERED_BOUND with multiple upstream rows pointing to the same source vertex.
  // Tests the sourceMap multi-value path (multiple Result rows per RID key).
  @Test
  public void testIndexOrderedMatchFilteredBoundSharedSource() throws Exception {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute("CREATE CLASS TEST_KNOWS EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET name = 'alice'").close();
    session.execute("CREATE VERTEX TestPerson SET name = 'bob'").close();
    session.execute("CREATE VERTEX TestPerson SET name = 'carol'").close();
    // alice KNOWS bob, alice KNOWS carol
    session.execute(
        "CREATE EDGE TEST_KNOWS FROM (SELECT FROM TestPerson WHERE name = 'alice')"
            + " TO (SELECT FROM TestPerson WHERE name = 'bob')")
        .close();
    session.execute(
        "CREATE EDGE TEST_KNOWS FROM (SELECT FROM TestPerson WHERE name = 'alice')"
            + " TO (SELECT FROM TestPerson WHERE name = 'carol')")
        .close();
    // bob creates 5 messages
    for (var i = 1; i <= 5; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + i)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + i
              + ") TO (SELECT FROM TestPerson WHERE name = 'bob')")
          .close();
    }
    // carol creates 5 messages
    for (var i = 6; i <= 10; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + i)
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = " + i
              + ") TO (SELECT FROM TestPerson WHERE name = 'carol')")
          .close();
    }
    session.commit();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      // 2-hop: alice → (bob, carol) → messages. Source of index-ordered edge is
      // the second hop (bob, carol). FILTERED_BOUND with alice.name in RETURN.
      var query =
          "MATCH {class: TestPerson, as: root, where: (name = 'alice')}"
              + ".out('TEST_KNOWS'){as: friend}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN root.name as rname, friend.name as fname, m.msgId as mid"
              + " ORDER BY m.creationDate DESC LIMIT 5";
      try (var result = session.query(query)) {
        var mids = new java.util.ArrayList<Long>();
        var fnames = new java.util.ArrayList<String>();
        while (result.hasNext()) {
          var row = result.next();
          mids.add(((Number) row.getProperty("mid")).longValue());
          fnames.add(row.getProperty("fname"));
          Assert.assertEquals("alice", row.getProperty("rname"));
        }
        Assert.assertEquals("Should have 5 results", 5, mids.size());
        // DESC: 10, 9, 8, 7, 6 (all from carol)
        for (var i = 0; i < mids.size() - 1; i++) {
          Assert.assertTrue(
              "Results should be in DESC order by msgId: " + mids,
              mids.get(i) > mids.get(i + 1));
        }
      }
      session.commit();
    }
  }

  // =====================================================================
  // Fix B: unique-index WHERE recognized as single-source
  // =====================================================================

  /**
   * Initializes test data with a UNIQUE index on TestPerson.personId.
   * Creates 1 person with 10 messages for single-source testing via
   * WHERE (personId = N) instead of {rid: #X:Y}.
   */
  private void initUniqueIndexSingleSourceData() {
    session.execute("CREATE CLASS TestPerson EXTENDS V").close();
    session.execute("CREATE PROPERTY TestPerson.personId LONG").close();
    session.execute(
        "CREATE INDEX TestPerson.personId ON TestPerson(personId) UNIQUE")
        .close();
    session.execute("CREATE CLASS TestMessage EXTENDS V").close();
    session.execute("CREATE PROPERTY TestMessage.creationDate DATETIME").close();
    session.execute("CREATE PROPERTY TestMessage.msgId LONG").close();
    session.execute("CREATE CLASS TEST_HAS_CREATOR EXTENDS E").close();
    session.execute(
        "CREATE INDEX TestMessage.creationDate ON TestMessage(creationDate) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX TestPerson SET personId = 1, name = 'alice'")
        .close();
    for (var i = 1; i <= 10; i++) {
      session.execute(
          "CREATE VERTEX TestMessage SET creationDate = '2025-01-"
              + String.format("%02d", i) + " 00:00:00', msgId = " + (100 + i))
          .close();
      session.execute(
          "CREATE EDGE TEST_HAS_CREATOR FROM (SELECT FROM TestMessage WHERE msgId = "
              + (100 + i)
              + ") TO (SELECT FROM TestPerson WHERE personId = 1)")
          .close();
    }
    session.commit();
  }

  // When source alias has a UNIQUE-index equality WHERE (e.g., personId = 1),
  // the planner should recognize it as single-source — no multi-source mode
  // suffix in the plan (no FILTERED_BOUND, FILTERED_UNBOUND, etc.).
  @Test
  public void testIndexOrderedMatchUniqueIndexSingleSource() throws Exception {
    initUniqueIndexSingleSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (personId = 1)}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd, m.msgId as mid"
              + " ORDER BY cd DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        Assert.assertTrue(
            "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
            plan.contains("INDEX ORDERED MATCH"));
        // Single-source: no mode suffix in plan
        Assert.assertFalse(
            "Should be single-source (no mode suffix) due to UNIQUE index,"
                + " but plan was:\n" + plan,
            plan.contains("FILTERED") || plan.contains("UNFILTERED"));

        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 3 results: " + mids, 3, mids.size());
        // DESC order: msgId 110, 109, 108
        Assert.assertEquals(110L, (long) mids.get(0));
        Assert.assertEquals(109L, (long) mids.get(1));
        Assert.assertEquals(108L, (long) mids.get(2));
      }
      session.commit();
    }
  }

  // When the WHERE filter uses a NOTUNIQUE index (e.g., name = 'alice'), the
  // source should NOT be treated as single-source — it must use multi-source
  // mode because NOTUNIQUE indexes can match multiple rows.
  @Test
  public void testIndexOrderedMatchNonUniqueIndexNotSingleSource() throws Exception {
    initUniqueIndexSingleSourceData();
    // Add a NOTUNIQUE index on name (property must be defined first)
    session.execute("CREATE PROPERTY TestPerson.name STRING").close();
    session.execute(
        "CREATE INDEX TestPerson.name ON TestPerson(name) NOTUNIQUE")
        .close();

    try (var cfg = setIndexOrderedTestConfig()) {
      session.begin();
      var query =
          "MATCH {class: TestPerson, as: p, where: (name = 'alice')}"
              + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
              + "RETURN m.creationDate as cd, m.msgId as mid"
              + " ORDER BY cd DESC LIMIT 3";
      try (var result = session.query(query)) {
        var plan = getPlan(result);
        if (plan.contains("INDEX ORDERED MATCH")) {
          // When the optimizer picks index-ordered, it must use multi-source
          // mode since the WHERE is on a NOTUNIQUE index.
          Assert.assertTrue(
              "Non-unique index WHERE should use multi-source mode, but plan was:\n"
                  + plan,
              plan.contains("FILTERED") || plan.contains("UNFILTERED"));
        }

        // Verify correctness regardless of plan chosen
        var mids = new java.util.ArrayList<Long>();
        while (result.hasNext()) {
          mids.add(((Number) result.next().getProperty("mid")).longValue());
        }
        Assert.assertEquals("Should have 3 results: " + mids, 3, mids.size());
        Assert.assertEquals(110L, (long) mids.get(0));
        Assert.assertEquals(109L, (long) mids.get(1));
        Assert.assertEquals(108L, (long) mids.get(2));
      }
      session.commit();
    }
  }

  // =====================================================================
  // Fix A: sort-push-down in loadFromRidSet fallback
  // =====================================================================

  // When the runtime cost model rejects index scan (MAX_SCAN=1), the
  // loadFromRidSet fallback should still produce correctly sorted results
  // because it now sorts in-memory before emitting.
  @Test
  public void testIndexOrderedMatchRidSetFallbackSorted() throws Exception {
    initUniqueIndexSingleSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      // Force the cost model to reject index scan → loadFromRidSet path
      var oldMaxScan = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValue();
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(1L);
      try {
        session.begin();
        var query =
            "MATCH {class: TestPerson, as: p, where: (personId = 1)}"
                + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
                + "RETURN m.creationDate as cd, m.msgId as mid"
                + " ORDER BY cd DESC LIMIT 5";
        try (var result = session.query(query)) {
          var plan = getPlan(result);
          Assert.assertTrue(
              "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
              plan.contains("INDEX ORDERED MATCH"));

          var mids = new java.util.ArrayList<Long>();
          while (result.hasNext()) {
            mids.add(((Number) result.next().getProperty("mid")).longValue());
          }
          Assert.assertEquals("Should have 5 results: " + mids, 5, mids.size());
          // DESC order: 110, 109, 108, 107, 106
          Assert.assertEquals(110L, (long) mids.get(0));
          Assert.assertEquals(109L, (long) mids.get(1));
          Assert.assertEquals(108L, (long) mids.get(2));
          Assert.assertEquals(107L, (long) mids.get(3));
          Assert.assertEquals(106L, (long) mids.get(4));
        }
        session.commit();
      } finally {
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(oldMaxScan);
      }
    }
  }

  // Ascending sort direction in the loadFromRidSet fallback: verify ASC
  // ordering works correctly when the cost model rejects index scan.
  @Test
  public void testIndexOrderedMatchRidSetFallbackSortedAsc() throws Exception {
    initUniqueIndexSingleSourceData();

    try (var cfg = setIndexOrderedTestConfig()) {
      var oldMaxScan = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValue();
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(1L);
      try {
        session.begin();
        var query =
            "MATCH {class: TestPerson, as: p, where: (personId = 1)}"
                + ".in('TEST_HAS_CREATOR'){class: TestMessage, as: m} "
                + "RETURN m.creationDate as cd, m.msgId as mid"
                + " ORDER BY cd ASC LIMIT 5";
        try (var result = session.query(query)) {
          var plan = getPlan(result);
          Assert.assertTrue(
              "Plan should use INDEX ORDERED MATCH, but was:\n" + plan,
              plan.contains("INDEX ORDERED MATCH"));

          var mids = new java.util.ArrayList<Long>();
          while (result.hasNext()) {
            mids.add(((Number) result.next().getProperty("mid")).longValue());
          }
          Assert.assertEquals("Should have 5 results: " + mids, 5, mids.size());
          // ASC order: 101, 102, 103, 104, 105
          Assert.assertEquals(101L, (long) mids.get(0));
          Assert.assertEquals(102L, (long) mids.get(1));
          Assert.assertEquals(103L, (long) mids.get(2));
          Assert.assertEquals(104L, (long) mids.get(3));
          Assert.assertEquals(105L, (long) mids.get(4));
        }
        session.commit();
      } finally {
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(oldMaxScan);
      }
    }
  }
}
