package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import java.util.ArrayList;
import java.util.List;

public interface EdgeInternal extends Edge, Relation<Vertex> {
  static void checkPropertyName(String name) {
    if (isEdgeConnectionProperty(name)) {
      throw new IllegalArgumentException(
          "Property name " + name + " is booked as a name that can be used to manage edges.");
    }
  }

  static boolean isEdgeConnectionProperty(String name) {
    return isOutEdgeConnectionProperty(name) || isInEdgeConnectionProperty(name);
  }

  static boolean isInEdgeConnectionProperty(String name) {
    return name.equals(DIRECTION_IN);
  }

  static boolean isOutEdgeConnectionProperty(String name) {
    return name.equals(DIRECTION_OUT);
  }

  static List<String> filterPropertyNames(List<String> propertyNames) {
    var propertiesToRemove = new ArrayList<String>();

    for (var propertyName : propertyNames) {
      if (isInEdgeConnectionProperty(propertyName) || isOutEdgeConnectionProperty(propertyName)) {
        propertiesToRemove.add(propertyName);
      }
    }

    if (propertiesToRemove.isEmpty()) {
      return propertyNames;
    }

    for (var propertyToRemove : propertiesToRemove) {
      propertyNames.remove(propertyToRemove);
    }

    return propertyNames;
  }
}
