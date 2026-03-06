package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLBaseVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Visitor that extracts node patterns from the MATCH clause using unified YQL IR.
///
/// For `MATCH (a:Person)`:
/// - Builds SQLMatchFilter with alias="a", className="Person" (YQL IR)
/// - Result: Map with binding {"a": vertex}
///
/// For `MATCH (:Person)`:
/// - Builds SQLMatchFilter with alias=null, className="Person" (YQL IR)
/// - Result: just the Vertex directly (no Map wrapper)
///
/// For `MATCH (a:Person), (b:Work)`:
/// - Builds [SQLMatchFilter("a", "Person"), SQLMatchFilter("b", "Work")]
///
/// Uses unified YQL IR (SQLMatchFilter) directly via factory method, which are then converted
/// to Pattern + PatternNode in GqlMatchStatement.buildPlan().
@SuppressWarnings({"unused", "ConstantConditions"})
public class GqlMatchVisitor extends GQLBaseVisitor<Void> {

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern(StorageConfiguration.DEFAULT_DATE_FORMAT);
  private static final DateTimeFormatter DATETIME_FORMAT =
      DateTimeFormatter.ofPattern(StorageConfiguration.DEFAULT_DATETIME_FORMAT);
  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("HH:mm:ss");

  private final List<SQLMatchFilter> matchFilters = new ArrayList<>();

  @Override
  public Void visitNode_pattern(GQLParser.Node_patternContext ctx) {
    var patternFiller = ctx.pattern_filler();

    String alias = null;
    String label = null;
    Map<String, Object> properties = Map.of();

    if (patternFiller != null) {
      var variableCtx = patternFiller.graph_pattern_variable();
      if (variableCtx != null) {
        alias = variableCtx.getText();
      }

      var labelCondition = patternFiller.is_label_condition();
      if (labelCondition != null && labelCondition.label_expression() != null) {
        label = stripBackticks(labelCondition.label_expression().getText());
      }

      var propFilters = patternFiller.property_filters();
      if (propFilters != null && propFilters.property_list() != null) {
        properties = extractProperties(propFilters.property_list());
      }
    }

    // Build SQLMatchFilter using YQL IR factory method
    var filter = SQLMatchFilter.fromGqlNode(alias, label);

    // If inline properties exist, convert them to SQLWhereClause and attach
    if (!properties.isEmpty()) {
      var whereClause = GqlMatchStatement.buildWhereClause(properties);
      filter.setFilter(whereClause);
    }

    matchFilters.add(filter);
    return null;
  }

  /// Returns all match filters (unified YQL IR) from MATCH clause.
  /// These SQLMatchFilter instances are converted to Pattern + PatternNode in GqlMatchStatement.buildPlan().
  public List<SQLMatchFilter> getMatchFilters() {
    return matchFilters;
  }

  private static Map<String, Object> extractProperties(
      GQLParser.Property_listContext listCtx) {
    var result = new LinkedHashMap<String, Object>();
    for (var assignment : listCtx.property_assignment()) {
      var key = assignment.ID().getText();
      var value = extractLiteralValue(assignment.value_expression());
      result.put(key, value);
    }
    return result;
  }

  /// Extracts a Java literal value from a GQL `value_expression` parse context.
  ///
  /// Supports all PropertyType literal values (except LINKBAG) in inline property filters:
  /// - STRING: `'text'` → String
  /// - BOOLEAN: `true` / `false` → Boolean
  /// - Integer numbers: `42` → Long (covers BYTE, SHORT, INTEGER, LONG)
  /// - Decimal numbers: `3.14` → Double (covers FLOAT, DOUBLE)
  /// - DECIMAL: `DECIMAL '123.456'` → BigDecimal
  /// - BINARY: `BINARY 'SGVsbG8='` → byte[] (Base64 encoded)
  /// - RID: `#12:0` → RecordIdInternal (covers LINK)
  /// - Temporal: `DATE '2024-01-01'`, `TIMESTAMP '2024-01-01 12:00:00'` → Date (covers DATE, DATETIME)
  /// - List: `[1, 2, 3]` → List (covers EMBEDDEDLIST, LINKLIST, EMBEDDEDSET, LINKSET via type coercion)
  /// - Map: `{key: 'value'}` → Map (covers EMBEDDEDMAP, LINKMAP, EMBEDDED)
  static Object extractLiteralValue(GQLParser.Value_expressionContext valueCtx) {
    if (valueCtx.STRING() != null) {
      var text = valueCtx.STRING().getText();
      return text.substring(1, text.length() - 1);
    }

    if (valueCtx.RID() != null) {
      return RecordIdInternal.fromString(valueCtx.RID().getText(), false);
    }

    if (valueCtx.temporal_literal() != null) {
      return extractTemporalValue(valueCtx.temporal_literal());
    }

    if (valueCtx.binary_literal() != null) {
      return extractBinaryValue(valueCtx.binary_literal());
    }

    if (valueCtx.decimal_literal() != null) {
      return extractDecimalValue(valueCtx.decimal_literal());
    }

    if (valueCtx.list_literal() != null) {
      return extractListValue(valueCtx.list_literal());
    }

    if (valueCtx.map_literal() != null) {
      return extractMapValue(valueCtx.map_literal());
    }

    if (valueCtx.BOOL() != null) {
      return "true".equalsIgnoreCase(valueCtx.BOOL().getText());
    }

    var text = valueCtx.getText();

    if (valueCtx.math_expression() != null) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
      }
      try {
        return Double.parseDouble(text);
      } catch (NumberFormatException ignored) {
      }
    }

    throw new IllegalArgumentException(
        "Unsupported inline property value: " + text);
  }

  private static Date extractTemporalValue(GQLParser.Temporal_literalContext ctx) {
    var str = ctx.STRING().getText();
    str = str.substring(1, str.length() - 1);

    if (ctx.DATE() != null) {
      var localDate = LocalDate.parse(str, DATE_FORMAT);
      return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    } else if (ctx.TIMESTAMP() != null) {
      var localDateTime = LocalDateTime.parse(str, DATETIME_FORMAT);
      return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    } else if (ctx.TIME() != null) {
      var localTime = LocalTime.parse(str, TIME_FORMAT);
      var instant = localTime.atDate(LocalDate.ofEpochDay(0))
          .atZone(ZoneId.systemDefault()).toInstant();
      return Date.from(instant);
    }

    throw new IllegalArgumentException(
        "Unsupported temporal type in inline property filter: " + ctx.getText());
  }

  private static List<Object> extractListValue(GQLParser.List_literalContext ctx) {
    var result = new ArrayList<>();
    for (var valueCtx : ctx.value_expression()) {
      result.add(extractLiteralValue(valueCtx));
    }
    return result;
  }

  private static Map<String, Object> extractMapValue(GQLParser.Map_literalContext ctx) {
    var result = new LinkedHashMap<String, Object>();
    for (var entry : ctx.map_entry()) {
      String key;
      if (entry.ID() != null) {
        key = entry.ID().getText();
      } else {
        var keyText = entry.STRING().getText();
        key = keyText.substring(1, keyText.length() - 1);
      }
      var value = extractLiteralValue(entry.value_expression());
      result.put(key, value);
    }
    return result;
  }

  /// Extracts a BINARY literal value: `BINARY 'SGVsbG8='` → byte[]
  /// The string is decoded from Base64.
  private static byte[] extractBinaryValue(GQLParser.Binary_literalContext ctx) {
    var base64 = ctx.STRING().getText();
    base64 = base64.substring(1, base64.length() - 1); // Remove quotes
    return Base64.getDecoder().decode(base64);
  }

  /// Extracts a DECIMAL literal value: `DECIMAL '123.456'` → BigDecimal
  /// Provides arbitrary precision for financial/scientific calculations.
  private static java.math.BigDecimal extractDecimalValue(GQLParser.Decimal_literalContext ctx) {
    var decimalStr = ctx.STRING().getText();
    decimalStr = decimalStr.substring(1, decimalStr.length() - 1); // Remove quotes
    return new java.math.BigDecimal(decimalStr);
  }

  /// Removes surrounding backticks from GQL quoted identifiers.
  /// GQL uses backticks to quote identifiers (e.g. `` `Person` ``).
  /// This matches SQL parser behavior which strips backticks during tokenization.
  private static String stripBackticks(String text) {
    if (text != null && !text.isEmpty() && text.charAt(0) == '`'
        && text.charAt(text.length() - 1) == '`'
        && text.length() >= 2) {
      return text.substring(1, text.length() - 1);
    }
    return text;
  }
}
