package com.jetbrain.youtrack.db.gremlin.internal;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.apache.tinkerpop.gremlin.structure.Direction;

public class YTDBGraphUtils {
    public static String encodeClassName(String className) {
        if (className == null) return null;

        if (Character.isDigit(className.charAt(0))) {
            className = "-" + className;
        }

        return URLEncoder.encode(className, StandardCharsets.UTF_8);

    }

    public static String decodeClassName(String iClassName) {
        if (iClassName == null) {
            return null;
        }

        if (iClassName.charAt(0) == '-') {
            iClassName = iClassName.substring(1);
        }

        return URLDecoder.decode(iClassName, StandardCharsets.UTF_8);
    }

    public static com.jetbrains.youtrack.db.api.record.Direction mapDirection(Direction direction) {
        return switch (direction) {
            case OUT -> com.jetbrains.youtrack.db.api.record.Direction.OUT;
            case IN -> com.jetbrains.youtrack.db.api.record.Direction.IN;
            case BOTH -> com.jetbrains.youtrack.db.api.record.Direction.BOTH;
        };
    }
}
