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

import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneMultiFieldTest extends BaseLuceneTest {

  public LuceneMultiFieldTest() {
    super();
  }

  @Before
  public void init() throws Exception {
    try (var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql")) {
      //noinspection deprecation
      session.runScript("sql", getScriptFromStream(stream)).close();
    }

    //noinspection deprecation
    session.execute(
            "create index Song.title_author on Song (title,author) FULLTEXT ENGINE LUCENE METADATA"
                + " {\"title_index\":\""
                + EnglishAnalyzer.class.getName()
                + "\" , "
                + "\"title_query\":\""
                + EnglishAnalyzer.class.getName()
                + "\" , "
                + "\"author_index\":\""
                + StandardAnalyzer.class.getName()
                + "\"}")
        .close();

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, "Song.title_author")
            .getMetadata();

    assertThat(index.get("author_index")).isEqualTo(StandardAnalyzer.class.getName());
    assertThat(index.get("title_index")).isEqualTo(EnglishAnalyzer.class.getName());
  }

  @Test
  public void testSelectSingleDocumentWithAndOperator() {

    var docs =
        session.query(
            "select * from Song where [title,author] LUCENE \"(title:mountain AND"
                + " author:Fabbio)\"");
    assertThat(docs).hasSize(1);
  }

  @Test
  public void testSelectSingleDocumentWithAndOperatorNEwExec() {
    try (var docs =
        session.query(
            "select * from Song where [title,author] LUCENE \"(title:mountain AND"
                + " author:Fabbio)\"")) {

      assertThat(docs.hasNext()).isTrue();
      docs.next();
      assertThat(docs.hasNext()).isFalse();
    }
  }

  @Test
  public void testSelectMultipleDocumentsWithOrOperator() {
    var docs =
        session.query(
            "select * from Song where [title,author] LUCENE \"(title:mountain OR author:Fabbio)\"");

    assertThat(docs).hasSize(91);
  }

  @Test
  public void testSelectOnTitleAndAuthorWithMatchOnTitle() {
    var docs = session.query("select * from Song where [title,author] LUCENE \"mountain\"");

    assertThat(docs).hasSize(5);
  }

  @Test
  public void testSelectOnTitleAndAuthorWithMatchOnAuthor() {
    var docs = session.query("select * from Song where [title,author] LUCENE \"author:fabbio\"");

    assertThat(docs).hasSize(87);
  }

  @Test
  @Ignore
  public void testSelectOnAuthorWithMatchOnAuthor() {
    var docs = session.query("select * from Song where [author,title] LUCENE \"(fabbio)\"");

    assertThat(docs).hasSize(87);
  }

  @Test
  public void testSelectOnIndexWithIgnoreNullValuesToFalse() {
    // #5579
    var script =
        """
            create class Item;
            create property Item.Title string;
            create property Item.Summary string;
            create property Item.Content string;
            create index Item.i_lucene on Item(Title, Summary, Content) fulltext engine lucene\
             METADATA {ignoreNullValues:false};
             begin;
            insert into Item set Title = 'wrong', content = 'not me please';
            insert into Item set Title = 'test', content = 'this is a test';
            commit;
            """;
    session.runScript("sql", script).close();

    var docs = session.query("select * from Item where Title lucene 'te*'");
    assertThat(docs).hasSize(1);

    //noinspection deprecation
    docs = session.query("select * from Item where [Title, Summary, Content] lucene 'test'");

    assertThat(docs).hasSize(1);

    // nidex api
    final var index = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "Item.i_lucene");
    try (var stream = index.getRids(session, "(Title:test )")) {
      assertThat(stream.findAny().isPresent()).isTrue();
    }
  }
}
