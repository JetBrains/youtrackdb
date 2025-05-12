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

import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class LuceneCreateIndexTest extends BaseLuceneTest {

  @Test
  public void loadAndTest() {
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");

    session.computeScript("sql", getScriptFromStream(stream)).close();

    session.execute(
            "create index Song.title on Song (title) FULLTEXT ENGINE LUCENE METADATA"
                + " {\"analyzer\":\""
                + StandardAnalyzer.class.getName()
                + "\"}")
        .close();
    session.execute(
            "create index Song.author on Song (author) FULLTEXT ENGINE LUCENE METADATA"
                + " {\"analyzer\":\""
                + StandardAnalyzer.class.getName()
                + "\"}")
        .close();

    session.begin();
    var doc = ((EntityImpl) session.newVertex("Song"));

    doc.setProperty("title", "Local");
    doc.setProperty("author", "Local");
    session.commit();

    testMetadata();
    assertQuery();

    assertNewQuery();

    session.close();

    session = openDatabase();

    assertQuery();

    assertNewQuery();
  }

  protected void testMetadata() {
    var index =
        session.getSharedContext().getIndexManager().getIndex("Song.title")
            .getMetadata();

    Assert.assertEquals(index.get("analyzer"), StandardAnalyzer.class.getName());
  }

  protected void assertQuery() {
    var docs = session.query("select * from Song where title LUCENE \"mountain\"");

    Assert.assertEquals(4, docs.stream().count());

    docs = session.query("select * from Song where author LUCENE \"Fabbio\"");

    Assert.assertEquals(87, docs.stream().count());

    System.out.println("-------------");
    var query =
        "select * from Song where title LUCENE \"mountain\" and author LUCENE \"Fabbio\"  ";
    // String query = "select * from Song where [title] LUCENE \"(title:mountain)\"  and author =
    // 'Fabbio'";
    docs = session.query(query);
    Assert.assertEquals(1, docs.stream().count());

    query = "select * from Song where title LUCENE \"mountain\"  and author = 'Fabbio'";
    docs = session.query(query);

    Assert.assertEquals(1, docs.stream().count());
  }

  protected void assertNewQuery() {

    var docs = session.query("select * from Song where [title] LUCENE \"(title:Local)\"");

    Assert.assertEquals(1, docs.stream().count());
  }
}
