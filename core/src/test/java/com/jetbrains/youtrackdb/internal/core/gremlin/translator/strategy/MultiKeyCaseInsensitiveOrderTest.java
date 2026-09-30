package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/**
 * Multi-key {@code ORDER BY} / {@code order().by(a).by(b)} when a key is case-insensitive: case
 * variants tie on that key so the next key (or {@code @rid}) decides. Covers YQL, MATCH, and Gremlin
 * translator on/off, including mixed ci/default declarations.
 */
public class MultiKeyCaseInsensitiveOrderTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  private void seedPersonCiNameDefaultNick() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    person.createProperty("nickname", PropertyType.STRING);
  }

  /**
   * Case 1: {@code ORDER BY name, nickname} with Bob/zed and bob/Alpha. Under ci, the names tie, so
   * nickname ASC puts Alpha before zed → bob/Alpha then Bob/zed.
   */
  @Test
  public void selectOrderBy_ciNameThenNicknameAsc_usesSecondKeyWithinCaseVariants() {
    seedPersonCiNameDefaultNick();
    graph.addVertex(T.label, "Person", "name", "Bob", "nickname", "zed");
    graph.addVertex(T.label, "Person", "name", "bob", "nickname", "Alpha");
    graph.tx().commit();

    try (var rs = session.query("SELECT name, nickname FROM Person ORDER BY name, nickname")) {
      var rows = rs.stream()
          .map(r -> r.getProperty("name") + "/" + r.getProperty("nickname"))
          .toList();
      assertThat(rows).containsExactly("bob/Alpha", "Bob/zed");
    }
  }

  /**
   * Case 2: {@code ORDER BY name, nickname DESC} with bob/zed and Bob/Alpha. Raw name order would
   * accidentally match nickname ASC; only DESC on the second key exposes the bug if name does not
   * leave case variants tied.
   */
  @Test
  public void selectOrderBy_ciNameThenNicknameDesc_usesSecondKeyDirection() {
    seedPersonCiNameDefaultNick();
    graph.addVertex(T.label, "Person", "name", "bob", "nickname", "zed");
    graph.addVertex(T.label, "Person", "name", "Bob", "nickname", "Alpha");
    graph.tx().commit();

    try (var rs =
        session.query("SELECT name, nickname FROM Person ORDER BY name, nickname DESC")) {
      var rows = rs.stream()
          .map(r -> r.getProperty("name") + "/" + r.getProperty("nickname"))
          .toList();
      assertThat(rows).containsExactly("bob/zed", "Bob/Alpha");
    }
  }

  /**
   * Cases 3–4: {@code order().by(name).by(nickname)} with translator on and off must both put
   * bob/Alpha before Bob/zed, matching the SELECT case-1 expectation.
   */
  @Test
  public void gremlinOrderBy_ciNameThenNickname_matchesOnBothTranslatorArms() {
    seedPersonCiNameDefaultNick();
    graph.addVertex(T.label, "Person", "name", "Bob", "nickname", "zed");
    graph.addVertex(T.label, "Person", "name", "bob", "nickname", "Alpha");
    graph.tx().commit();

    Supplier<GraphTraversal<?, ?>> traversal = () -> graph.traversal().V()
        .hasLabel("Person")
        .order().by("name").by("nickname")
        .project("name", "nickname").by("name").by("nickname");

    assertEquivalentOrdered(
        "g.V().hasLabel(Person).order().by(name).by(nickname)",
        traversal);

    var expected = List.of(
        "{name=bob, nickname=Alpha}",
        "{name=Bob, nickname=zed}");
    assertThat(runTranslatorArm(true, traversal)).containsExactlyElementsOf(expected);
    assertThat(runTranslatorArm(false, traversal)).containsExactlyElementsOf(expected);
  }

  /**
   * MATCH {@code ORDER BY} uses the same SQL order comparison as SELECT, so the same fixture must
   * put bob/Alpha before Bob/zed.
   */
  @Test
  public void matchOrderBy_ciNameThenNicknameAsc_usesSecondKeyWithinCaseVariants() {
    seedPersonCiNameDefaultNick();
    graph.addVertex(T.label, "Person", "name", "Bob", "nickname", "zed");
    graph.addVertex(T.label, "Person", "name", "bob", "nickname", "Alpha");
    graph.tx().commit();

    var query = "MATCH {class: Person, as: p} RETURN p.name AS name, p.nickname AS nickname"
        + " ORDER BY name, nickname";
    try (var rs = session.query(query)) {
      var rows = rs.stream()
          .map(r -> r.getProperty("name") + "/" + r.getProperty("nickname"))
          .toList();
      assertThat(rows).containsExactly("bob/Alpha", "Bob/zed");
    }
  }

  /**
   * Default (case-sensitive) name before a ci nickname: {@code Ada} vs {@code ada} already differ on
   * the first key, so nickname is unused — Ada before ada on both Gremlin arms.
   */
  @Test
  public void gremlinOrderBy_defaultNameThenCiNick_nameDecidesCaseVariants() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    person.createProperty("nickname", PropertyType.STRING).setCollate("ci");
    // Insert lower-case name first so a wrong ci-on-name path would put ada first via RID.
    graph.addVertex(T.label, "Person", "name", "ada", "nickname", "zed");
    graph.addVertex(T.label, "Person", "name", "Ada", "nickname", "Alpha");
    graph.tx().commit();

    Supplier<GraphTraversal<?, ?>> traversal = () -> graph.traversal().V()
        .hasLabel("Person")
        .order().by("name").by("nickname")
        .project("name", "nickname").by("name").by("nickname");

    assertEquivalentOrdered(
        "default name then ci nick: g.V().hasLabel(Person).order().by(name).by(nickname)",
        traversal);

    var expected = List.of(
        "{name=Ada, nickname=Alpha}",
        "{name=ada, nickname=zed}");
    assertThat(runTranslatorArm(true, traversal)).containsExactlyElementsOf(expected);
    assertThat(runTranslatorArm(false, traversal)).containsExactlyElementsOf(expected);
  }

  /**
   * Both keys ci: when folded nicknames differ, the second key decides among name case variants.
   */
  @Test
  public void gremlinOrderBy_ciNameThenCiNick_secondKeyDecides() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    person.createProperty("nickname", PropertyType.STRING).setCollate("ci");
    graph.addVertex(T.label, "Person", "name", "Bob", "nickname", "Zed");
    graph.addVertex(T.label, "Person", "name", "bob", "nickname", "alpha");
    graph.tx().commit();

    Supplier<GraphTraversal<?, ?>> traversal = () -> graph.traversal().V()
        .hasLabel("Person")
        .order().by("name").by("nickname")
        .project("name", "nickname").by("name").by("nickname");

    assertEquivalentOrdered(
        "ci name then ci nick: g.V().hasLabel(Person).order().by(name).by(nickname)",
        traversal);

    var expected = List.of(
        "{name=bob, nickname=alpha}",
        "{name=Bob, nickname=Zed}");
    assertThat(runTranslatorArm(true, traversal)).containsExactlyElementsOf(expected);
    assertThat(runTranslatorArm(false, traversal)).containsExactlyElementsOf(expected);
  }

  /**
   * Both keys ci and both fold equal (Ada/Zed vs ada/zed): all user keys tie, so both arms agree on
   * the record-id micro-tie-break (not case-sensitive adjacency).
   */
  @Test
  public void gremlinOrderBy_ciNameThenCiNick_fullTieUsesRidOnBothArms() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    person.createProperty("nickname", PropertyType.STRING).setCollate("ci");
    graph.addVertex(T.label, "Person", "name", "Ada", "nickname", "Zed");
    graph.addVertex(T.label, "Person", "name", "ada", "nickname", "zed");
    graph.tx().commit();

    Supplier<GraphTraversal<?, ?>> traversal = () -> graph.traversal().V()
        .hasLabel("Person")
        .order().by("name").by("nickname")
        .project("name", "nickname").by("name").by("nickname");

    assertEquivalentOrdered(
        "ci name+nick full tie: g.V().hasLabel(Person).order().by(name).by(nickname)",
        traversal);

    var on = runTranslatorArm(true, traversal);
    var off = runTranslatorArm(false, traversal);
    assertThat(on).containsExactlyElementsOf(off);
    assertThat(on).containsExactlyInAnyOrder(
        "{name=Ada, nickname=Zed}",
        "{name=ada, nickname=zed}");
  }

  /**
   * ci name, default nickname, equal nicknames: name case variants tie, nick ties, both arms agree
   * on rid order (absolute Ada-vs-ada order is the micro-tie-break, not CS).
   */
  @Test
  public void gremlinOrderBy_ciNameEqualDefaultNick_fullTieUsesRidOnBothArms() {
    seedPersonCiNameDefaultNick();
    graph.addVertex(T.label, "Person", "name", "Ada", "nickname", "zed");
    graph.addVertex(T.label, "Person", "name", "ada", "nickname", "zed");
    graph.tx().commit();

    Supplier<GraphTraversal<?, ?>> traversal = () -> graph.traversal().V()
        .hasLabel("Person")
        .order().by("name").by("nickname")
        .project("name", "nickname").by("name").by("nickname");

    assertEquivalentOrdered(
        "ci name, equal default nick: g.V().hasLabel(Person).order().by(name).by(nickname)",
        traversal);

    var on = runTranslatorArm(true, traversal);
    var off = runTranslatorArm(false, traversal);
    assertThat(on).containsExactlyElementsOf(off);
    assertThat(on).containsExactlyInAnyOrder(
        "{name=Ada, nickname=zed}",
        "{name=ada, nickname=zed}");
  }

  private List<String> runTranslatorArm(boolean enabled, Supplier<GraphTraversal<?, ?>> traversal) {
    var before = GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValueAsBoolean();
    GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(enabled);
    try {
      return traversal.get().toList().stream().map(Object::toString).toList();
    } finally {
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(before);
    }
  }

  private void assertEquivalentOrdered(String label, Supplier<GraphTraversal<?, ?>> traversal) {
    support.assertEquivalent(
        label,
        Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY,
        results -> results.stream().map(String::valueOf).toList(),
        traversal);
  }
}
