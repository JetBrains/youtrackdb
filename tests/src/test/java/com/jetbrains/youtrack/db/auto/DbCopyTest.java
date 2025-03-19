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

import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class DbCopyTest extends BaseDBTest implements CommandOutputListener {

  @Parameters(value = {"remote"})
  public DbCopyTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void checkCopy() throws IOException {
    final var className = "DbCopyTest";
    session.getMetadata().getSchema().createClass(className);

    final var otherDB = session.copy();
    session.activateOnCurrentThread();
    var thread =
        new Thread(() -> {
          try {
            otherDB.activateOnCurrentThread();
            for (var i = 0; i < 5; i++) {
              otherDB.begin();
              EntityImpl doc = otherDB.newInstance(className);
              doc.setProperty("num", 20 + i);

              otherDB.commit();
              try {
                Thread.sleep(10);
              } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
              }
            }
            otherDB.close();
          } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
          }
        });
    thread.start();

    for (var i = 0; i < 20; i++) {
      session.begin();
      EntityImpl doc = session.newInstance(className);
      doc.setProperty("num", i);

      session.commit();
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    try {
      thread.join();
    } catch (InterruptedException e) {
      Assert.fail();
    }

    session.begin();
    var result = session.query("SELECT FROM " + className);
    Assert.assertEquals(result.stream().count(), 25);
    session.commit();
  }

  @Override
  @Test(enabled = false)
  public void onMessage(final String iText) {
    // System.out.print(iText);
    // System.out.flush();
  }
}
