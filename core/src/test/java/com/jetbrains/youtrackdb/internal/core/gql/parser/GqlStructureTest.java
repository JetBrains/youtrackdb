package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLLexer;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class GqlStructureTest {

  @ParameterizedTest
  @MethodSource("getGqlFiles")
  @DisplayName("GQL Parser Structure Test")
  public void testGqlStructure(Path gqlFile) throws IOException {
    var query = Files.readString(gqlFile);

    assertDoesNotThrow(() -> {
      var lexer = new GQLLexer(CharStreams.fromString(query));
      var tokens = new CommonTokenStream(lexer);

      var parser = new GQLParser(tokens);

      parser.removeErrorListeners();
      parser.addErrorListener(new ThrowingErrorListener());

      ParseTree tree = parser.graph_query();
      var actualTree = tree.toStringTree(parser);
      var expectedTreePath = Paths.get(gqlFile.toString().replace(".gql", ".tree"));

      if (Files.exists(expectedTreePath)) {
        var expectedTree = Files.readString(expectedTreePath).trim();
        Assertions.assertEquals(actualTree, "Generated tree for" +
            gqlFile.getFileName() + " is incorrect!", expectedTree);
      }
    }, "Syntax error in: " + gqlFile);
  }

  static Stream<Path> getGqlFiles() throws IOException {

    var testResources = Paths.get("src/test/resources/gql-tests/positive");
    if (!Files.exists(testResources)) {
      return Stream.empty();
    }
    return Files.walk(testResources).filter(p -> p.toString().endsWith(".gql"));
  }

  public static class ThrowingErrorListener extends BaseErrorListener {
    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
        int line, int charPositionInLine, String msg, RecognitionException e) {
      throw new ParseCancellationException("Line " + line + ":" + charPositionInLine + " " + msg);
    }
  }
}