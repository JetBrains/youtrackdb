/*
 *
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

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneInsertUpdateTransactionTest extends LuceneBaseTest {

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();

    var oClass = schema.createClass("City");
    oClass.createProperty("name", PropertyType.STRING);
    //noinspection EmptyTryBlock
    try (var command =
        session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE")) {
    }
  }

  @Test
  public void testInsertUpdateTransactionWithIndex() {

    var schema = session.getMetadata().getSchema();
    session.begin();
    var doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "Rome");

    var idx = schema.getClassInternal("City").getClassIndex(session, "City.name");
    Assert.assertNotNull(idx);
    Collection<?> coll;
    try (var stream = idx.getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(1, coll.size());
    session.rollback();
    try (var stream = idx.getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(0, coll.size());
    session.begin();
    doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "Rome");

    var user = new SecurityUserImpl(session, "test", "test");
    user.save(session);

    session.commit();
    try (var stream = idx.getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(1, coll.size());
  }
}
