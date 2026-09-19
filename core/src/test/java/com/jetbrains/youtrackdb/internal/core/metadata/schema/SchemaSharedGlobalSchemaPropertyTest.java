package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class SchemaSharedGlobalSchemaPropertyTest extends DbTestBase {

  @Test
  public void testGlobalPropertyCreate() {

    Schema schema = session.getMetadata().getSchema();

    schema.createGlobalProperty("testaasd", PropertyType.SHORT, 100);
    var prop = schema.getGlobalPropertyById(100);
    assertEquals(prop.getName(), "testaasd");
    assertEquals(prop.getId(), (Integer) 100);
    assertEquals(prop.getType(), PropertyType.SHORT);
  }

  /**
   * A missing global property triggers reload and a retry. The retry-owned snapshot pin must be
   * released so a later schema operation in the same session can capture a fresh snapshot.
   */
  @Test
  public void missingGlobalPropertyRetryReleasesItsSnapshotPin() {
    var schema = session.getMetadata().getSchema();
    schema.createGlobalProperty("knownBeforeRetry", PropertyType.STRING, 300);
    var entity = new EntityImpl(new RecordId(-1, -1), session);

    assertEquals("knownBeforeRetry", entity.getGlobalPropertyById(300).getName());
    assertEquals("the retry must begin without an incoming pin", 0,
        session.getMetadata().getThreadLocalSchemaSnapshotPinCount());

    schema.createGlobalProperty("createdAfterEntitySnapshot", PropertyType.STRING, 301);

    assertEquals("createdAfterEntitySnapshot", entity.getGlobalPropertyById(301).getName());
    assertEquals("the locally acquired retry pin must be released", 0,
        session.getMetadata().getThreadLocalSchemaSnapshotPinCount());

    schema.createGlobalProperty("visibleAfterRetry", PropertyType.STRING, 302);
    var laterEntity = new EntityImpl(new RecordId(-1, -1), session);
    assertEquals("visibleAfterRetry", laterEntity.getGlobalPropertyById(302).getName());

    var indexedClass = schema.createClass("IndexedAfterMissingPropertyRetry");
    indexedClass.createProperty("key", PropertyType.STRING);
    indexedClass.createIndex("IndexedAfterMissingPropertyRetry.key",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");
    assertTrue("the later index must be visible after the retry pin is released",
        session.getMetadata().getImmutableSchemaSnapshot()
            .indexExists("IndexedAfterMissingPropertyRetry.key"));

    var indexedRid = session.computeInTx(tx -> {
      var indexedEntity = (EntityImpl) session.newEntity("IndexedAfterMissingPropertyRetry");
      indexedEntity.setProperty("key", "indexed-value");
      return indexedEntity.getIdentity();
    });
    session.begin();
    try {
      Index index = session.getIndex("IndexedAfterMissingPropertyRetry.key");
      try (var indexedRids = index.getRids(session, "indexed-value")) {
        assertTrue("the created index must contain the written record identity",
            indexedRids.anyMatch(indexedRid::equals));
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * An already pinned caller owns its pin. Reload rejects the retry while that pin is held, and
   * the exceptional path must not increase or consume the caller's depth.
   */
  @Test
  public void missingGlobalPropertyRetryPreservesIncomingSnapshotPin() {
    var schema = session.getMetadata().getSchema();
    schema.createGlobalProperty("knownPinned", PropertyType.STRING, 303);
    var entity = new EntityImpl(new RecordId(-1, -1), session);
    assertEquals("knownPinned", entity.getGlobalPropertyById(303).getName());

    session.getMetadata().makeThreadLocalSchemaSnapshot();
    try {
      int incomingPinCount = session.getMetadata().getThreadLocalSchemaSnapshotPinCount();
      assertThrows(IllegalStateException.class, () -> entity.getGlobalPropertyById(304));
      assertEquals("reload failure must preserve the caller-owned pin depth", incomingPinCount,
          session.getMetadata().getThreadLocalSchemaSnapshotPinCount());
    } finally {
      session.getMetadata().clearThreadLocalSchemaSnapshot();
    }
    assertEquals(0, session.getMetadata().getThreadLocalSchemaSnapshotPinCount());
  }

  /**
   * A mocked caller starts with one owned pin and permits reload. The retry acquires another pin,
   * then snapshot retrieval fails. Its finally block must release only the retry-owned pin.
   */
  @Test
  public void snapshotFailureAfterRetryAcquisitionPreservesIncomingPin() {
    var mockedSession = mock(DatabaseSessionEmbedded.class);
    var metadata = mock(MetadataDefault.class);
    var initialSnapshot = mock(ImmutableSchema.class);
    var pinDepth = new AtomicInteger(1);
    var snapshotFailure = new IllegalStateException("snapshot retrieval failed");

    when(mockedSession.assertIfNotActive()).thenReturn(true);
    when(mockedSession.getMetadata()).thenReturn(metadata);
    when(mockedSession.isClosed()).thenReturn(false);
    when(metadata.getImmutableSchemaSnapshot())
        .thenReturn(initialSnapshot)
        .thenThrow(snapshotFailure);
    when(initialSnapshot.getGlobalPropertyById(305)).thenReturn(null);
    doAnswer(invocation -> {
      pinDepth.incrementAndGet();
      return null;
    }).when(metadata).makeThreadLocalSchemaSnapshot();
    doAnswer(invocation -> {
      pinDepth.decrementAndGet();
      return null;
    }).when(metadata).clearThreadLocalSchemaSnapshot();

    var entity = new EntityImpl(new RecordId(-1, -1), mockedSession);
    assertEquals("the mocked caller must start with one owned pin", 1, pinDepth.get());
    assertThrows(IllegalStateException.class, () -> entity.getGlobalPropertyById(305));
    assertEquals("post-acquisition failure must release only the retry-owned pin",
        1, pinDepth.get());
  }

  @Test
  public void testGlobalPropertyCreateDoubleSame() {

    Schema schema = session.getMetadata().getSchema();

    schema.createGlobalProperty("test", PropertyType.SHORT, 200);
    schema.createGlobalProperty("test", PropertyType.SHORT, 200);
  }

  @Test(expected = SchemaException.class)
  public void testGlobalPropertyCreateDouble() {

    Schema schema = session.getMetadata().getSchema();

    schema.createGlobalProperty("test", PropertyType.SHORT, 201);
    schema.createGlobalProperty("test1", PropertyType.SHORT, 201);
  }
}
