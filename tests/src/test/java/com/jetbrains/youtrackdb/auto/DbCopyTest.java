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

import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import java.io.IOException;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class DbCopyTest extends BaseDBTest implements CommandOutputListener {
  @Test
  public void checkCopy() throws IOException {
    final var className = "DbCopyTest";
    session.getMetadata().getSlowMutableSchema().createClass(className);

    try (final var otherDB = session.copy()) {
      for (var i = 0; i < 5; i++) {
        otherDB.begin();
        var doc = otherDB.newInstance(className);
        doc.setProperty("num", 20 + i);

        otherDB.commit();
        try {
          Thread.sleep(10);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }

    for (var i = 0; i < 20; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("num", i);

      session.commit();
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
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
