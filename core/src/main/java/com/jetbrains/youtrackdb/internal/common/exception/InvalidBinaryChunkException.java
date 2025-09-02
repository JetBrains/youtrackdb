package com.jetbrains.youtrackdb.internal.common.exception;

import java.io.IOException;

public class InvalidBinaryChunkException extends IOException {

  public InvalidBinaryChunkException() {
  }

  public InvalidBinaryChunkException(String message) {
    super(message);
  }

  public InvalidBinaryChunkException(String message, Throwable cause) {
    super(message, cause);
  }

  public InvalidBinaryChunkException(Throwable cause) {
    super(cause);
  }
}
