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
  public void beforeMeth() {
    session.getMetadata().getSchema().createClass("FetchClass");

    session
        .getMetadata()
        .getSchema()
        .createClass("SecondFetchClass")
        .createProperty("surname", PropertyType.STRING)
        .setMandatory(true);
    session.getMetadata().getSchema().createClass("OutInFetchClass");

    session.begin();
    var singleLinked = ((EntityImpl) session.newEntity());
    var doc = ((EntityImpl) session.newEntity("FetchClass"));
    doc.setProperty("name", "first");
    var doc1 = ((EntityImpl) session.newEntity("FetchClass"));
    doc1.setProperty("name", "second");
    doc1.setProperty("linked", singleLinked);
    var doc2 = ((EntityImpl) session.newEntity("FetchClass"));
    doc2.setProperty("name", "third");
    List<EntityImpl> linkList = new ArrayList<>();
    linkList.add(doc);
    linkList.add(doc1);
    doc2.setProperty("linkList", linkList);
    doc2.setProperty("linked", singleLinked);
    Set<EntityImpl> linkSet = new HashSet<>();
    linkSet.add(doc);
    linkSet.add(doc1);
    doc2.setProperty("linkSet", linkSet);

    var doc3 = ((EntityImpl) session.newEntity("FetchClass"));
    doc3.setProperty("name", "forth");
    doc3.setProperty("ref", doc2);
    doc3.setProperty("linkSet", linkSet);
    doc3.setProperty("linkList", linkList);

    var doc4 = ((EntityImpl) session.newEntity("SecondFetchClass"));
    doc4.setProperty("name", "fifth");
    doc4.setProperty("surname", "test");

    var doc5 = ((EntityImpl) session.newEntity("SecondFetchClass"));
    doc5.setProperty("name", "sixth");
    doc5.setProperty("surname", "test");

    var doc6 = ((EntityImpl) session.newEntity("OutInFetchClass"));
    var out = new RidBag(session);
    out.add(doc2.getIdentity());
    out.add(doc3.getIdentity());
    doc6.setProperty("out_friend", out);
    var in = new RidBag(session);
    in.add(doc4.getIdentity());
    in.add(doc5.getIdentity());
    doc6.setProperty("in_friend", in);
    doc6.setProperty("name", "myName");

    session.commit();
  }

  @AfterMethod
  public void afterMeth() {
    session.getMetadata().getSchema().dropClass("FetchClass");
    session.getMetadata().getSchema().dropClass("SecondFetchClass");
    session.getMetadata().getSchema().dropClass("OutInFetchClass");
  }

  @Test
  public void queryTest() {
    final long times = ProfilerStub.INSTANCE.getCounter("Cache.reused");

    List<EntityImpl> resultset =
        session.query(new SQLSynchQuery<EntityImpl>("select * from FetchClass"));
    Assert.assertEquals(
        ProfilerStub.INSTANCE.getCounter("Cache.reused"), times);

    RID linked;
    for (var d : resultset) {
      linked = d.getLink("linked");
      if (linked != null) {
        Assert.assertNull(session.getLocalCache().findRecord(linked));
      }
    }
  }
}
