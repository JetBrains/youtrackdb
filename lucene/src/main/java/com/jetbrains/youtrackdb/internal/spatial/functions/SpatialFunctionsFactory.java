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
package com.jetbrains.youtrackdb.internal.spatial.functions;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionFactoryTemplate;

public class SpatialFunctionsFactory extends SQLFunctionFactoryTemplate {

  public SpatialFunctionsFactory() {
  }

  @Override
  public void registerDefaultFunctions(DatabaseSessionEmbedded session) {
    register(session, new STGeomFromTextFunction());
    register(session, new STAsTextFunction());
    register(session, new STWithinFunction());
    register(session, new STDWithinFunction());
    register(session, new STEqualsFunction());
    register(session, new STAsBinaryFunction());
    register(session, new STEnvelopFunction());
    register(session, new STBufferFunction());
    register(session, new STDistanceFunction());
    register(session, new STDistanceSphereFunction());
    register(session, new STDisjointFunction());
    register(session, new STIntersectsFunction());
    register(session, new STContainsFunction());
    register(session, new STSrid());
    register(session, new STAsGeoJSONFunction());
    register(session, new STGeomFromGeoJSONFunction());
  }
}
