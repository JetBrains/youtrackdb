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

package com.jetbrains.youtrackdb.internal.lucene.tests;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.assertj.core.api.Assertions;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneMassiveInsertDeleteTest extends LuceneBaseTest {

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();
    var song = session.createVertexClass("City");
    song.createProperty("name", PropertyType.STRING);

    session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE");
  }

  @Test
  public void loadCloseDelete() {

    var size = 1000;
    for (var i = 0; i < size; i++) {
      session.begin();
      var city = session.newVertex("City");
      city.setProperty("name", "Rome " + i);

      session.commit();
    }

    session.begin();
    var query = "select * from City where search_class('name:Rome')=true";
    var docs = session.query(query);
    Assertions.assertThat(IteratorUtils.count(docs)).isEqualTo(size);
    docs.close();
    session.commit();
    session.close();

    session = (DatabaseSessionEmbedded) pool.acquire();
    session.begin();
    docs = session.query(query);
    Assertions.assertThat(IteratorUtils.count(docs)).isEqualTo(size);
    docs.close();
    session.commit();

    session.begin();
    session.execute("delete vertex City");
    session.commit();

    docs = session.query(query);
    Assertions.assertThat(IteratorUtils.count(docs)).isEqualTo(0);
    docs.close();
    session.close();
    session = (DatabaseSessionEmbedded) pool.acquire();
    docs = session.query(query);
    Assertions.assertThat(IteratorUtils.count(docs)).isEqualTo(0);
    docs.close();
    session.getMetadata().reload();

    session.begin();
    var idx = session.getMetadata().getSchema().getClassInternal("City")
        .getClassIndex(session, "City.name");
    Assert.assertEquals(0, idx.size(session));
    session.commit();
  }
}
