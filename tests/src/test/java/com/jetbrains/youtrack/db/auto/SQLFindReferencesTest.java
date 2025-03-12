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

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test(groups = "sql-findReferences")
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

  @Parameters(value = "remote")
  public SQLFindReferencesTest(boolean remote) {
    super(remote);
  }

  @Test
  public void findSimpleReference() {
    var result = session.command("find references " + carID).stream().toList();

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.iterator().next().getProperty("referredBy"), johnDoeID);

    // SUB QUERY
    result = session.command("find references ( select from " + carID + ")").stream().toList();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.iterator().next().getProperty("referredBy"), johnDoeID);

    result = session.command("find references " + chuckNorrisID).stream().toList();
    Assert.assertEquals(result.size(), 2);

    for (var rid : result) {
      Assert.assertTrue(
          rid.getProperty("referredBy").equals(ctuID)
              || rid.getProperty("referredBy").equals(fbiID));
    }

    result = session.command("find references " + johnDoeID).stream().toList();
    Assert.assertEquals(result.size(), 0);

    result = null;
  }

  @Test
  public void findReferenceByClassAndClusters() {
    var result =
        session.command("find references " + janeDoeID + " [" + WORKPLACE + "]").stream().toList();

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(ctuID, result.iterator().next().getProperty("referredBy"));

    result =
        session
            .command("find references " + jackBauerID + " [" + WORKPLACE + ",cluster:" + CAR + "]")
            .stream()
            .toList();

    Assert.assertEquals(result.size(), 3);

    for (var res : result) {
      Identifiable rid = res.getProperty("referredBy");
      Assert.assertTrue(rid.equals(ctuID) || rid.equals(fbiID) || rid.equals(carID));
    }

    result =
        session
            .command(
                "find references "
                    + johnDoeID
                    + " ["
                    + WORKPLACE
                    + ","
                    + CAR
                    + ",cluster:"
                    + WORKER
                    + "]")
            .stream()
            .toList();

    Assert.assertEquals(result.size(), 0);

    result = null;
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
    car.field("plate", "JINF223S");

    var johnDoe = ((EntityImpl) session.newEntity(WORKER));
    johnDoe.field("name", "John");
    johnDoe.field("surname", "Doe");
    johnDoe.field("car", car);

    var janeDoe = ((EntityImpl) session.newEntity(WORKER));
    janeDoe.field("name", "Jane");
    janeDoe.field("surname", "Doe");

    var chuckNorris = ((EntityImpl) session.newEntity(WORKER));
    chuckNorris.field("name", "Chuck");
    chuckNorris.field("surname", "Norris");

    var jackBauer = ((EntityImpl) session.newEntity(WORKER));
    jackBauer.field("name", "Jack");
    jackBauer.field("surname", "Bauer");

    var ctu = ((EntityImpl) session.newEntity(WORKPLACE));
    ctu.field("name", "CTU");
    ctu.field("boss", jackBauer);
    List<EntityImpl> workplace1Workers = new ArrayList<EntityImpl>();
    workplace1Workers.add(chuckNorris);
    workplace1Workers.add(janeDoe);
    ctu.field("workers", workplace1Workers);

    var fbi = ((EntityImpl) session.newEntity(WORKPLACE));
    fbi.field("name", "FBI");
    fbi.field("boss", chuckNorris);
    List<EntityImpl> workplace2Workers = new ArrayList<EntityImpl>();
    workplace2Workers.add(chuckNorris);
    workplace2Workers.add(jackBauer);
    fbi.field("workers", workplace2Workers);

    car.field("owner", jackBauer);

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
    session.command("drop class " + iClass).close();
    while (session.getMetadata().getSchema().existsClass(iClass)) {
      session.getMetadata().getSchema().dropClass(iClass);
      session.reload();
    }
    while (session.getClusterIdByName(iClass) > -1) {
      session.dropCluster(iClass);
      session.reload();
    }
  }
}
