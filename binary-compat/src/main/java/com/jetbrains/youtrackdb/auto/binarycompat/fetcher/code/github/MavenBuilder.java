package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.github;

import java.io.IOException;

public interface MavenBuilder {

  String build(String repositoryPath) throws IOException, InterruptedException;
}
