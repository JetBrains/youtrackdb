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
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class GraphEmbeddedTest extends BaseLuceneTest {

  public GraphEmbeddedTest() {
  }

  @Before
  public void init() {

    var type = session.createVertexClass("City");
    type.createProperty("latitude", PropertyType.DOUBLE);
    type.createProperty("longitude", PropertyType.DOUBLE);
    type.createProperty("name", PropertyType.STRING);

    session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void embeddedTx() {

    // THIS WON'T USE LUCENE INDEXES!!!! see #6997

    session.begin();
    var city = session.newVertex("City");
    city.setProperty("name", "London / a");

    city = session.newVertex("City");
    city.setProperty("name", "Rome");
    session.commit();

    session.begin();

    var resultSet = session.query("SELECT from City where name = 'London / a' ");

    Assertions.assertThat(IteratorUtils.count(resultSet)).isEqualTo(1);

    resultSet = session.query("SELECT from City where name = 'Rome' ");

    Assertions.assertThat(IteratorUtils.count(resultSet)).isEqualTo(1);
  }

  @Test
  public void testGetVericesFilterClass() {
    var v = session.getClass("V");
    v.createProperty("name", PropertyType.STRING);
    session.execute("CREATE INDEX V.name ON V(name) NOTUNIQUE");

    var oneClass = session.createVertexClass("One");
    var twoClass = session.createVertexClass("Two");

    session.begin();
    var one = session.newVertex(oneClass);
    one.setProperty("name", "Same");
    session.commit();

    session.begin();
    var two = session.newVertex(twoClass);
    two.setProperty("name", "Same");
    session.commit();

    session.begin();
    var resultSet = session.query("SELECT from One where name = 'Same' ");

    Assertions.assertThat(IteratorUtils.count(resultSet)).isEqualTo(1);
    session.commit();
  }
}
