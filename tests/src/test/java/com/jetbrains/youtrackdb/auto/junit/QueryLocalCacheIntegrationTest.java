/*
 * JUnit 4 version of QueryLocalCacheIntegrationTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/QueryLocalCacheIntegrationTest.java
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
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of QueryLocalCacheIntegrationTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/QueryLocalCacheIntegrationTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class QueryLocalCacheIntegrationTest extends BaseDBTest {

  private static QueryLocalCacheIntegrationTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new QueryLocalCacheIntegrationTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeMeth (line 29) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/QueryLocalCacheIntegrationTest.java
   */
  @Before
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
    var linkList = session.newLinkList();
    linkList.add(doc);
    linkList.add(doc1);
    doc2.setProperty("linkList", linkList);
    doc2.setProperty("linked", singleLinked);
    var linkSet = session.newLinkSet();
    linkSet.add(doc);
    linkSet.add(doc1);
    doc2.setProperty("linkSet", linkSet);

    var doc3 = ((EntityImpl) session.newEntity("FetchClass"));
    doc3.setProperty("name", "forth");
    doc3.setProperty("ref", doc2);
    doc3.setProperty("linkSet", session.newLinkSet(linkSet));
    doc3.setProperty("linkList", session.newLinkList(linkList));

    var doc4 = ((EntityImpl) session.newEntity("SecondFetchClass"));
    doc4.setProperty("name", "fifth");
    doc4.setProperty("surname", "test");

    var doc5 = ((EntityImpl) session.newEntity("SecondFetchClass"));
    doc5.setProperty("name", "sixth");
    doc5.setProperty("surname", "test");

    var doc6 = ((EntityImpl) session.newEntity("OutInFetchClass"));
    var out = new LinkBag(session);
    out.add(doc2.getIdentity());
    out.add(doc3.getIdentity());
    doc6.setProperty("out_friend", out);
    var in = new LinkBag(session);
    in.add(doc4.getIdentity());
    in.add(doc5.getIdentity());
    doc6.setProperty("in_friend", in);
    doc6.setProperty("name", "myName");

    session.commit();
  }

  /**
   * Original: afterMeth (line 88) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/QueryLocalCacheIntegrationTest.java
   */
  @After
  public void afterMeth() {
    session.getMetadata().getSchema().dropClass("FetchClass");
    session.getMetadata().getSchema().dropClass("SecondFetchClass");
    session.getMetadata().getSchema().dropClass("OutInFetchClass");
  }

  /**
   * Original: queryTest (line 96) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/QueryLocalCacheIntegrationTest.java
   */
  @Test
  public void test01_QueryTest() {
    session.begin();

    var resultset =
        session.query("select * from FetchClass").toList();

    RID linked;
    for (var d : resultset) {
      linked = d.getLink("linked");
      if (linked != null) {
        Assert.assertNull(session.getLocalCache().findRecord(linked));
      }
    }
    session.commit();
  }
}
