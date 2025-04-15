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
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneBooleanIndexTest extends BaseLuceneTest {

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();
    var song = schema.createVertexClass("Person");
    song.createProperty("isDeleted", PropertyType.BOOLEAN);

    session.execute("create index Person.isDeleted on Person (isDeleted) FULLTEXT ENGINE LUCENE")
        .close();
  }

  @Test
  public void insertPerson() {

    for (var i = 0; i < 1000; i++) {
      session.begin();
      var doc = ((EntityImpl) session.newVertex("Person"));
      doc.setProperty("isDeleted", i % 2 == 0);

      session.commit();
    }

    session.begin();
    var docs = session.query("select from Person where isDeleted lucene false");

    Assert.assertEquals(
        500, docs.stream().filter((doc) -> !((Boolean) doc.getProperty("isDeleted"))).count());
    docs = session.query("select from Person where isDeleted lucene true");
    Assert.assertEquals(500, docs.stream().filter((doc) -> doc.getProperty("isDeleted")).count());
    session.commit();
  }

  @Test
  public void testMemoryIndex() throws ParseException {
    // TODO To be used in evaluate Record
    var index = new MemoryIndex();

    var doc = new Document();
    doc.add(new StringField("text", "my text", Field.Store.YES));
    var analyzer = new StandardAnalyzer();

    for (var field : doc.getFields()) {
      index.addField(field.name(), field.stringValue(), analyzer);
    }

    var parser = new QueryParser("text", analyzer);
    var score = index.search(parser.parse("+text:my"));
  }
}
