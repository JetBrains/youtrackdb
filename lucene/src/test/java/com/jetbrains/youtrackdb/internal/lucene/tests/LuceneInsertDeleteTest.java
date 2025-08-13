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

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneInsertDeleteTest extends LuceneBaseTest {

  @Before
  public void init() {

    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("City");

    oClass.createProperty("name", PropertyType.STRING);
    //noinspection EmptyTryBlock
    try (var resultSet =
        session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE")) {
    }
  }

  @Test
  public void testInsertUpdateWithIndex() {
    session.getMetadata().reload();
    var schema = session.getMetadata().getSchema();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "Rome");
    session.commit();

    var idx = schema.getClassInternal("City").getClassIndex(session, "City.name");
    Collection<?> coll;
    try (var stream = idx.getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }

    session.begin();
    assertThat(coll).hasSize(1);
    assertThat(idx.size(session)).isEqualTo(1);

    var next = (Identifiable) coll.iterator().next();
    doc = session.load(next.getIdentity());

    session.delete(doc);
    session.commit();

    try (var stream = idx.getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(0);
    assertThat(idx.size(session)).isEqualTo(0);
  }

  @Test
  public void testDeleteWithQueryOnClosedIndex() throws Exception {

    try (var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql")) {
      //noinspection EmptyTryBlock
      try (var resultSet = session.computeScript("sql", getScriptFromStream(stream))) {
      }
    }

    //noinspection EmptyTryBlock
    try (var resultSet =
        session.execute(
            "create index Song.title on Song (title) FULLTEXT ENGINE LUCENE metadata"
                + " {'closeAfterInterval':1000 , 'firstFlushAfter':1000 }")) {
    }

    session.begin();
    try (var docs = session.query("select from Song where title lucene 'mountain'")) {
      assertThat(docs).hasSize(4);
    }
    session.commit();
    TimeUnit.SECONDS.sleep(5);

    session.begin();
    //noinspection EmptyTryBlock
    try (var command =
        session.execute("delete vertex from Song where title lucene 'mountain'")) {
    }
    session.commit();

    session.begin();
    try (var resultSet = session.query("select from Song where  title lucene 'mountain'")) {
      assertThat(resultSet).hasSize(0);
    }
    session.commit();
  }
}
