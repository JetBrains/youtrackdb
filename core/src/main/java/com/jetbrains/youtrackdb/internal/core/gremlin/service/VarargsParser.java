// Copyright (c) 2014-2024 JetBrains. All rights reserved.
package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/// Helper for parsing varargs-style parameter lists.
///
/// Parses lists in the format: (String command, key1, value1, key2, value2, ...)
/// where the first element is the command/query string and the remaining elements
/// are alternating key-value pairs for parameters.
final class VarargsParser {

  private VarargsParser() {
    throw new AssertionError("Utility class");
  }

  /// Result of parsing a varargs list.
  ///
  /// @param command   The command/query string (first element of the list).
  /// @param arguments The parsed key-value parameter map.
  record ParseResult(@Nonnull String command, @Nonnull Map<?, ?> arguments) {
  }

  /// Parse a varargs list into command string and parameter map.
  ///
  /// @param argsList The list to parse. Must not be empty. First element must be a String.
  ///                 Remaining elements must be alternating key-value pairs.
  /// @return Parsed command and arguments, or null if first element is not a String.
  /// @throws IllegalArgumentException if the number of arguments after the command is odd.
  static ParseResult parseVarargs(@Nonnull List<?> argsList) {
    if (argsList.isEmpty()) {
      throw new IllegalArgumentException("Argument list must not be empty");
    }

    if (!(argsList.getFirst() instanceof String command)) {
      throw new IllegalArgumentException("First argument must be a String (command/query)");
    }

    if (argsList.size() % 2 == 0) {
      throw new IllegalArgumentException("Arguments must be provided in key-value pairs");
    }

    var map = new LinkedHashMap<>();
    for (var i = 1; i < argsList.size(); i += 2) {
      map.put(argsList.get(i), argsList.get(i + 1));
    }

    return new ParseResult(command, map);
  }
}
