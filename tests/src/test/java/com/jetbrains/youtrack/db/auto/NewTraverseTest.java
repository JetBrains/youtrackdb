/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
@SuppressWarnings("unused")
public class NewTraverseTest extends BaseDBTest {

  private int totalElements = 0;
  private Vertex tomCruise;
  private Vertex megRyan;
  private Vertex nicoleKidman;

  @Parameters(value = "remote")
  public NewTraverseTest(boolean remote) {
    super(remote);
  }

  @BeforeClass
  public void init() {
    session.createVertexClass("Actor");
    session.createVertexClass("Movie");

    session.createEdgeClass("actorIn");
    session.createEdgeClass("friend");
    session.createEdgeClass("married");

    session.begin();
    tomCruise = session.newVertex("Actor");
    tomCruise.setProperty("name", "Tom Cruise");

    totalElements++;
    megRyan = session.newVertex("Actor");
    megRyan.setProperty("name", "Meg Ryan");

    totalElements++;
    nicoleKidman = session.newVertex("Actor");
    nicoleKidman.setProperty("name", "Nicole Kidman");
    nicoleKidman.setProperty("attributeWithDotValue", "a.b");

    totalElements++;

    var topGun = session.newVertex("Movie");
    topGun.setProperty("name", "Top Gun");
    topGun.setProperty("year", 1986);

    totalElements++;
    var missionImpossible = session.newVertex("Movie");
    missionImpossible.setProperty("name", "Mission: Impossible");
    missionImpossible.setProperty("year", 1996);

    totalElements++;
    var youHaveGotMail = session.newVertex("Movie");
    youHaveGotMail.setProperty("name", "You've Got Mail");
    youHaveGotMail.setProperty("year", 1998);
    totalElements++;

    var e = session.newStatefulEdge(tomCruise, topGun, "actorIn");

    totalElements++;
    e = session.newStatefulEdge(megRyan, topGun, "actorIn");

    totalElements++;
    e = session.newStatefulEdge(tomCruise, missionImpossible, "actorIn");

    totalElements++;
    e = session.newStatefulEdge(megRyan, youHaveGotMail, "actorIn");

    totalElements++;

    e = session.newStatefulEdge(tomCruise, megRyan, "friend");

    totalElements++;
    e = session.newStatefulEdge(tomCruise, nicoleKidman, "married");

    e.setProperty("year", 1990);

    totalElements++;
    session.commit();
  }

  public void traverseSQLAllFromActorNoWhereBreadthFrirst() {
    var result1 =
        session.query("traverse * from " + tomCruise.getIdentity() + " strategy BREADTH_FIRST");

    for (var i = 0; i < totalElements; i++) {
      Assert.assertTrue(result1.hasNext());
      result1.next();
    }
    result1.close();
  }

  public void traverseSQLAllFromActorNoWhereDepthFrirst() {
    var result1 =
        session.query("traverse * from " + tomCruise.getIdentity() + " strategy DEPTH_FIRST");

    for (var i = 0; i < totalElements; i++) {
      Assert.assertTrue(result1.hasNext());
      result1.next();
    }
    result1.close();
  }

  @Test
  public void traverseSQLOutFromActor1Depth() {
    var result1 =
        session.query("traverse out_ from " + tomCruise.getIdentity() + " while $depth <= 1");

    Assert.assertTrue(result1.hasNext());
    result1.close();
  }

  @Test
  public void traverseSQLMoviesOnly() {
    var result1 =
        session.query("select from ( traverse * from Movie ) where @class = 'Movie'");
    Assert.assertTrue(result1.hasNext());
    while (result1.hasNext()) {
      var d = result1.next();

      Assert.assertEquals(d.asEntity().getSchemaClassName(), "Movie");
    }
    result1.close();
  }

  @Test
  public void traverseSQLPerClassFields() {
    var result1 =
        session.query(
            "select from ( traverse out() from "
                + tomCruise.getIdentity()
                + ") where @class = 'Movie'");
    Assert.assertTrue(result1.hasNext());
    while (result1.hasNext()) {
      var d = result1.next();
      Assert.assertEquals(d.asEntity().getSchemaClassName(), "Movie");
    }
    result1.close();
  }

  @Test
  public void traverseSQLMoviesOnlyDepth() {
    var result1 =
        session.query(
            "select from ( traverse * from "
                + tomCruise.getIdentity()
                + " while $depth <= 1 ) where @class = 'Movie'");
    Assert.assertFalse(result1.hasNext());
    result1.close();
    var result2 =
        session.query(
            "select from ( traverse * from "
                + tomCruise.getIdentity()
                + " while $depth <= 2 ) where @class = 'Movie'");
    Assert.assertTrue(result2.hasNext());
    var size2 = 0;
    while (result2.hasNext()) {
      Identifiable identifiable = result2.next().asEntity();
      var transaction = session.getActiveTransaction();
      EntityImpl d = transaction.load(identifiable);
      Assert.assertEquals(d.getSchemaClassName(), "Movie");
      size2++;
    }
    result2.close();
    var result3 =
        session.query(
            "select from ( traverse * from "
                + tomCruise.getIdentity()
                + " ) where @class = 'Movie'");
    Assert.assertTrue(result3.hasNext());
    var size3 = 0;
    while (result3.hasNext()) {
      Identifiable identifiable = result3.next().asEntity();
      var transaction = session.getActiveTransaction();
      EntityImpl d = transaction.load(identifiable);
      Assert.assertEquals(d.getSchemaClassName(), "Movie");
      size3++;
    }
    Assert.assertTrue(size3 > size2);
    result3.close();
  }

  @Test
  public void traverseSelect() {
    var result1 = session.query("traverse * from ( select from Movie )");
    var tot = 0;
    while (result1.hasNext()) {
      result1.next();
      tot++;
    }

    Assert.assertEquals(tot, totalElements);
    result1.close();
  }

  @Test
  public void traverseSQLSelectAndTraverseNested() {
    var result1 =
        session.query(
            "traverse * from ( select from ( traverse * from "
                + tomCruise.getIdentity()
                + " while $depth <= 2 ) where @class = 'Movie' )");

    var tot = 0;
    while (result1.hasNext()) {
      result1.next();
      tot++;
    }

    Assert.assertEquals(tot, totalElements);
    result1.close();
  }

  @Test
  public void traverseAPISelectAndTraverseNested() {
    var result1 =
        session.execute(
            "traverse * from ( select from ( traverse * from "
                + tomCruise.getIdentity()
                + " while $depth <= 2 ) where @class = 'Movie' )");
    var tot = 0;
    while (result1.hasNext()) {
      result1.next();
      tot++;
    }
    Assert.assertEquals(tot, totalElements);
  }

  @Test
  public void traverseAPISelectAndTraverseNestedDepthFirst() {
    var result1 =
        session.query(
            "traverse * from ( select from ( traverse * from "
                + tomCruise.getIdentity()
                + " while $depth <= 2 strategy depth_first ) where @class = 'Movie' )");
    var tot = 0;
    while (result1.hasNext()) {
      result1.next();
      tot++;
    }
    Assert.assertEquals(tot, totalElements);
    result1.close();
  }

  @Test
  public void traverseAPISelectAndTraverseNestedBreadthFirst() {
    var result1 =
        session.execute(
            "traverse * from ( select from ( traverse * from "
                + tomCruise.getIdentity()
                + " while $depth <= 2 strategy breadth_first ) where @class = 'Movie' )");
    var tot = 0;
    while (result1.hasNext()) {
      result1.next();
      tot++;
    }
    Assert.assertEquals(tot, totalElements);
  }

  @Test
  public void traverseSelectNoInfluence() {
    var result1 = session.query("traverse * from Movie while $depth < 2");
    List<Result> list1 = new ArrayList<>();
    while (result1.hasNext()) {
      list1.add(result1.next());
    }
    result1.close();
    var result2 = session.query("select from ( traverse * from Movie while $depth < 2 )");
    List<Result> list2 = new ArrayList<>();
    while (result2.hasNext()) {
      list2.add(result2.next());
    }
    result2.close();
    var result3 =
        session.query("select from ( traverse * from Movie while $depth < 2 ) where true");
    List<Result> list3 = new ArrayList<>();
    while (result3.hasNext()) {
      list3.add(result3.next());
    }
    result3.close();
    var result4 =
        session.query(
            "select from ( traverse * from Movie while $depth < 2 and ( true = true ) ) where"
                + " true");

    List<Result> list4 = new ArrayList<>();
    while (result4.hasNext()) {
      list4.add(result4.next());
    }

    Assert.assertEquals(list1, list2);
    Assert.assertEquals(list1, list3);
    Assert.assertEquals(list1, list4);
    result4.close();
  }

  @Test
  public void traverseNoConditionLimit1() {
    var result1 = session.query("traverse * from Movie limit 1");
    Assert.assertTrue(result1.hasNext());
    result1.next();
    Assert.assertFalse(result1.hasNext());
  }

  @Test
  public void traverseAndFilterByAttributeThatContainsDotInValue() {
    // issue #4952
    var result1 =
        session.query(
            "select from ( traverse out_married, in[attributeWithDotValue = 'a.b']  from "
                + tomCruise.getIdentity()
                + ")");
    Assert.assertTrue(result1.hasNext());
    var found = false;
    while (result1.hasNext()) {
      var doc = result1.next();
      String name = doc.getProperty("name");
      if ("Nicole Kidman".equals(name)) {
        found = true;
        break;
      }
    }
    Assert.assertTrue(found);
    result1.close();
  }

  @Test
  public void traverseAndFilterWithNamedParam() {
    // issue #5225
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("param1", "a.b");
    var result1 =
        session.query(
            "select from (traverse out_married, in[attributeWithDotValue = :param1]  from "
                + tomCruise.getIdentity()
                + ")",
            params);
    Assert.assertTrue(result1.hasNext());
    var found = false;
    while (result1.hasNext()) {
      var doc = result1.next();
      String name = doc.getProperty("name");
      if ("Nicole Kidman".equals(name)) {
        found = true;
        break;
      }
    }
    Assert.assertTrue(found);
  }

  @Test
  public void traverseAndCheckDepthInSelect() {
    var result1 =
        session.query(
            "select *, $depth as d from ( traverse out_married  from "
                + tomCruise.getIdentity()
                + " while $depth < 2)");
    var found = false;
    Integer i = 0;
    while (result1.hasNext()) {
      var doc = result1.next();
      Integer depth = doc.getProperty("d");
      Assert.assertEquals(depth, i++);
    }
    Assert.assertEquals(i.intValue(), 2);
    result1.close();
  }

  @Test
  public void traverseAndCheckReturn() {
    var q = "traverse in('married')  from " + nicoleKidman.getIdentity();
    var db = this.session.copy();
    var result1 = db.query(q);
    Assert.assertTrue(result1.hasNext());
    var found = false;
    Integer i = 0;
    Result doc;
    while (result1.hasNext()) {
      doc = result1.next();
      i++;
    }
    Assert.assertEquals(i.intValue(), 2);
    result1.close();
  }
}
