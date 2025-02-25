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
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandPredicate;
import com.jetbrains.youtrack.db.internal.core.command.traverse.Traverse;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLPredicate;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
@SuppressWarnings("unused")
public class TraverseTest extends BaseDBTest {

  private int totalElements = 0;
  private Vertex tomCruise;
  private Vertex nicoleKidman;

  @Parameters(value = "remote")
  public TraverseTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void init() {
    session.createVertexClass("Movie");
    session.createVertexClass("Actor");

    session.createEdgeClass("actorIn");
    session.createEdgeClass("friend");
    session.createEdgeClass("married");

    session.begin();
    tomCruise = session.newVertex("Actor");
    tomCruise.setProperty("name", "Tom Cruise");

    totalElements++;

    var megRyan = session.newVertex("Actor");
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

  public void traverseSQLAllFromActorNoWhere() {
    List<EntityImpl> result1 =
        session
            .command(new SQLSynchQuery<EntityImpl>("traverse * from " + tomCruise.getIdentity()))
            .execute(session);
    Assert.assertEquals(result1.size(), totalElements);
  }

  public void traverseAPIAllFromActorNoWhere() {
    var result1 =
        new Traverse(session).fields("*").target(tomCruise.getIdentity()).execute(session);
    Assert.assertEquals(result1.size(), totalElements);
  }

  @Test
  public void traverseSQLOutFromActor1Depth() {
    List<EntityImpl> result1 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "traverse out_ from " + tomCruise.getIdentity() + " while $depth <= 1"))
            .execute(session);

    Assert.assertFalse(result1.isEmpty());
  }

  @Test
  public void traverseSQLMoviesOnly() {
    List<EntityImpl> result1 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from ( traverse any() from Movie ) where @class = 'Movie'"))
            .execute(session);
    Assert.assertFalse(result1.isEmpty());
    for (var d : result1) {
      Assert.assertEquals(d.getSchemaClassName(), "Movie");
    }
  }

  @Test
  public void traverseSQLPerClassFields() {
    List<EntityImpl> result1 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from ( traverse out() from "
                        + tomCruise.getIdentity()
                        + ") where @class = 'Movie'"))
            .execute(session);
    Assert.assertFalse(result1.isEmpty());
    for (Entity d : result1) {
      Assert.assertEquals(d.getSchemaClassName(), "Movie");
    }
  }

  @Test
  public void traverseSQLMoviesOnlyDepth() {
    var result1 =
        session
            .query(
                "select from (traverse * from "
                    + tomCruise.getIdentity()
                    + " while $depth <= 1 ) where @class = 'Movie'").toList();
    Assert.assertTrue(result1.isEmpty());

    List<EntityImpl> result2 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from ( traverse * from "
                        + tomCruise.getIdentity()
                        + " while $depth <= 2 ) where @class = 'Movie'"))
            .execute(session);
    Assert.assertFalse(result2.isEmpty());
    for (var d : result2) {
      Assert.assertEquals(d.getSchemaClassName(), "Movie");
    }

    List<EntityImpl> result3 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from ( traverse * from "
                        + tomCruise.getIdentity()
                        + " ) where @class = 'Movie'"))
            .execute(session);
    Assert.assertFalse(result3.isEmpty());
    Assert.assertTrue(result3.size() > result2.size());
    for (var d : result3) {
      Assert.assertEquals(d.getSchemaClassName(), "Movie");
    }
  }

  @Test
  public void traverseSelect() {
    List<EntityImpl> result1 =
        session
            .command(new SQLSynchQuery<EntityImpl>("traverse * from ( select from Movie )"))
            .execute(session);
    Assert.assertEquals(result1.size(), totalElements);
  }

  @Test
  public void traverseSQLSelectAndTraverseNested() {
    List<EntityImpl> result1 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "traverse * from ( select from ( traverse * from "
                        + tomCruise.getIdentity()
                        + " while $depth <= 2 ) where @class = 'Movie' )"))
            .execute(session);
    Assert.assertEquals(result1.size(), totalElements);
  }

  @Test
  public void traverseAPISelectAndTraverseNested() {
    List<EntityImpl> result1 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "traverse * from ( select from ( traverse * from "
                        + tomCruise.getIdentity()
                        + " while $depth <= 2 ) where @class = 'Movie' )"))
            .execute(session);
    Assert.assertEquals(result1.size(), totalElements);
  }

  @Test
  public void traverseAPISelectAndTraverseNestedDepthFirst() {
    List<EntityImpl> result1 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "traverse * from ( select from ( traverse * from "
                        + tomCruise.getIdentity()
                        + " while $depth <= 2 strategy depth_first ) where @class = 'Movie' )"))
            .execute(session);
    Assert.assertEquals(result1.size(), totalElements);
  }

  @Test
  public void traverseAPISelectAndTraverseNestedBreadthFirst() {
    List<EntityImpl> result1 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "traverse * from ( select from ( traverse * from "
                        + tomCruise.getIdentity()
                        + " while $depth <= 2 strategy breadth_first ) where @class = 'Movie' )"))
            .execute(session);
    Assert.assertEquals(result1.size(), totalElements);
  }

  @Test
  public void traverseSQLIterating() {
    var cycles = 0;
    for (var result : session.query("traverse * from Movie while $depth < 2").toList()) {
      cycles++;
    }
    Assert.assertTrue(cycles > 0);
  }

  @Test
  public void traverseAPIIterating() {
    var cycles = 0;
    for (var id :
        new Traverse(session)
            .target(session.browseClass("Movie").iterator())
            .predicate(
                new CommandPredicate() {
                  @Override
                  public Object evaluate(
                      Result iRecord, EntityImpl iCurrentResult,
                      CommandContext iContext) {
                    return ((Integer) iContext.getVariable("depth")) <= 2;
                  }
                })) {
      cycles++;
    }
    Assert.assertTrue(cycles > 0);
  }

  @Test
  public void traverseAPIandSQLIterating() {
    var cycles = 0;
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    for (var id :
        new Traverse(session)
            .target(session.browseClass("Movie").iterator())
            .predicate(new SQLPredicate(context, "$depth <= 2"))) {
      cycles++;
    }
    Assert.assertTrue(cycles > 0);
  }

  @Test
  public void traverseSelectIterable() {
    var cycles = 0;
    for (var result : session.query(
        "select from ( traverse * from Movie while $depth < 2 )").toList()) {
      cycles++;
    }
    Assert.assertTrue(cycles > 0);
  }

  @Test
  public void traverseSelectNoInfluence() {
    List<EntityImpl> result1 =
        session
            .command(new SQLSynchQuery<EntityImpl>("traverse any() from Movie while $depth < 2"))
            .execute(session);
    List<EntityImpl> result2 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from ( traverse any() from Movie while $depth < 2 )"))
            .execute(session);
    List<EntityImpl> result3 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from ( traverse any() from Movie while $depth < 2 ) where true"))
            .execute(session);
    List<EntityImpl> result4 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from ( traverse any() from Movie while $depth < 2 and ( true = true ) )"
                        + " where true"))
            .execute(session);

    Assert.assertEquals(result1, result2);
    Assert.assertEquals(result1, result3);
    Assert.assertEquals(result1, result4);
  }

  @Test
  public void traverseNoConditionLimit1() {
    List<EntityImpl> result1 =
        session
            .command(new SQLSynchQuery<EntityImpl>("traverse any() from Movie limit 1"))
            .execute(session);

    Assert.assertEquals(result1.size(), 1);
  }

  @Test
  public void traverseAndFilterByAttributeThatContainsDotInValue() {
    // issue #4952
    List<EntityImpl> result1 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from ( traverse out_married, in[attributeWithDotValue = 'a.b']  from "
                        + tomCruise.getIdentity()
                        + ")"))
            .execute(session);
    Assert.assertFalse(result1.isEmpty());
    var found = false;
    for (var doc : result1) {
      String name = doc.field("name");
      if ("Nicole Kidman".equals(name)) {
        found = true;
        break;
      }
    }
    Assert.assertTrue(found);
  }

  @Test
  public void traverseAndFilterWithNamedParam() {
    // issue #5225
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("param1", "a.b");
    List<EntityImpl> result1 =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select from (traverse out_married, in[attributeWithDotValue = :param1]  from "
                        + tomCruise.getIdentity()
                        + ")"))
            .execute(session, params);
    Assert.assertFalse(result1.isEmpty());
    var found = false;
    for (var doc : result1) {
      String name = doc.field("name");
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
        executeQuery(
            "select *, $depth as d from ( traverse out_married  from "
                + tomCruise.getIdentity()
                + " while $depth < 2)");
    Assert.assertEquals(result1.size(), 2);

    var found = false;
    var i = 0;
    for (var res : result1) {
      Integer depth = res.getProperty("d");
      Assert.assertEquals(depth, i++);
    }
  }

  @Test
  public void traverseAndCheckReturn() {
    var q = "traverse in('married')  from " + nicoleKidman.getIdentity();
    var db = this.session.copy();
    List<Object> result1 = db.command(new SQLSynchQuery<EntityImpl>(q)).execute(this.session);
    Assert.assertEquals(result1.size(), 2);
    var found = false;
    Integer i = 0;
    for (var doc : result1) {
      Assert.assertTrue(((Entity) doc).isVertex());
    }
  }
}
