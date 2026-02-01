package com.jetbrains.youtrackdb.internal.core.metadata.security.auth;

import com.jetbrains.youtrackdb.internal.core.security.ParsedToken;
import java.util.Optional;

public record TokenAuthInfo(ParsedToken token) implements AuthenticationInfo {

  @Override
  public Optional<String> getDatabase() {
    return Optional.ofNullable(token.token().getDatabaseName());
  }
}
