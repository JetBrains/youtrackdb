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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class FunctionsTest extends BaseDBTest {
  @Test
  public void createFunctionBug2415() {
    session.begin();
    var r1 = session.execute(
        "create function FunctionsTest \"return a + b\" PARAMETERS [a,b] IDEMPOTENT"
            + " true LANGUAGE Javascript"
    );
    var r2 = r1.next();
    var result = r2.getIdentity();

    var transaction = session.getActiveTransaction();
    final EntityImpl record = transaction.load(result);
    final List<String> parameters = record.getProperty("parameters");

    Assert.assertNotNull(parameters);
    Assert.assertEquals(parameters.size(), 2);
    session.rollback();
  }

  @Test
  public void testFunctionDefinitionAndCall() {
    session.execute("create function testCall \"return 0;\" LANGUAGE Javascript").close();

    var res1 = session.execute("select testCall() as testCall");
    Assert.assertEquals((int) res1.next().getProperty("testCall"), 0);
  }

  @Test
  public void testFunctionCacheAndReload() {
    Identifiable f =
        session
            .execute("create function testCache \"return 1;\" LANGUAGE Javascript")
            .findFirst(Result::getIdentity);
    Assert.assertNotNull(f);

    try (var res1 = session.execute("select testCache() as testCache")) {
      Assert.assertEquals(res1.next().<Object>getProperty("testCache"), 1);
    }

    session.begin();
    var transaction = session.getActiveTransaction();
    EntityImpl func = transaction.load(f);
    func.setProperty("code", "return 2;");

    session.commit();

    try (var res2 = session.execute("select testCache() as testCache")) {
      Assert.assertEquals(res2.next().<Object>getProperty("testCache"), 2);
    }
  }

  @Test
  public void testMultiThreadsFunctionCallMoreThanPool() {
    session.execute("create function testMTCall \"return 3;\" LANGUAGE Javascript").close();

    final var TOT = 1000;
    final var threadNum = GlobalConfiguration.SCRIPT_POOL.getValueAsInteger() * 3;
    final var counter = new AtomicLong();

    final var threads = new Thread[threadNum];
    for (var i = 0; i < threadNum; ++i) {
      threads[i] =
          new Thread() {
            public void run() {
              for (var cycle = 0; cycle < TOT; ++cycle) {
                var res1 = session.execute("select testMTCall() as testMTCall");
                Assert.assertNotNull(res1);
                Assert.assertEquals(res1.next().<Object>getProperty("testMTCall"), 3);

                counter.incrementAndGet();
              }
            }
          };
      threads[i].start();
    }

    for (var i = 0; i < threadNum; ++i) {
      try {
        threads[i].join();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    Assert.assertEquals(counter.get(), (long) threadNum * TOT);
  }

  @Test
  public void testFunctionDefinitionAndCallWithParams() {
    session
        .execute(
            "create function testParams \"return 'Hello ' + name + ' ' + surname + ' from ' +"
                + " country;\" PARAMETERS [name,surname,country] LANGUAGE Javascript")
        .close();

    try (var res1 =
        session.execute("select testParams('Jay', 'Miner', 'USA') as testParams")) {
      Assert.assertEquals(res1.next().getProperty("testParams"), "Hello Jay Miner from USA");
    }

    final var params = new HashMap<String, Object>();
    params.put("name", "Jay");
    params.put("surname", "Miner");
    params.put("country", "USA");

    var result =
        session
            .getMetadata()
            .getFunctionLibrary()
            .getFunction(session, "testParams")
            .executeInContext(null, params);
    Assert.assertEquals(result, "Hello Jay Miner from USA");
  }

  @Test
  public void testMapParamToFunction() {
    session
        .execute(
            "create function testMapParamToFunction \"return mapParam.get('foo')[0];\" PARAMETERS"
                + " [mapParam] LANGUAGE Javascript")
        .close();

    Map<String, Object> params = new HashMap<String, Object>();

    List theList = new ArrayList();
    theList.add("bar");
    Map theMap = new HashMap();
    theMap.put("foo", theList);
    params.put("theParam", theMap);

    var res1 = session.execute("select testMapParamToFunction(:theParam) as a", params);
    Assert.assertEquals(res1.next().getProperty("a"), "bar");
  }
}
