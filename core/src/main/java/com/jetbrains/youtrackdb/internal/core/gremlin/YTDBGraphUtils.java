package com.jetbrains.youtrackdb.internal.core.gremlin;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.Direction;

public class YTDBGraphUtils {

  @Nullable
  public static String encodeClassName(String className) {
    if (className == null) {
      return null;
    }

    if (Character.isDigit(className.charAt(0))) {
      className = "-" + className;
    }

    return URLEncoder.encode(className, StandardCharsets.UTF_8);

  }

  @Nullable
  public static String decodeClassName(String iClassName) {
    if (iClassName == null) {
      return null;
    }

    if (iClassName.charAt(0) == '-') {
      iClassName = iClassName.substring(1);
    }

    return URLDecoder.decode(iClassName, StandardCharsets.UTF_8);
  }

  public static com.jetbrains.youtrackdb.api.record.Direction mapDirection(Direction direction) {
    return switch (direction) {
      case Direction.OUT -> com.jetbrains.youtrackdb.api.record.Direction.OUT;
      case Direction.IN -> com.jetbrains.youtrackdb.api.record.Direction.IN;
      case Direction.BOTH -> com.jetbrains.youtrackdb.api.record.Direction.BOTH;
    };
  }
}
