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

package com.jetbrains.youtrack.db.internal.lucene.test;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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
    var v = schema.getClass("V");
    var song = schema.createClass("City");
    song.addSuperClass(v);
    song.createProperty("name", PropertyType.STRING);

    session.command("create index City.name on City (name) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void loadCloseDelete() {

    var size = 1000;
    for (var i = 0; i < size; i++) {
      var city = ((EntityImpl) session.newEntity("City"));
      city.field("name", "Rome " + i);

      session.begin();
      session.commit();
    }
    var query = "select * from City where name LUCENE 'name:Rome'";
    var docs = session.query(query);
    Assert.assertEquals(docs.stream().count(), size);

    session.close();
    session = openDatabase();

    docs = session.query(query);
    Assert.assertEquals(docs.stream().count(), size);

    session.begin();
    session.command("delete vertex City").close();
    session.commit();

    docs = session.query(query);
    Assert.assertEquals(docs.stream().count(), 0);

    session.close();
    session = openDatabase();
    docs = session.query(query);
    Assert.assertEquals(docs.stream().count(), 0);

    session.getMetadata().reload();

    session.begin();
    var idx = session.getMetadata().getSchemaInternal().getClassInternal("City")
        .getClassIndex(session, "City.name");
    Assert.assertEquals(idx.size(session), 0);
    session.commit();
  }
}
