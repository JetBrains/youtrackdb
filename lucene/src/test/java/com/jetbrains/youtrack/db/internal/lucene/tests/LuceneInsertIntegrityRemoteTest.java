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

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
// Renable when solved killing issue
public class LuceneInsertIntegrityRemoteTest extends LuceneBaseTest {

  @Before
  public void init() {

    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("City");

    oClass.createProperty("name", PropertyType.STRING);
    //noinspection deprecation
    session.command("create index City.name on City (name) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  @Ignore
  public void testInsertUpdateWithIndex() throws Exception {
    session.getMetadata().reload();
    var schema = session.getMetadata().getSchema();

    var doc = ((EntityImpl) session.newEntity("City"));
    doc.field("name", "Rome");

    session.begin();
    session.commit();
    var idx = schema.getClassInternal("City").getClassIndex(session, "City.name");

    Collection<?> coll;
    try (var stream = idx.getInternal().getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(1, coll.size());

    doc = session.load((RID) coll.iterator().next());
    Assert.assertEquals("Rome", doc.field("name"));

    session.begin();
    doc.field("name", "London");
    session.commit();

    try (var stream = idx.getInternal().getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(0, coll.size());
    try (var stream = idx.getInternal().getRids(session, "London")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(1, coll.size());

    doc = session.load((RID) coll.iterator().next());
    Assert.assertEquals("London", doc.field("name"));

    session.begin();
    doc.field("name", "Berlin");
    session.commit();

    doc = session.load(doc.getIdentity());
    Assert.assertEquals("Berlin", doc.field("name"));

    try (var stream = idx.getInternal().getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(0, coll.size());
    try (var stream = idx.getInternal().getRids(session, "London")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(0, coll.size());
    try (var stream = idx.getInternal().getRids(session, "Berlin")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(1, idx.getInternal().size(session));
    Assert.assertEquals(1, coll.size());

    Thread.sleep(1000);

    doc = session.load(doc.getIdentity());

    Assert.assertEquals("Berlin", doc.field("name"));

    schema = session.getMetadata().getSchema();
    idx = schema.getClassInternal("City").getClassIndex(session, "City.name");

    Assert.assertEquals(1, idx.getInternal().size(session));
    try (var stream = idx.getInternal().getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(0, coll.size());
    try (var stream = idx.getInternal().getRids(session, "London")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(0, coll.size());
    try (var stream = idx.getInternal().getRids(session, "Berlin")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(1, coll.size());
  }
}
