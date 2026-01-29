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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GqlStructureTest {

  @ParameterizedTest
  @MethodSource("com.jetbrains.youtrackdb.internal.core.gql.parser.GqlStructureTest#getPositiveGqlFiles")
  @DisplayName("Positive GQL Parser Test")
  public void testPositiveGqlStructure(Path gqlFile) throws IOException {
    var query = Files.readString(gqlFile);

    assertDoesNotThrow(() -> {
      var actualTree = parseQuery(query);

      System.out.println("\n--- POSITIVE TEST: " + gqlFile.getFileName() + " ---");
      System.out.println("Query: " + query.trim());
      System.out.println("Tree: " + actualTree);

      var expectedTreePath = Paths.get(gqlFile.toString().replace(".gql", ".tree"));
      if (Files.exists(expectedTreePath)) {
        var expectedTree = Files.readString(expectedTreePath).trim();
        Assertions.assertEquals(expectedTree, actualTree,
            "Generated tree for " + gqlFile.getFileName() + " does not match!");
      } else {
        Files.writeString(expectedTreePath, actualTree);
        System.out.println("[GOLDEN MASTER] Created new pattern: "
            + expectedTreePath.getFileName());
      }
    }, "Syntax error in positive test: " + gqlFile);
  }

  @ParameterizedTest
  @MethodSource("com.jetbrains.youtrackdb.internal.core.gql.parser.GqlStructureTest#getNegativeGqlFiles")
  @DisplayName("Negative GQL Parser Test")
  public void testNegativeGqlStructure(Path gqlFile) throws IOException {
    var query = Files.readString(gqlFile);

    System.out.println("\n--- NEGATIVE TEST: " + gqlFile.getFileName() + " ---");
    System.out.println("Query: " + query.trim());

    assertThrows(ParseCancellationException.class, () -> parseQuery(query),
        "Expected syntax error in " + gqlFile.getFileName() + " but none was thrown!");

    System.out.println("Result: Successfully caught expected syntax error.");
  }

  private static String parseQuery(String query) {
    var lexer = new GQLLexer(CharStreams.fromString(query));
    var tokens = new CommonTokenStream(lexer);
    var parser = new GQLParser(tokens);

    parser.removeErrorListeners();
    parser.addErrorListener(new ThrowingErrorListener());

    ParseTree tree = parser.graph_query();
    return tree.toStringTree(parser).trim();
  }

  static Stream<Path> getPositiveGqlFiles() throws IOException {
    return getFilesFromPath("src/test/resources/gql-tests/positive");
  }

  static Stream<Path> getNegativeGqlFiles() throws IOException {
    return getFilesFromPath("src/test/resources/gql-tests/negative");
  }

  private static Stream<Path> getFilesFromPath(String pathStr) throws IOException {
    var path = Paths.get(pathStr).toAbsolutePath();
    if (!Files.exists(path)) {
      System.err.println("WARNING: Directory does not exist: " + path);
      return Stream.empty();
    }

    try (var walkStream = Files.walk(path)) {
      return walkStream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".gql"))
          .toList()
          .stream();
    }
  }

  public static class ThrowingErrorListener extends BaseErrorListener {

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
        int line, int charPositionInLine, String msg, RecognitionException e) {
      throw new ParseCancellationException("Line " + line + ":" + charPositionInLine + " " + msg);
    }
  }
}