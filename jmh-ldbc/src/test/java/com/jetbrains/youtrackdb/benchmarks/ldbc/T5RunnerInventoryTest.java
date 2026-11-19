package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class T5RunnerInventoryTest {
  @Rule
  public TemporaryFolder temporary = TemporaryFolder.builder().assureDeletion().build();

  private T5RunnerFeeds feeds() throws Exception {
    Path curated = temporary.newFile().toPath();
    Files.writeString(curated, """
        {
          "isPersonIds":[101,102,103], "isMessageIds":[201,202],
          "ic1":[{"personId":111,"firstName":"one"}],
          "ic2":[{"personId":301,"maxDate":444}],
          "ic3":[{"personId":331,"countryX":"country-three","countryY":"unused","startDate":333}],
          "ic4":[{"personId":441,"startDate":4444}],
          "ic5PersonIds":[551], "dates":[5555],
          "ic6":[{"personId":661,"tagName":"unused"}],
          "ic7PersonIds":[771], "ic8PersonIds":[881],
          "ic9":[{"personId":901,"maxDate":999}],
          "ic10PersonIds":[1011], "ic11PersonIds":[1111], "ic11CountryNames":["country-eleven"],
          "ic12":[{"personId":1211,"tagClassName":"unused"}],
          "ic13PersonIds1":[1311], "ic13PersonIds2":[1312]
        }
        """);
    Path factors = temporary.newFile().toPath();
    Files.writeString(factors, "{\"personFriendCounts\":[[9999,7]]}");
    return new T5RunnerFeeds(curated, factors, new T5RunnerFeeds.Lookup() {
      public boolean exists(String label, long id) {
        return label.equals("Person") ? id == 102 || id == 103 : id == 202;
      }

      public String place(long person) {
        return person == 103 ? "resolved-place" : null;
      }

      public long[] dates(long person) {
        return person == 901 ? new long[] {10, 73} : null;
      }
    });
  }

  @Test
  public void literalInputsKeepCanonicalArraysResolvedPoolsAndDerivedSlotsDistinct()
      throws Exception {
    var feeds = feeds();
    Map<String, Object> expected = Map.ofEntries(
        Map.entry("arm.personId", 103L), Map.entry("arm.personRid", 103L),
        Map.entry("arm.messageId", 202L), Map.entry("arm.placeName", "resolved-place"),
        Map.entry("arm.ic2PersonId", 901L), Map.entry("arm.ic9PersonId", 901L),
        Map.entry("arm.ic2MaxDate", new Date(12)), Map.entry("arm.ic9MaxDate", new Date(12)),
        Map.entry("state.ic1PersonId", 111L), Map.entry("state.ic1FirstName", "one"),
        Map.entry("state.ic2PersonId", 301L), Map.entry("state.ic2MaxDate", new Date(444)),
        Map.entry("state.ic3PersonId", 331L), Map.entry("state.ic3CountryX", "country-three"),
        Map.entry("state.ic3StartDate", new Date(333)),
        Map.entry("state.ic4PersonId", 441L), Map.entry("state.ic4StartDate", new Date(4444)),
        Map.entry("state.ic5PersonId", 551L), Map.entry("state.ic5Date", new Date(5555)),
        Map.entry("state.ic6PersonId", 661L), Map.entry("state.ic7PersonId", 771L),
        Map.entry("state.ic8PersonId", 881L), Map.entry("state.ic10PersonId", 1011L),
        Map.entry("state.ic11PersonId", 1111L),
        Map.entry("state.ic11CountryName", "country-eleven"),
        Map.entry("state.ic12PersonId", 1211L), Map.entry("state.ic13Person1Id", 1311L),
        Map.entry("state.ic13Person2Id", 1312L));
    expected.forEach((name, value) -> assertEquals(name, value, feeds.value(name, 1)));
    assertEquals(102L, feeds.value("arm.personId", 0));
    assertEquals(new Date(74), feeds.value("arm.ic9MaxDate", 63));
    assertThrows(IllegalArgumentException.class, () -> feeds.value("state.noSuchFeed", 0));
    assertThrows(IllegalArgumentException.class, () -> feeds.value("arm.personId", 64));
    var form = T5BenchmarkCatalogue.loadLdbc().stream()
        .filter(f -> f.identity().equals("gremlin_ic2_friendsMessagesOrdered")).findFirst()
        .orElseThrow();
    assertEquals(Map.of("state.ic2PersonId", T5FreezeCapture.Value.of(301L),
        "state.ic2MaxDate", T5FreezeCapture.Value.of(new Date(444))), feeds.feeds(form, 0));
    assertThrows(IllegalArgumentException.class,
        () -> T5CatalogueWorkload.cases((f, slot) -> Map.of("wrong-feed",
            T5FreezeCapture.Value.of(1L))));
  }

  @Test
  public void fullInventoryMatchesLiteralMembershipAndAllBindingArmRegimeProducts()
      throws Exception {
    var feeds = feeds();
    var cases = T5CatalogueWorkload.cases(feeds::feeds);
    Set<String> expectedA = new HashSet<>();
    // Each graph has this independently pinned 122-member population.
    String members = """
        values/q0 selectBy/q2 values/q8 selectBy/q10 fold/q14 limit/q15 valueMap/q17
        selectBy/q18 groupCount/q20 valueMap/q25 selectBy/q26 fold/q30 values/q32
        selectBy/q34 fold/q38 values/q40 valueMap/q41 selectBy/q42 groupCount/q44
        orderLimit/q45 selectBy/q50 limit/q55 values/q56 selectBy/q58 groupCount/q60
        fold/q62 valueMap/q65 selectBy/q66 groupCount/q68 fold/q70 selectBy/q74
        count/q75 values/q80 selectBy/q82 orderLimit/q85 fold/q86 valueMap/q89
        selectBy/q90 groupCount/q92 selectBy/q98 groupCount/q100 values/q104
        valueMap/q105 selectBy/q106 fold/q110 valueMap/q113 selectBy/q114
        count/q115 groupCount/q116
        """;
    // The grammar repeats every 120 ids. Its last block ends at q298.
    for (String graph : List.of("n20000-skewed", "n20000-uniform", "n200000-skewed")) {
      for (int offset : List.of(0, 120, 240)) {
        for (String member : members.strip().split("\\s+")) {
          int split = member.indexOf("/q");
          int id = Integer.parseInt(member.substring(split + 2)) + offset;
          if (id <= 298) {
            expectedA.add("A/" + graph + "/" + member.substring(0, split) + "/q" + id);
          }
        }
      }
    }
    Set<String> expectedB = new HashSet<>();
    for (String variant : List.of("indexed", "no-index")) {
      for (String member : """
          5-hub-hit 5-rare-late-hit 5-slug-miss 5-target-miss CONTROL-warm-native
          H-email-miss H-hub-hit H-rare-late-hit H-unverified H-user-miss U1-rank-desc
          U2-user-0 U2-user-997 U2-user-998 U2-user-999 LONG-late-seeded-fold
          """.strip().split("\\s+")) {
        expectedB.add("B/" + variant + "/" + member);
      }
    }
    Set<String> expectedC = new HashSet<>();
    for (String member : """
        gremlin_bulkingWhereDuplicate gremlin_bulkingWhereOneRow gremlin_bulkingWhereUnique
        gremlin_bulkingDuplicate gremlin_bulkingOneRow gremlin_bulkingUnique
        gremlin_bulkingMostlyUnique gremlin_knowsFirstNames gremlin_knowsFirstNameCount
        gremlin_knowsFirstNamesFolded gremlin_is1_personCityProfile gremlin_is2_personMessages
        gremlin_is3_friendsWithNames gremlin_is4_messageContent gremlin_is5_messageCreator
        gremlin_is6_forumOfPost gremlin_is7_repliesWithAuthors gremlin_ic1_friendsWithName
        gremlin_ic2_friendsMessagesOrdered gremlin_ic2_friendsMessagesOrderedLimit
        gremlin_ic3_friendsMessagesInCountry gremlin_ic4_friendPostTags
        gremlin_ic5_friendPostForums gremlin_ic6_friendPostTagCounts gremlin_ic7_likers
        gremlin_ic8_recentRepliesOrdered gremlin_ic9_friendsMessagesOrdered
        gremlin_ic10_friendsOfFriendsInCity gremlin_ic11_friendsCompaniesInCountry
        gremlin_ic12_friendCommentPostTags gremlin_ic13_directKnowsCount gremlin_twoHopKnows
        gremlin_knowsFilteredByFriendFirstName gremlin_threeHopKnowsExcludingIntermediate
        gremlin_knowsGroupCountByLastName gremlin_friendsNotLocatedInPlace
        gremlin_mutualFriendTriangle gremlin_knowsOrderedPage gremlin_vertexByRid
        gremlin_is3_friendsWithDates gremlin_is4_coalesceMessageContentDeclines
        gremlin_is7_optionalFriendOfCreatorDeclines gremlin_ic1_repeatKnowsToThreeHopsDeclines
        G7/recentFriendMessages G7/distinctForumsWithModerator G7/tagCounts
        G7/mutualFriendCycle G7/fullIc5 G7/distinctForumCount G7/knownEndVertex
        """.strip().split("\\s+")) {
      expectedC.add("C/" + member);
    }
    assertEquals(366, expectedA.size());
    assertEquals(32, expectedB.size());
    assertEquals(50, expectedC.size());
    for (var expected : List.of(expectedA, expectedB, expectedC)) {
      String prefix = expected.iterator().next().substring(0, 2);
      assertEquals(expected, cases.stream().filter(c -> c.identity().startsWith(prefix))
          .map(T5BaselineRunner.Case::identity).collect(java.util.stream.Collectors.toSet()));
    }
    assertEquals(5062, cases.size());
    for (var c : cases) {
      assertTrue(c.identity().startsWith("B/") ? c.binding().equals("archive")
          : Integer.parseInt(c.binding()) >= 0
              && Integer.parseInt(c.binding()) < (c.identity().startsWith("A/") ? 5 : 64));
    }
    var request = new T5BaselineRunner.Request(T5CatalogueWorkload.class.getName(), cases,
        Map.of(), T5BaselineRunnerTest.REVISION);
    var inventory = T5BaselineRunner.inventory(request);
    assertEquals(30372, inventory.size());
    assertEquals(30372, inventory.stream().map(T5FreezeCapture.Required::key).distinct().count());
    assertEquals(Set.of("native", "baseline"), inventory.stream().map(r -> r.key().arm())
        .collect(java.util.stream.Collectors.toSet()));
    assertEquals(Set.of(T5FreezeCapture.Regime.values()), inventory.stream()
        .map(r -> r.key().regime()).collect(java.util.stream.Collectors.toSet()));
    var missing = new ArrayList<>(cases);
    missing.removeFirst();
    assertThrows(IllegalArgumentException.class, () -> T5CatalogueWorkload.validate(missing));
    var noFeed = new ArrayList<>(cases);
    var c = noFeed.getLast();
    noFeed.set(noFeed.size() - 1, new T5BaselineRunner.Case(c.identity(), c.binding(), Map.of(),
        c.order(), c.stepLabelFixDifference()));
    assertThrows(IllegalArgumentException.class, () -> T5CatalogueWorkload.validate(noFeed));
    // Independent literal q ids pin every positive and negative A decision in every graph.
    Set<Integer> nativeA = java.util.Arrays.stream("""
        0 2 8 10 14 15 20 25 26 30 32 38 40 44 45 50 55 56 60 62 65 68 70 74 75
        80 85 86 90 92 98 100 104 105 110 115 116 120 122 128 130 134 135 140 145
        146 150 152 158 160 164 165 170 175 176 180 182 185 188 190 194 195 200
        205 206 210 212 218 220 224 225 230 235 236 240 242 248 250 254 255 260
        265 266 270 272 278 280 284 285 290 295 296
        """.strip().split("\\s+")).map(Integer::valueOf)
        .collect(java.util.stream.Collectors.toSet());
    Set<String> wholeNative = new HashSet<>();
    for (String identity : expectedA) {
      if (nativeA.contains(Integer.parseInt(identity.substring(identity.lastIndexOf("/q") + 2)))) {
        wholeNative.add(identity);
      }
    }
    for (String variant : List.of("indexed", "no-index")) {
      for (String member : """
          5-hub-hit 5-rare-late-hit 5-slug-miss 5-target-miss
          H-email-miss H-hub-hit H-rare-late-hit H-unverified H-user-miss
          U2-user-0 U2-user-997 U2-user-998 U2-user-999 LONG-late-seeded-fold
          """.strip().split("\\s+")) {
        wholeNative.add("B/" + variant + "/" + member);
      }
    }
    wholeNative.add("C/gremlin_vertexByRid");
    assertEquals(305, wholeNative.size());
    try (var workload = new T5CatalogueWorkload()) {
      for (var query : cases) {
        assertEquals(query.identity(), wholeNative.contains(query.identity()),
            workload.wholeNative(query));
      }
    }
  }
}
