package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class IndexTxAwareMultiValueGetEntriesTest extends BaseDBTest {

  private static final String CLASS_NAME = "IndexTxAwareMultiValueGetEntriesTest";
  private static final String FIELD_NAME = "values";
  private static final String INDEX_NAME = "IndexTxAwareMultiValueGetEntriesTestIndex";

  @Parameters(value = "remote")
  public IndexTxAwareMultiValueGetEntriesTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final var cls = session.getMetadata().getSchema().createClass(CLASS_NAME);
    cls.createProperty(FIELD_NAME, PropertyType.INTEGER);
    cls.createIndex(INDEX_NAME, SchemaClass.INDEX_TYPE.NOTUNIQUE, FIELD_NAME);
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    session.getMetadata().getSchema().getClassInternal(CLASS_NAME).truncate();
    super.afterMethod();
  }

  @Test
  public void testPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();
    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    session.commit();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    Assert.assertEquals(resultOne.size(), 3);

    session.begin();

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    Assert.assertEquals(resultTwo.size(), 4);

    session.rollback();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    Assert.assertEquals(resultThree.size(), 3);
  }

  @Test
  public void testRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();
    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    var docOne = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME, 1);

    var docTwo = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME, 1);

    ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME, 2);

    session.commit();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    Assert.assertEquals(resultOne.size(), 3);

    session.begin();

    docOne = session.bindToSession(docOne);
    docTwo = session.bindToSession(docTwo);

    docOne.delete();
    docTwo.delete();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    Assert.assertEquals(resultTwo.size(), 1);

    session.rollback();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    Assert.assertEquals(resultThree.size(), 3);
  }

  @Test
  public void testRemoveOne() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();
    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    var docOne = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    session.commit();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    Assert.assertEquals(resultOne.size(), 3);

    session.begin();

    docOne = session.bindToSession(docOne);
    docOne.delete();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    Assert.assertEquals(resultTwo.size(), 2);

    session.rollback();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    Assert.assertEquals(resultThree.size(), 3);
  }

  @Test
  public void testMultiPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    session.begin();
    final var document = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME,
        1);

    document.setProperty(FIELD_NAME, 0);
    document.setProperty(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    session.commit();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 2);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 2);
  }

  @Test
  public void testPutAfterTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(result.size(), 2);
    session.commit();

    session.begin();
    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(result.size(), 3);
  }

  @Test
  public void testRemoveOneWithinTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    var doc = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    doc.delete();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    session.commit();

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 1);

    result = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testRemoveAllWithinTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    var docOne = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    docOne.delete();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 1);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testPutAfterRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    final var docOne = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME,
        1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    docOne.delete();
    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 2);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 2);
  }

  private static void streamToSet(
      Stream<RawPair<Object, RID>> stream, Set<Identifiable> result) {
    result.clear();
    result.addAll(stream.map((entry) -> entry.second).collect(Collectors.toSet()));
  }
}
