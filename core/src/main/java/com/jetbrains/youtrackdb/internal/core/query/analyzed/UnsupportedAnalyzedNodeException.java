package com.jetbrains.youtrackdb.internal.core.query.analyzed;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;

/// Thrown by the lowering pass for any AST shape outside the analyzed-expression covered
/// subset.
///
/// Lowering happens in the same logical phase as execution preparation, so this extends
/// `CommandExecutionException` — the established base for SQL execution-time errors — and an
/// unsupported-node failure surfaces the same way other execution-time failures do.
///
/// The exception carries the unsupported AST node's class, not its rendered text, so the
/// diagnostic names the unsupported shape (e.g. `SQLJson`) rather than echoing user SQL. This
/// type is the mechanism behind the no-silent-fallback guarantee: lowering either produces a
/// complete IR tree or throws this, never a partial tree, so a successful `lower(...)` means
/// full IR coverage of the input. The throw sites live in the lowering pass; this type ships
/// the exception alone.
public class UnsupportedAnalyzedNodeException extends CommandExecutionException {

  /// Builds the message from the unsupported AST node's class.
  ///
  /// `CommandExecutionException` has no `(Class)` constructor, so the class name is rendered
  /// into the message here and passed to `super(String)`.
  public UnsupportedAnalyzedNodeException(Class<?> astNodeClass) {
    super("unsupported analyzed node: " + astNodeClass.getName());
  }

  /// Copy constructor matching the `CoreException`-subclass convention.
  public UnsupportedAnalyzedNodeException(UnsupportedAnalyzedNodeException exception) {
    super(exception);
  }
}
