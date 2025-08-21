package com.jetbrains.youtrackdb.internal.lucene.sandbox;

import com.jetbrains.youtrackdb.internal.lucene.tests.LuceneBaseTest;
import org.apache.lucene.codecs.simpletext.SimpleTextCodec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneSandboxTest extends LuceneBaseTest {

  @Before
  public void setUp() throws Exception {
    session.execute("CREATE CLASS CDR");
    session.execute("CREATE PROPERTY  CDR.filename STRING");

    session.begin();
    session.execute(
        "INSERT into cdr(filename)"
            + " values('MDCA10MCR201612291808.276388.eno.RRC.20161229183002.PROD_R4.eno.data') ");
    session.execute(
        "INSERT into cdr(filename)"
            + " values('MDCA20MCR201612291911.277904.eno.RRC.20161229193002.PROD_R4.eno.data') ");
    session.commit();
  }

  @Test
  public void shouldFetchOneDocumentWithExactMatchOnLuceneIndexStandardAnalyzer() throws Exception {
    session.execute("CREATE INDEX cdr.filename ON cdr(filename) FULLTEXT ENGINE LUCENE ");
    // partial match
    var res =
        session.query(
            "select from cdr WHERE filename LUCENE ' RRC.20161229193002.PROD_R4.eno.data '");

    Assertions.assertThat(res).hasSize(2);
    res.close();
    // exact match
    res =
        session.query(
            "select from cdr WHERE filename LUCENE '"
                + " \"MDCA20MCR201612291911.277904.eno.RRC.20161229193002.PROD_R4.eno.data\" '");

    Assertions.assertThat(res).hasSize(1);
    res.close();
    // wildcard
    res = session.query("select from cdr WHERE filename LUCENE ' MDCA* '");

    Assertions.assertThat(res).hasSize(2);
    res.close();
  }

  @Test
  public void shouldFetchOneDocumentWithExactMatchOnLuceneIndexKeyWordAnalyzer() throws Exception {

    session.execute(
        "CREATE INDEX cdr.filename ON cdr(filename) FULLTEXT ENGINE LUCENE metadata {"
            + " 'allowLeadingWildcard': true}");

    // partial match
    var res =
        session.query(
            "select from cdr WHERE SEARCH_CLASS( ' RRC.20161229193002.PROD_R4.eno.data ') = true");

    Assertions.assertThat(res).hasSize(2);
    res.close();
    // exact match
    res =
        session.query(
            "select from cdr WHERE SEARCH_CLASS( '"
                + " \"MDCA20MCR201612291911.277904.eno.RRC.20161229193002.PROD_R4.eno.data\" ') ="
                + " true");

    Assertions.assertThat(res).hasSize(1);
    res.close();
    // wildcard
    res = session.query("select from cdr WHERE SEARCH_CLASS(' MDCA* ')= true");
    res.close();
    // leadind wildcard
    res = session.query("select from cdr WHERE SEARCH_CLASS(' *20MCR2016122* ') =true");

    Assertions.assertThat(res).hasSize(1);
    res.close();
  }

  @Test
  public void testHierarchy() throws Exception {

    session.execute("CREATE Class Father EXTENDS V");
    session.execute("CREATE PROPERTY Father.text STRING");

    session.execute("CREATE INDEX Father.text ON Father(text) FULLTEXT ENGINE LUCENE ");

    session.execute("CREATE Class Son EXTENDS Father");
    session.execute("CREATE PROPERTY Son.textOfSon STRING");

    session.execute("CREATE INDEX Son.textOfSon ON Son(textOfSon) FULLTEXT ENGINE LUCENE ");
    var father = session.getMetadata().getSchema().getClass("Father");
  }

  @Test
  public void documentSertest() throws Exception {

    var doc = new Document();
    doc.add(new StringField("text", "yabba dabba", Field.Store.YES));

    var codec = new SimpleTextCodec();
  }

  @Test
  public void charset() throws Exception {

    var element = ";";
    int x = element.charAt(0);
    System.out.println("x=" + x);
  }
}
