/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Edge-case tests for {@link SQLFunctionAstar} that complement {@link SQLFunctionAstarTest}.
 *
 * <p>Pinned branches:
 *
 * <ul>
 *   <li>{@code execute()} MultiValue source/destination rejection — multi-element collection
 *       triggers the inline {@code "Only one sourceVertex is allowed"} /
 *       {@code "Only one destinationVertex is allowed"} {@link IllegalArgumentException}
 *       (TC4).
 *   <li>{@code bindAdditionalParams} Identifiable branch — when the {@code options} arg is a
 *       stored record (not a Map), the function loads it and reads the configuration via
 *       {@code EntityImpl.toMap()} (TC1). Symmetric with the equivalent
 *       {@link SQLFunctionShortestPathEdgeTest} coverage.
 * </ul>
 */
public class SQLFunctionAstarEdgeCasesTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private SQLFunctionAstar function;

  private Vertex v1;
  private Vertex v2;
  private Vertex v3;

  @Before
  public void setUp() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create(
        "SQLFunctionAstarEdgeCasesTest",
        DatabaseType.MEMORY,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    session = youTrackDB.open("SQLFunctionAstarEdgeCasesTest", "admin", DbTestBase.ADMIN_PASSWORD);

    session.createEdgeClass("knows");

    session.begin();
    v1 = session.newVertex();
    v1.setProperty("weight", 1.0f);
    v2 = session.newVertex();
    v2.setProperty("weight", 1.0f);
    v3 = session.newVertex();
    v3.setProperty("weight", 1.0f);

    var e12 = session.newEdge(v1, v2, "knows");
    e12.setProperty("weight", 1.0f);
    var e23 = session.newEdge(v2, v3, "knows");
    e23.setProperty("weight", 1.0f);
    session.commit();

    function = new SQLFunctionAstar();
  }

  @After
  public void tearDown() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
    if (session != null) {
      session.close();
    }
    if (youTrackDB != null) {
      try {
        if (youTrackDB.exists("SQLFunctionAstarEdgeCasesTest")) {
          youTrackDB.drop("SQLFunctionAstarEdgeCasesTest");
        }
      } finally {
        youTrackDB.close();
      }
    }
  }

  @Test
  public void multiValueSourceWithMoreThanOneElementThrowsWithDescriptiveMessage() {
    // Pins the inline IllegalArgumentException branch in SQLFunctionAstar.execute() that
    // rejects collection-typed source vertices with size > 1. SQLFunctionAstarTest exercises
    // only the bare-vertex path; this branch is reachable from SQL via
    //   SELECT astar([v1, v2], dst, 'weight').
    var v1Id = v1.getIdentity();
    var v2Id = v2.getIdentity();
    var v3Id = v3.getIdentity();

    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    // execute() calls db.getActiveTransaction() before the size-1 unwrap path; the size>1
    // branch returns earlier, but begin a transaction anyway so the lookup never throws and
    // the test cleanly isolates the rejection assertion.
    session.begin();
    try {
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> function.execute(
                  null,
                  null,
                  null,
                  new Object[] {List.of(v1Id, v2Id), v3Id, "'weight'"},
                  ctx));
      assertEquals("Only one sourceVertex is allowed", ex.getMessage());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void multiValueDestinationWithMoreThanOneElementThrowsWithDescriptiveMessage() {
    // Symmetric pin for the destinationVertex branch. The source is a single bare vertex so
    // execution proceeds past the source-handling block (which calls transaction.load on the
    // source Identifiable) and reaches the destination MultiValue check.
    var v1Id = v1.getIdentity();
    var v2Id = v2.getIdentity();
    var v3Id = v3.getIdentity();

    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    try {
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> function.execute(
                  null,
                  null,
                  null,
                  new Object[] {v1Id, List.of(v2Id, v3Id), "'weight'"},
                  ctx));
      assertEquals("Only one destinationVertex is allowed", ex.getMessage());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void optionsFromIdentifiableRecordAreReadViaToMap() {
    // Pins the `additionalParams instanceof Identifiable` branch of
    // SQLFunctionAstar.bindAdditionalParams: when the 4th arg is a stored record (not a Map),
    // the function loads it and converts its properties to a Map. SQLFunctionAstarTest only
    // exercises the Map literal path; this branch is reachable from SQL via
    //   SELECT astar(v1, dst, 'weight', $optsRid).
    session.begin();
    var optsEntity = session.newEntity();
    // Only store scalar properties — collection values would be re-materialised through the
    // session's data-container API when the entity is reloaded, which is incompatible with
    // bindAdditionalParams' direct toMap consumption. PARAM_DIRECTION alone is enough to
    // exercise the Identifiable branch (the rest of the configuration falls back to defaults
    // and the path-finding still has to traverse v1 -> v2 -> v3).
    optsEntity.setProperty(SQLFunctionAstar.PARAM_DIRECTION, "OUT");
    // session.newEntity() auto-registers with the transaction; commit persists it.
    session.commit();
    var optsRid = optsEntity.getIdentity();

    session.begin();
    var v1Id = v1.getIdentity();
    var v2Id = v2.getIdentity();
    var v3Id = v3.getIdentity();
    var managedOpts = session.load(optsRid);

    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    @SuppressWarnings("unchecked")
    var path = (List<Vertex>) function.execute(null, null, null,
        new Object[] {v1Id, v3Id, "'weight'", managedOpts}, ctx);

    // Configuration coming from the stored record must produce the same path as if a Map
    // literal had been used: v1 -> v2 -> v3. Pin every hop (including the v2 middle) so a
    // regression that still returned three elements with correct endpoints but a wrong
    // intermediate vertex would fail.
    assertNotNull("Identifiable-options path must produce a non-null result", path);
    assertEquals(3, path.size());
    assertEquals("path[0] must be the source vertex", v1Id, path.get(0).getIdentity());
    assertEquals("path[1] must be the only intermediate hop", v2Id, path.get(1).getIdentity());
    assertEquals("path[2] must be the destination vertex", v3Id, path.get(2).getIdentity());
    session.rollback();
  }
}
