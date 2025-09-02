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

package com.jetbrains.youtrack.db.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneInheritanceQueryTest extends LuceneBaseTest {

  @Before
  public void setUp() throws Exception {
    final var c1 = session.createVertexClass("C1");
    c1.createProperty("name", PropertyType.STRING);
    c1.createIndex("C1.name", "FULLTEXT", null, null, "LUCENE", new String[]{"name"});

    final var c2 = session.createClass("C2", "C1");
  }

  @Test
  public void testQuery() {
    session.begin();
    var doc = ((EntityImpl) session.newVertex("C2"));
    doc.setProperty("name", "abc");
    session.commit();

    session.begin();
    var resultSet = session.query("select from C1 where search_class(\"abc\")=true ");

    assertThat(IteratorUtils.count(resultSet)).isEqualTo(1);
    resultSet.close();
    session.commit();
  }
}
