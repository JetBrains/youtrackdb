package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class MatchStatementExecutionNewTest extends DbTestBase {

  private static final String DB_NAME = "MatchStatementExecutionNewTest";

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
          "CREATE EDGE Friend from (select from Person where name = ?) to (select from Person where"
              + " name = ?)",
          pair[0],
          pair[1]);
    }
    db.commit();

    db.command("CREATE class MathOp extends V").close();

    db.begin();
    db.command("CREATE VERTEX MathOp set a = 1, b = 3, c = 2").close();
    db.command("CREATE VERTEX MathOp set a = 5, b = 3, c = 2").close();
    db.commit();
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

    db.executeInTx(
        () -> {
          for (int i = 0; i < nodes; i++) {
            EntityImpl doc = new EntityImpl("IndexedVertex");
            doc.field("uid", i);
            doc.save();
          }
        });

    for (int i = 0; i < 100; i++) {
      String cmd =
          "CREATE EDGE IndexedEDGE FROM (SELECT FROM IndexedVertex WHERE uid = 0) TO (SELECT FROM"
              + " IndexedVertex WHERE uid > "
              + (i * nodes / 100)
              + " and uid <"
              + ((i + 1) * nodes / 100)
              + ")";

      db.begin();
      db.command(cmd).close();
      db.commit();
    }

    //    for (int i = 0; i < 100; i++) {
    //      String cmd =
    //          "CREATE EDGE IndexedEDGE FROM (SELECT FROM IndexedVertex WHERE uid > " + ((i * nodes
    // / 100) + 1) + " and uid < " + (
    //              ((i + 1) * nodes / 100) + 1) + ") TO (SELECT FROM IndexedVertex WHERE uid = 1)";
    //      System.out.println(cmd);
    //      db.command(new CommandSQL(cmd)).execute();
    //    }

    //    db.query("select expand(out()) from IndexedVertex where uid = 0").stream().forEach(x->
    // System.out.println("x = " + x));
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
    db.commit();

    db.begin();
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
    db.commit();

    db.begin();
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

    for (int i = 0; i < 10; i++) {
      db.begin();
      db.command("CREATE VERTEX TriangleV set uid = ?", i).close();
      db.commit();
    }
    int[][] edges = {
        {0, 1}, {0, 2}, {1, 2}, {1, 3}, {2, 4}, {3, 4}, {3, 5}, {4, 0}, {4, 7}, {6, 7}, {7, 8},
        {7, 9}, {8, 9}, {9, 1}, {8, 3}, {8, 4}
    };
    for (int[] edge : edges) {
      db.begin();
      db.command(
              "CREATE EDGE TriangleE from (select from TriangleV where uid = ?) to (select from"
                  + " TriangleV where uid = ?)",
              edge[0],
              edge[1])
          .close();
      db.commit();
    }
  }

  private void initDiamondTest() {
    db.command("CREATE class DiamondV extends V").close();
    db.command("CREATE class DiamondE extends E").close();

    for (int i = 0; i < 4; i++) {
      db.begin();
      db.command("CREATE VERTEX DiamondV set uid = ?", i).close();
      db.commit();
    }
    int[][] edges = {{0, 1}, {0, 2}, {1, 3}, {2, 3}};
    for (int[] edge : edges) {
      db.begin();
      db.command(
              "CREATE EDGE DiamondE from (select from DiamondV where uid = ?) to (select from"
                  + " DiamondV where uid = ?)",
              edge[0],
              edge[1])
          .close();
      db.commit();
    }
  }

  @Test
  public void testSimple() throws Exception {
    ResultSet qResult = db.query("match {class:Person, as: person} return person");
    printExecutionPlan(qResult);

    for (int i = 0; i < 6; i++) {
      Result item = qResult.next();
      Assert.assertEquals(1, item.getPropertyNames().size());
      Entity person = db.load(item.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(name.startsWith("n"));
    }
    qResult.close();
  }

  @Test
  public void testSimpleWhere() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person");

    for (int i = 0; i < 2; i++) {
      Result item = qResult.next();
      Assert.assertEquals(1, item.getPropertyNames().size());
      Entity personId = db.load(item.getProperty("person"));

      EntityImpl person = personId.getRecord();
      String name = person.field("name");
      Assert.assertTrue(name.equals("n1") || name.equals("n2"));
    }
    qResult.close();
  }

  @Test
  public void testSimpleLimit() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person"
                + " limit 1");
    Assert.assertTrue(qResult.hasNext());
    qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testSimpleLimit2() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person"
                + " limit -1");
    for (int i = 0; i < 2; i++) {
      Assert.assertTrue(qResult.hasNext());
      qResult.next();
    }
    qResult.close();
  }

  @Test
  public void testSimpleLimit3() throws Exception {

    ResultSet qResult =
        db.query(
            "match {class:Person, as: person, where: (name = 'n1' or name = 'n2')} return person"
                + " limit 3");
    for (int i = 0; i < 2; i++) {
      Assert.assertTrue(qResult.hasNext());
      qResult.next();
    }
    qResult.close();
  }

  @Test
  public void testSimpleUnnamedParams() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, as: person, where: (name = ? or name = ?)} return person",
            "n1",
            "n2");

    printExecutionPlan(qResult);
    for (int i = 0; i < 2; i++) {

      Result item = qResult.next();
      Assert.assertEquals(1, item.getPropertyNames().size());
      Entity person = db.load(item.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(name.equals("n1") || name.equals("n2"));
    }
    qResult.close();
  }

  @Test
  public void testCommonFriends() throws Exception {

    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testCommonFriendsPatterns() throws Exception {

    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $patterns)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testPattens() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $patterns");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals(1, item.getPropertyNames().size());
    Assert.assertEquals("friend", item.getPropertyNames().iterator().next());
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testPaths() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $paths");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals(3, item.getPropertyNames().size());
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testElements() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $elements");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testPathElements() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $pathElements");
    printExecutionPlan(qResult);
    Set<String> expected = new HashSet<>();
    expected.add("n1");
    expected.add("n2");
    expected.add("n4");
    for (int i = 0; i < 3; i++) {
      Assert.assertTrue(qResult.hasNext());
      Result item = qResult.next();
      expected.remove(item.getProperty("name"));
    }
    Assert.assertFalse(qResult.hasNext());
    Assert.assertTrue(expected.isEmpty());
    qResult.close();
  }

  @Test
  public void testCommonFriendsMatches() throws Exception {

    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return $matches)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testCommonFriendsArrows() throws Exception {

    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend-{as:friend}-Friend-{class: Person, where:(name = 'n4')} return"
                + " friend)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testCommonFriendsArrowsPatterns() throws Exception {

    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend-{as:friend}-Friend-{class: Person, where:(name = 'n4')} return"
                + " $patterns)");
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testCommonFriends2() throws Exception {

    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testCommonFriends2Arrows() throws Exception {

    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class: Person,"
                + " where:(name = 'n4')} return friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testReturnMethod() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend.name.toUpperCase(Locale.ENGLISH) as name");
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("N2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testReturnMethodArrows() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class: Person,"
                + " where:(name = 'n4')} return friend.name.toUpperCase(Locale.ENGLISH) as name");
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("N2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testReturnExpression() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend.name + ' ' +friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2 n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testReturnExpressionArrows() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class: Person,"
                + " where:(name = 'n4')} return friend.name + ' ' +friend.name as name");

    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2 n2", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testReturnDefaultAlias() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name ="
                + " 'n1')}.both('Friend'){as:friend}.both('Friend'){class: Person, where:(name ="
                + " 'n4')} return friend.name");

    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("friend.name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testReturnDefaultAliasArrows() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, where:(name = 'n1')}-Friend-{as:friend}-Friend-{class: Person,"
                + " where:(name = 'n4')} return friend.name");

    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n2", item.getProperty("friend.name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testFriendsOfFriends() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend').out('Friend'){as:friend} return $matches)");

    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n4", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testFriendsOfFriendsArrows() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{}-Friend->{as:friend} return $matches)");

    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertEquals("n4", item.getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testFriendsOfFriends2() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                + " me}.both('Friend').both('Friend'){as:friend, where: ($matched.me !="
                + " $currentMatch)} return $matches)");

    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    while (qResult.hasNext()) {
      Assert.assertNotEquals(qResult.next().getProperty("name"), "n1");
    }
    qResult.close();
  }

  @Test
  public void testFriendsOfFriends2Arrows() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1'), as:"
                + " me}-Friend-{}-Friend-{as:friend, where: ($matched.me != $currentMatch)} return"
                + " $matches)");

    Assert.assertTrue(qResult.hasNext());
    while (qResult.hasNext()) {
      Assert.assertNotEquals(qResult.next().getProperty("name"), "n1");
    }
    qResult.close();
  }

  @Test
  public void testFriendsWithName() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1 ="
                + " 2)}.out('Friend'){as:friend, where:(name = 'n2' and 1 + 1 = 2)} return"
                + " friend)");

    Assert.assertTrue(qResult.hasNext());
    Assert.assertEquals("n2", qResult.next().getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testFriendsWithNameArrows() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name = 'n1' and 1 + 1 ="
                + " 2)}-Friend->{as:friend, where:(name = 'n2' and 1 + 1 = 2)} return friend)");
    Assert.assertTrue(qResult.hasNext());
    Assert.assertEquals("n2", qResult.next().getProperty("name"));
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
  }

  @Test
  public void testWhile() throws Exception {

    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 1)} return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 2), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: ($depth < 4), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend)");
    Assert.assertEquals(6, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend limit 3)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, while: (true) } return friend) limit 3");
    Assert.assertEquals(3, size(qResult));
    qResult.close();
  }

  private int size(ResultSet qResult) {
    int result = 0;
    while (qResult.hasNext()) {
      result++;
      qResult.next();
    }
    return result;
  }

  @Test
  public void testWhileArrows() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 1)} return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 2), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: ($depth < 4), where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, while: (true) } return friend)");
    Assert.assertEquals(6, size(qResult));
    qResult.close();
  }

  @Test
  public void testMaxDepth() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1, where: ($depth=1) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1 } return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 0 } return friend)");
    Assert.assertEquals(1, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}.out('Friend'){as:friend, maxDepth: 1, where: ($depth > 0) } return"
                + " friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();
  }

  @Test
  public void testMaxDepthArrow() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1, where: ($depth=1) } return friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1 } return friend)");
    Assert.assertEquals(3, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 0 } return friend)");
    Assert.assertEquals(1, size(qResult));
    qResult.close();

    qResult =
        db.query(
            "select friend.name as name from (match {class:Person, where:(name ="
                + " 'n1')}-Friend->{as:friend, maxDepth: 1, where: ($depth > 0) } return friend)");
    Assert.assertEquals(2, size(qResult));
    qResult.close();
  }

  @Test
  public void testManager() {
    initOrgChart();
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one
    Assert.assertEquals("c", getManager("p10").field("name"));
    Assert.assertEquals("c", getManager("p12").field("name"));
    Assert.assertEquals("b", getManager("p6").field("name"));
    Assert.assertEquals("b", getManager("p11").field("name"));

    Assert.assertEquals("c", getManagerArrows("p10").field("name"));
    Assert.assertEquals("c", getManagerArrows("p12").field("name"));
    Assert.assertEquals("b", getManagerArrows("p6").field("name"));
    Assert.assertEquals("b", getManagerArrows("p11").field("name"));
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

    ResultSet qResult = db.query(query);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    return item.getEntity().get().getRecord();
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

    ResultSet qResult = db.query(query);
    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    return item.getEntity().get().getRecord();
  }

  @Test
  public void testManager2() {
    initOrgChart();
    // the manager of a person is the manager of the department that person belongs to.
    // if that department does not have a direct manager, climb up the hierarchy until you find one

    Assert.assertEquals("c", getManager2("p10").getProperty("name"));
    Assert.assertEquals("c", getManager2("p12").getProperty("name"));
    Assert.assertEquals("b", getManager2("p6").getProperty("name"));
    Assert.assertEquals("b", getManager2("p11").getProperty("name"));

    Assert.assertEquals("c", getManager2Arrows("p10").getProperty("name"));
    Assert.assertEquals("c", getManager2Arrows("p12").getProperty("name"));
    Assert.assertEquals("b", getManager2Arrows("p6").getProperty("name"));
    Assert.assertEquals("b", getManager2Arrows("p11").getProperty("name"));
  }

  private Result getManager2(String personName) {
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

    ResultSet qResult = db.query(query);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    return item;
  }

  private Result getManager2Arrows(String personName) {
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

    ResultSet qResult = db.query(query);
    Assert.assertTrue(qResult.hasNext());
    Result item = qResult.next();
    Assert.assertFalse(qResult.hasNext());
    qResult.close();
    return item;
  }

  @Test
  public void testManaged() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    ResultSet managedByA = getManagedBy("a");
    Assert.assertTrue(managedByA.hasNext());
    Result item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();

    ResultSet managedByB = getManagedBy("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (int i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      Result id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
  }

  private ResultSet getManagedBy(String managerName) {
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

    return db.query(query);
  }

  @Test
  public void testManagedArrows() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    ResultSet managedByA = getManagedByArrows("a");
    Assert.assertTrue(managedByA.hasNext());
    Result item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();
    ResultSet managedByB = getManagedByArrows("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (int i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      Result id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
  }

  private ResultSet getManagedByArrows(String managerName) {
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

    return db.query(query);
  }

  @Test
  public void testManaged2() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    ResultSet managedByA = getManagedBy2("a");
    Assert.assertTrue(managedByA.hasNext());
    Result item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();
    ResultSet managedByB = getManagedBy2("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (int i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      Result id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
  }

  private ResultSet getManagedBy2(String managerName) {
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

    return db.query(query);
  }

  @Test
  public void testManaged2Arrows() {
    initOrgChart();
    // people managed by a manager are people who belong to his department or people who belong to
    // sub-departments without a manager
    ResultSet managedByA = getManagedBy2Arrows("a");
    Assert.assertTrue(managedByA.hasNext());
    Result item = managedByA.next();
    Assert.assertFalse(managedByA.hasNext());
    Assert.assertEquals("p1", item.getProperty("name"));
    managedByA.close();
    ResultSet managedByB = getManagedBy2Arrows("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (int i = 0; i < 5; i++) {
      Assert.assertTrue(managedByB.hasNext());
      Result id = managedByB.next();
      String name = id.getProperty("name");
      names.add(name);
    }
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
  }

  private ResultSet getManagedBy2Arrows(String managerName) {
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

    return db.query(query);
  }

  @Test
  public void testTriangle1() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "  .out('TriangleE'){as: friend2}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    ResultSet result = db.query(query);

    printExecutionPlan(result);

    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testTriangle1Arrows() {
    initTriangleTest();
    String query =
        "match {class:TriangleV, as: friend1, where: (uid = 0)} -TriangleE-> {as: friend2}"
            + " -TriangleE-> {as: friend3},{class:TriangleV, as: friend1} -TriangleE-> {as:"
            + " friend3}return $matches";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testTriangle2Old() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    ResultSet result = db.query(query);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Entity friend1 = db.load(doc.getProperty("friend1"));
    Entity friend2 = db.load(doc.getProperty("friend2"));
    Entity friend3 = db.load(doc.getProperty("friend3"));
    Assert.assertEquals(0, friend1.<Object>getProperty("uid"));
    Assert.assertEquals(1, friend2.<Object>getProperty("uid"));
    Assert.assertEquals(2, friend3.<Object>getProperty("uid"));
    result.close();
  }

  @Test
  public void testTriangle2() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $patterns";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    Entity friend1 = db.load(doc.getProperty("friend1"));
    Entity friend2 = db.load(doc.getProperty("friend2"));
    Entity friend3 = db.load(doc.getProperty("friend3"));
    Assert.assertEquals(0, friend1.<Object>getProperty("uid"));
    Assert.assertEquals(1, friend2.<Object>getProperty("uid"));
    Assert.assertEquals(2, friend3.<Object>getProperty("uid"));
    result.close();
  }

  @Test
  public void testTriangle2Arrows() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{class:TriangleV, as: friend2, where: (uid = 1)}"
            + "  -TriangleE->{as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    Entity friend1 = db.load(doc.getProperty("friend1"));
    Entity friend2 = db.load(doc.getProperty("friend2"));
    Entity friend3 = db.load(doc.getProperty("friend3"));
    Assert.assertEquals(0, friend1.<Object>getProperty("uid"));
    Assert.assertEquals(1, friend2.<Object>getProperty("uid"));
    Assert.assertEquals(2, friend3.<Object>getProperty("uid"));
    result.close();
  }

  @Test
  public void testTriangle3() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend2}"
            + "  -TriangleE->{as: friend3, where: (uid = 2)},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testTriangle4() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend2, where: (uid = 1)}"
            + "  .out('TriangleE'){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .out('TriangleE'){as: friend3}"
            + "return $matches";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testTriangle4Arrows() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend2, where: (uid = 1)}"
            + "  -TriangleE->{as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  -TriangleE->{as: friend3}"
            + "return $matches";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testTriangleWithEdges4() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1}"
            + "  .outE('TriangleE').inV(){as: friend2, where: (uid = 1)}"
            + "  .outE('TriangleE').inV(){as: friend3},"
            + "{class:TriangleV, as: friend1}"
            + "  .outE('TriangleE').inV(){as: friend3}"
            + "return $matches";

    ResultSet result = db.query(query);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testCartesianProduct() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where:(uid = 1)},"
            + "{class:TriangleV, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches";

    ResultSet result = db.query(query);
    printExecutionPlan(result);

    for (int i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      Result doc = result.next();
      Entity friend1 = db.load(doc.getProperty("friend1"));
      Assert.assertEquals(friend1.<Object>getProperty("uid"), 1);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testNoPrefetch() {
    initEdgeIndexTest();
    String query = "match " + "{class:IndexedVertex, as: one}" + "return $patterns";

    ResultSet result = db.query(query);
    printExecutionPlan(result);

    result
        .getExecutionPlan()
        .ifPresent(
            x ->
                x.getSteps().stream()
                    .filter(y -> y instanceof MatchPrefetchStep)
                    .forEach(prefetchStepFound -> Assert.fail()));

    for (int i = 0; i < 1000; i++) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testCartesianProductLimit() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where:(uid = 1)},"
            + "{class:TriangleV, as: friend2, where:(uid = 2 or uid = 3)}"
            + "return $matches LIMIT 1";

    ResultSet result = db.query(query);

    Assert.assertTrue(result.hasNext());
    Result d = result.next();
    Entity friend1 = db.load(d.getProperty("friend1"));
    Assert.assertEquals(friend1.<Object>getProperty("uid"), 1);
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testArrayNumber() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0] as foo";

    ResultSet result = db.query(query);

    Assert.assertTrue(result.hasNext());

    Result doc = result.next();
    Object foo = db.load(doc.getProperty("foo"));
    Assert.assertNotNull(foo);
    Assert.assertTrue(((Entity) foo).isVertex());
    result.close();
  }

  @Test
  public void testArraySingleSelectors2() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0,1] as foo";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    Object foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(2, ((List) foo).size());
    result.close();
  }

  @Test
  public void testArrayRangeSelectors1() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..1] as foo";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());

    Object foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(1, ((List) foo).size());
    result.close();
  }

  @Test
  public void testArrayRange2() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..2] as foo";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());

    Object foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(2, ((List) foo).size());
    result.close();
  }

  @Test
  public void testArrayRange3() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[0..3] as foo";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());

    Object foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(2, ((List) foo).size());
    result.close();
  }

  @Test
  public void testConditionInSquareBrackets() {
    initTriangleTest();
    String query =
        "match "
            + "{class:TriangleV, as: friend1, where: (uid = 0)}"
            + "return friend1.out('TriangleE')[uid = 2] as foo";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());

    Object foo = doc.getProperty("foo");
    Assert.assertNotNull(foo);
    Assert.assertTrue(foo instanceof List);
    Assert.assertEquals(1, ((List) foo).size());
    Vertex resultVertex = (Vertex) ((List) foo).get(0);
    Assert.assertEquals(2, resultVertex.<Object>getProperty("uid"));
    result.close();
  }

  @Test
  public void testIndexedEdge() {
    initEdgeIndexTest();
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)}"
            + ".out('IndexedEdge'){class:IndexedVertex, as: two, where: (uid = 1)}"
            + "return one, two";

    ResultSet result = db.query(query);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testIndexedEdgeArrows() {
    initEdgeIndexTest();
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)}"
            + "-IndexedEdge->{class:IndexedVertex, as: two, where: (uid = 1)}"
            + "return one, two";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testJson() {
    initEdgeIndexTest();
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'uuid':one.uid}";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());

    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("uuid"));
    result.close();
  }

  @Test
  public void testJson2() {
    initEdgeIndexTest();
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'sub': {'uuid':one.uid}}";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub.uuid"));
    result.close();
  }

  @Test
  public void testJson3() {
    initEdgeIndexTest();
    String query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'sub': [{'uuid':one.uid}]}";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub[0].uuid"));

    result.close();
  }

  @Test
  public void testUnique() {
    initDiamondTest();
    StringBuilder query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return DISTINCT one, two");

    ResultSet result = db.query(query.toString());
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertFalse(result.hasNext());

    query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return DISTINCT one.uid, two.uid");

    result.close();

    result = db.query(query.toString());
    Assert.assertTrue(result.hasNext());
    doc = result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub[0].uuid"));
  }

  @Test
  public void testNotUnique() {
    initDiamondTest();
    StringBuilder query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return one, two");

    ResultSet result = db.query(query.toString());
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    Result doc = result.next();
    Assert.assertTrue(result.hasNext());
    doc = result.next();
    Assert.assertFalse(result.hasNext());
    result.close();

    query = new StringBuilder();
    query.append("match ");
    query.append(
        "{class:DiamondV, as: one, where: (uid = 0)}.out('DiamondE').out('DiamondE'){as: two} ");
    query.append("return one.uid, two.uid");

    result = db.query(query.toString());
    Assert.assertTrue(result.hasNext());
    doc = result.next();
    Assert.assertTrue(result.hasNext());
    doc = result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    //    EntityImpl doc = result.get(0);
    //    assertEquals("foo", doc.field("name"));
    //    assertEquals(0, doc.field("sub[0].uuid"));
  }

  @Test
  public void testManagedElements() {
    initOrgChart();
    ResultSet managedByB = getManagedElements("b");

    Set<String> expectedNames = new HashSet<String>();
    expectedNames.add("b");
    expectedNames.add("p2");
    expectedNames.add("p3");
    expectedNames.add("p6");
    expectedNames.add("p7");
    expectedNames.add("p11");
    Set<String> names = new HashSet<String>();
    for (int i = 0; i < 6; i++) {
      Assert.assertTrue(managedByB.hasNext());
      Result doc = managedByB.next();
      String name = doc.getProperty("name");
      names.add(name);
    }
    Assert.assertFalse(managedByB.hasNext());
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
  }

  private ResultSet getManagedElements(String managerName) {
    String query =
        "  match {class:Employee, as:boss, where: (name = '"
            + managerName
            + "')}"
            + "  -ManagerOf->{}<-ParentDepartment-{"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return distinct $elements";

    return db.query(query);
  }

  @Test
  public void testManagedPathElements() {
    initOrgChart();
    ResultSet managedByB = getManagedPathElements("b");

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
    for (int i = 0; i < 10; i++) {
      Assert.assertTrue(managedByB.hasNext());
      Result doc = managedByB.next();
      String name = doc.getProperty("name");
      names.add(name);
    }
    Assert.assertFalse(managedByB.hasNext());
    Assert.assertEquals(expectedNames, names);
    managedByB.close();
  }

  @Test
  public void testOptional() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, as: person} -NonExistingEdge-> {as:b, optional:true} return"
                + " person, b.name");

    printExecutionPlan(qResult);
    for (int i = 0; i < 6; i++) {
      Assert.assertTrue(qResult.hasNext());
      Result doc = qResult.next();
      Assert.assertEquals(2, doc.getPropertyNames().size());
      Entity person = db.load(doc.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(name.startsWith("n"));
    }
  }

  @Test
  public void testOptional2() throws Exception {
    ResultSet qResult =
        db.query(
            "match {class:Person, as: person} --> {as:b, optional:true, where:(nonExisting = 12)}"
                + " return person, b.name");

    for (int i = 0; i < 6; i++) {
      Assert.assertTrue(qResult.hasNext());
      Result doc = qResult.next();
      Assert.assertEquals(2, doc.getPropertyNames().size());
      Entity person = db.load(doc.getProperty("person"));

      String name = person.getProperty("name");
      Assert.assertTrue(name.startsWith("n"));
    }
  }

  @Test
  public void testOptional3() throws Exception {
    ResultSet qResult =
        db.query(
            "select friend.name as name, b from (match {class:Person, as:a, where:(name = 'n1' and"
                + " 1 + 1 = 2)}.out('Friend'){as:friend, where:(name = 'n2' and 1 + 1 ="
                + " 2)},{as:a}.out(){as:b, where:(nonExisting = 12),"
                + " optional:true},{as:friend}.out(){as:b, optional:true} return friend, b)");

    printExecutionPlan(qResult);
    Assert.assertTrue(qResult.hasNext());
    Result doc = qResult.next();
    Assert.assertEquals("n2", doc.getProperty("name"));
    Assert.assertNull(doc.getProperty("b"));
    Assert.assertFalse(qResult.hasNext());
  }

  @Test
  public void testOrderByAsc() {
    db.command("CREATE CLASS testOrderByAsc EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX testOrderByAsc SET name = 'bbb'").close();
    db.command("CREATE VERTEX testOrderByAsc SET name = 'zzz'").close();
    db.command("CREATE VERTEX testOrderByAsc SET name = 'aaa'").close();
    db.command("CREATE VERTEX testOrderByAsc SET name = 'ccc'").close();
    db.commit();

    String query = "MATCH { class: testOrderByAsc, as:a} RETURN a.name as name order by name asc";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("aaa", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("bbb", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("ccc", result.next().getProperty("name"));
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("zzz", result.next().getProperty("name"));
    Assert.assertFalse(result.hasNext());
  }

  @Test
  public void testOrderByDesc() {
    db.command("CREATE CLASS testOrderByDesc EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX testOrderByDesc SET name = 'bbb'").close();
    db.command("CREATE VERTEX testOrderByDesc SET name = 'zzz'").close();
    db.command("CREATE VERTEX testOrderByDesc SET name = 'aaa'").close();
    db.command("CREATE VERTEX testOrderByDesc SET name = 'ccc'").close();
    db.commit();

    String query = "MATCH { class: testOrderByDesc, as:a} RETURN a.name as name order by name desc";

    ResultSet result = db.query(query);
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
  }

  @Test
  public void testNestedProjections() {
    String clazz = "testNestedProjections";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX " + clazz + " SET name = 'bbb', surname = 'ccc'").close();
    db.commit();

    String query = "MATCH { class: " + clazz + ", as:a} RETURN a:{name}, 'x' ";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result item = result.next();
    Result a = item.getProperty("a");
    Assert.assertEquals("bbb", a.getProperty("name"));
    Assert.assertNull(a.getProperty("surname"));
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testAggregate() {
    String clazz = "testAggregate";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 1").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 2").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 3").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'bbb', num = 4").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'bbb', num = 5").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'bbb', num = 6").close();
    db.commit();

    String query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as a, max(a.num) as maxNum group by a.name order by a.name";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result item = result.next();
    Assert.assertEquals("aaa", item.getProperty("a"));
    Assert.assertEquals(3, (int) item.getProperty("maxNum"));

    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertEquals("bbb", item.getProperty("a"));
    Assert.assertEquals(6, (int) item.getProperty("maxNum"));

    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testOrderByOutOfProjAsc() {
    String clazz = "testOrderByOutOfProjAsc";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 0, num2 = 1").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 1, num2 = 2").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 2, num2 = 3").close();
    db.commit();

    String query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name, a.num as num order by a.num2 asc";

    ResultSet result = db.query(query);
    for (int i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      Result item = result.next();
      Assert.assertEquals("aaa", item.getProperty("name"));
      Assert.assertEquals(i, (int) item.getProperty("num"));
    }

    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testOrderByOutOfProjDesc() {
    String clazz = "testOrderByOutOfProjDesc";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 0, num2 = 1").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 1, num2 = 2").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', num = 2, num2 = 3").close();
    db.commit();

    String query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name, a.num as num order by a.num2 desc";

    ResultSet result = db.query(query);
    for (int i = 2; i >= 0; i--) {
      Assert.assertTrue(result.hasNext());
      Result item = result.next();
      Assert.assertEquals("aaa", item.getProperty("name"));
      Assert.assertEquals(i, (int) item.getProperty("num"));
    }

    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testUnwind() {
    String clazz = "testUnwind";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa', coll = [1, 2]").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'bbb', coll = [3, 4]").close();
    db.commit();

    String query =
        "MATCH { class: " + clazz + ", as:a} RETURN a.name as name, a.coll as num unwind num";

    int sum = 0;
    ResultSet result = db.query(query);
    for (int i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      Result item = result.next();
      sum += item.<Integer>getProperty("num");
    }

    Assert.assertFalse(result.hasNext());

    result.close();
    Assert.assertEquals(10, sum);
  }

  @Test
  public void testSkip() {
    String clazz = "testSkip";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa'").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'bbb'").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'ccc'").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'ddd'").close();
    db.commit();

    String query =
        "MATCH { class: "
            + clazz
            + ", as:a} RETURN a.name as name ORDER BY name ASC skip 1 limit 2";

    ResultSet result = db.query(query);

    Assert.assertTrue(result.hasNext());
    Result item = result.next();
    Assert.assertEquals("bbb", item.getProperty("name"));

    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertEquals("ccc", item.getProperty("name"));

    Assert.assertFalse(result.hasNext());

    result.close();
  }

  @Test
  public void testDepthAlias() {
    String clazz = "testDepthAlias";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa'").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'bbb'").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'ccc'").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'ddd'").close();

    db.command(
            "CREATE EDGE E FROM (SELECT FROM "
                + clazz
                + " WHERE name = 'aaa') TO (SELECT FROM "
                + clazz
                + " WHERE name = 'bbb')")
        .close();
    db.command(
            "CREATE EDGE E FROM (SELECT FROM "
                + clazz
                + " WHERE name = 'bbb') TO (SELECT FROM "
                + clazz
                + " WHERE name = 'ccc')")
        .close();
    db.command(
            "CREATE EDGE E FROM (SELECT FROM "
                + clazz
                + " WHERE name = 'ccc') TO (SELECT FROM "
                + clazz
                + " WHERE name = 'ddd')")
        .close();
    db.commit();

    String query =
        "MATCH { class: "
            + clazz
            + ", as:a, where:(name = 'aaa')} --> {as:b, while:($depth<10), depthAlias: xy} RETURN"
            + " a.name as name, b.name as bname, xy";

    ResultSet result = db.query(query);

    int sum = 0;
    for (int i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      Result item = result.next();
      Object depth = item.getProperty("xy");
      Assert.assertTrue(depth instanceof Integer);
      Assert.assertEquals("aaa", item.getProperty("name"));
      switch ((int) depth) {
        case 0:
          Assert.assertEquals("aaa", item.getProperty("bname"));
          break;
        case 1:
          Assert.assertEquals("bbb", item.getProperty("bname"));
          break;
        case 2:
          Assert.assertEquals("ccc", item.getProperty("bname"));
          break;
        case 3:
          Assert.assertEquals("ddd", item.getProperty("bname"));
          break;
        default:
          Assert.fail();
      }
      sum += (int) depth;
    }
    Assert.assertEquals(sum, 6);
    Assert.assertFalse(result.hasNext());

    result.close();
  }

  @Test
  public void testPathAlias() {
    String clazz = "testPathAlias";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX " + clazz + " SET name = 'aaa'").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'bbb'").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'ccc'").close();
    db.command("CREATE VERTEX " + clazz + " SET name = 'ddd'").close();

    db.command(
            "CREATE EDGE E FROM (SELECT FROM "
                + clazz
                + " WHERE name = 'aaa') TO (SELECT FROM "
                + clazz
                + " WHERE name = 'bbb')")
        .close();
    db.command(
            "CREATE EDGE E FROM (SELECT FROM "
                + clazz
                + " WHERE name = 'bbb') TO (SELECT FROM "
                + clazz
                + " WHERE name = 'ccc')")
        .close();
    db.command(
            "CREATE EDGE E FROM (SELECT FROM "
                + clazz
                + " WHERE name = 'ccc') TO (SELECT FROM "
                + clazz
                + " WHERE name = 'ddd')")
        .close();
    db.commit();

    String query =
        "MATCH { class: "
            + clazz
            + ", as:a, where:(name = 'aaa')} --> {as:b, while:($depth<10), pathAlias: xy} RETURN"
            + " a.name as name, b.name as bname, xy";

    ResultSet result = db.query(query);

    for (int i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      Result item = result.next();
      Object path = item.getProperty("xy");
      Assert.assertTrue(path instanceof List);
      List<Identifiable> thePath = (List<Identifiable>) path;

      String bname = item.getProperty("bname");
      if (bname.equals("aaa")) {
        Assert.assertEquals(0, thePath.size());
      } else if (bname.equals("aaa")) {
        Assert.assertEquals(1, thePath.size());
        Assert.assertEquals("bbb", ((Entity) thePath.get(0).getRecord()).getProperty("name"));
      } else if (bname.equals("ccc")) {
        Assert.assertEquals(2, thePath.size());
        Assert.assertEquals("bbb", ((Entity) thePath.get(0).getRecord()).getProperty("name"));
        Assert.assertEquals("ccc", ((Entity) thePath.get(1).getRecord()).getProperty("name"));
      } else if (bname.equals("ddd")) {
        Assert.assertEquals(3, thePath.size());
        Assert.assertEquals("bbb", ((Entity) thePath.get(0).getRecord()).getProperty("name"));
        Assert.assertEquals("ccc", ((Entity) thePath.get(1).getRecord()).getProperty("name"));
        Assert.assertEquals("ddd", ((Entity) thePath.get(2).getRecord()).getProperty("name"));
      }
    }
    Assert.assertFalse(result.hasNext());

    result.close();
  }

  @Test
  public void testNegativePattern() {
    String clazz = "testNegativePattern";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.executeInTx(
        () -> {
          Vertex v1 = db.newVertex(clazz);
          v1.setProperty("name", "a");
          v1.save();

          Vertex v2 = db.newVertex(clazz);
          v2.setProperty("name", "b");
          v2.save();

          Vertex v3 = db.newVertex(clazz);
          v3.setProperty("name", "c");
          v3.save();

          v1.addRegularEdge(v2).save();
          v2.addRegularEdge(v3).save();
        });

    String query = "MATCH { class:" + clazz + ", as:a} --> {as:b} --> {as:c}, ";
    query += " NOT {as:a} --> {as:c}";
    query += " RETURN $patterns";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());

    result.close();
  }

  @Test
  public void testNegativePattern2() {
    String clazz = "testNegativePattern2";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.executeInTx(
        () -> {
          Vertex v1 = db.newVertex(clazz);
          v1.setProperty("name", "a");
          v1.save();

          Vertex v2 = db.newVertex(clazz);
          v2.setProperty("name", "b");
          v2.save();

          Vertex v3 = db.newVertex(clazz);
          v3.setProperty("name", "c");
          v3.save();

          v1.addRegularEdge(v2).save();
          v2.addRegularEdge(v3).save();
          v1.addRegularEdge(v3).save();
        });

    String query = "MATCH { class:" + clazz + ", as:a} --> {as:b} --> {as:c}, ";
    query += " NOT {as:a} --> {as:c}";
    query += " RETURN $patterns";

    ResultSet result = db.query(query);
    Assert.assertFalse(result.hasNext());

    result.close();
  }

  @Test
  public void testNegativePattern3() {
    String clazz = "testNegativePattern3";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.executeInTx(
        () -> {
          Vertex v1 = db.newVertex(clazz);
          v1.setProperty("name", "a");
          v1.save();

          Vertex v2 = db.newVertex(clazz);
          v2.setProperty("name", "b");
          v2.save();

          Vertex v3 = db.newVertex(clazz);
          v3.setProperty("name", "c");
          v3.save();

          v1.addRegularEdge(v2).save();
          v2.addRegularEdge(v3).save();
          v1.addRegularEdge(v3).save();
        });

    String query = "MATCH { class:" + clazz + ", as:a} --> {as:b} --> {as:c}, ";
    query += " NOT {as:a} --> {as:c, where:(name <> 'c')}";
    query += " RETURN $patterns";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());

    result.close();
  }

  @Test
  public void testPathTraversal() {
    String clazz = "testPathTraversal";
    db.command("CREATE CLASS " + clazz + " EXTENDS V").close();

    db.executeInTx(
        () -> {
          Vertex v1 = db.newVertex(clazz);
          v1.setProperty("name", "a");
          v1.save();

          Vertex v2 = db.newVertex(clazz);
          v2.setProperty("name", "b");
          v2.save();

          Vertex v3 = db.newVertex(clazz);
          v3.setProperty("name", "c");
          v3.save();

          v1.setProperty("next", v2);
          v2.setProperty("next", v3);

          v1.save();
          v2.save();
        });

    String query = "MATCH { class:" + clazz + ", as:a}.next{as:b, where:(name ='b')}";
    query += " RETURN a.name as a, b.name as b";

    ResultSet result = db.query(query);
    Assert.assertTrue(result.hasNext());
    Result item = result.next();
    Assert.assertEquals("a", item.getProperty("a"));
    Assert.assertEquals("b", item.getProperty("b"));

    Assert.assertFalse(result.hasNext());

    result.close();

    query = "MATCH { class:" + clazz + ", as:a, where:(name ='a')}.next{as:b}";
    query += " RETURN a.name as a, b.name as b";

    result = db.query(query);
    Assert.assertTrue(result.hasNext());
    item = result.next();
    Assert.assertEquals("a", item.getProperty("a"));
    Assert.assertEquals("b", item.getProperty("b"));

    Assert.assertFalse(result.hasNext());

    result.close();
  }

  private ResultSet getManagedPathElements(String managerName) {
    String query =
        "  match {class:Employee, as:boss, where: (name = '"
            + managerName
            + "')}"
            + "  -ManagerOf->{}<-ParentDepartment-{"
            + "      while: ($depth = 0 or in('ManagerOf').size() = 0),"
            + "      where: ($depth = 0 or in('ManagerOf').size() = 0)"
            + "  }<-WorksAt-{as: managed}"
            + "  return distinct $pathElements";

    return db.query(query);
  }

  @Test
  public void testQuotedClassName() {
    String className = "testQuotedClassName";
    db.command("CREATE CLASS " + className + " EXTENDS V").close();

    db.begin();
    db.command("CREATE VERTEX " + className + " SET name = 'a'").close();
    db.commit();

    String query = "MATCH {class: `" + className + "`, as:foo} RETURN $elements";

    try (ResultSet rs = db.query(query)) {
      Assert.assertEquals(1L, rs.stream().count());
    }
  }

  private void printExecutionPlan(ResultSet result) {
    printExecutionPlan(null, result);
  }

  private void printExecutionPlan(String query, ResultSet result) {
    //    if (query != null) {
    //      System.out.println(query);
    //    }
    //    result.getExecutionPlan().ifPresent(x -> System.out.println(x.prettyPrint(0, 3)));
    //    System.out.println();
  }
}
