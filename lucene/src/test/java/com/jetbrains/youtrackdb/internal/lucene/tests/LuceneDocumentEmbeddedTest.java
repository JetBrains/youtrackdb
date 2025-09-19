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
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneDocumentEmbeddedTest extends LuceneBaseTest {

  @Before
  public void init() {
    var type = session.getMetadata().getSlowMutableSchema().createClass("City");
    type.createProperty("name", PropertyType.STRING);

    session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE");
  }

  @Test
  public void embeddedNoTx() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("City"));

    doc.setProperty("name", "London");

    doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "Rome");

    session.commit();

    session.begin();
    var results =
        session.execute("select from City where SEARCH_FIELDS(['name'] ,'London') = true ");

    Assertions.assertThat(IteratorUtils.count(results)).isEqualTo(1);
    session.commit();
  }

  @Test
  public void embeddedTx() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "Berlin");

    session.commit();

    session.begin();
    var results =
        session.execute("select from City where SEARCH_FIELDS(['name'] ,'Berlin')=true ");

    Assertions.assertThat(IteratorUtils.count(results)).isEqualTo(1);
    session.commit();
  }
}
