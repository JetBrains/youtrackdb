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
package com.jetbrains.youtrackdb.internal.spatial.shape;

/**
 *
 */
public enum OShapeType {
  /**
   * Enumeration that lists all {@link OShapeType}s that can be handled
   */
  POINT("point"),
  MULTIPOINT("multipoint"),
  LINESTRING("linestring"),
  MULTILINESTRING("multilinestring"),
  POLYGON("polygon"),
  MULTIPOLYGON("multipolygon"),
  GEOMETRYCOLLECTION("geometrycollection"),
  RECTANGLE("rectangle"),
  CIRCLE("circle");

  private final String shapeName;

  OShapeType(String shapeName) {
    this.shapeName = shapeName;
  }

  @Override
  public String toString() {
    return shapeName;
  }
}
