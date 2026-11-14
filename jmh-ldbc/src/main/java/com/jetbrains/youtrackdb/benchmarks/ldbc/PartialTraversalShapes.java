package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.api.gremlin.__;
import java.util.Date;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Column;

/**
 * G7 query bodies with coalesce, optional, local formatting or count continuations.
 * These definitions do not certify a supported prefix or an admitted cut.
 * The existing controls are {@link GremlinTraversalShapes#coalesceMessageContent},
 * {@link GremlinTraversalShapes#optionalFriendOfCreator} and
 * {@link GremlinTraversalShapes#repeatKnowsToThreeHops} for IS4, IS7 and IC1.
 */
public final class PartialTraversalShapes {

  private PartialTraversalShapes() {
  }

  /** Top twenty messages before maxDate. The id tie-breaker makes the selected payload stable. */
  public static Traversal<?, ?> recentFriendMessages(
      YTDBGraphTraversalSource g, long personId, Date maxDate) {
    return g.V().hasLabel("Person").has("id", personId)
        .out("KNOWS").in("HAS_CREATOR").hasLabel("Message")
        .has("creationDate", P.lt(maxDate))
        .order().by("creationDate", Order.desc).by("id", Order.asc).limit(20)
        .coalesce(__.values("imageFile"), __.values("content"));
  }

  /** Optional emits the forum itself when it has no moderator. Duplicate forums count once. */
  public static Traversal<?, ?> distinctForumsWithModerator(
      YTDBGraphTraversalSource g, long personId) {
    return forums(g, personId).dedup().optional(__.out("HAS_MODERATOR")).values("id");
  }

  /** The boundary payload is one complete aggregate map, including one empty map on no input. */
  public static Traversal<?, ?> tagCounts(YTDBGraphTraversalSource g, long personId) {
    return g.V().hasLabel("Person").has("id", personId)
        .out("KNOWS").in("HAS_CREATOR").hasLabel("Post").out("HAS_TAG")
        .groupCount().by("name")
        .local(__.unfold().project("tag", "postCount").by(Column.keys).by(Column.values));
  }

  /** The closing cross-label predicate is inside the prefix, not inside optional. */
  public static Traversal<?, ?> mutualFriendCycle(YTDBGraphTraversalSource g, long personId) {
    return g.V().hasLabel("Person").has("id", personId).as("start")
        .out("KNOWS").out("KNOWS").out("KNOWS").where(P.eq("start"))
        .optional(__.out("WORK_AT")).values("id");
  }

  /** Count observes neither encounter order nor any label before the distinct element boundary. */
  public static Traversal<?, ?> distinctForumCount(YTDBGraphTraversalSource g, long personId) {
    return forums(g, personId).dedup().count();
  }

  /** A property-bound endpoint control. This query does not embed a record identifier. */
  public static Traversal<?, ?> knownEndVertex(
      YTDBGraphTraversalSource g, long personId, long endId) {
    return g.V().hasLabel("Person").has("id", personId)
        .out("KNOWS").has("id", endId).optional(__.out("WORK_AT")).values("id");
  }

  /**
   * Complete IC5 first part from IC5.sql, followed by local result formatting.
   * The union enumerates depths one and two without repeat. Distinct forum/post pairs remove
   * repeated friendship paths and repeated matching membership edges before counting posts.
   * Forums with no qualifying post do not appear, matching the SQL inner chain.
   */
  public static Traversal<?, ?> fullIc5(
      YTDBGraphTraversalSource g, long personId, Date minDate) {
    return g.V().hasLabel("Person").has("id", personId).as("start")
        .union(__.out("KNOWS"), __.out("KNOWS").out("KNOWS"))
        .where(P.neq("start")).as("person")
        .in("HAS_CREATOR").hasLabel("Post").as("post")
        .in("CONTAINER_OF").hasLabel("Forum").as("forum")
        .where(__.outE("HAS_MEMBER").has("joinDate", P.gte(minDate)).inV()
            .where(P.eq("person")))
        .dedup("forum", "post")
        .group().by(__.project("forumId", "forumTitle").by("id").by("title"))
        .by(__.count())
        .local(__.unfold().order().by(Column.values, Order.desc)
            .by(__.select(Column.keys).select("forumId"), Order.asc).limit(20)
            .project("forumId", "forumTitle", "postCount")
            .by(__.select(Column.keys).select("forumId"))
            .by(__.select(Column.keys).select("forumTitle"))
            .by(Column.values));
  }

  private static YTDBGraphTraversal<?, ?> forums(
      YTDBGraphTraversalSource g, long personId) {
    return g.V().hasLabel("Person").has("id", personId)
        .out("KNOWS").in("HAS_CREATOR").hasLabel("Post").in("CONTAINER_OF");
  }
}
