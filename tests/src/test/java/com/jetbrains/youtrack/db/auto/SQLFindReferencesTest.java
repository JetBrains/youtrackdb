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


import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class SQLFindReferencesTest extends BaseDBTest {

  private static final String WORKPLACE = "Workplace";
  private static final String WORKER = "Worker";
  private static final String CAR = "Car";

  private RecordId carID;
  private RecordId johnDoeID;
  private RecordId janeDoeID;
  private RecordId chuckNorrisID;
  private RecordId jackBauerID;
  private RecordId ctuID;
  private RecordId fbiID;

  @Test
  public void findSimpleReference() {
    session.begin();
    var result = session.execute("find references " + carID).stream().toList();

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.iterator().next().getProperty("referredBy"), johnDoeID);

    // SUB QUERY
    result = session.execute("find references ( select from " + carID + ")").stream().toList();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.iterator().next().getProperty("referredBy"), johnDoeID);

    result = session.execute("find references " + chuckNorrisID).stream().toList();
    Assert.assertEquals(result.size(), 2);

    for (var rid : result) {
      Assert.assertTrue(
          rid.getProperty("referredBy").equals(ctuID)
              || rid.getProperty("referredBy").equals(fbiID));
    }

    result = session.execute("find references " + johnDoeID).stream().toList();
    Assert.assertEquals(result.size(), 0);

    session.commit();
  }

  @Test
  public void findReferenceByClassAndCollections() {
    session.begin();
    var result =
        session.execute("find references " + janeDoeID + " [" + WORKPLACE + "]").stream().toList();

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(ctuID, result.iterator().next().getProperty("referredBy"));

    session.commit();
  }

  @BeforeClass
  public void createTestEnvironment() {
    createSchema();
    populateDatabase();
  }

  private void createSchema() {
    var worker = session.getMetadata().getSchema().createClass(WORKER);
    var workplace = session.getMetadata().getSchema().createClass(WORKPLACE);
    var car = session.getMetadata().getSchema().createClass(CAR);

    worker.createProperty("name", PropertyType.STRING);
    worker.createProperty("surname", PropertyType.STRING);
    worker.createProperty("colleagues", PropertyType.LINKLIST, worker);
    worker.createProperty("car", PropertyType.LINK, car);

    workplace.createProperty("name", PropertyType.STRING);
    workplace.createProperty("boss", PropertyType.LINK, worker);
    workplace.createProperty("workers", PropertyType.LINKLIST, worker);

    car.createProperty("plate", PropertyType.STRING);
    car.createProperty("owner", PropertyType.LINK, worker);
  }

  private void populateDatabase() {
    session.begin();
    var car = ((EntityImpl) session.newEntity(CAR));
    car.setProperty("plate", "JINF223S");

    var johnDoe = ((EntityImpl) session.newEntity(WORKER));
    johnDoe.setProperty("name", "John");
    johnDoe.setProperty("surname", "Doe");
    johnDoe.setProperty("car", car);

    var janeDoe = ((EntityImpl) session.newEntity(WORKER));
    janeDoe.setProperty("name", "Jane");
    janeDoe.setProperty("surname", "Doe");

    var chuckNorris = ((EntityImpl) session.newEntity(WORKER));
    chuckNorris.setProperty("name", "Chuck");
    chuckNorris.setProperty("surname", "Norris");

    var jackBauer = ((EntityImpl) session.newEntity(WORKER));
    jackBauer.setProperty("name", "Jack");
    jackBauer.setProperty("surname", "Bauer");

    var ctu = ((EntityImpl) session.newEntity(WORKPLACE));
    ctu.setProperty("name", "CTU");
    ctu.setProperty("boss", jackBauer);
    var workplace1Workers = session.newLinkList();
    workplace1Workers.add(chuckNorris);
    workplace1Workers.add(janeDoe);
    ctu.setProperty("workers", workplace1Workers);

    var fbi = ((EntityImpl) session.newEntity(WORKPLACE));
    fbi.setProperty("name", "FBI");
    fbi.setProperty("boss", chuckNorris);
    var workplace2Workers = session.newLinkList();
    workplace2Workers.add(chuckNorris);
    workplace2Workers.add(jackBauer);
    fbi.setProperty("workers", workplace2Workers);

    car.setProperty("owner", jackBauer);

    session.commit();

    chuckNorrisID = chuckNorris.getIdentity().copy();
    janeDoeID = janeDoe.getIdentity().copy();
    johnDoeID = johnDoe.getIdentity().copy();
    jackBauerID = jackBauer.getIdentity().copy();
    ctuID = ctu.getIdentity();
    fbiID = fbi.getIdentity();
    carID = car.getIdentity().copy();
  }

  @AfterClass
  public void deleteTestEnvironment() {
    session = createSessionInstance();

    carID.reset();
    carID = null;
    johnDoeID.reset();
    johnDoeID = null;
    janeDoeID.reset();
    janeDoeID = null;
    chuckNorrisID.reset();
    chuckNorrisID = null;
    jackBauerID.reset();
    jackBauerID = null;
    ctuID.reset();
    ctuID = null;
    fbiID.reset();
    fbiID = null;
    deleteSchema();

    session.close();
  }

  private void deleteSchema() {
    dropClass(CAR);
    dropClass(WORKER);
    dropClass(WORKPLACE);
  }

  private void dropClass(String iClass) {
    session.execute("drop class " + iClass).close();
    while (session.getMetadata().getSchema().existsClass(iClass)) {
      session.getMetadata().getSchema().dropClass(iClass);
      session.reload();
    }
    while (session.getCollectionIdByName(iClass) > -1) {
      session.dropCollection(iClass);
      session.reload();
    }
  }
}
