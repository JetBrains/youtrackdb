package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.T;

/** Internal, versioned correctness evidence. This format does not certify cut safety. */
public final class T5FreezeCapture {
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
      .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
      .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
      .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
      .build();

  static {
    // Jackson handles textual targets separately from scalar coercion.
    JSON.coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
  }

  public enum Regime {
    COLD, WARM, UNCACHEABLE
  }

  public enum Observation {
    HIT, MISS, BYPASS
  }

  /** Binding identifies one resolved parameter slot, not a rendered query or physical RID. */
  public record Key(String identity, String binding, String arm, Regime regime) {
  }

  public record Cache(String revision, String invalidationEvent, Observation observed,
      String bypassReason) {
  }

  public record Declaration(Value preparedIdentity, Map<String, String> cuts,
      Map<String, String> refusedCuts) {
  }

  public record Item(Key key, Map<String, Value> feeds, T5ResultComparison.Order order,
      List<Value> answers, String outcome, String reason, Cache cache, Declaration declaration,
      boolean knownBaselineDifference, String differenceReason, String failure) {
    public T5ResultComparison.Expected expected() {
      return new T5ResultComparison.Expected(key.identity(), order,
          answers.stream().map(Value::decode).toList());
    }
  }

  public record Capture(int version, boolean complete, Map<String, String> revisions,
      Map<String, String> inputHashes, List<Item> items) {
  }

  public record Required(Key key, Set<String> bindings) {
  }

  public record Completeness(List<String> problems) {
    public Completeness {
      problems = List.copyOf(problems);
    }

    public boolean complete() {
      return problems.isEmpty();
    }

    public int exitCode() {
      return complete() ? 0 : 1;
    }
  }

  /** Explicit tags prevent JSON number coercion and preserve null versus an absent map key. */
  public record Value(String type, String scalar, List<Value> children) {
    public Value {
      children = List.copyOf(children);
    }

    public static Value of(Object value) {
      if (value == null) {
        return new Value("null", null, List.of());
      }
      if (value instanceof List<?> list) {
        return new Value("list", null, list.stream().map(Value::of).toList());
      }
      if (value instanceof Map<?, ?> map) {
        return new Value("map", null, map.entrySet().stream().map(Value::of).toList());
      }
      if (value instanceof Map.Entry<?, ?> entry) {
        return new Value("entry", null, List.of(of(entry.getKey()), of(entry.getValue())));
      }
      if (value instanceof T5ResultComparison.ElementKey key) {
        return new Value("element", null, List.of(of(key.kind()), of(key.label()), of(key.key())));
      }
      Class<?> c = value.getClass();
      if (c == Date.class) {
        return new Value("date", Long.toString(((Date) value).getTime()), List.of());
      }
      if (value instanceof T token) {
        return new Value("token", token.name(), List.of());
      }
      if (!List.of(String.class, Boolean.class, Character.class, Byte.class, Short.class,
          Integer.class, Long.class, Float.class, Double.class, BigInteger.class,
          BigDecimal.class).contains(c)) {
        throw new IllegalArgumentException("Unsupported capture value: " + c.getName());
      }
      return new Value(c.getSimpleName(), value.toString(), List.of());
    }

    public Object decode() {
      // Scalar and container layouts are disjoint. No ignored payload is permitted.
      boolean container = Set.of("null", "list", "map", "entry", "element").contains(type);
      require(container ? scalar == null : scalar != null && children.isEmpty(), "Value layout");
      return switch (type) {
        case "null" -> {
          require(children.isEmpty(), "Null children");
          yield null;
        }
        case "String" -> scalar;
        case "Boolean" -> {
          require(Set.of("true", "false").contains(scalar), "Boolean");
          yield Boolean.valueOf(scalar);
        }
        case "Character" -> {
          require(scalar.length() == 1, "Character");
          yield scalar.charAt(0);
        }
        case "Byte" -> Byte.valueOf(scalar);
        case "Short" -> Short.valueOf(scalar);
        case "Integer" -> Integer.valueOf(scalar);
        case "Long" -> Long.valueOf(scalar);
        case "Float" -> Float.valueOf(scalar);
        case "Double" -> Double.valueOf(scalar);
        case "BigInteger" -> new BigInteger(scalar);
        case "BigDecimal" -> new BigDecimal(scalar);
        case "date" -> new Date(Long.parseLong(scalar));
        case "token" -> T.valueOf(scalar);
        case "list" -> children.stream().map(Value::decode).toList();
        case "entry" -> {
          require(children.size() == 2, "Entry arity");
          yield new AbstractMap.SimpleImmutableEntry<>(children.get(0).decode(),
              children.get(1).decode());
        }
        case "element" -> {
          require(children.size() == 3, "Element arity");
          yield new T5ResultComparison.ElementKey((String) children.get(0).decode(),
              (String) children.get(1).decode(), children.get(2).decode());
        }
        case "map" -> {
          var map = new LinkedHashMap<Object, Object>();
          for (Value child : children) {
            require(child.type.equals("entry"), "Map entry");
            var entry = (Map.Entry<?, ?>) child.decode();
            require(!map.containsKey(entry.getKey()), "Duplicate map key");
            map.put(entry.getKey(), entry.getValue());
          }
          yield map;
        }
        default -> throw new IllegalArgumentException("Unknown value tag: " + type);
      };
    }
  }

  /** The caller supplies the expanded A/B/C inventory, including every slot, arm and regime. */
  public static Completeness check(Capture capture, List<Required> inventory) {
    var problems = new ArrayList<String>();
    try {
      validate(capture);
    } catch (RuntimeException e) {
      problems.add("Invalid or partial capture: " + e);
    }
    List<Item> items = capture == null || capture.items() == null ? List.of() : capture.items();
    if (capture == null || !capture.complete() || items.isEmpty()) {
      problems.add("Partial or empty capture");
    }
    if (inventory.isEmpty()) {
      problems.add("Empty required inventory");
    }
    var actual = new LinkedHashMap<Key, Item>();
    for (Item item : items) {
      if (item == null || item.key() == null) {
        continue;
      }
      actual.put(item.key(), item);
      if (!"".equals(item.failure())) {
        problems.add("Failed " + item.key() + ": " + item.failure());
      }
    }
    var keys = new java.util.HashSet<Key>();
    for (Required required : inventory) {
      if (!keys.add(required.key())) {
        problems.add("Duplicate requirement " + required.key());
      }
      var item = actual.get(required.key());
      if (item == null) {
        problems.add("Missing identity/binding/arm/regime " + required.key());
      }
      for (String binding : required.bindings()) {
        if (item == null || item.feeds() == null || !item.feeds().containsKey(binding)) {
          problems.add("Missing binding " + required.key() + ": " + binding);
        }
      }
    }
    for (Key key : actual.keySet()) {
      if (!keys.contains(key)) {
        problems.add("Unexpected item " + key);
      }
    }
    return new Completeness(problems);
  }

  private static void validate(Capture capture) {
    require(capture.version() == 1, "Unknown capture version");
    require(!capture.revisions().isEmpty() && !capture.inputHashes().isEmpty(),
        "Missing provenance");
    capture.revisions().forEach((k, v) -> {
      text(k);
      text(v);
    });
    capture.inputHashes().forEach((k, v) -> {
      text(k);
      require(v.matches("[0-9a-f]{64}"), "Input SHA-256");
    });
    var keys = new java.util.HashSet<Key>();
    for (Item item : capture.items()) {
      Key key = item.key();
      text(key.identity());
      text(key.binding());
      text(key.arm());
      Objects.requireNonNull(key.regime());
      require(keys.add(key), "Duplicate identity: " + key);
      Objects.requireNonNull(item.order());
      text(item.outcome());
      text(item.reason());
      Objects.requireNonNull(item.failure());
      Objects.requireNonNull(item.differenceReason());
      require(item.knownBaselineDifference() == !item.differenceReason().isBlank(),
          "Difference reason");
      require(Objects.equals(capture.revisions().get(key.arm()), item.cache().revision())
          && capture.revisions().containsKey(key.arm()), "Arm revision mismatch");
      text(item.cache().invalidationEvent());
      Objects.requireNonNull(item.cache().observed());
      require(item.cache().observed() == Observation.BYPASS
          ? !item.cache().bypassReason().isBlank() : item.cache().bypassReason().isEmpty(),
          "Bypass reason");
      require(key.regime() != Regime.UNCACHEABLE || item.cache().observed() == Observation.BYPASS,
          "Uncacheable observation");
      item.feeds().forEach((name, value) -> {
        text(name);
        value.decode();
      });
      item.answers().forEach(Value::decode);
      item.declaration().preparedIdentity().decode();
      item.declaration().cuts().forEach((cut, status) -> {
        text(cut);
        require(status.equals("declared-not-validated"), "Cut status");
      });
      item.declaration().refusedCuts().forEach((cut, reason) -> {
        text(cut);
        text(reason);
      });
    }
  }

  public static Capture read(Path path) throws IOException {
    try {
      var capture = JSON.readValue(Files.readAllBytes(path), Capture.class);
      validate(capture);
      require(capture.complete() && !capture.items().isEmpty(), "Partial or empty capture");
      return capture;
    } catch (RuntimeException e) {
      throw new IOException("Invalid capture", e);
    }
  }

  public static void write(Path path, Capture capture) throws IOException {
    validate(capture);
    require(capture.complete() && !capture.items().isEmpty(), "Partial or empty capture");
    publish(path, temporary -> JSON.writeValue(temporary.toFile(), capture));
  }

  @FunctionalInterface
  interface FileWriter {
    void write(Path path) throws IOException;
  }

  // No non-atomic fallback: an unsupported atomic move is a visible failure.
  static void publish(Path path, FileWriter writer) throws IOException {
    Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".t5-freeze-", ".tmp");
    try {
      writer.write(temporary);
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void text(String value) {
    require(value != null && !value.isBlank(), "Missing text");
  }

  private static void require(boolean condition, String reason) {
    if (!condition) {
      throw new IllegalArgumentException(reason);
    }
  }

  private T5FreezeCapture() {
  }
}
