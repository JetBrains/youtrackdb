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
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneMassiveInsertDeleteTest extends BaseLuceneTest {

  public LuceneMassiveInsertDeleteTest() {
  }

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();
    var song = schema.createVertexClass("City");
    song.createProperty("name", PropertyType.STRING);

    session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void loadCloseDelete() {

    var size = 1000;
    for (var i = 0; i < size; i++) {
      session.begin();
      var city = ((EntityImpl) session.newVertex("City"));
      city.setProperty("name", "Rome " + i);

      session.commit();
    }

    session.begin();
    var query = "select * from City where name LUCENE 'name:Rome'";
    var docs = session.query(query);
    Assert.assertEquals(size, docs.stream().count());
    session.commit();

    session.close();
    session = openDatabase();

    session.begin();
    docs = session.query(query);
    Assert.assertEquals(size, docs.stream().count());
    session.commit();

    session.begin();
    session.execute("delete vertex City").close();
    session.commit();

    session.begin();
    docs = session.query(query);
    Assert.assertEquals(0, docs.stream().count());
    session.commit();

    session.close();
    session = openDatabase();
    session.begin();
    docs = session.query(query);
    Assert.assertEquals(0, docs.stream().count());
    session.commit();

    session.begin();
    var idx = session.getMetadata().getSchemaInternal().getClassInternal("City")
        .getClassIndex(session, "City.name");
    Assert.assertEquals(0, idx.size(session));
    session.commit();
  }
}
