/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Contains the index metadata.
 */
public class IndexMetadata {

  @Nonnull
  private final String name;
  private final IndexDefinition indexDefinition;
  private final Set<String> collectionsToIndex;
  private final String type;
  private final String algorithm;
  private int version;
  private Map<String, Object> metadata;

  public IndexMetadata(
      @Nonnull String name,
      IndexDefinition indexDefinition,
      Set<String> collectionsToIndex,
      String type,
      String algorithm,
      int version,
      Map<String, Object> metadata) {
    this.name = name;
    this.indexDefinition = indexDefinition;
    this.collectionsToIndex = collectionsToIndex;
    this.type = type;

    this.algorithm = algorithm;
    this.version = version;
    this.metadata = metadata;
  }


  @Nonnull
  public String getName() {
    return name;
  }

  public IndexDefinition getIndexDefinition() {
    return indexDefinition;
  }

  public Set<String> getCollectionsToIndex() {
    return collectionsToIndex;
  }

  public String getType() {
    return type;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final var that = (IndexMetadata) o;

    if (!Objects.equals(algorithm, that.algorithm)) {
      return false;
    }
    if (!collectionsToIndex.equals(that.collectionsToIndex)) {
      return false;
    }
    if (!Objects.equals(indexDefinition, that.indexDefinition)) {
      return false;
    }
    if (!name.equals(that.name)) {
      return false;
    }
    return type.equals(that.type);
  }

  @Override
  public int hashCode() {
    var result = name.hashCode();
    result = 31 * result + (indexDefinition != null ? indexDefinition.hashCode() : 0);
    result = 31 * result + collectionsToIndex.hashCode();
    result = 31 * result + type.hashCode();
    result = 31 * result + (algorithm != null ? algorithm.hashCode() : 0);
    return result;
  }

  public boolean isMultivalue() {
    var t = type.toUpperCase(Locale.ROOT);
    return SchemaClass.INDEX_TYPE.NOTUNIQUE.toString().equals(t);
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }
}
