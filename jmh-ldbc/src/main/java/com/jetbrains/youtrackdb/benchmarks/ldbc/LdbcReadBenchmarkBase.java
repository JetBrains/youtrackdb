package com.jetbrains.youtrackdb.benchmarks.ldbc;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Abstract base class containing all LDBC SNB Interactive read query benchmarks
 * using YouTrackDB SQL (MATCH queries).
 * Subclasses specify the thread count via @Threads annotation.
 *
 * <p>Queries:
 * <ul>
 *   <li>IS1-IS7: Interactive Short queries (single-hop lookups)</li>
 *   <li>IC1-IC13: Interactive Complex queries (multi-hop analytical traversals)</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(value = 1, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g",
    "-Dyoutrackdb.storage.diskCache.bufferSize=4096",
    "-Dyoutrackdb.memory.directMemory.trackMode=false",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
    "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
})
public abstract class LdbcReadBenchmarkBase {

  private static final int LIMIT = 20;

  // ==================== SHORT QUERIES (IS1-IS7) ====================

  /**
   * IS1: Profile of a person.
   * Given a Person, retrieve their profile (name, birthday, IP, browser, city).
   */
  @Benchmark
  public List<Map<String, Object>> is1_personProfile(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS1,
        "personId", state.personId(i));
  }

  /**
   * IS2: Recent messages of a person.
   * Given a Person, retrieve their last 10 messages with original post info.
   */
  @Benchmark
  public List<Map<String, Object>> is2_personPosts(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS2,
        "personId", state.personId(i),
        "limit", LIMIT);
  }

  /**
   * IS3: Friends of a person.
   * Given a Person, retrieve all friends with friendship creation dates.
   */
  @Benchmark
  public List<Map<String, Object>> is3_personFriends(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS3,
        "personId", state.personId(i));
  }

  /**
   * IS4: Content of a message.
   * Given a Message (Post/Comment), retrieve its content and creation date.
   */
  @Benchmark
  public List<Map<String, Object>> is4_messageContent(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS4,
        "messageId", state.messageId(i));
  }

  /**
   * IS5: Creator of a message.
   * Given a Message, retrieve its author.
   */
  @Benchmark
  public List<Map<String, Object>> is5_messageCreator(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS5,
        "messageId", state.messageId(i));
  }

  /**
   * IS6: Forum of a message.
   * Given a Message, retrieve the containing Forum and its moderator.
   */
  @Benchmark
  public List<Map<String, Object>> is6_messageForum(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS6,
        "messageId", state.messageId(i));
  }

  /**
   * IS7: Replies of a message.
   * Given a Message, retrieve 1-hop reply Comments with author-knows-author flag.
   */
  @Benchmark
  public List<Map<String, Object>> is7_messageReplies(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS7,
        "messageId", state.messageId(i));
  }

  // ==================== COMPLEX QUERIES (IC1-IC13) ====================

  /**
   * IC1: Transitive friends with a certain name.
   * Find Persons with a given first name connected within 3 hops via KNOWS.
   */
  @Benchmark
  public List<Map<String, Object>> ic1_transitiveFriends(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC1,
        "personId", state.personId(i),
        "firstName", state.firstName(i),
        "limit", LIMIT);
  }

  /**
   * IC2: Recent messages by friends.
   * Find most recent Messages from a Person's friends before a given date.
   */
  @Benchmark
  public List<Map<String, Object>> ic2_recentFriendMessages(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC2,
        "personId", state.personId(i),
        "maxDate", state.maxDate(i),
        "limit", LIMIT);
  }

  /**
   * IC3: Friends in countries.
   * Find friends/friends-of-friends who posted in both given countries
   * within a time period.
   */
  @Benchmark
  public List<Map<String, Object>> ic3_friendsInCountries(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    Date startDate = state.maxDate(i);
    Date endDate = new Date(startDate.getTime() + 30L * 24 * 60 * 60 * 1000);

    return state.executeSql(LdbcQuerySql.IC3,
        "personId", state.personId(i),
        "countryX", state.countryName(i),
        "countryY", state.countryName2(i),
        "startDate", startDate,
        "endDate", endDate,
        "limit", LIMIT);
  }

  /**
   * IC4: New topics.
   * Find Tags attached to friends' Posts in a time interval that were never
   * used before.
   */
  @Benchmark
  public List<Map<String, Object>> ic4_newTopics(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    Date startDate = state.maxDate(i);
    Date endDate = new Date(startDate.getTime() + 30L * 24 * 60 * 60 * 1000);

    return state.executeSql(LdbcQuerySql.IC4,
        "personId", state.personId(i),
        "startDate", startDate,
        "endDate", endDate,
        "limit", LIMIT);
  }

  /**
   * IC5: New groups.
   * Find Forums joined by friends/friends-of-friends after a given date,
   * counting Posts by those friends.
   */
  @Benchmark
  public List<Map<String, Object>> ic5_newGroups(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC5,
        "personId", state.personId(i),
        "minDate", state.maxDate(i),
        "limit", LIMIT);
  }

  /**
   * IC6: Tag co-occurrence.
   * Find Tags that co-occur with a given Tag on friends' Posts.
   */
  @Benchmark
  public List<Map<String, Object>> ic6_tagCoOccurrence(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC6,
        "personId", state.personId(i),
        "tagName", state.tagName(i),
        "limit", LIMIT);
  }

  /**
   * IC7: Recent likers.
   * Find most recent likes on a Person's Messages.
   */
  @Benchmark
  public List<Map<String, Object>> ic7_recentLikers(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC7,
        "personId", state.personId(i),
        "limit", LIMIT);
  }

  /**
   * IC8: Recent replies.
   * Find most recent Comments replying to a Person's Messages.
   */
  @Benchmark
  public List<Map<String, Object>> ic8_recentReplies(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC8,
        "personId", state.personId(i),
        "limit", LIMIT);
  }

  /**
   * IC9: Recent messages by friends or friends of friends.
   * Find most recent Messages created by friends/FoF before a given date.
   */
  @Benchmark
  public List<Map<String, Object>> ic9_recentFofMessages(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC9,
        "personId", state.personId(i),
        "maxDate", state.maxDate(i),
        "limit", LIMIT);
  }

  /**
   * IC10: Friend recommendation.
   * Find friends-of-friends born in a date range, score by common interests.
   */
  @Benchmark
  public List<Map<String, Object>> ic10_friendRecommendation(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    int startMonth = 6;
    String startMd = String.format("%02d21", startMonth);
    String endMd = String.format("%02d22", startMonth + 1);

    return state.executeSql(LdbcQuerySql.IC10,
        "personId", state.personId(i),
        "startMd", startMd,
        "endMd", endMd,
        "wrap", false,
        "limit", LIMIT);
  }

  /**
   * IC11: Job referral.
   * Find friends/FoF who started working in a Company in a given Country
   * before a given year.
   */
  @Benchmark
  public List<Map<String, Object>> ic11_jobReferral(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC11,
        "personId", state.personId(i),
        "countryName", state.countryName(i),
        "workFromYear", 2010,
        "limit", LIMIT);
  }

  /**
   * IC12: Expert search.
   * Find friends' Comments replying to Posts with Tags in a given TagClass
   * hierarchy.
   */
  @Benchmark
  public List<Map<String, Object>> ic12_expertSearch(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC12,
        "personId", state.personId(i),
        "tagClassName", state.tagClassName(i),
        "limit", LIMIT);
  }

  /**
   * IC13: Single shortest path.
   * Find the shortest path between two Persons via KNOWS edges.
   */
  @Benchmark
  public List<Map<String, Object>> ic13_shortestPath(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC13,
        "person1Id", state.personId(i),
        "person2Id", state.personId2(i));
  }
}
