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

package com.jetbrains.youtrack.db.internal.core.command.traverse;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import java.util.ArrayDeque;

/**
 *
 */
public class TraversePath {

  private static final TraversePath EMPTY_PATH = new TraversePath(new FirstPathItem());

  private final PathItem lastPathItem;

  private TraversePath(PathItem lastPathItem) {
    this.lastPathItem = lastPathItem;
  }

  @Override
  public String toString() {
    final var stack = new ArrayDeque<PathItem>();
    var currentItem = lastPathItem;
    while (currentItem != null) {
      stack.push(currentItem);
      currentItem = currentItem.parentItem;
    }

    final var buf = new StringBuilder(1024);
    for (var pathItem : stack) {
      buf.append(pathItem.toString());
    }

    return buf.toString();
  }

  public TraversePath append(Identifiable record) {
    return new TraversePath(new RecordPathItem(record, lastPathItem));
  }

  public TraversePath appendField(String fieldName) {
    return new TraversePath(new FieldPathItem(fieldName, lastPathItem));
  }

  public TraversePath appendIndex(int index) {
    return new TraversePath(new CollectionPathItem(index, lastPathItem));
  }

  public TraversePath appendRecordSet() {
    return this;
  }

  public int getDepth() {
    return lastPathItem.depth;
  }

  public static TraversePath empty() {
    return EMPTY_PATH;
  }

  private abstract static class PathItem {

    protected final PathItem parentItem;
    protected final int depth;

    private PathItem(PathItem parentItem, int depth) {
      this.parentItem = parentItem;
      this.depth = depth;
    }
  }

  private static class RecordPathItem extends PathItem {

    private final Identifiable record;

    private RecordPathItem(Identifiable record, PathItem parentItem) {
      super(parentItem, parentItem.depth + 1);
      this.record = record;
    }

    @Override
    public String toString() {
      return "(" + record.getIdentity().toString() + ")";
    }
  }

  private static class FieldPathItem extends PathItem {

    private final String name;

    private FieldPathItem(String name, PathItem parentItem) {
      super(parentItem, parentItem.depth);
      this.name = name;
    }

    @Override
    public String toString() {
      return "." + name;
    }
  }

  private static class CollectionPathItem extends PathItem {

    private final int index;

    private CollectionPathItem(int index, PathItem parentItem) {
      super(parentItem, parentItem.depth);
      this.index = index;
    }

    @Override
    public String toString() {
      return "[" + index + "]";
    }
  }

  private static class FirstPathItem extends PathItem {

    private FirstPathItem() {
      super(null, -1);
    }

    @Override
    public String toString() {
      return "";
    }
  }
}
