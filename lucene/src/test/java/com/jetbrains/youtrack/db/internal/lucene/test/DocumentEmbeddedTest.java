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
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class DocumentEmbeddedTest extends BaseLuceneTest {

  public DocumentEmbeddedTest() {
  }

  @Before
  public void init() {
    var type = session.getMetadata().getSchema().createClass("City");
    type.createProperty("name", PropertyType.STRING);

    session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void embeddedNoTx() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("City"));

    doc.setProperty("name", "London");

    session.commit();

    session.begin();
    doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "Rome");
    session.commit();

    session.begin();
    var results = session.query("select from City where name lucene 'London'");

    Assert.assertEquals(1, results.stream().count());
    session.commit();
  }

  @Test
  public void embeddedTx() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "Berlin");
    session.commit();

    session.begin();
    var results = session.query("select from City where name lucene 'Berlin'");

    Assert.assertEquals(1, results.stream().count());
    session.commit();
  }
}
