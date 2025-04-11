package com.jetbrains.youtrack.db.internal.lucene.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseDocumentTx;
import java.io.IOException;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.RAMDirectory;
import org.junit.After;
import org.junit.Test;

/**
 *
 */
public class VertexIndexTest {

  @Test
  public void testSpacesInQuery() throws IOException, ParseException {

    var conf = new IndexWriterConfig(new StandardAnalyzer());
    final var directory = new RAMDirectory();
    final var writer = new IndexWriter(directory, conf);

    var doc = new Document();
    doc.add(new TextField("name", "Max Water", Field.Store.YES));
    writer.addDocument(doc);

    doc = new Document();
    doc.add(new TextField("name", "Max Waterson", Field.Store.YES));
    writer.addDocument(doc);

    doc = new Document();
    doc.add(new TextField("name", "Cory Watney", Field.Store.YES));
    writer.addDocument(doc);

    writer.commit();

    IndexReader reader = DirectoryReader.open(directory);

    var searcher = new IndexSearcher(reader);

    Analyzer analyzer = new StandardAnalyzer();

    var queryParser = new QueryParser("name", analyzer);

    final var query = queryParser.parse("name:Max AND name:Wat*");

    final var topDocs = searcher.search(query, 10);

    assertThat(topDocs.totalHits).isEqualTo(2);
    for (var i = 0; i < topDocs.totalHits; i++) {

      final var found = searcher.doc(topDocs.scoreDocs[i].doc);

      assertThat(found.get("name")).startsWith("Max");
    }

    reader.close();
    writer.close();
  }

  @Test
  public void name() throws Exception {
  }

  @After
  public void deInit() {
    DatabaseDocumentTx.closeAll();
  }
}
