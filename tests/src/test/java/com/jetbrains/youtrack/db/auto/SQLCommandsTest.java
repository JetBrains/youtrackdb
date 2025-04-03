/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrack.db.internal.core.storage.collection.CollectionPositionMap;
import com.jetbrains.youtrack.db.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrack.db.internal.core.storage.disk.LocalPaginatedStorage;
import java.io.File;
import java.util.Locale;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class SQLCommandsTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLCommandsTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  public void createProperty() {
    Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("account")) {
      schema.createClass("account");
    }

    session.execute("create property account.timesheet string").close();

    Assert.assertEquals(
        session.getMetadata().getSchema().getClass("account").getProperty("timesheet")
            .getType(),
        PropertyType.STRING);
  }

  @Test(dependsOnMethods = "createProperty")
  public void createLinkedClassProperty() {
    session.execute("create property account.knows embeddedmap account").close();

    Assert.assertEquals(
        session.getMetadata().getSchema().getClass("account").getProperty("knows")
            .getType(),
        PropertyType.EMBEDDEDMAP);
    Assert.assertEquals(
        session
            .getMetadata()
            .getSchema()
            .getClass("account")
            .getProperty("knows")
            .getLinkedClass(),
        session.getMetadata().getSchema().getClass("account"));
  }

  @Test(dependsOnMethods = "createLinkedClassProperty")
  public void createLinkedTypeProperty() {
    session.execute("create property account.tags embeddedlist string").close();

    Assert.assertEquals(
        session.getMetadata().getSchema().getClass("account").getProperty("tags")
            .getType(),
        PropertyType.EMBEDDEDLIST);
    Assert.assertEquals(
        session.getMetadata().getSchema().getClass("account").getProperty("tags")
            .getLinkedType(),
        PropertyType.STRING);
  }

  @Test(dependsOnMethods = "createLinkedTypeProperty")
  public void removeProperty() {
    session.execute("drop property account.timesheet").close();
    session.execute("drop property account.tags").close();

    Assert.assertFalse(
        session.getMetadata().getSchema().getClass("account").existsProperty("timesheet"));
    Assert.assertFalse(
        session.getMetadata().getSchema().getClass("account").existsProperty("tags"));
  }

  @Test(dependsOnMethods = "removeProperty")
  public void testSQLScript() {
    var cmd = "";
    cmd += "select from ouser limit 1;begin;";
    cmd += "let a = create vertex set script = true\n";
    cmd += "let b = select from v limit 1;";
    cmd += "create edge from $a to $b;";
    cmd += "commit;";
    cmd += "return $a;";

    var result = session.runScript("sql", cmd).findFirst(Result::asEntity);

    var transaction1 = session.getActiveTransaction();
    Assert.assertTrue(transaction1.load(result) instanceof EntityImpl);
    var transaction = session.getActiveTransaction();
    EntityImpl identifiable = transaction.load(result);
    var activeTx = session.getActiveTransaction();
    EntityImpl entity = activeTx.load(identifiable);
    Assert.assertTrue(
        entity.getProperty("script"));
  }

  public void testCollectionRename() {
    if (session.getURL().startsWith("memory:")) {
      return;
    }

    var names = session.getCollectionNames();
    Assert.assertFalse(names.contains("testCollectionRename".toLowerCase(Locale.ENGLISH)));

    session.execute("create collection testCollectionRename").close();

    names = session.getCollectionNames();
    Assert.assertTrue(names.contains("testCollectionRename".toLowerCase(Locale.ENGLISH)));

    session.execute("alter collection testCollectionRename name testCollectionRename42").close();
    names = session.getCollectionNames();

    Assert.assertTrue(names.contains("testCollectionRename42".toLowerCase(Locale.ENGLISH)));
    Assert.assertFalse(names.contains("testCollectionRename".toLowerCase(Locale.ENGLISH)));

    if (!remoteDB && databaseType.equals(DatabaseType.DISK)) {
      var storagePath = session.getStorage().getConfiguration().getDirectory();

      final var wowCache =
          (WOWCache) ((LocalPaginatedStorage) session.getStorage()).getWriteCache();

      var dataFile =
          new File(
              storagePath,
              wowCache.nativeFileNameById(
                  wowCache.fileIdByName("testCollectionRename42" + PaginatedCollection.DEF_EXTENSION)));
      var mapFile =
          new File(
              storagePath,
              wowCache.nativeFileNameById(
                  wowCache.fileIdByName(
                      "testCollectionRename42" + CollectionPositionMap.DEF_EXTENSION)));

      Assert.assertTrue(dataFile.exists());
      Assert.assertTrue(mapFile.exists());
    }
  }
}
