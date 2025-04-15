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
package com.jetbrains.youtrack.db.internal.spatial;

import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.lucene.test.BaseLuceneTest;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialClassCreateTest extends BaseLuceneTest {

  @Test
  public void testClasses() {

    Schema schema = session.getMetadata().getSchema();

    Assert.assertNotNull(schema.getClass("OPoint"));

    Assert.assertNotNull(schema.getClass("OMultiPoint"));

    Assert.assertNotNull(schema.getClass("OLineString"));

    Assert.assertNotNull(schema.getClass("OMultiLineString"));

    Assert.assertNotNull(schema.getClass("ORectangle"));

    Assert.assertNotNull(schema.getClass("OPolygon"));

    Assert.assertNotNull(schema.getClass("OMultiPolygon"));
  }
}
