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

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class QueryLocalCacheIntegrationTest extends BaseDBTest {

  @Parameters(value = "remote")
  public QueryLocalCacheIntegrationTest(boolean remote) {
    super(remote);
  }

  @BeforeMethod
  public void beforeMeth() throws Exception {
    database.getMetadata().getSchema().createClass("FetchClass");

    database
        .getMetadata()
        .getSchema()
        .createClass("SecondFetchClass")
        .createProperty(database, "surname", PropertyType.STRING)
        .setMandatory(database, true);
    database.getMetadata().getSchema().createClass("OutInFetchClass");

    database.begin();
    EntityImpl singleLinked = new EntityImpl();
    database.save(singleLinked);
    EntityImpl doc = new EntityImpl("FetchClass");
    doc.field("name", "first");
    database.save(doc);
    EntityImpl doc1 = new EntityImpl("FetchClass");
    doc1.field("name", "second");
    doc1.field("linked", singleLinked);
    database.save(doc1);
    EntityImpl doc2 = new EntityImpl("FetchClass");
    doc2.field("name", "third");
    List<EntityImpl> linkList = new ArrayList<EntityImpl>();
    linkList.add(doc);
    linkList.add(doc1);
    doc2.field("linkList", linkList);
    doc2.field("linked", singleLinked);
    Set<EntityImpl> linkSet = new HashSet<EntityImpl>();
    linkSet.add(doc);
    linkSet.add(doc1);
    doc2.field("linkSet", linkSet);
    database.save(doc2);

    EntityImpl doc3 = new EntityImpl("FetchClass");
    doc3.field("name", "forth");
    doc3.field("ref", doc2);
    doc3.field("linkSet", linkSet);
    doc3.field("linkList", linkList);
    database.save(doc3);

    EntityImpl doc4 = new EntityImpl("SecondFetchClass");
    doc4.field("name", "fifth");
    doc4.field("surname", "test");
    database.save(doc4);

    EntityImpl doc5 = new EntityImpl("SecondFetchClass");
    doc5.field("name", "sixth");
    doc5.field("surname", "test");
    database.save(doc5);

    EntityImpl doc6 = new EntityImpl("OutInFetchClass");
    RidBag out = new RidBag(database);
    out.add(doc2);
    out.add(doc3);
    doc6.field("out_friend", out);
    RidBag in = new RidBag(database);
    in.add(doc4);
    in.add(doc5);
    doc6.field("in_friend", in);
    doc6.field("name", "myName");
    database.save(doc6);

    database.commit();
  }

  @AfterMethod
  public void afterMeth() throws Exception {
    database.getMetadata().getSchema().dropClass("FetchClass");
    database.getMetadata().getSchema().dropClass("SecondFetchClass");
    database.getMetadata().getSchema().dropClass("OutInFetchClass");
  }

  @Test
  public void queryTest() {
    final long times = ProfilerStub.INSTANCE.getCounter("Cache.reused");

    List<EntityImpl> resultset =
        database.query(new SQLSynchQuery<EntityImpl>("select * from FetchClass"));
    Assert.assertEquals(
        ProfilerStub.INSTANCE.getCounter("Cache.reused"), times);

    RID linked;
    for (EntityImpl d : resultset) {
      linked = d.field("linked", RID.class);
      if (linked != null) {
        Assert.assertNull(database.getLocalCache().findRecord(linked));
      }
    }
  }
}
