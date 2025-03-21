package com.jetbrains.youtrack.db.internal.core.metadata.sequence;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SequenceLimitReachedException;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.SequenceException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class DBSequenceTest {

  private static YouTrackDB youTrackDB;

  private DatabaseSessionInternal db;
  private SequenceLibrary sequences;

  @BeforeClass
  public static void beforeClass() {
    var builder = YouTrackDBConfig.builder();
    youTrackDB = YourTracks.embedded("./target/databases/" + DBSequenceTest.class.getSimpleName(),
        builder.build());
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.close();
  }

  @Before
  public void setUp() throws Exception {
    youTrackDB.create(
        DBSequenceTest.class.getSimpleName(), DatabaseType.MEMORY, "admin", "admin", "admin");
    db =
        (DatabaseSessionInternal)
            youTrackDB.open(DBSequenceTest.class.getSimpleName(), "admin", "admin");
    sequences = db.getMetadata().getSequenceLibrary();
  }

  @After
  public void after() {
    youTrackDB.drop(DBSequenceTest.class.getSimpleName());
    db.close();
  }

  @Test
  public void shouldCreateSeqWithGivenAttribute() {
    try {
      sequences.createSequence(
          "mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, new DBSequence.CreateParams().setDefaults());
    } catch (DatabaseException exc) {
      Assert.fail("Can not create sequence");
    }

    assertThat(sequences.getSequenceCount()).isEqualTo(1);
    assertThat(sequences.getSequenceNames()).contains("MYSEQ");

    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.getSequenceType()).isEqualTo(DBSequence.SEQUENCE_TYPE.ORDERED);
    assertThat(myseq.getMaxRetry()).isEqualTo(1_000);
  }

  @Test
  public void shouldGivesValuesOrdered() {
    sequences.createSequence(
        "mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, new DBSequence.CreateParams().setDefaults());
    var myseq = sequences.getSequence("MYSEQ");

    assertThat(myseq.current(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(1);
    assertThat(myseq.current(db)).isEqualTo(1);
    assertThat(myseq.next(db)).isEqualTo(2);
    assertThat(myseq.current(db)).isEqualTo(2);
  }

  @Test
  public void shouldGivesValuesWithIncrement() {
    var params = new DBSequence.CreateParams().setDefaults().setIncrement(30);
    assertThat(params.increment).isEqualTo(30);

    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    var myseq = sequences.getSequence("MYSEQ");

    assertThat(myseq.current(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(30);
    assertThat(myseq.current(db)).isEqualTo(30);
    assertThat(myseq.next(db)).isEqualTo(60);
  }

  @Test
  public void shouldCache() {
    var params =
        new DBSequence.CreateParams().setDefaults().setCacheSize(100).setIncrement(30);
    assertThat(params.increment).isEqualTo(30);

    db.begin();
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq).isInstanceOf(SequenceCached.class);
    db.commit();

    db.begin();
    assertThat(myseq.current(db)).isEqualTo(0);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(30);
    db.commit();

    db.begin();
    assertThat(myseq.current(db)).isEqualTo(30);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(60);
    db.commit();

    db.begin();
    assertThat(myseq.current(db)).isEqualTo(60);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(90);
    db.commit();

    db.begin();
    assertThat(myseq.current(db)).isEqualTo(90);
    assertThat(myseq.next(db)).isEqualTo(120);
    db.commit();

    db.begin();
    assertThat(myseq.current(db)).isEqualTo(120);
    db.commit();
  }

  @Test(expected = SequenceException.class)
  public void shouldThrowExceptionOnDuplicateSeqDefinition() {
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, null);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, null);
  }

  @Test
  public void shouldDropSequence() {
    db.begin();
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, null);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();

    db.begin();
    assertThat(sequences.getSequenceCount()).isEqualTo(0);
    db.commit();

    db.begin();
    // IDEMPOTENT
    sequences.dropSequence("MYSEQ");
    db.commit();

    db.begin();
    assertThat(sequences.getSequenceCount()).isEqualTo(0);
    db.commit();
  }

  @Test
  public void testCreateSequenceWithoutExplicitDefaults() {
    // issue #6484
    var params = new DBSequence.CreateParams().setStart(0L);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(1);
  }

  @Test
  public void shouldSequenceMTNoTx() throws Exception {
    var params = new DBSequence.CreateParams().setStart(0L);
    var mtSeq = sequences.createSequence("mtSeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    mtSeq.setMaxRetry(1000);
    final var count = 1000;
    final var threads = 2;
    final var latch = new CountDownLatch(count);
    final var errors = new AtomicInteger(0);
    final var success = new AtomicInteger(0);
    var service = Executors.newFixedThreadPool(threads);

    for (var i = 0; i < threads; i++) {
      service.execute(
          () -> {
            var databaseDocument =
                (DatabaseSessionInternal)
                    youTrackDB.open(DBSequenceTest.class.getSimpleName(), "admin", "admin");
            var mtSeq1 =
                databaseDocument.getMetadata().getSequenceLibrary().getSequence("mtSeq");

            for (var j = 0; j < count / threads; j++) {
              try {
                mtSeq1.next(databaseDocument);
                success.incrementAndGet();
              } catch (Exception e) {
                e.printStackTrace();
                errors.incrementAndGet();
              }
              latch.countDown();
            }
          });
    }
    latch.await();

    assertThat(errors.get()).isEqualTo(0);
    assertThat(success.get()).isEqualTo(1000);
    //    assertThat(mtSeq.getDocument().getVersion()).isEqualTo(1001);
    assertThat(mtSeq.current(db)).isEqualTo(1000);
  }

  @Test
  public void shouldSequenceMTTx() throws Exception {
    var params = new DBSequence.CreateParams().setStart(0L);
    var mtSeq = sequences.createSequence("mtSeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    final var count = 1000;
    final var threads = 2;
    final var latch = new CountDownLatch(count);
    final var errors = new AtomicInteger(0);
    final var success = new AtomicInteger(0);
    try (var service = Executors.newFixedThreadPool(threads)) {

      for (var i = 0; i < threads; i++) {
        service.execute(
            () -> {
              var databaseDocument =
                  (DatabaseSessionInternal)
                      youTrackDB.open(DBSequenceTest.class.getSimpleName(), "admin", "admin");
              var mtSeq1 =
                  databaseDocument.getMetadata().getSequenceLibrary().getSequence("mtSeq");

              for (var j = 0; j < count / threads; j++) {
                for (var retry = 0; retry < 10; ++retry) {
                  try {

                    databaseDocument.begin();
                    mtSeq1.next(databaseDocument);
                    databaseDocument.commit();
                    success.incrementAndGet();
                    break;

                  } catch (ConcurrentModificationException e) {
                    if (retry >= 10) {
                      e.printStackTrace();
                      errors.incrementAndGet();
                      break;
                    }

                    // RETRY
                    try {
                      Thread.sleep(10 + new Random().nextInt(100));
                    } catch (InterruptedException e1) {
                    }
                    continue;
                  } catch (Exception e) {
                    e.printStackTrace();
                    errors.incrementAndGet();
                    throw e;
                  }
                }
                latch.countDown();
              }
            });
      }
      latch.await();

      assertThat(errors.get()).isEqualTo(0);
      assertThat(success.get()).isEqualTo(1000);
      assertThat(mtSeq.current(db)).isEqualTo(1000);
    }
  }

  @Test
  public void shouldSequenceWithDefaultValueNoTx() {

    db.execute("CREATE CLASS Person EXTENDS V");
    db.execute("CREATE SEQUENCE personIdSequence TYPE ORDERED;");
    db.execute(
        "CREATE PROPERTY Person.id LONG (MANDATORY TRUE, default"
            + " \"sequence('personIdSequence').next()\");");
    db.execute("CREATE INDEX Person.id ON Person (id) UNIQUE");

    db.executeInTx(
        transaction -> {
          for (var i = 0; i < 10; i++) {
            var person = db.newVertex("Person");
            person.setProperty("name", "Foo" + i);
          }
        });

    assertThat(db.countClass("Person")).isEqualTo(10);
  }

  @Test
  public void shouldSequenceWithDefaultValueTx() {

    db.execute("CREATE CLASS Person EXTENDS V");
    db.execute("CREATE SEQUENCE personIdSequence TYPE ORDERED;");
    db.execute(
        "CREATE PROPERTY Person.id LONG (MANDATORY TRUE, default"
            + " \"sequence('personIdSequence').next()\");");
    db.execute("CREATE INDEX Person.id ON Person (id) UNIQUE");

    db.begin();

    for (var i = 0; i < 10; i++) {
      var person = db.newVertex("Person");
      person.setProperty("name", "Foo" + i);
    }

    db.commit();

    assertThat(db.countClass("Person")).isEqualTo(10);
  }

  @Test
  public void testCachedSequeneceUpperLimit() throws Exception {
    // issue #6484
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(10)
            .setRecyclable(true)
            .setLimitValue(30L);
    db.begin();
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(10);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(20);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(30);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(0);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testNegativeCachedSequeneceDownerLimit() {
    // issue #6484
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(30L)
            .setIncrement(10)
            .setLimitValue(0L)
            .setRecyclable(true)
            .setOrderType(SequenceOrderType.ORDER_NEGATIVE);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(30);
    assertThat(myseq.next(db)).isEqualTo(20);
    assertThat(myseq.next(db)).isEqualTo(10);
    assertThat(myseq.next(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(30);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testCachedSequeneceOverCache() throws Exception {
    // issue #6484
    db.begin();
    var params =
        new DBSequence.CreateParams().setStart(0L).setIncrement(1).setCacheSize(3);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(1);
    assertThat(myseq.next(db)).isEqualTo(2);
    assertThat(myseq.next(db)).isEqualTo(3);
    assertThat(myseq.next(db)).isEqualTo(4);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testNegativeCachedSequeneceOverCache() throws Exception {
    // issue #6484
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(6L)
            .setIncrement(1)
            .setCacheSize(3)
            .setOrderType(SequenceOrderType.ORDER_NEGATIVE);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(6);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(5);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(4);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(3);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(2);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(1);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(0);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testOrderedSequeneceUpperLimit() throws Exception {
    // issue #6484
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(10)
            .setRecyclable(true)
            .setLimitValue(30L);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(10);
    assertThat(myseq.next(db)).isEqualTo(20);
    assertThat(myseq.next(db)).isEqualTo(30);
    assertThat(myseq.next(db)).isEqualTo(0);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testNegativeOrderedSequenece() throws Exception {
    // issue #6484
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(6L)
            .setIncrement(1)
            .setOrderType(SequenceOrderType.ORDER_NEGATIVE);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(6);
    assertThat(myseq.next(db)).isEqualTo(5);
    assertThat(myseq.next(db)).isEqualTo(4);
    assertThat(myseq.next(db)).isEqualTo(3);
    assertThat(myseq.next(db)).isEqualTo(2);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testNegativeOrderedSequeneceDownerLimit() throws Exception {
    // issue #6484
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(30L)
            .setIncrement(10)
            .setLimitValue(0L)
            .setRecyclable(true)
            .setOrderType(SequenceOrderType.ORDER_NEGATIVE);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(30);
    assertThat(myseq.next(db)).isEqualTo(20);
    assertThat(myseq.next(db)).isEqualTo(10);
    assertThat(myseq.next(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(30);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testNonRecyclableCachedSequeneceLimitReach() throws Exception {
    // issue #6484
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(10)
            .setLimitValue(30L)
            .setOrderType(SequenceOrderType.ORDER_POSITIVE)
            .setRecyclable(false);
    db.begin();
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    db.commit();

    db.begin();
    assertThat(myseq.current(db)).isEqualTo(0);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(10);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(20);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(30);
    db.commit();

    db.executeInTx(
        transaction -> {
          Byte exceptionsCought = 0;
          try {
            myseq.next(db);
          } catch (SequenceLimitReachedException exc) {
            exceptionsCought++;
          }
          assertThat(exceptionsCought).isEqualTo((byte) 1);

          sequences.dropSequence("MYSEQ");
        });
  }

  @Test
  public void testNonRecyclableOrderedSequeneceLimitReach() throws Exception {
    // issue #6484
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(10)
            .setLimitValue(30L)
            .setOrderType(SequenceOrderType.ORDER_POSITIVE)
            .setRecyclable(false);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(10);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(20);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(30);
    db.commit();

    db.executeInTx(
        transaction -> {
          Byte exceptionsCought = 0;
          try {
            myseq.next(db);
          } catch (SequenceLimitReachedException exc) {
            exceptionsCought++;
          }
          assertThat(exceptionsCought).isEqualTo((byte) 1);

          sequences.dropSequence("MYSEQ");
        });
  }

  @Test
  public void testReinitSequence() {
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(1)
            .setLimitValue(5L)
            .setCacheSize(3)
            .setOrderType(SequenceOrderType.ORDER_POSITIVE);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(1);
    assertThat(myseq.next(db)).isEqualTo(2);
    assertThat(myseq.next(db)).isEqualTo(3);
    db.commit();

    db.begin();
    var transaction1 = db.getActiveTransaction();
    var newSeq = new SequenceCached(transaction1.load(myseq.entityRid));
    var val = newSeq.current(db);
    assertThat(val).isEqualTo(5);
    db.commit();

    db.executeInTx(
        transaction -> {
          Byte exceptionsCought = 0;
          try {
            newSeq.next(db);
          } catch (SequenceLimitReachedException exc) {
            exceptionsCought++;
          }
          assertThat(exceptionsCought).isEqualTo((byte) 1);
        });

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testTurnLimitOffCached() {
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(1)
            .setLimitValue(3L)
            .setOrderType(SequenceOrderType.ORDER_POSITIVE);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(1);
    assertThat(myseq.next(db)).isEqualTo(2);
    assertThat(myseq.next(db)).isEqualTo(3);
    db.commit();

    db.executeInTx(
        transaction -> {
          Byte exceptionsCought = 0;
          try {
            myseq.next(db);
          } catch (SequenceLimitReachedException exc) {
            exceptionsCought++;
          }

          assertThat(exceptionsCought).isEqualTo((byte) 1);
        });

    db.begin();
    params = new DBSequence.CreateParams().resetNull().setTurnLimitOff(true);
    myseq.updateParams(db, params);
    db.commit();

    db.begin();
    // there is reset after update params, so go from begining
    assertThat(myseq.next(db)).isEqualTo(4);
    assertThat(myseq.next(db)).isEqualTo(5);
    assertThat(myseq.next(db)).isEqualTo(6);
    assertThat(myseq.next(db)).isEqualTo(7);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testTurnLimitOnCached() throws Exception {
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(1)
            .setOrderType(SequenceOrderType.ORDER_POSITIVE);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(1);
    assertThat(myseq.next(db)).isEqualTo(2);
    assertThat(myseq.next(db)).isEqualTo(3);
    db.commit();

    db.begin();
    params = new DBSequence.CreateParams().resetNull().setLimitValue(3L);
    myseq.updateParams(db, params);
    db.commit();

    db.executeInTx(
        transaction -> {
          Byte exceptionsCought = 0;
          try {
            myseq.next(db);
          } catch (SequenceLimitReachedException exc) {
            exceptionsCought++;
          }

          assertThat(exceptionsCought).isEqualTo((byte) 1);
        });

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testTurnLimitOffOrdered() throws Exception {
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(1)
            .setLimitValue(3L)
            .setOrderType(SequenceOrderType.ORDER_POSITIVE);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(1);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(2);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(3);
    db.commit();

    db.executeInTx(
        transaction -> {
          Byte exceptionsCought = 0;
          try {
            myseq.next(db);
          } catch (SequenceLimitReachedException exc) {
            exceptionsCought++;
          }
          assertThat(exceptionsCought).isEqualTo((byte) 1);
        });

    db.begin();
    params = new DBSequence.CreateParams().resetNull().setTurnLimitOff(true);
    myseq.updateParams(db, params);
    db.commit();

    db.begin();
    // there is reset after update params, so go from begining
    assertThat(myseq.next(db)).isEqualTo(4);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(5);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(6);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(7);
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testTurnLimitOnOrdered() throws Exception {
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(1)
            .setOrderType(SequenceOrderType.ORDER_POSITIVE);
    db.begin();
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.current(db)).isEqualTo(0);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(1);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(2);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(3);
    db.commit();

    db.begin();
    params = new DBSequence.CreateParams().resetNull().setLimitValue(3L);
    myseq.updateParams(db, params);
    db.commit();

    db.executeInTx(
        transaction -> {
          Byte exceptionsCought = 0;
          try {
            myseq.next(db);
          } catch (SequenceLimitReachedException exc) {
            exceptionsCought++;
          }
          assertThat(exceptionsCought).isEqualTo((byte) 1);
        });

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }

  @Test
  public void testAfterNextCache() throws Exception {
    db.begin();
    var params =
        new DBSequence.CreateParams()
            .setStart(0L)
            .setIncrement(1)
            .setLimitValue(10L)
            .setOrderType(SequenceOrderType.ORDER_POSITIVE);
    sequences.createSequence("mySeq", DBSequence.SEQUENCE_TYPE.CACHED, params);
    db.commit();

    db.begin();
    var myseq = sequences.getSequence("MYSEQ");
    assertThat(myseq.next(db)).isEqualTo(1);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(2);
    db.commit();

    db.begin();
    params = new DBSequence.CreateParams().resetNull().setRecyclable(true).setCacheSize(3);
    myseq.updateParams(db, params);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(3);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(4);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(5);
    assertThat(myseq.next(db)).isEqualTo(6);
    assertThat(myseq.next(db)).isEqualTo(7);
    assertThat(myseq.next(db)).isEqualTo(8);
    db.commit();

    db.begin();
    params = new DBSequence.CreateParams().resetNull().setLimitValue(11L);
    myseq.updateParams(db, params);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(9);
    assertThat(myseq.next(db)).isEqualTo(10);
    assertThat(myseq.next(db)).isEqualTo(11);
    assertThat(myseq.next(db)).isEqualTo(0);
    assertThat(myseq.next(db)).isEqualTo(1);
    db.commit();

    db.begin();
    params = new DBSequence.CreateParams().resetNull().setLimitValue(12L);
    myseq.updateParams(db, params);
    db.commit();

    db.begin();
    assertThat(myseq.next(db)).isEqualTo(2);
    assertThat(myseq.next(db)).isEqualTo(3);
    db.commit();

    db.begin();
    sequences.dropSequence("MYSEQ");
    db.commit();
  }
}
