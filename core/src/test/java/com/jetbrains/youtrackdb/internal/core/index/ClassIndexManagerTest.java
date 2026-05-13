package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ClassIndexManager} — the utility that hooks into record create/update/delete
 * events and synchronises the per-class indexes accordingly.
 *
 * <p>All paths are exercised via the public database API (insert/update/delete records through the
 * session), which internally invokes ClassIndexManager. Tests then query the index directly to
 * verify the expected state.
 *
 * <p>The class under test uses a UNIQUE index backed by {@code IndexUnique} (extends
 * {@code IndexOneValue}). {@code IndexOneValue.get()} returns the matching {@code RID} when a
 * key is present or {@code null} when it is absent, so presence is checked via {@code != null}
 * rather than {@code .iterator().hasNext()}.
 */
public class ClassIndexManagerTest extends DbTestBase {

  private static final String CLS = "CimTestCls";
  private static final String IDX = CLS + ".name";

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();
    // Schema setup must happen outside an active transaction.
    var cls = session.getMetadata().getSchema().createClass(CLS);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex(IDX, SchemaClass.INDEX_TYPE.UNIQUE, "name");
  }

  // -----------------------------------------------------------------------
  //  checkIndexesAfterCreate (called indirectly on record insert + commit)
  // -----------------------------------------------------------------------

  /**
   * Inserting a record with an indexed property and committing must add an index entry.
   * Exercises ClassIndexManager.checkIndexesAfterCreate → addIndexesEntries.
   */
  @Test
  public void checkIndexesAfterCreate_indexedPropertySet_entryAddedToIndex() {
    session.begin();
    var e = session.newEntity(CLS);
    e.setProperty("name", "Alice");
    session.commit();

    session.begin();
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    // IndexOneValue.get returns the RID when found, null otherwise.
    assertNotNull("Index must contain the newly inserted key",
        idx.get(session, "Alice"));
    session.rollback();
  }

  /**
   * Inserting a record whose indexed property is left null must not throw; the null-values
   * guard in ClassIndexManager skips the put when nullValuesIgnored is true (default).
   */
  @Test
  public void checkIndexesAfterCreate_nullProperty_doesNotThrow() {
    // No 'name' set → null key → default nullValuesIgnored=true skips the index put.
    session.begin();
    session.newEntity(CLS);
    session.commit(); // must complete without exception
  }

  // -----------------------------------------------------------------------
  //  checkIndexesAfterUpdate (called indirectly on record update + commit)
  // -----------------------------------------------------------------------

  /**
   * Updating an indexed property must replace the old key with the new key in the index.
   * Exercises ClassIndexManager.checkIndexesAfterUpdate → processIndexOnUpdate →
   * processSingleIndexUpdate → addPut + addRemove paths.
   */
  @Test
  public void checkIndexesAfterUpdate_indexedPropertyChanged_oldKeyRemovedNewKeyAdded() {
    session.begin();
    var e = session.newEntity(CLS);
    e.setProperty("name", "Before");
    session.commit();

    session.begin();
    session.execute("UPDATE " + CLS + " SET name = 'After' WHERE name = 'Before'").close();
    session.commit();

    session.begin();
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    assertNull("Old key 'Before' must be removed from the index after update",
        idx.get(session, "Before"));
    assertNotNull("New key 'After' must be present in the index after update",
        idx.get(session, "After"));
    session.rollback();
  }

  // -----------------------------------------------------------------------
  //  checkIndexesAfterDelete (called indirectly on record delete + commit)
  // -----------------------------------------------------------------------

  /**
   * Deleting a record that has an indexed property must remove that key from the index.
   * Exercises ClassIndexManager.checkIndexesAfterDelete → processIndexOnDelete.
   */
  @Test
  public void checkIndexesAfterDelete_indexedRecord_entryRemovedFromIndex() {
    session.begin();
    var e = session.newEntity(CLS);
    e.setProperty("name", "ToDelete");
    session.commit();

    session.begin();
    session.execute("DELETE FROM " + CLS + " WHERE name = 'ToDelete'").close();
    session.commit();

    session.begin();
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    assertNull("Deleted record's key must not remain in the index",
        idx.get(session, "ToDelete"));
    session.rollback();
  }

  // -----------------------------------------------------------------------
  //  reIndex
  // -----------------------------------------------------------------------

  /**
   * reIndex must re-add the entity's current indexed value to the given index. This is used when
   * rebuilding an index from existing records; the test verifies that calling reIndex inside a
   * transaction results in a queryable index entry after commit.
   */
  @Test
  public void reIndex_existingEntity_indexEntryRestoredAfterCommit() {
    session.begin();
    var e = (EntityImpl) session.newEntity(CLS);
    e.setProperty("name", "ReIndexMe");
    session.commit();

    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);

    // Remove the entry manually inside a TX, then call reIndex to restore it, then commit.
    session.begin();
    var tx = session.getActiveTransaction(); // FrontendTransactionImpl
    e = tx.load(e); // reload to get the TX-attached instance
    idx.remove(tx, "ReIndexMe");
    // After removal, reIndex puts it back via ClassIndexManager.addIndexEntry.
    ClassIndexManager.reIndex(tx, e, idx);
    session.commit();

    session.begin();
    assertNotNull("reIndex must restore the index entry for the entity",
        idx.get(session, "ReIndexMe"));
    session.rollback();
  }

  // -----------------------------------------------------------------------
  //  addIndexesEntries (public static helper)
  // -----------------------------------------------------------------------

  /**
   * addIndexesEntries with a single-element list must add the entity's indexed property to the
   * given index within the current transaction, exercising the collection-iteration path.
   */
  @Test
  public void addIndexesEntries_singleEntity_entryAddedInTx() {
    session.begin();
    var e = (EntityImpl) session.newEntity(CLS);
    e.setProperty("name", "BatchAdd");
    session.commit();

    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);

    session.begin();
    var tx = session.getActiveTransaction();
    e = tx.load(e);
    idx.remove(tx, "BatchAdd");
    ClassIndexManager.addIndexesEntries(tx, e, Collections.singletonList(idx));
    session.commit();

    session.begin();
    assertNotNull("addIndexesEntries must have added the key back to the index",
        idx.get(session, "BatchAdd"));
    session.rollback();
  }
}
