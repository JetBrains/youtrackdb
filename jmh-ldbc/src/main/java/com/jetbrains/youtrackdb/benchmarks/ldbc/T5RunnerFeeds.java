package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical arrays and resolved graph pools have separate slot spaces. Never curates parameters. */
final class T5RunnerFeeds {
  interface Lookup {
    boolean exists(String label, long id);

    String place(long person);

    long[] dates(long person);
  }

  private final JsonNode canonical;
  private final List<Long> persons = new ArrayList<>();
  private final List<Long> messages = new ArrayList<>();
  private final List<String> places = new ArrayList<>();
  private final long seed;
  private final long[] range;

  T5RunnerFeeds(Path curated, Path factors, Lookup lookup) throws Exception {
    canonical = T5BaselineRunner.JSON.readTree(curated.toFile());
    // Parse the factor input too. It is provenance, not a replacement parameter source.
    if (!T5BaselineRunner.JSON.readTree(factors.toFile()).isObject()) {
      throw new IllegalArgumentException("Invalid factor tables");
    }
    for (int i = 0; i < 64; i++) {
      long person = number(at("isPersonIds", i));
      if (lookup.exists("Person", person)) {
        persons.add(person);
      }
      String place = lookup.place(person);
      if (place != null) {
        places.add(place);
      }
      long message = number(at("isMessageIds", i));
      if (lookup.exists("Message", message)) {
        messages.add(message);
      }
    }
    if (persons.isEmpty() || messages.isEmpty()) {
      throw new IllegalArgumentException("Empty resolved Person or Message pool");
    }
    if (places.isEmpty()) {
      places.add("");
    }
    long selected = 0;
    long[] dates = null;
    for (int i = 0; i < 64; i++) {
      long person = number(at(i < 32 ? "ic2" : "ic9", i % 32).required("personId"));
      dates = lookup.dates(person);
      if (dates != null) {
        selected = person;
        break;
      }
    }
    if (dates == null || dates.length != 2 || dates[1] < dates[0]) {
      throw new IllegalArgumentException("Missing IC2/IC9 friend-message range");
    }
    seed = selected;
    range = dates.clone();
  }

  private JsonNode at(String name, int slot) {
    var array = canonical.required(name);
    if (!array.isArray() || array.isEmpty()) {
      throw new IllegalArgumentException("Missing canonical array " + name);
    }
    return array.get(slot % array.size());
  }

  private static long number(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new IllegalArgumentException("Expected long feed");
    }
    return node.longValue();
  }

  Object value(String name, int slot) {
    if (slot < 0 || slot >= 64) {
      throw new IllegalArgumentException("Invalid resolved slot");
    }
    return switch (name) {
      case "arm.personId", "arm.personRid" -> persons.get(slot % persons.size());
      case "arm.messageId" -> messages.get(slot % messages.size());
      case "arm.placeName" -> places.get(slot % places.size());
      case "arm.ic2PersonId", "arm.ic9PersonId" -> seed;
      case "arm.ic2MaxDate", "arm.ic9MaxDate" ->
          T5BenchmarkCatalogue.derivedDate(range[0], range[1], slot);
      case "state.ic5Date" -> new Date(number(at("dates", slot)));
      case "state.ic11CountryName" -> text(at("ic11CountryNames", slot));
      case "state.ic13Person1Id" -> number(at("ic13PersonIds1", slot));
      case "state.ic13Person2Id" -> number(at("ic13PersonIds2", slot));
      default -> canonicalValue(name, slot);
    };
  }

  private Object canonicalValue(String name, int slot) {
    if (!name.matches(
        "state.ic(1|2|3|4|5|6|7|8|10|11|12)(PersonId|FirstName|MaxDate|CountryX|StartDate)")) {
      throw new IllegalArgumentException("Unknown feed " + name);
    }
    String field = name.substring(6);
    int end = 2;
    while (Character.isDigit(field.charAt(end))) {
      end++;
    }
    String query = field.substring(0, end);
    String attribute = Character.toLowerCase(field.charAt(end)) + field.substring(end + 1);
    JsonNode node = List.of("ic5", "ic7", "ic8", "ic10", "ic11").contains(query)
        ? at(query + "PersonIds", slot) : at(query, slot).required(attribute);
    return attribute.endsWith("Date") ? new Date(number(node))
        : attribute.equals("personId") ? number(node) : text(node);
  }

  private static String text(JsonNode node) {
    if (!node.isTextual()) {
      throw new IllegalArgumentException("Expected string feed");
    }
    return node.textValue();
  }

  Map<String, T5FreezeCapture.Value> feeds(T5BenchmarkCatalogue.LdbcForm form, int slot) {
    var result = new LinkedHashMap<String, T5FreezeCapture.Value>();
    for (String argument : form.arguments()) {
      String name = argument.split("\\+")[0];
      result.put(name, T5FreezeCapture.Value.of(value(name, slot)));
    }
    return Map.copyOf(result);
  }
}
