package com.jetbrains.youtrackdb.internal.core.security.authenticator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Tests {@link TemporaryGlobalUser} — a plain-old-Java-object that holds a
 * username/password/resources triple used by the server's ephemeral-user registry.
 *
 * <p>The class has no logic beyond field accessors; these tests pin the value-class
 * contract so that a refactor that accidentally drops or renames a getter surfaces
 * immediately rather than at runtime in the server's authentication path.
 */
public class TemporaryGlobalUserTest {

  @Test
  public void constructorShouldInitialiseAllThreeFields() {
    // The 3-arg constructor populates name, password, and resources exactly as supplied.
    var user = new TemporaryGlobalUser("alice", "s3cr3t", "database.*");
    assertThat(user.getName()).isEqualTo("alice");
    assertThat(user.getPassword()).isEqualTo("s3cr3t");
    assertThat(user.getResources()).isEqualTo("database.*");
  }

  @Test
  public void settersShouldUpdateEachFieldIndependently() {
    // Setters must mutate only the targeted field; the other two remain unchanged.
    var user = new TemporaryGlobalUser("alice", "s3cr3t", "database.*");

    user.setName("bob");
    assertThat(user.getName()).isEqualTo("bob");
    assertThat(user.getPassword()).isEqualTo("s3cr3t");
    assertThat(user.getResources()).isEqualTo("database.*");

    user.setPassword("n3wp4ss");
    assertThat(user.getPassword()).isEqualTo("n3wp4ss");

    user.setResources("*");
    assertThat(user.getResources()).isEqualTo("*");
  }

  @Test
  public void shouldAcceptNullFieldValues() {
    // Defensive: the server sometimes creates ephemeral users with null password; the
    // constructor and setters must not throw in that case.
    var user = new TemporaryGlobalUser(null, null, null);
    assertThat(user.getName()).isNull();
    assertThat(user.getPassword()).isNull();
    assertThat(user.getResources()).isNull();
  }

  @Test
  public void implementsGlobalUserInterface() {
    // TemporaryGlobalUser is used polymorphically as GlobalUser in DefaultSecuritySystem;
    // the cast must never fail.
    var user = new TemporaryGlobalUser("charlie", "pw", "res");
    assertThat(user)
        .isInstanceOf(com.jetbrains.youtrackdb.internal.core.security.GlobalUser.class);
  }
}
