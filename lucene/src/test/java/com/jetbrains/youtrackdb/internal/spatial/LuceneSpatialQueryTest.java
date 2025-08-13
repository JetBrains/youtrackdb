/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrackdb.internal.spatial;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.lucene.test.BaseLuceneTest;
import java.io.File;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.zip.ZipFile;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialQueryTest extends BaseLuceneTest {

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");

    var oClass = schema.createClass("Place");
    oClass.addSuperClass(v);
    oClass.createProperty("latitude", PropertyType.DOUBLE);
    oClass.createProperty("longitude", PropertyType.DOUBLE);
    oClass.createProperty("name", PropertyType.STRING);

    session.execute("CREATE INDEX Place.l_lon ON Place(latitude,longitude) SPATIAL ENGINE LUCENE")
        .close();

    try {
      var zipFile =
          new ZipFile(new File(ClassLoader.getSystemResource("location.csv.zip").getPath()));
      var entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();

        YouTrackDBEnginesManager.instance()
            .getScheduler()
            .scheduleTask(
                new Runnable() {
                  @Override
                  public void run() {

                    var runtime = Runtime.getRuntime();

                    var sb = new StringBuilder();
                    var maxMemory = runtime.maxMemory();
                    var allocatedMemory = runtime.totalMemory();
                    var freeMemory = runtime.freeMemory();
                    LogManager.instance()
                        .info(
                            this,
                            "Memory Stats: free [%d], allocated [%d], max [%d] total free [%d]",
                            freeMemory / 1024,
                            allocatedMemory / 1024,
                            maxMemory / 1024,
                            (freeMemory + (maxMemory - allocatedMemory)) / 1024);
                  }
                },
                10000,
                10000);
        if (entry.getName().equals("location.csv")) {

          var stream = zipFile.getInputStream(entry);
          var lnr = new LineNumberReader(new InputStreamReader(stream));

          String line;
          var i = 0;
          while ((line = lnr.readLine()) != null) {
            var nextLine = line.split(",");
            var doc = ((EntityImpl) session.newEntity("Place"));
            doc.setProperty("name", nextLine[3]);
            doc.setProperty("country", nextLine[1]);
            try {

              Double lat = PropertyTypeInternal.convert(session, nextLine[5], Double.class)
                  .doubleValue();
              Double lng = PropertyTypeInternal.convert(session, nextLine[6], Double.class)
                  .doubleValue();
              doc.setProperty("latitude", lat);
              doc.setProperty("longitude", lng);
            } catch (Exception e) {
              continue;
            }

            if (i % 100000 == 0) {
              LogManager.instance().info(this, "Imported: [%d] records", i);
              session.commit();
              session.begin();
            }
            i++;
          }
          lnr.close();
          stream.close();
          session.commit();
        }
      }

    } catch (Exception e) {

    }
  }

  @Test
  @Ignore
  public void testNearQuery() {

    var query =
        "select *,$distance from Place where [latitude,longitude,$spatial] NEAR"
            + " [41.893056,12.482778,{\"maxDistance\": 0.5}]";
    var docs = session.query(query);

    Assert.assertTrue(docs.hasNext());

    // WHY ? 0.2749329729746763
    // Assert.assertEquals(0.27504313167833594, docs.get(0).field("$distance"));
    //    Assert.assertEquals(db, docs.get(0).field("$distance"));

    assertThat(docs.next().<Float>getProperty("$distance")).isEqualTo(0.2749329729746763);
    Assert.assertFalse(docs.hasNext());
  }

  @Test
  @Ignore
  public void testWithinQuery() {
    var query =
        "select * from Place where [latitude,longitude] WITHIN"
            + " [[51.507222,-0.1275],[55.507222,-0.1275]]";
    var docs = session.query(query);
    Assert.assertEquals(238, docs.stream().count());
  }
}
