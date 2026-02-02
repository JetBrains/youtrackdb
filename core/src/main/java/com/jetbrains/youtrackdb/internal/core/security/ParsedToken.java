package com.jetbrains.youtrackdb.internal.core.security;

import com.jetbrains.youtrackdb.internal.core.metadata.security.Token;

public record ParsedToken(Token token, byte[] tokenBytes, byte[] signature) {

}
