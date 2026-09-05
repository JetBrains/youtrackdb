package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Named Gremlin traversal shapes measured by {@link LdbcGremlinTranslatorBenchmark} on the LDBC
 * schema, plus engagement checks ({@link #requireTranslated}, {@link #requireNotTranslated}) that
 * pin whether a shape compiles through the translator.
 *
 * <p>TODO(Track 07 / PF7): add unchanged-body JMH baseline shapes (translator off / native twin)
 * so head-vs-base comparisons stay like-for-like when a translating shape's body changes. This
 * file only owns the shapes the harness already measures.
 *
 * <p>The shapes are named methods rather than inline {@code @Benchmark} expressions so the harness
 * and {@link LdbcGremlinShapeTranslationTest} assert over byte-identical traversals.
 *
 * <h2>How the numbers are read in CI</h2>
 *
 * <p>The {@code ldbc-jmh-compare} workflow compares <b>head against the fork-point with
 * {@code develop}</b>, with {@code translatorEnabled=true} on both sides — the production Gremlin
 * path. A PR delta on a translating shape therefore means "this branch changed MATCH-plan
 * throughput vs {@code develop}", the same framing as the SQL IC/IS benchmarks beside it. The
 * workflow passes {@code -p translatorEnabled=true}; local runs should do the same when reproducing
 * a PR comment.
 *
 * <h2>Optional axis: translator on vs off (same commit)</h2>
 *
 * <p>{@link LdbcGremlinTranslatorBenchmark} still exposes {@code translatorEnabled} as a JMH
 * {@code @Param} for a secondary A/B: MATCH against the native pipeline on one tree. Run both arms
 * with {@code -Djmh.args=".*gremlin_.*"} and no {@code -p} filter, or pass {@code
 * --gremlin-arms both} to {@code jmh-compare.py}. That axis is for recogniser and kill-switch work,
 * not for the default PR regression comment.
 *
 * <h2>Two shape groups</h2>
 *
 * <p><b>Translating shapes</b> carry one {@link AbstractMatchPlanStep} with the kill-switch on. In
 * CI (translator on both sides) their head-vs-base delta is a regression on the MATCH pipeline. In
 * the optional on/off A/B, the same shape's delta is MATCH vs native on one commit.
 *
 * <p><b>Declining shapes</b> carry no boundary step even with the kill-switch on — the translator
 * walks and declines, or vetoes before the walk ({@code RepeatDeclineStrategy}). Both CI arms
 * therefore run native; head-vs-base measures native-pipeline or decline-overhead changes, not MATCH
 * plan improvements. {@link #requireNotTranslated} on both arms in
 * {@link LdbcGremlinShapeTranslationTest} is the tripwire when a recogniser starts claiming the
 * shape.
 *
 * <h2>Relation to the SQL IC / IS benchmarks</h2>
 *
 * <p><b>Throughput is not comparable across SQL and Gremlin rows</b> — different entry points.
 * Every builder's Javadoc starts with {@code LDBC:}: {@code complete} for a full IC/IS twin,
 * {@code <query> reduced}, or {@code none} for a translator primitive that does not echo a
 * workload query.
 *
 * <p>Reduced shapes include every SQL column and clause the translator accepts today — the same
 * rule as {@link #is5MessageCreator}. One {@code select} label still takes one property
 * {@code by(...)} modulator; several columns from one vertex use several {@code as(...)} labels on
 * that step (see {@link #is1PersonCityProfile}). Gap lists name only what still declines:
 * <ul>
 *   <li>{@code [not-yet-translatable]} — MATCH plan blocked until a recogniser exists (name the
 *       gap): typically {@code repeat}/{@code while}, {@code optional}, {@code coalesce}, or
 *       edge-{@code as} property projection
 *   <li>{@code [depends-on-above]} — blocked until another listed gap lands first
 * </ul>
 *
 * <p>Three of the twenty-one queries in {@code ldbc-queries/} use {@code LET}; most of the rest are
 * plain MATCH. The declining twins ({@link #is3FriendsWithDates}, {@link #repeatKnowsToThreeHops},
 * {@link #coalesceMessageContent}, {@link #optionalFriendOfCreator}) are tripwires for those
 * {@code [not-yet-translatable]} gaps. {@link #is1PersonCityProfile}, {@link #is5MessageCreator},
 * and {@link #is4MessageContent} are complete / near-complete twins (IS4 drops only coalesce).
 */
public final class GremlinTraversalShapes {

  /** Vertex class the LDBC schema gives the {@code id} and {@code firstName} properties. */
  public static final String PERSON_LABEL = "Person";

  /** Edge label the LDBC schema uses for the friendship graph. */
  public static final String KNOWS_LABEL = "KNOWS";

  /** Vertex superclass of {@code Post} and {@code Comment}; the IS queries start from it. */
  public static final String MESSAGE_LABEL = "Message";

  /** Edge label from a {@code Message} to its authoring {@code Person}. */
  public static final String HAS_CREATOR_LABEL = "HAS_CREATOR";

  /** Edge label from a {@code Person} to the {@code Place} they live in. */
  public static final String IS_LOCATED_IN_LABEL = "IS_LOCATED_IN";

  /** Vertex class the LDBC schema gives the {@code name} property the anti-join shape filters on. */
  public static final String PLACE_LABEL = "Place";

  /** Vertex class that contains posts; IS6 walks from a Post to its Forum. */
  public static final String FORUM_LABEL = "Forum";

  /** Vertex class of a reply; IC8 filters the inbound {@code REPLY_OF} hop to it. */
  public static final String COMMENT_LABEL = "Comment";

  /**
   * Vertex class of an employer. The LDBC loader inserts organisations into this class (with {@code
   * type = 'company'}), not into the empty {@code Company} subclass.
   */
  public static final String ORGANISATION_LABEL = "Organisation";

  /** Edge label from a {@code Comment} to the {@code Message} it replies to. */
  public static final String REPLY_OF_LABEL = "REPLY_OF";

  /** Edge label from a {@code Forum} to a {@code Message} it contains. */
  public static final String CONTAINER_OF_LABEL = "CONTAINER_OF";

  /** Edge label from a {@code Forum} to its moderating {@code Person}. */
  public static final String HAS_MODERATOR_LABEL = "HAS_MODERATOR";

  /** Edge label from a {@code Person} to a {@code Message} they liked. */
  public static final String LIKES_LABEL = "LIKES";

  /** Edge label from a {@code Person} to an {@code Organisation} they work at. */
  public static final String WORK_AT_LABEL = "WORK_AT";

  /** Vertex class of a {@code Post} (Message subclass). */
  public static final String POST_LABEL = "Post";

  /** Vertex class of an LDBC tag. */
  public static final String TAG_LABEL = "Tag";

  /** Edge label from a {@code Message}/{@code Forum} to a {@code Tag}. */
  public static final String HAS_TAG_LABEL = "HAS_TAG";

  /**
   * How many rows {@code .limit(...)} keeps after {@code ORDER BY}. Same value as the {@code :limit}
   * parameter in the SQL IC/IS JMH queries ({@code 20}), so Gremlin and SQL top-N sizes match.
   */
  public static final int RESULT_LIMIT = 20;

  private GremlinTraversalShapes() {
  }

  // ---------------------------------------------------------------------------------------------
  // Translating shapes: boundary step with kill-switch on. CI delta = head vs base (translator on).
  // Optional on/off A/B on one commit = MATCH vs native.
  // ---------------------------------------------------------------------------------------------

  /**
   * LDBC: none. Bare {@code g.V(rid)} point-lookup — a DECLINING translator primitive, not an
   * IC/IS query.
   *
   * <p>Held apart from the other walk shapes because it is the only one where the native path issues
   * no query: TinkerPop resolves the id straight to a record. Translating it would compile an
   * uncached MATCH plan every call ({@code cacheEligible=false}) for no join to optimise, so the
   * translator declines the bare lookup and both arms run natively. The RID has to be resolved from
   * an LDBC {@code id} long before the call, which is why the benchmark state builds a RID pool at
   * trial setup.
   */
  public static YTDBGraphTraversal<Vertex, Vertex> personByRid(
      YTDBGraphTraversalSource g, Object rid) {
    return g.V(rid);
  }

  /**
   * LDBC: none. Translator primitive: one-hop {@code KNOWS} under {@code values}.
   *
   * <p>This is the witness shape for the kill-switch installation check, because every step in it
   * ({@code V}, {@code hasLabel}, {@code has}, {@code out}, {@code values}) has been in the
   * recognised set since well before the terminators, so a missing boundary step on the on-arm
   * means the flag flip did not reach the traversal rather than that the shape declined.
   */
  public static YTDBGraphTraversal<Vertex, String> knowsFirstNames(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .values("firstName");
  }

  /** LDBC: none. Translator primitive: the {@link #knowsFirstNames} walk under {@code count()}. */
  public static YTDBGraphTraversal<Vertex, Long> knowsFirstNameCount(
      YTDBGraphTraversalSource g, long personId) {
    return knowsFirstNames(g, personId).count();
  }

  /**
   * LDBC: none. Translator primitive: the {@link #knowsFirstNames} walk under {@code fold()}.
   *
   * <p>The list-shaping terminator the boundary step drains through a {@code ListShapingOp}, and
   * the newest recogniser this harness covers. Its assertion doubles as a classpath check: a
   * {@code youtrackdb-core} without the {@code FoldStep} registry entry declines the shape, both
   * arms measure the native path, and the two numbers coincide instead of failing loudly.
   */
  public static YTDBGraphTraversal<Vertex, List<String>> knowsFirstNamesFolded(
      YTDBGraphTraversalSource g, long personId) {
    return knowsFirstNames(g, personId).fold();
  }

  /**
   * LDBC: IS1 complete — every SQL RETURN column via multi-{@code as} on the person step plus
   * {@code cityId} on the city.
   *
   * <p>SQL IS1: person profile fields + {@code city.id}. One {@code by} per {@code select} label;
   * several person columns are several labels on the same hop.
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> is1PersonCityProfile(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .as(
            "firstName",
            "lastName",
            "birthday",
            "locationIP",
            "browserUsed",
            "gender",
            "creationDate")
        .out(IS_LOCATED_IN_LABEL).as("cityId")
        .select(
            "firstName",
            "lastName",
            "birthday",
            "locationIP",
            "browserUsed",
            "gender",
            "creationDate",
            "cityId")
        .by("firstName")
        .by("lastName")
        .by("birthday")
        .by("locationIP")
        .by("browserUsed")
        .by("gender")
        .by("creationDate")
        .by("id");
  }

  /**
   * LDBC: IS2 reduced — person's messages, SQL {@code ORDER BY creationDate DESC}, top
   * {@link #RESULT_LIMIT}.
   *
   * <p>Kept: {@code in(HAS_CREATOR)}; message {@code valueMap(id, content, creationDate)}; same
   * sort + limit as SQL (date only).
   *
   * <p>Gaps vs SQL IS2:
   * <ul>
   *   <li>[not-yet-translatable] {@code REPLY_OF} climb to original post / author — {@code while}/{@code repeat}
   *       ({@code RepeatDeclineStrategy})
   *   <li>[not-yet-translatable] {@code coalesce(imageFile, content)} — {@code CoalesceStep}; shape uses plain
   *       {@code content} ({@link #coalesceMessageContent})
   *   <li>[depends-on-above] original-post / original-author RETURN columns — need the climb above
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Object>> is2PersonMessages(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .in(HAS_CREATOR_LABEL)
        .hasLabel(MESSAGE_LABEL)
        .order().by("creationDate", Order.desc)
        .limit(RESULT_LIMIT)
        .valueMap("id", "content", "creationDate");
  }

  /**
   * LDBC: IS3 reduced — friends, {@code ORDER BY firstName}, three friend columns.
   *
   * <p>Kept: {@code outE(KNOWS).inV()}; friend {@code valueMap(id, firstName, lastName)}; sort on
   * friend {@code firstName} (not SQL's friendship date).
   *
   * <p>Gaps vs SQL IS3:
   * <ul>
   *   <li>[not-yet-translatable] friendship {@code creationDate} — edge-{@code as} property
   *       projection; declining twin {@link #is3FriendsWithDates}
   *   <li>[depends-on-above] SQL sort (friendship date, {@code personId}) — needs the edge date;
   *       foreign-alias {@code order().by(select(...))} already translates once that column exists
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Object>> is3FriendsWithNames(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .outE(KNOWS_LABEL)
        .inV()
        .order().by("firstName")
        .valueMap("id", "firstName", "lastName");
  }

  /**
   * LDBC: IS4 reduced — message {@code content} + {@code creationDate} (plain content, no coalesce).
   *
   * <p>Gaps vs SQL IS4:
   * <ul>
   *   <li>[not-yet-translatable] {@code coalesce(imageFile, content)} — {@link #coalesceMessageContent}
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Object>> is4MessageContent(
      YTDBGraphTraversalSource g, long messageId) {
    return g.V()
        .hasLabel(MESSAGE_LABEL)
        .has("id", messageId)
        .valueMap("content", "creationDate");
  }

  /**
   * LDBC: IS5 complete. Message author {@code id}/{@code firstName}/{@code lastName}; every SQL
   * column, no dropped clause.
   *
   * <p>IS5 is {@code MATCH {class: Message, as: m, where: (id = :messageId)}.out('HAS_CREATOR'){as:
   * author} RETURN author.id, author.firstName, author.lastName}. Every RETURN column comes from the
   * boundary alias, so {@code valueMap} covers the projection and no {@code as(...)} label is needed.
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Object>> is5MessageCreator(
      YTDBGraphTraversalSource g, long messageId) {
    return g.V()
        .hasLabel(MESSAGE_LABEL)
        .has("id", messageId)
        .out(HAS_CREATOR_LABEL)
        .valueMap("id", "firstName", "lastName");
  }

  /**
   * LDBC: IS6 reduced — forum + moderator from a Post (not from an arbitrary Message).
   *
   * <p>Kept: {@code in(CONTAINER_OF)} → forum; {@code out(HAS_MODERATOR)}; every SQL RETURN column
   * via multi-{@code as} ({@code forumId}/{@code forumTitle}, moderator id/name fields).
   *
   * <p>Gaps vs SQL IS6:
   * <ul>
   *   <li>[not-yet-translatable] start at Message + {@code REPLY_OF} climb to Post — {@code while}/
   *       {@code repeat} ({@code RepeatDeclineStrategy}); shape starts at Post id instead
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> is6ForumOfPost(
      YTDBGraphTraversalSource g, long messageId) {
    return g.V()
        .hasLabel("Post")
        .has("id", messageId)
        .in(CONTAINER_OF_LABEL).as("forumId", "forumTitle")
        .out(HAS_MODERATOR_LABEL).as("moderatorId", "moderatorFirstName", "moderatorLastName")
        .select(
            "forumId",
            "forumTitle",
            "moderatorId",
            "moderatorFirstName",
            "moderatorLastName")
        .by("id")
        .by("title")
        .by("id")
        .by("firstName")
        .by("lastName");
  }

  /**
   * LDBC: IS7 reduced — direct replies + authors, SQL
   * {@code ORDER BY commentCreationDate DESC, replyAuthorId ASC} (no {@code LIMIT} in SQL IS7).
   *
   * <p>Kept: {@code in(REPLY_OF)} → {@code out(HAS_CREATOR)}; every non-coalesce / non-optional
   * RETURN column via multi-{@code as}; sort on reply date then author id.
   *
   * <p>Gaps vs SQL IS7:
   * <ul>
   *   <li>[not-yet-translatable] {@code coalesce} on reply content — {@link #coalesceMessageContent};
   *       shape returns plain {@code content}
   *   <li>[not-yet-translatable] optional knows-author flag — {@link #optionalFriendOfCreator}
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> is7RepliesWithAuthors(
      YTDBGraphTraversalSource g, long messageId) {
    return g.V()
        .hasLabel(MESSAGE_LABEL)
        .has("id", messageId)
        .in(REPLY_OF_LABEL).as("commentId", "commentContent", "commentCreationDate")
        .out(HAS_CREATOR_LABEL).as("replyAuthorId", "replyAuthorFirstName", "replyAuthorLastName")
        .order()
        .by(__.select("commentCreationDate").by("creationDate"), Order.desc)
        .by("id", Order.asc)
        .select(
            "commentId",
            "commentContent",
            "commentCreationDate",
            "replyAuthorId",
            "replyAuthorFirstName",
            "replyAuthorLastName")
        .by("id")
        .by("content")
        .by("creationDate")
        .by("id")
        .by("firstName")
        .by("lastName");
  }

  /**
   * LDBC: IC1 reduced — direct friends with a given {@code firstName} plus city name (one
   * {@code KNOWS} hop).
   *
   * <p>Gaps vs SQL IC1:
   * <ul>
   *   <li>[not-yet-translatable] transitive depth &lt; 3 / distance — {@code while}/{@code repeat}
   *       ({@link #repeatKnowsToThreeHops})
   *   <li>[not-yet-translatable] {@code LET} universities / companies — nested edge projections
   *   <li>[depends-on-above] {@code ORDER BY distance} — needs depth alias from the climb
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> ic1FriendsWithName(
      YTDBGraphTraversalSource g, long personId, String firstName) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .has("firstName", firstName)
        .as("personId", "lastName", "birthday", "creationDate", "gender", "browserUsed",
            "locationIP")
        .out(IS_LOCATED_IN_LABEL).as("cityName")
        .order().by(__.select("lastName").by("lastName"), Order.asc)
        .by(__.select("personId").by("id"), Order.asc)
        .limit(RESULT_LIMIT)
        .select(
            "personId",
            "lastName",
            "birthday",
            "creationDate",
            "gender",
            "browserUsed",
            "locationIP",
            "cityName")
        .by("id")
        .by("lastName")
        .by("birthday")
        .by("creationDate")
        .by("gender")
        .by("browserUsed")
        .by("locationIP")
        .by("name");
  }

  /**
   * LDBC: IC2 reduced — friends' messages before {@code maxDate}, SQL
   * {@code ORDER BY creationDate DESC, id ASC}, top {@link #RESULT_LIMIT}.
   *
   * <p>Kept: {@code out(KNOWS).in(HAS_CREATOR)}; date filter; {@code order}+{@code limit}; every
   * non-coalesce RETURN column via multi-{@code as} after the slice (post-cardinality presence).
   *
   * <p>Gaps vs SQL IC2:
   * <ul>
   *   <li>[not-yet-translatable] {@code coalesce(imageFile, content)} — {@link #coalesceMessageContent};
   *       shape returns plain {@code content}
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> ic2FriendsMessagesOrdered(
      YTDBGraphTraversalSource g, long personId, Date maxDate) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL).as("personId", "firstName", "lastName")
        .in(HAS_CREATOR_LABEL)
        .hasLabel(MESSAGE_LABEL).as("messageId", "messageContent", "messageCreationDate")
        .has("creationDate", P.lt(maxDate))
        .order().by("creationDate", Order.desc).by("id", Order.asc)
        .limit(RESULT_LIMIT)
        .select(
            "personId",
            "firstName",
            "lastName",
            "messageId",
            "messageContent",
            "messageCreationDate")
        .by("id")
        .by("firstName")
        .by("lastName")
        .by("id")
        .by("content")
        .by("creationDate");
  }

  /**
   * LDBC: IC3 reduced — direct friends' messages in a date window located in {@code countryX}.
   *
   * <p>Gaps vs SQL IC3:
   * <ul>
   *   <li>[not-yet-translatable] FoF {@code while} depth &lt; 2
   *   <li>[not-yet-translatable] dual-country counts / {@code GROUP BY} + both-country filter
   *   <li>[not-yet-translatable] person home-country exclusion via {@code IS_PART_OF}
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> ic3FriendsMessagesInCountry(
      YTDBGraphTraversalSource g,
      long personId,
      String countryX,
      Date startDate,
      Date endDate) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL).as("personId", "firstName", "lastName")
        .in(HAS_CREATOR_LABEL)
        .hasLabel(MESSAGE_LABEL)
        .has("creationDate", P.gte(startDate))
        .has("creationDate", P.lt(endDate))
        .out(IS_LOCATED_IN_LABEL)
        .has("name", countryX).as("msgCountry")
        .select("personId", "firstName", "lastName", "msgCountry")
        .by("id")
        .by("firstName")
        .by("lastName")
        .by("name");
  }

  /**
   * LDBC: IC4 reduced — tags on direct friends' posts in a date window, {@code groupCount} by name.
   *
   * <p>Gaps vs SQL IC4:
   * <ul>
   *   <li>[not-yet-translatable] {@code NOT} anti-join for tags used on older posts (shape counts
   *       every in-window tag)
   *   <li>[not-yet-translatable] {@code ORDER BY}/{@code LIMIT} after {@code groupCount} — plain
   *       {@code order}+{@code limit} translate on this branch; the post-aggregate slice still declines
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Long>> ic4FriendPostTags(
      YTDBGraphTraversalSource g, long personId, Date startDate, Date endDate) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .in(HAS_CREATOR_LABEL)
        .hasLabel(POST_LABEL)
        .has("creationDate", P.gte(startDate))
        .has("creationDate", P.lt(endDate))
        .out(HAS_TAG_LABEL)
        .groupCount().by("name");
  }

  /**
   * LDBC: IC5 reduced — forums containing direct friends' posts.
   *
   * <p>Gaps vs SQL IC5:
   * <ul>
   *   <li>[not-yet-translatable] FoF {@code while} depth &lt; 2
   *   <li>[not-yet-translatable] {@code HAS_MEMBER} joinDate filter (edge-{@code as})
   *   <li>[not-yet-translatable] per-forum post {@code count} ({@code GROUP BY} forum)
   *   <li>[depends-on-above] {@code ORDER BY postCount}/{@code LIMIT} — {@code order}+{@code limit}
   *       already translate; omitted until the count column exists
   *   <li>[not-yet-translatable] {@code dedup()} after a labeled hop — named scope keys on
   *       {@code DedupGlobalStep} decline (MATCH {@code DISTINCT} is whole-row only); shape emits
   *       one row per friend-post→forum path
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> ic5FriendPostForums(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .in(HAS_CREATOR_LABEL)
        .hasLabel(POST_LABEL)
        .in(CONTAINER_OF_LABEL).as("forumId", "forumTitle")
        .select("forumId", "forumTitle").by("id").by("title");
  }

  /**
   * LDBC: IC6 reduced — tag names on direct friends' posts ({@code groupCount}).
   *
   * <p>Gaps vs SQL IC6:
   * <ul>
   *   <li>[not-yet-translatable] FoF {@code while} depth &lt; 2
   *   <li>[not-yet-translatable] co-occurrence filter with a given tag ({@code where(out(HAS_TAG))}
   *       declines)
   *   <li>[not-yet-translatable] {@code ORDER BY}/{@code LIMIT} after {@code groupCount} — plain
   *       {@code order}+{@code limit} translate on this branch; the post-aggregate slice still declines
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Long>> ic6FriendPostTagCounts(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .in(HAS_CREATOR_LABEL)
        .hasLabel(POST_LABEL)
        .out(HAS_TAG_LABEL)
        .groupCount().by("name");
  }

  /**
   * LDBC: IC7 reduced — likers' {@code firstName} via {@code in(HAS_CREATOR).in(LIKES)}.
   *
   * <p>Kept: two-hop like walk; liker {@code values(firstName)} only.
   *
   * <p>Gaps vs SQL IC7:
   * <ul>
   *   <li>[not-yet-translatable] like-edge {@code creationDate} — edge-{@code as} property projection
   *   <li>[not-yet-translatable] per-liker latest like ({@code GROUP BY} + {@code first()}) — not the
   *       plain hop walk this shape prices
   *   <li>[not-yet-translatable] optional knows / {@code isNew} — {@link #optionalFriendOfCreator}
   *   <li>[not-yet-translatable] {@code coalesce} on message content — {@link #coalesceMessageContent}
   *   <li>[depends-on-above] message id/content/date columns, {@code ORDER BY}/{@code LIMIT} — need
   *       the edge date and latest-per-liker plan above
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, String> ic7Likers(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .in(HAS_CREATOR_LABEL)
        .in(LIKES_LABEL)
        .values("firstName");
  }

  /**
   * LDBC: IC8 reduced — comments on a person's messages, SQL
   * {@code ORDER BY creationDate DESC, id ASC}, top {@link #RESULT_LIMIT}.
   *
   * <p>Kept: bind comment + creator (same pattern as {@link #is7RepliesWithAuthors}); sort on
   * comment date/id via {@code select} modulators; {@code limit}; every non-coalesce RETURN column
   * via multi-{@code as} after the slice. Labels that {@code LazyBarrierStrategy} parks on {@code
   * NoOpBarrierStep} are salvaged by the walker.
   *
   * <p>Gaps vs SQL IC8:
   * <ul>
   *   <li>[not-yet-translatable] {@code coalesce(imageFile, content)} — {@link #coalesceMessageContent};
   *       shape returns plain {@code content}
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> ic8RecentRepliesOrdered(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .in(HAS_CREATOR_LABEL)
        .in(REPLY_OF_LABEL).as("commentCreationDate", "commentId", "commentContent")
        .out(HAS_CREATOR_LABEL).as("personId", "firstName", "lastName")
        .order()
        .by(__.select("commentCreationDate").by("creationDate"), Order.desc)
        .by(__.select("commentId").by("id"), Order.asc)
        .limit(RESULT_LIMIT)
        .select(
            "personId",
            "firstName",
            "lastName",
            "commentCreationDate",
            "commentId",
            "commentContent")
        .by("id")
        .by("firstName")
        .by("lastName")
        .by("creationDate")
        .by("id")
        .by("content");
  }

  /**
   * LDBC: IC9 reduced — direct friends' messages before {@code maxDate} (same walk as
   * {@link #ic2FriendsMessagesOrdered}; SQL IC9 adds FoF).
   *
   * <p>Gaps vs SQL IC9:
   * <ul>
   *   <li>[not-yet-translatable] FoF {@code while} depth &lt; 2
   *   <li>[not-yet-translatable] {@code coalesce(imageFile, content)} — {@link #coalesceMessageContent}
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> ic9FriendsMessagesOrdered(
      YTDBGraphTraversalSource g, long personId, Date maxDate) {
    return ic2FriendsMessagesOrdered(g, personId, maxDate);
  }

  /**
   * LDBC: IC10 reduced — friends-of-friends (two {@code KNOWS} hops) with city name.
   *
   * <p>Gaps vs SQL IC10:
   * <ul>
   *   <li>[not-yet-translatable] exclude the start person via {@code where(neq(start))} before a
   *       multi-{@code as}/{@code select} — TinkerPop parks the person labels on the
   *       {@code WherePredicateStep}, and the walker cannot bind those aliases for the following
   *       {@code select} (see {@link #threeHopKnowsExcludingIntermediate} for {@code where(neq)}
   *       without a later select). Shape therefore keeps the Alice←Bob→Alice cycle row.
   *   <li>[not-yet-translatable] exclude direct friends ({@code NOT IN start.out(KNOWS)})
   *   <li>[not-yet-translatable] birthday MMdd window + {@code LET} interest score
   *   <li>[depends-on-above] {@code ORDER BY commonInterestScore} + {@code LIMIT}
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> ic10FriendsOfFriendsInCity(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .out(KNOWS_LABEL)
        .as("personId", "firstName", "lastName", "gender", "birthday")
        .out(IS_LOCATED_IN_LABEL).as("cityName")
        .select(
            "personId",
            "firstName",
            "lastName",
            "gender",
            "birthday",
            "cityName")
        .by("id")
        .by("firstName")
        .by("lastName")
        .by("gender")
        .by("birthday")
        .by("name");
  }

  /**
   * LDBC: IC11 reduced — direct friends' companies in a named country.
   *
   * <p>Kept: one {@code KNOWS} hop; {@code out(WORK_AT)}; country filter; friend id/name + company
   * name via multi-{@code as} (SQL RETURN without {@code workFrom}).
   *
   * <p>Gaps vs SQL IC11:
   * <ul>
   *   <li>[not-yet-translatable] friends-of-friends ({@code while} depth &lt; 2) — {@code repeat}/
   *       {@code while} ({@code RepeatDeclineStrategy})
   *   <li>[not-yet-translatable] {@code workFrom} filter + column — edge-{@code as} on {@code
   *       outE(WORK_AT)}
   *   <li>[depends-on-above] SQL {@code ORDER BY workFrom, personId, organizationName} +
   *       {@code LIMIT} — {@code order}+{@code limit} already translates; the shape omits them
   *       until {@code workFrom} exists so remis order stays SQL-identical (a personId/name-only
   *       sort would compile but disagree with SQL on ties)
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> ic11FriendsCompaniesInCountry(
      YTDBGraphTraversalSource g, long personId, String countryName) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL).as("personId", "firstName", "lastName")
        .out(WORK_AT_LABEL).as("organizationName")
        .out(IS_LOCATED_IN_LABEL)
        .has("name", countryName)
        .select("personId", "firstName", "lastName", "organizationName")
        .by("id")
        .by("firstName")
        .by("lastName")
        .by("name");
  }

  /**
   * LDBC: IC12 reduced — friends' comments that reply to a post, with that post's tags.
   *
   * <p>Gaps vs SQL IC12:
   * <ul>
   *   <li>[not-yet-translatable] {@code HAS_TYPE} / {@code IS_SUBCLASS_OF} {@code while} TagClass
   *       filter
   *   <li>[not-yet-translatable] {@code set(tag.name)} + {@code count} {@code GROUP BY} friend
   *   <li>[depends-on-above] {@code ORDER BY replyCount}/{@code LIMIT} — {@code order}+{@code limit}
   *       already translate; the shape omits them until {@code replyCount} exists so remis order
   *       stays SQL-identical
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> ic12FriendCommentPostTags(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL).as("personId", "firstName", "lastName")
        .in(HAS_CREATOR_LABEL)
        .hasLabel(COMMENT_LABEL)
        .out(REPLY_OF_LABEL)
        .hasLabel(POST_LABEL)
        .out(HAS_TAG_LABEL).as("tagName")
        .select("personId", "firstName", "lastName", "tagName")
        .by("id")
        .by("firstName")
        .by("lastName")
        .by("name");
  }

  /**
   * LDBC: IC13 reduced — whether {@code person1} has a direct {@code KNOWS} edge to {@code person2}
   * ({@code count}, 0 or more).
   *
   * <p>Gaps vs SQL IC13:
   * <ul>
   *   <li>[not-yet-translatable] {@code shortestPath(...)} length — no shortest-path recogniser;
   *       shape only probes a one-hop edge
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Long> ic13DirectKnowsCount(
      YTDBGraphTraversalSource g, long person1Id, long person2Id) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", person1Id)
        .out(KNOWS_LABEL)
        .has("id", person2Id)
        .count();
  }

  /**
   * LDBC: none. Translator primitive: two chained {@code KNOWS} hops.
   *
   * <p>The first shape where the two engines can disagree on plan shape rather than on overhead: the
   * native pipeline walks adjacency twice with a barrier between the hops, while MATCH enumerates
   * one row per distinct two-hop path. Both emit one result per path, so the answer sets match and
   * only the cost differs.
   */
  public static YTDBGraphTraversal<Vertex, String> twoHopKnows(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .out(KNOWS_LABEL)
        .values("firstName");
  }

  /**
   * LDBC: none. Translator primitive: two-hop {@code KNOWS} with an indexed filter on the
   * intermediate hop.
   *
   * <p>Where index selection should tell: {@code Person.firstName} carries a {@code NOTUNIQUE}
   * index, so a MATCH planner is free to enter the pattern from the filtered alias and intersect
   * back, while the native pipeline can only expand the first hop and filter the result.
   */
  public static YTDBGraphTraversal<Vertex, String> knowsFilteredByFriendFirstName(
      YTDBGraphTraversalSource g, long personId, String friendFirstName) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .has("firstName", friendFirstName)
        .out(KNOWS_LABEL)
        .values("firstName");
  }

  /**
   * LDBC: none. Translator primitive: three-hop {@code KNOWS} with {@code where(neq)} against a
   * mid-walk alias.
   *
   * <p>{@code where(P.neq("f"))} drops the paths whose third hop returns to the friend the second
   * hop came from — a back-reference to a mid-walk alias. An earlier note here explained that
   * choice by a start-step label being unresolvable; that gate has since closed on {@link
   * #is1PersonCityProfile}'s {@code as("p")} label, and no shape in this class measures the
   * start-alias variant, so
   * the explanation is withdrawn rather than restated.
   */
  public static YTDBGraphTraversal<Vertex, String> threeHopKnowsExcludingIntermediate(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL).as("f")
        .out(KNOWS_LABEL)
        .out(KNOWS_LABEL)
        .where(P.neq("f"))
        .values("firstName");
  }

  /**
   * LDBC: none. Translator primitive: {@code groupCount().by(lastName)}, the IC-style
   * {@code GROUP BY} + {@code count(*)} shape without a workload query behind it.
   *
   * <p>The aggregate pushes into the MATCH plan, so the on-arm returns a grouped result set while
   * the off-arm builds the map in the traverser pipeline.
   */
  public static YTDBGraphTraversal<Vertex, Map<Object, Long>> knowsGroupCountByLastName(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .groupCount().by("lastName");
  }

  /**
   * LDBC: none. Translator primitive: hash anti-join of friends not located in a place. Echoes
   * IC-style {@code NOT} without IC4's {@code GROUP BY} / {@code LIMIT} or IS4/IS2's declined
   * projections.
   *
   * <p>Echoes the shape of LDBC IC-style negation and IS4/IS2's {@code NOT} projections without
   * their declined steps: {@code MATCH {class: Person, where: (id = :personId)}.out('KNOWS'){as:
   * friend} RETURN friend.firstName} restricted to friends for whom no {@code
   * .out('IS_LOCATED_IN'){where: (name = :placeName)}} row exists. Gremlin spells the exclusion
   * {@code not(__.out(IS_LOCATED_IN).has(name, placeName))}, an edge-bearing {@code not(...)}.
   *
   * <p><b>Why MATCH wins.</b> This is the one shape whose optimisation none of the others reach:
   * the edge-bearing {@code not(...)} compiles to a detached NOT {@code MATCH} expression that the
   * planner runs as a <b>hash anti-join</b> — build a hash set of the friends who <em>are</em>
   * located in the place once, then probe. The native TinkerPop pipeline instead re-walks {@code
   * IS_LOCATED_IN} and filters {@code name} per candidate friend, a nested-loop anti-join whose
   * cost is quadratic in the friend count. {@code NotStepRecogniser}'s edge-bearing branch is what
   * makes the boundary step appear; a {@code youtrackdb-core} whose recogniser predates it declines
   * the shape and both arms run natively.
   */
  public static YTDBGraphTraversal<Vertex, String> friendsNotLocatedInPlace(
      YTDBGraphTraversalSource g, long personId, String placeName) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .not(__.out(IS_LOCATED_IN_LABEL).has("name", placeName))
        .values("firstName");
  }

  /**
   * LDBC: none. Translator primitive: three-hop {@code KNOWS} triangle closed by
   * {@code where(eq(start))}. Cyclic social-graph shape; not a named IC/IS query.
   *
   * <p>Echoes the cyclic patterns the LDBC social queries lean on: three {@code KNOWS} hops that
   * must return to the person they started from, i.e. {@code MATCH {class: Person, as: start,
   * where: (id = :personId)}.out('KNOWS').out('KNOWS').out('KNOWS'){where: (@rid =
   * $matched.start.@rid)}}. Gremlin spells the closure {@code where(P.eq("start"))} against the
   * start-step label.
   *
   * <p><b>Why MATCH wins.</b> The cycle constraint is a self-join back onto a pattern alias, which
   * MATCH schedules <b>topologically</b>: it enters from the bound {@code start} alias at both ends
   * of the pattern and enumerates only the closing paths, rather than materialising every three-hop
   * path and discarding the ones that do not return. The native pipeline has no notion of the
   * closing alias until the {@code where()} step runs, so it expands the full three-hop frontier
   * first and filters last. {@code WherePredicateStepRecogniser} translates the {@code
   * where(P.eq(label))} closure via a {@code $matched.start} accessor; the start-step {@code
   * as("start")} label binds through the {@code has} recogniser (the same label gate {@link
   * #is1PersonCityProfile} relies on).
   */
  public static YTDBGraphTraversal<Vertex, String> mutualFriendTriangle(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId).as("start")
        .out(KNOWS_LABEL)
        .out(KNOWS_LABEL)
        .out(KNOWS_LABEL)
        .where(P.eq("start"))
        .values("firstName");
  }

  /**
   * LDBC: none. Translator primitive: {@code order().by(firstName).range(1, 3)} on friends.
   *
   * <p>{@code Person.firstName} is {@code NOTUNIQUE}; ties are implementation-defined like YQL.
   * Same-boundary slice translates. Non-unique control beside the LDBC top-N shapes
   * ({@link #is2PersonMessages} date-only; {@link #ic2FriendsMessagesOrdered} /
   * {@link #ic8RecentRepliesOrdered} date+id as in SQL).
   */
  public static YTDBGraphTraversal<Vertex, String> knowsOrderedPage(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .order().by("firstName")
        .range(1, 3)
        .values("firstName");
  }

  // ---------------------------------------------------------------------------------------------
  // Declining shapes: no boundary step with kill-switch on. CI: both sides native — head-vs-base
  // is not a MATCH win/loss. Optional on/off A/B prices decline overhead on one commit.
  // ---------------------------------------------------------------------------------------------

  /**
   * LDBC: IS3 fragment — declining twin of {@link #is3FriendsWithNames}.
   *
   * <p>Target: friendship {@code creationDate} via {@code select("k", "friend")}.
   *
   * <p>Gap (whole shape declines):
   * <ul>
   *   <li>[not-yet-translatable] edge-property projection — {@code as("k")} on {@code outE} binds the
   *       edge-as-node vertex alias, so {@code select("k").by("creationDate")} reads the wrong entity
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, Map<String, Object>> is3FriendsWithDates(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId).as("p")
        .outE(KNOWS_LABEL).as("k")
        .inV().as("friend")
        .order().by("firstName")
        .select("k", "friend").by("creationDate").by("firstName");
  }

  /**
   * LDBC: IC1 fragment — variable-depth {@code KNOWS} walk.
   *
   * <p>Target: {@code repeat(out(KNOWS)).times(3).emit()} (one slice of IC1's pattern).
   *
   * <p>Gap (whole shape declines):
   * <ul>
   *   <li>[not-yet-translatable] {@code repeat}/{@code emit} — {@code RepeatDeclineStrategy}
   * </ul>
   *
   * <p>Other IC1 pieces not in this fragment: {@code LET}, {@code GROUP BY} min(distance), profile
   * columns, {@code ORDER BY}, {@code LIMIT}.
   */
  public static YTDBGraphTraversal<Vertex, String> repeatKnowsToThreeHops(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .repeat(__.out(KNOWS_LABEL))
        .times(3)
        .emit()
        .dedup()
        .values("firstName");
  }

  /**
   * LDBC: IS4 fragment — {@code coalesce(imageFile, content)}.
   *
   * <p>Translating twin without coalesce: {@link #is4MessageContent}.
   *
   * <p>Gap (whole shape declines):
   * <ul>
   *   <li>[not-yet-translatable] {@code CoalesceStep} — no recogniser
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, String> coalesceMessageContent(
      YTDBGraphTraversalSource g, long messageId) {
    return g.V()
        .hasLabel(MESSAGE_LABEL)
        .has("id", messageId)
        .coalesce(__.values("imageFile"), __.values("content"));
  }

  /**
   * LDBC: IS7 fragment — {@code optional(out(KNOWS))} after message author.
   *
   * <p>Target: SQL IS7 knows-author flag ({@code ifnull(knowsCheck, false, true)}).
   *
   * <p>Gap (whole shape declines):
   * <ul>
   *   <li>[not-yet-translatable] {@code optional()} — no recogniser
   * </ul>
   */
  public static YTDBGraphTraversal<Vertex, String> optionalFriendOfCreator(
      YTDBGraphTraversalSource g, long messageId) {
    return g.V()
        .hasLabel(MESSAGE_LABEL)
        .has("id", messageId)
        .out(HAS_CREATOR_LABEL)
        .optional(__.out(KNOWS_LABEL))
        .values("firstName");
  }

  // ---------------------------------------------------------------------------------------------
  // Engagement checks.
  // ---------------------------------------------------------------------------------------------

  /**
   * Counts boundary steps in a strategy-applied traversal.
   *
   * <p>Keys on {@link AbstractMatchPlanStep} rather than a concrete boundary class so every
   * boundary form counts — the single-plan step and the multi-plan step share the base. {@code
   * core}'s test-side {@code countBoundarySteps} helper is unreachable from here because this
   * module declares no {@code core} test-jar dependency, so the check is restated rather than
   * imported.
   *
   * @param strategyApplied a traversal on which {@code applyStrategies()} has already run;
   *     counting before that always returns zero and would make every caller vacuous
   */
  public static int countBoundarySteps(Traversal.Admin<?, ?> strategyApplied) {
    var count = 0;
    for (var step : strategyApplied.getSteps()) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }

  /**
   * Throws unless the traversal translated to exactly one boundary step.
   *
   * <p>Throws rather than asserting. The JMH launcher in {@code jmh-ldbc/pom.xml} runs {@code java}
   * with no {@code -ea} and no {@code @Fork(jvmArgsAppend)} adds one, while surefire's
   * {@code argLine} does carry it — so a Java {@code assert} here would hold in-track and become a
   * no-op under measurement, which is the one place the check has to hold.
   *
   * @param shape human-readable shape name, so a failure names which shape broke
   * @param strategyApplied a traversal on which {@code applyStrategies()} has already run
   */
  public static void requireTranslated(String shape, Traversal.Admin<?, ?> strategyApplied) {
    var boundaries = countBoundarySteps(strategyApplied);
    if (boundaries != 1) {
      throw new IllegalStateException(
          "translator-on arm: shape '" + shape + "' must carry exactly one AbstractMatchPlanStep"
              + " after applyStrategies(), found " + boundaries
              + ". Either the kill-switch flip did not reach this traversal or the shape declined."
              + " Step list: " + strategyApplied.getSteps());
    }
  }

  /**
   * Throws unless the traversal carries no boundary step over a non-empty native pipeline.
   *
   * <p>The empty-step-list guard is not defensive padding. "No boundary step" is also what a
   * traversal that never built, a closed session, or a degenerate fixture produces, so the absence
   * check alone would pass for the wrong reason. Requiring a non-empty step list leaves absence as
   * the only reading.
   *
   * <p>Two callers with different meanings. On a translating shape this is the off-arm check, and a
   * successful {@link #requireTranslated} on the same shape has already shown the translator does
   * engage there. On a declining shape it is the check for <em>both</em> arms, and a failure on the
   * on-arm means the shape has started translating — see the class Javadoc on why that is a
   * deliberate tripwire rather than good news.
   *
   * @param shape human-readable shape name, so a failure names which shape broke
   * @param strategyApplied a traversal on which {@code applyStrategies()} has already run
   */
  public static void requireNotTranslated(String shape, Traversal.Admin<?, ?> strategyApplied) {
    var steps = strategyApplied.getSteps();
    if (steps.isEmpty()) {
      throw new IllegalStateException(
          "translator-off arm: shape '" + shape + "' produced an empty step list, so the absence"
              + " of a boundary step says nothing about the kill-switch. The traversal never"
              + " built.");
    }
    var boundaries = countBoundarySteps(strategyApplied);
    if (boundaries != 0) {
      throw new IllegalStateException(
          "shape '" + shape
              + "' must carry no AbstractMatchPlanStep after applyStrategies(), found "
              + boundaries
              + ". On a translating shape's off-arm the kill-switch flip did not reach this"
              + " traversal — a session-local override shadowing the global flag is the usual cause."
              + " On a declining shape this means a recogniser now claims the shape, so its recorded"
              + " decline-path baseline needs re-reading. Step list: " + steps);
    }
  }
}
