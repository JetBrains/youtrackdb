/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrackdb.internal.lucene.test;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneGraphTXTest extends BaseLuceneTest {

  @Before
  public void init() {
    var type = session.createVertexClass("City");
    type.createProperty("name", PropertyType.STRING);

    session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void graphTxTest() throws Exception {
    session.begin();
    var v = session.newVertex("City");
    v.setProperty("name", "London");
    session.commit();

    session.begin();
    var results = session.execute("select from City where name lucene 'London'");
    Assert.assertEquals(results.stream().count(), 1);

    var activeTx = session.getActiveTransaction();
    v = activeTx.load(v);
    v.setProperty("name", "Berlin");
    session.commit();

    session.begin();
    results = session.execute("select from City where name lucene 'Berlin'");
    Assert.assertEquals(results.stream().count(), 1);

    results = session.execute("select from City where name lucene 'London'");
    Assert.assertEquals(results.stream().count(), 0);

    // Assert After Commit
    results = session.execute("select from City where name lucene 'Berlin'");
    Assert.assertEquals(results.stream().count(), 1);
    results = session.execute("select from City where name lucene 'London'");
    Assert.assertEquals(results.stream().count(), 0);
    session.commit();
  }
}
