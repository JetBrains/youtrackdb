/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 */

package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DuplicateUniqueIndexChangesTxTest extends DbTestBase {

  private Index index;

  public void beforeTest() throws Exception {
    super.beforeTest();
    final var class_ = session.getMetadata().getSchema().createClass("Person");
    var indexName =
        class_
            .createProperty("name", PropertyType.STRING)
            .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
    index = session.getIndex(indexName);
  }

  @Test
  public void testDuplicateNullsOnCreate() {
    session.begin();

    // saved persons will have null name
    final var person1 = session.newInstance("Person");
    final var person2 = session.newInstance("Person");
    final var person3 = session.newInstance("Person");

    // change names to unique
    person1.setProperty("name", "Name1");

    person2.setProperty("name", "Name2");

    person3.setProperty("name", "Name3");

    // should not throw RecordDuplicatedException exception
    session.commit();

    session.begin();
    // verify index state
    Assert.assertNull(fetchDocumentFromIndex(null));
    var activeTx2 = session.getActiveTransaction();
    Assert.assertEquals(activeTx2.<EntityImpl>load(person1), fetchDocumentFromIndex("Name1"));
    var activeTx1 = session.getActiveTransaction();
    Assert.assertEquals(activeTx1.<EntityImpl>load(person2), fetchDocumentFromIndex("Name2"));
    var activeTx = session.getActiveTransaction();
    Assert.assertEquals(activeTx.<EntityImpl>load(person3), fetchDocumentFromIndex("Name3"));
    session.commit();
  }

  private EntityImpl fetchDocumentFromIndex(String o) {
    try (var stream = index.getRids(session, o)) {
      return (EntityImpl) stream.findFirst().map(rid -> {
        var transaction = session.getActiveTransaction();
        return transaction.load(rid);
      }).orElse(null);
    }
  }

  @Test
  public void testDuplicateNullsOnUpdate() {
    session.begin();
    var person1 = session.newInstance("Person");
    person1.setProperty("name", "Name1");
    var person2 = session.newInstance("Person");
    person2.setProperty("name", "Name2");
    var person3 = session.newInstance("Person");
    person3.setProperty("name", "Name3");
    session.commit();

    // verify index state
    session.begin();
    var activeTx5 = session.getActiveTransaction();
    person1 = activeTx5.load(person1);
    var activeTx4 = session.getActiveTransaction();
    person2 = activeTx4.load(person2);
    var activeTx3 = session.getActiveTransaction();
    person3 = activeTx3.load(person3);

    Assert.assertNull(fetchDocumentFromIndex(null));
    Assert.assertEquals(person1, fetchDocumentFromIndex("Name1"));
    Assert.assertEquals(person2, fetchDocumentFromIndex("Name2"));
    Assert.assertEquals(person3, fetchDocumentFromIndex("Name3"));

    // saved persons will have null name
    person1.setProperty("name", null);

    person2.setProperty("name", null);

    person3.setProperty("name", null);

    // change names back to unique swapped
    person1.setProperty("name", "Name2");

    person2.setProperty("name", "Name1");

    person3.setProperty("name", "Name3");

    // and again
    person1.setProperty("name", "Name1");

    person2.setProperty("name", "Name2");

    // should not throw RecordDuplicatedException exception
    session.commit();

    // verify index state
    session.begin();
    Assert.assertNull(fetchDocumentFromIndex(null));
    var activeTx2 = session.getActiveTransaction();
    Assert.assertEquals(activeTx2.<EntityImpl>load(person1), fetchDocumentFromIndex("Name1"));
    var activeTx1 = session.getActiveTransaction();
    Assert.assertEquals(activeTx1.<EntityImpl>load(person2), fetchDocumentFromIndex("Name2"));
    var activeTx = session.getActiveTransaction();
    Assert.assertEquals(activeTx.<EntityImpl>load(person3), fetchDocumentFromIndex("Name3"));
    session.commit();
  }

  @Test
  public void testDuplicateValuesOnCreate() {
    session.begin();

    // saved persons will have same name
    final var person1 = session.newInstance("Person");
    person1.setProperty("name", "same");
    final var person2 = session.newInstance("Person");
    person2.setProperty("name", "same");
    final var person3 = session.newInstance("Person");
    person3.setProperty("name", "same");

    // change names to unique
    person1.setProperty("name", "Name1");

    person2.setProperty("name", "Name2");

    person3.setProperty("name", "Name3");

    // should not throw RecordDuplicatedException exception
    session.commit();

    // verify index state
    session.begin();
    Assert.assertNull(fetchDocumentFromIndex("same"));
    var activeTx2 = session.getActiveTransaction();
    Assert.assertEquals(activeTx2.<EntityImpl>load(person1), fetchDocumentFromIndex("Name1"));
    var activeTx1 = session.getActiveTransaction();
    Assert.assertEquals(activeTx1.<EntityImpl>load(person2), fetchDocumentFromIndex("Name2"));
    var activeTx = session.getActiveTransaction();
    Assert.assertEquals(activeTx.<EntityImpl>load(person3), fetchDocumentFromIndex("Name3"));
    session.commit();
  }

  @Test
  public void testDuplicateValuesOnUpdate() {
    session.begin();
    var person1 = session.newInstance("Person");
    person1.setProperty("name", "Name1");
    var person2 = session.newInstance("Person");
    person2.setProperty("name", "Name2");
    var person3 = session.newInstance("Person");
    person3.setProperty("name", "Name3");
    session.commit();

    // verify index state
    session.begin();
    var activeTx5 = session.getActiveTransaction();
    person1 = activeTx5.load(person1);
    var activeTx4 = session.getActiveTransaction();
    person2 = activeTx4.load(person2);
    var activeTx3 = session.getActiveTransaction();
    person3 = activeTx3.load(person3);

    Assert.assertEquals(person1, fetchDocumentFromIndex("Name1"));
    Assert.assertEquals(person2, fetchDocumentFromIndex("Name2"));
    Assert.assertEquals(person3, fetchDocumentFromIndex("Name3"));

    // saved persons will have same name
    person1.setProperty("name", "same");

    person2.setProperty("name", "same");

    person3.setProperty("name", "same");

    // change names back to unique in reverse order
    person3.setProperty("name", "Name3");

    person2.setProperty("name", "Name2");

    person1.setProperty("name", "Name1");

    // should not throw RecordDuplicatedException exception
    session.commit();

    session.begin();
    // verify index state

    var activeTx2 = session.getActiveTransaction();
    person1 = activeTx2.load(person1);
    var activeTx1 = session.getActiveTransaction();
    person2 = activeTx1.load(person2);
    var activeTx = session.getActiveTransaction();
    person3 = activeTx.load(person3);

    Assert.assertNull(fetchDocumentFromIndex("same"));
    Assert.assertEquals(person1, fetchDocumentFromIndex("Name1"));
    Assert.assertEquals(person2, fetchDocumentFromIndex("Name2"));
    Assert.assertEquals(person3, fetchDocumentFromIndex("Name3"));
    session.commit();
  }

  @Test
  public void testDuplicateValuesOnCreateDelete() {
    session.begin();

    // saved persons will have same name
    final var person1 = session.newInstance("Person");
    person1.setProperty("name", "same");
    final var person2 = session.newInstance("Person");
    person2.setProperty("name", "same");
    final var person3 = session.newInstance("Person");
    person3.setProperty("name", "same");
    final var person4 = session.newInstance("Person");
    person4.setProperty("name", "same");

    person1.delete();
    person2.setProperty("name", "Name2");

    person3.delete();

    // should not throw RecordDuplicatedException exception
    session.commit();

    // verify index state
    session.begin();
    var activeTx1 = session.getActiveTransaction();
    Assert.assertEquals(activeTx1.<EntityImpl>load(person2), fetchDocumentFromIndex("Name2"));
    var activeTx = session.getActiveTransaction();
    Assert.assertEquals(activeTx.<EntityImpl>load(person4), fetchDocumentFromIndex("same"));
    session.commit();
  }

  @Test
  public void testDuplicateValuesOnUpdateDelete() {
    session.begin();
    var person1 = session.newInstance("Person");
    person1.setProperty("name", "Name1");
    var person2 = session.newInstance("Person");
    person2.setProperty("name", "Name2");
    var person3 = session.newInstance("Person");
    person3.setProperty("name", "Name3");
    var person4 = session.newInstance("Person");
    person4.setProperty("name", "Name4");
    session.commit();

    // verify index state
    session.begin();

    var activeTx5 = session.getActiveTransaction();
    person1 = activeTx5.load(person1);
    var activeTx4 = session.getActiveTransaction();
    person2 = activeTx4.load(person2);
    var activeTx3 = session.getActiveTransaction();
    person3 = activeTx3.load(person3);
    var activeTx2 = session.getActiveTransaction();
    person4 = activeTx2.load(person4);

    Assert.assertEquals(person1, fetchDocumentFromIndex("Name1"));
    Assert.assertEquals(person2, fetchDocumentFromIndex("Name2"));
    Assert.assertEquals(person3, fetchDocumentFromIndex("Name3"));
    Assert.assertEquals(person4, fetchDocumentFromIndex("Name4"));

    person1.delete();
    person2.setProperty("name", "same");

    person3.delete();
    person4.setProperty("name", "same");

    person2.setProperty("name", "Name2");

    // should not throw RecordDuplicatedException exception
    session.commit();

    // verify index state
    session.begin();
    var activeTx1 = session.getActiveTransaction();
    person2 = activeTx1.load(person2);
    var activeTx = session.getActiveTransaction();
    person4 = activeTx.load(person4);

    Assert.assertEquals(person2, fetchDocumentFromIndex("Name2"));
    Assert.assertEquals(person4, fetchDocumentFromIndex("same"));

    person2.delete();
    person4.delete();
    session.commit();

    // verify index state
    Assert.assertNull(fetchDocumentFromIndex("Name2"));
    Assert.assertNull(fetchDocumentFromIndex("same"));
  }

  @Test(expected = RecordDuplicatedException.class)
  public void testDuplicateCreateThrows() {
    session.begin();
    var person1 = session.newInstance("Person");
    person1.setProperty("name", "Name1");
    var person2 = session.newInstance("Person");
    var person3 = session.newInstance("Person");
    var person4 = session.newInstance("Person");
    person4.setProperty("name", "Name1");
    //    Assert.assertThrows(RecordDuplicatedException.class, new Assert.ThrowingRunnable() {
    //      @Override
    //      public void run() throws Throwable {
    //        db.commit();
    //      }
    //    });
    session.commit();
  }

  @Test(expected = RecordDuplicatedException.class)
  public void testDuplicateUpdateThrows() {
    session.begin();
    var person1 = session.newInstance("Person");
    person1.setProperty("name", "Name1");
    var person2 = session.newInstance("Person");
    person2.setProperty("name", "Name2");
    var person3 = session.newInstance("Person");
    person3.setProperty("name", "Name3");
    var person4 = session.newInstance("Person");
    person4.setProperty("name", "Name4");
    session.commit();

    // verify index state
    session.begin();

    var activeTx3 = session.getActiveTransaction();
    person1 = activeTx3.load(person1);
    var activeTx2 = session.getActiveTransaction();
    person2 = activeTx2.load(person2);
    var activeTx1 = session.getActiveTransaction();
    person3 = activeTx1.load(person3);
    var activeTx = session.getActiveTransaction();
    person4 = activeTx.load(person4);

    Assert.assertEquals(person1, fetchDocumentFromIndex("Name1"));
    Assert.assertEquals(person2, fetchDocumentFromIndex("Name2"));
    Assert.assertEquals(person3, fetchDocumentFromIndex("Name3"));
    Assert.assertEquals(person4, fetchDocumentFromIndex("Name4"));

    person1.setProperty("name", "Name1");

    person2.setProperty("name", null);

    person3.setProperty("name", "Name1");

    person4.setProperty("name", null);

    session.commit();
  }
}
