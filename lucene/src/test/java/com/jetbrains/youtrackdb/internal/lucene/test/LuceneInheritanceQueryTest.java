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
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;


public class LuceneInheritanceQueryTest extends BaseLuceneTest {
  public LuceneInheritanceQueryTest() {
  }

  @Test
  public void testQuery() {
    createSchema(session);
    session.begin();
    var doc = ((EntityImpl) session.newVertex("C2"));
    doc.setProperty("name", "abc");
    session.commit();

    session.begin();
    var vertices = session.query("select from C1 where name lucene \"abc\" ");

    Assert.assertEquals(1, vertices.stream().count());
    session.commit();
  }

  protected static void createSchema(DatabaseSessionInternal db) {
    final var c1 = db.createVertexClass("C1");
    c1.createProperty("name", PropertyType.STRING);
    c1.createIndex("C1.name", "FULLTEXT", null, null, "LUCENE", new String[]{"name"});

    db.createClass("C2", "C1");
  }
}
