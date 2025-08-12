package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.HashMap;
import java.util.Map;

public class FieldTypesString {

  public static final String ATTRIBUTE_FIELD_TYPES = "@fieldTypes";

  /**
   * Parses the field type char returning the closer type. Default is STRING. b=binary if
   * iValue.length() >= 4 b=byte if iValue.length() <= 3 s=short, l=long f=float d=double a=date
   * t=datetime
   *
   * @param iValue    Value to parse
   * @param iCharType Char value indicating the type
   * @return The closest type recognized
   */
  public static PropertyTypeInternal getType(final String iValue, final char iCharType) {
    if (iCharType == 'f') {
      return PropertyTypeInternal.FLOAT;
    } else if (iCharType == 'c') {
      return PropertyTypeInternal.DECIMAL;
    } else if (iCharType == 'l') {
      return PropertyTypeInternal.LONG;
    } else if (iCharType == 'd') {
      return PropertyTypeInternal.DOUBLE;
    } else if (iCharType == 'b') {
      if (iValue.length() >= 1 && iValue.length() <= 3) {
        return PropertyTypeInternal.BYTE;
      } else {
        return PropertyTypeInternal.BINARY;
      }
    } else if (iCharType == 'a') {
      return PropertyTypeInternal.DATE;
    } else if (iCharType == 't') {
      return PropertyTypeInternal.DATETIME;
    } else if (iCharType == 's') {
      return PropertyTypeInternal.SHORT;
    } else if (iCharType == 'e') {
      return PropertyTypeInternal.EMBEDDEDSET;
    } else if (iCharType == 'g') {
      return PropertyTypeInternal.LINKBAG;
    } else if (iCharType == 'z') {
      return PropertyTypeInternal.LINKLIST;
    } else if (iCharType == 'm') {
      return PropertyTypeInternal.LINKMAP;
    } else if (iCharType == 'x') {
      return PropertyTypeInternal.LINK;
    } else if (iCharType == 'n') {
      return PropertyTypeInternal.LINKSET;
    }

    return PropertyTypeInternal.STRING;
  }

  public static PropertyTypeInternal getOTypeFromChar(final char iCharType) {
    if (iCharType == 'f') {
      return PropertyTypeInternal.FLOAT;
    } else if (iCharType == 'c') {
      return PropertyTypeInternal.DECIMAL;
    } else if (iCharType == 'l') {
      return PropertyTypeInternal.LONG;
    } else if (iCharType == 'd') {
      return PropertyTypeInternal.DOUBLE;
    } else if (iCharType == 'b') {
      return PropertyTypeInternal.BINARY;
    } else if (iCharType == 'a') {
      return PropertyTypeInternal.DATE;
    } else if (iCharType == 't') {
      return PropertyTypeInternal.DATETIME;
    } else if (iCharType == 's') {
      return PropertyTypeInternal.SHORT;
    } else if (iCharType == 'e') {
      return PropertyTypeInternal.EMBEDDEDSET;
    } else if (iCharType == 'g') {
      return PropertyTypeInternal.LINKBAG;
    } else if (iCharType == 'z') {
      return PropertyTypeInternal.LINKLIST;
    } else if (iCharType == 'm') {
      return PropertyTypeInternal.LINKMAP;
    } else if (iCharType == 'x') {
      return PropertyTypeInternal.LINK;
    } else if (iCharType == 'n') {
      return PropertyTypeInternal.LINKSET;
    }

    return PropertyTypeInternal.STRING;
  }

  public static Map<String, Character> loadFieldTypesV0(
      Map<String, Character> fieldTypes, final String fieldValueAsString) {
    // LOAD THE FIELD TYPE MAP
    final var fieldTypesParts = fieldValueAsString.split(",");
    if (fieldTypesParts.length > 0) {
      if (fieldTypes == null) {
        fieldTypes = new HashMap<>();
      }
      String[] part;
      for (var f : fieldTypesParts) {
        part = f.split("=");
        if (part.length == 2) {
          fieldTypes.put(part[0], part[1].charAt(0));
        }
      }
    }
    return fieldTypes;
  }

  public static Map<String, Character> loadFieldTypes(final String fieldValueAsString) {
    Map<String, Character> fieldTypes = new HashMap<>();
    loadFieldTypesV0(fieldTypes, fieldValueAsString);
    return fieldTypes;
  }
}
