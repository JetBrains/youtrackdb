package com.jetbrains.youtrack.db.internal.lucene.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.lucene.test.BaseLuceneTest;
import java.io.IOException;
import java.util.logging.Level;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneAllIndexTest extends BaseLuceneTest {

  @Before
  public void init() throws IOException {
    LogManager.instance().installCustomFormatter();
    LogManager.instance().setConsoleLevel(Level.INFO.getName());

    System.setProperty("youtrackdb.test.env", "ci");

    var fromStream =
        IOUtils.readStreamAsString(ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql"));
    session.runScript("sql", fromStream).close();
    session.setProperty("CUSTOM", "strictSql=false");

    // three separate indeexs, one result
    session.execute(
            "create index Song.title on Song (title) FULLTEXT ENGINE LUCENE METADATA"
                + " {\"index_analyzer\":\""
                + StandardAnalyzer.class.getName()
                + "\"}")
        .close();

    session.execute(
            "create index Song.author on Song (author) FULLTEXT ENGINE LUCENE METADATA"
                + " {\"index_analyzer\":\""
                + StandardAnalyzer.class.getName()
                + "\"}")
        .close();

    session.execute(
            "create index Song.lyrics on Song (lyrics) FULLTEXT ENGINE LUCENE METADATA"
                + " {\"index_analyzer\":\""
                + EnglishAnalyzer.class.getName()
                + "\"}")
        .close();
  }

  @Test
  @Ignore // FIXME: No function with name 'lucene_match'
  public void testLuceneFunction() {
    var docs =
        session.query("select from Song where lucene_match( \"Song.author:Fabbio\" ) = true ");
    assertThat(docs).hasSize(87);
  }
}
