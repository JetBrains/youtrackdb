/*
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
import static org.assertj.core.api.Assertions.within;

import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.lucene.analyzer.LucenePerFieldAnalyzerWrapper;
import java.io.File;
import java.io.IOException;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneIndexVsLuceneTest extends LuceneBaseTest {

  private IndexWriter indexWriter;
  private LucenePerFieldAnalyzerWrapper analyzer;

  @Before
  public void init() {
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");

    session.execute("sql", getScriptFromStream(stream));

    session.getMetadata().getSchema();

    FileUtils.deleteRecursively(getPath().getAbsoluteFile());
    try {
      var dir = getDirectory();
      analyzer = new LucenePerFieldAnalyzerWrapper(new StandardAnalyzer());

      analyzer.add("title", new StandardAnalyzer()).add("Song.title", new StandardAnalyzer());

      var iwc = new IndexWriterConfig(analyzer);
      iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
      indexWriter = new IndexWriter(dir, iwc);

    } catch (IOException e) {
      e.printStackTrace();
    }
    session.command("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE").close();
  }

  private File getPath() {
    return new File("./target/databases/" + name.getMethodName());
  }

  protected Directory getDirectory() throws IOException {
    return NIOFSDirectory.open(getPath().toPath());
  }

  @Test
  @Ignore
  public void testLuceneVsLucene() throws IOException, ParseException {

    var entityIterator = session.browseClass("Song");
    while (entityIterator.hasNext()) {
      var entity = entityIterator.next();
      String title = entity.field("title");
      if (title != null) {
        var d = new Document();

        d.add(new TextField("title", title, Field.Store.YES));
        d.add(new TextField("Song.title", title, Field.Store.YES));
        indexWriter.addDocument(d);
      }
    }

    indexWriter.commit();
    indexWriter.close();

    IndexReader reader = DirectoryReader.open(getDirectory());
    assertThat(reader.numDocs()).isEqualTo(Long.valueOf(session.countClass("Song")).intValue());

    var searcher = new IndexSearcher(reader);

    var query = new MultiFieldQueryParser(new String[]{"title"}, analyzer).parse("down the");
    final var docs = searcher.search(query, Integer.MAX_VALUE);

    var resultSet =
        session.query("select *,$score from Song where search_class('down the')=true");

    resultSet.stream()
        .forEach(
            r -> {
              System.out.println("r = " + r);
              assertThat((Object[]) r.getProperty("$score")).isNotNull();
            });

    resultSet.close();
  }
}
