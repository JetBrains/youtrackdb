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
package com.jetbrains.youtrackdb.internal.spatial.collections;

import com.jetbrains.youtrackdb.internal.lucene.collections.LuceneCompositeKey;
import java.util.List;
import org.apache.lucene.spatial.query.SpatialOperation;

public class SpatialCompositeKey extends LuceneCompositeKey {

  private double maxDistance;

  private SpatialOperation operation;

  public SpatialCompositeKey(final List<?> keys) {
    super(keys);
  }

  public double getMaxDistance() {
    return maxDistance;
  }

  public SpatialCompositeKey setMaxDistance(double maxDistance) {
    this.maxDistance = maxDistance;
    return this;
  }

  public SpatialOperation getOperation() {
    return operation;
  }

  public SpatialCompositeKey setOperation(SpatialOperation operation) {
    this.operation = operation;
    return this;
  }
}
