package com.jetbrains.youtrackdb.internal.lucene.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.internal.lucene.test.BaseLuceneTest;
import org.apache.commons.configuration2.Configuration;
import org.junit.Before;
import org.junit.Test;

public class LuceneSearchMoreLikeThisFunctionTest extends BaseLuceneTest {

  @Before
  public void setUp() throws Exception {
    try (var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql")) {
      session.computeScript("sql", getScriptFromStream(stream)).close();
    }
  }

  @Override
  protected Configuration createConfig() {
    var config = super.createConfig();
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);

    return config;
  }

  @Test
  public void shouldSearchMoreLikeThisWithRid() {
    session.execute("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE ");
    var tx = session.begin();
    var firstRecordId = tx.query("SELECT * FROM Song WHERE title = 'UNCLE SAMS BLUES'")
        .findFirstEntity(
            DBRecord::getIdentity);
    var secondRecordId = tx.query("SELECT * FROM Song WHERE title = 'THINGS I USED TO DO'")
        .findFirstEntity(
            DBRecord::getIdentity);
    try (var resultSet =
        session.query(
            "SELECT from Song where SEARCH_More([?, ?],{'minTermFreq':1, 'minDocFreq':1} ) = true",
            firstRecordId, secondRecordId)) {
      assertThat(resultSet).hasSize(48);
    }
    tx.commit();
  }

  @Test
  public void shouldSearchMoreLikeThisWithRidOnMultiFieldsIndex() throws Exception {
    session.execute("create index Song.multi on Song (title,author) FULLTEXT ENGINE LUCENE ");

    var tx = session.begin();
    var firstRecordId = tx.query("SELECT * FROM Song WHERE title = 'UNCLE SAMS BLUES'")
        .findFirstEntity(
            DBRecord::getIdentity);
    var secondRecordId = tx.query("SELECT * FROM Song WHERE title = 'THINGS I USED TO DO'")
        .findFirstEntity(
            DBRecord::getIdentity);
    try (var resultSet =
        tx.query(
            "SELECT from Song where SEARCH_More([?, ?] , {'minTermFreq':1, 'minDocFreq':1} ) = true",
            firstRecordId, secondRecordId)) {
      assertThat(resultSet).hasSize(84);
    }
    tx.commit();
  }

  @Test
  public void shouldSearchOnFieldAndMoreLikeThisWithRidOnMultiFieldsIndex() throws Exception {
    session.execute("create index Song.multi on Song (title) FULLTEXT ENGINE LUCENE ");
    var tx = session.begin();
    var firstRecordId = tx.query("SELECT * FROM Song WHERE title = 'UNCLE SAMS BLUES'")
        .findFirstEntity(
            DBRecord::getIdentity);
    var secondRecordId = tx.query("SELECT * FROM Song WHERE title = 'THINGS I USED TO DO'")
        .findFirstEntity(
            DBRecord::getIdentity);
    var thirdRecordId = tx.query("SELECT * FROM Song WHERE title = 'STEALING'")
        .findFirstEntity(
            DBRecord::getIdentity);
    var fourthRecordId = tx.query("SELECT * FROM Song WHERE title = 'SITTING ON TOP OF THE WORLD'")
        .findFirstEntity(
            DBRecord::getIdentity);
    try (var resultSet =
        session.query(
            "SELECT from Song where author ='Hunter' AND SEARCH_More([?, ?, ? ,?],{'minTermFreq':1, 'minDocFreq':1} ) = true",
            firstRecordId, secondRecordId, thirdRecordId, fourthRecordId)) {
      assertThat(resultSet).hasSize(8);
    }
    tx.commit();
  }

  @Test
  public void shouldSearchOnFieldOrMoreLikeThisWithRidOnMultiFieldsIndex() throws Exception {

    session.execute("create index Song.multi on Song (title) FULLTEXT ENGINE LUCENE ");

    var tx = session.begin();
    var firstRecordId = tx.query("SELECT * FROM Song WHERE title = 'UNCLE SAMS BLUES'")
        .findFirstEntity(
            DBRecord::getIdentity);
    var secondRecordId = tx.query("SELECT * FROM Song WHERE title = 'THINGS I USED TO DO'")
        .findFirstEntity(
            DBRecord::getIdentity);
    try (var resultSet =
        tx.query(
            "SELECT from Song where SEARCH_More([?, ?], {'minTermFreq':1, 'minDocFreq':1} ) = true OR author ='Hunter' ",
            firstRecordId, secondRecordId)) {
      var plan = resultSet.getExecutionPlan();
      if (plan != null) {
        System.out.println(plan.prettyPrint(1, 1));
      }
      assertThat(resultSet).hasSize(138);
    }
  }

  @Test
  public void shouldSearchMoreLikeThisWithRidOnMultiFieldsIndexWithMetadata() throws Exception {

    session.execute("create index Song.multi on Song (title,author) FULLTEXT ENGINE LUCENE ");

    var tx = session.begin();
    var firstRecordId = tx.query("SELECT * FROM Song WHERE title = 'UNCLE SAMS BLUES'")
        .findFirstEntity(
            DBRecord::getIdentity);
    var secondRecordId = tx.query("SELECT * FROM Song WHERE title = 'THINGS I USED TO DO'")
        .findFirstEntity(
            DBRecord::getIdentity);

    try (var resultSet =
        tx.query(
            "SELECT from Song where SEARCH_More( [?, ?] "
                + ", {'fields': [ 'title' ], 'minTermFreq':1, 'minDocFreq':1}) = true",
            firstRecordId, secondRecordId)) {

      var plan = resultSet.getExecutionPlan();
      if (plan != null) {
        System.out.println(plan.prettyPrint(1, 1));
      }
      assertThat(resultSet).hasSize(84);
    }
    tx.commit();
  }

  @Test
  public void shouldSearchMoreLikeThisWithInnerQuery() {

    session.execute("create index Song.multi on Song (title,author) FULLTEXT ENGINE LUCENE ");

    try (var resultSet =
        session.query(
            "SELECT from Song  let $a=(SELECT @rid FROM Song WHERE author = 'Hunter')  where"
                + " SEARCH_More( $a, { 'minTermFreq':1, 'minDocFreq':1} ) = true")) {
      assertThat(resultSet).hasSize(229);
    }
  }
}
