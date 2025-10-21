/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class CommandExecutorSQLCreateLinkTest extends DbTestBase {

  @Test
  public void testBasic() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Basic1").
            createSchemaProperty("theLink", PropertyType.LINK).
            createSchemaClass("Basic2").
            createSchemaProperty("theLink", PropertyType.LINK)
    );

    session.begin();
    session.execute("insert into Basic1 set pk = 'pkb1_1', fk = 'pkb2_1'").close();
    session.execute("insert into Basic1 set pk = 'pkb1_2', fk = 'pkb2_2'").close();

    session.execute("insert into Basic2 set pk = 'pkb2_1'").close();
    session.execute("insert into Basic2 set pk = 'pkb2_2'").close();

    session.execute("CREATE LINK theLink type link FROM Basic1.fk TO Basic2.pk ").close();
    session.commit();

    var result = session.query("select pk, theLink.pk as other from Basic1 order by pk");

    var otherKey = result.next().getProperty("other");
    Assert.assertNotNull(otherKey);

    Assert.assertEquals("pkb2_1", otherKey);

    otherKey = result.next().getProperty("other");
    Assert.assertEquals("pkb2_2", otherKey);
  }

  @Test
  public void testInverse() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Inverse1").
            createSchemaProperty("theLink", PropertyType.LINK).
            createSchemaClass("Inverse2").
            createSchemaProperty("theLink", PropertyType.LINKSET)
    );

    session.begin();
    session.execute("insert into Inverse1 set pk = 'pkb1_1', fk = 'pkb2_1'").close();
    session.execute("insert into Inverse1 set pk = 'pkb1_2', fk = 'pkb2_2'").close();
    session.execute("insert into Inverse1 set pk = 'pkb1_3', fk = 'pkb2_2'").close();

    session.execute("insert into Inverse2 set pk = 'pkb2_1'").close();
    session.execute("insert into Inverse2 set pk = 'pkb2_2'").close();

    session.execute("CREATE LINK theLink TYPE LINKSET FROM Inverse1.fk TO Inverse2.pk INVERSE")
        .close();
    session.commit();

    var result = session.query("select pk, theLink.pk as other from Inverse2 order by pk");
    var first = result.next();
    var otherKeys = first.getProperty("other");
    Assert.assertNotNull(otherKeys);
    Assert.assertTrue(otherKeys instanceof List);
    Assert.assertEquals("pkb1_1", ((List) otherKeys).get(0));

    var second = result.next();
    otherKeys = second.getProperty("other");
    Assert.assertNotNull(otherKeys);
    Assert.assertTrue(otherKeys instanceof List);
    Assert.assertEquals(2, ((List) otherKeys).size());
    Assert.assertTrue(((List) otherKeys).contains("pkb1_2"));
    Assert.assertTrue(((List) otherKeys).contains("pkb1_3"));
  }
}
