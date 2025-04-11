package com.jetbrains.youtrack.db.internal.core.metadata.security.auth;

import com.jetbrains.youtrack.db.internal.core.security.ParsedToken;
import java.util.Optional;

public class TokenAuthInfo implements AuthenticationInfo {

  private final ParsedToken token;

  public TokenAuthInfo(ParsedToken iToken) {
    this.token = iToken;
  }

  @Override
  public Optional<String> getDatabase() {
    return Optional.ofNullable(token.getToken().getDatabaseName());
  }

  public ParsedToken getToken() {
    return token;
  }
}
