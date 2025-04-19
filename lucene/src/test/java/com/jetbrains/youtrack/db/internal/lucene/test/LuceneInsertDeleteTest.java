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

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneInsertDeleteTest extends BaseLuceneTest {

  @Before
  public void init() {

    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("City");

    oClass.createProperty("name", PropertyType.STRING);
    session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void testInsertUpdateWithIndex() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "Rome");
    session.commit();

    session.begin();
    var idx = session.getClassInternal("City").getClassIndex(session, "City.name");
    Collection<?> coll;
    try (var stream = idx.getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }

    assertThat(coll).hasSize(1);
    assertThat(idx.size(session)).isEqualTo(1);
    session.commit();

    session.begin();
    var next = (Identifiable) coll.iterator().next();
    doc = session.load(next.getIdentity());

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    session.delete(doc);
    session.commit();

    session.begin();
    try (var stream = idx.getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(0);
    assertThat(idx.size(session)).isEqualTo(0);
    session.commit();
  }

  @Test
  public void testDeleteWithQueryOnClosedIndex() throws Exception {

    try (var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql")) {
      session.computeScript("sql", getScriptFromStream(stream)).close();
    }

    session.execute(
            "create index Song.title on Song (title) FULLTEXT ENGINE LUCENE metadata"
                + " {'closeAfterInterval':1000 , 'firstFlushAfter':1000 }")
        .close();

    var docs = session.query("select from Song where title lucene 'mountain'");

    assertThat(docs).hasSize(4);
    TimeUnit.SECONDS.sleep(5);

    session.begin();
    session.execute("delete vertex from Song where title lucene 'mountain'").close();
    session.commit();

    docs = session.query("select from Song where  title lucene 'mountain'");
    assertThat(docs).hasSize(0);
  }
}
