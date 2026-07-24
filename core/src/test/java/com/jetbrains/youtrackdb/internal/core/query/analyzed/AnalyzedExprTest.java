package com.jetbrains.youtrackdb.internal.core.query.analyzed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.BinaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Const;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.FuncCall;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Param;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.UnaryOp;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr.Var;
import java.util.List;
import org.junit.Test;

/// Substrate unit test for the analyzed-expression IR.
///
/// Two properties are checked, since this slice ships no live consumer: that
/// {@link AnalyzedExpr#dispatch} routes each variant to the matching `visitX` (the runtime
/// half of the exhaustive-dispatch guarantee — the compile-time half is enforced by the
/// compiler, since a seventh variant would fail to compile against the `default`-free
/// `switch` and the no-default base visitor), and that
/// {@link AnalyzedExpr#transformChildren} shares unchanged structure by reference identity
/// rather than value equality.
public class AnalyzedExprTest {

  /// A visitor returning a distinct sentinel string per variant, used to confirm dispatch
  /// routes each variant to its own `visitX` rather than a shared or mismatched branch.
  private static final class NamingVisitor implements AnalyzedExprVisitor<String> {

    @Override
    public String visitVar(Var var) {
      return "var";
    }

    @Override
    public String visitConst(Const constant) {
      return "const";
    }

    @Override
    public String visitParam(Param param) {
      return "param";
    }

    @Override
    public String visitBinaryOp(BinaryOp binaryOp) {
      return "binary";
    }

    @Override
    public String visitUnaryOp(UnaryOp unaryOp) {
      return "unary";
    }

    @Override
    public String visitFuncCall(FuncCall funcCall) {
      return "func";
    }
  }

  // ---- Dispatch exhaustiveness ----

  /// WHEN each of the six variants is dispatched through {@link AnalyzedExpr#dispatch}, THE
  /// matching `visitX` is invoked and its result returned — confirming the `switch` routes
  /// every variant to its own branch.
  @Test
  public void dispatchRoutesEachVariantToItsVisitMethod() {
    NamingVisitor visitor = new NamingVisitor();

    assertEquals("var", AnalyzedExpr.dispatch(new Var(List.of("name")), visitor));
    assertEquals("const", AnalyzedExpr.dispatch(new Const(42), visitor));
    assertEquals("param", AnalyzedExpr.dispatch(new Param(0, null), visitor));
    assertEquals(
        "binary",
        AnalyzedExpr.dispatch(
            new BinaryOp(BinaryOperator.PLUS, new Var(List.of("a")), new Var(List.of("b"))),
            visitor));
    assertEquals(
        "unary",
        AnalyzedExpr.dispatch(new UnaryOp(UnaryOperator.NOT, new Const(true)), visitor));
    assertEquals(
        "func",
        AnalyzedExpr.dispatch(new FuncCall("f", List.of(new Const(1))), visitor));
  }

  // ---- Structural sharing by reference identity (transformChildren) ----

  /// An identity transform: it overrides nothing, so every `visitX` keeps its
  /// recurse-into-children default and no node is ever rebuilt. Used to prove the
  /// all-unchanged path returns inputs by reference.
  private static final class IdentityTransform implements AnalyzedExprTransform {
  }

  /// A transform that replaces every {@link Const} with a brand-new {@code Const} of the same
  /// value. The replacement is `equals` to the original but a distinct instance, which lets a
  /// test assert that the parent chain is rebuilt above any changed leaf.
  private static final class ReplaceConstTransform implements AnalyzedExprTransform {

    @Override
    public AnalyzedExpr visitConst(Const constant) {
      return new Const(constant.value());
    }
  }

  /// (a) WHEN a leaf variant ({@link Var}, {@link Const}, {@link Param}) is transformed by an
  /// identity pass, THE helper returns the very same instance.
  @Test
  public void leafVariantsAreReturnedByReference() {
    IdentityTransform t = new IdentityTransform();

    Var var = new Var(List.of("name"));
    Const constant = new Const(7);
    Param param = new Param(0, "p");

    assertSame(var, AnalyzedExpr.transformChildren(var, t));
    assertSame(constant, AnalyzedExpr.transformChildren(constant, t));
    assertSame(param, AnalyzedExpr.transformChildren(param, t));
  }

  /// (b) WHEN a compound variant has no child changed, THE helper returns the parent by
  /// reference. Covered for both compound shapes: {@link BinaryOp} and {@link UnaryOp}.
  @Test
  public void compoundVariantWithNoChildChangedReturnsParentByReference() {
    IdentityTransform t = new IdentityTransform();

    BinaryOp binary =
        new BinaryOp(BinaryOperator.PLUS, new Var(List.of("a")), new Var(List.of("b")));
    UnaryOp unary = new UnaryOp(UnaryOperator.NOT, new Const(true));

    assertSame(binary, AnalyzedExpr.transformChildren(binary, t));
    assertSame(unary, AnalyzedExpr.transformChildren(unary, t));
  }

  /// (c) WHEN exactly one child of a compound variant changes, THE helper returns a new parent
  /// while the unchanged sibling is shared by reference.
  @Test
  public void compoundVariantWithOneChildChangedRebuildsParentAndSharesSibling() {
    ReplaceConstTransform t = new ReplaceConstTransform();

    Var unchangedSibling = new Var(List.of("x"));
    Const changingChild = new Const(1);
    BinaryOp binary = new BinaryOp(BinaryOperator.STAR, changingChild, unchangedSibling);

    AnalyzedExpr result = AnalyzedExpr.transformChildren(binary, t);

    assertNotSame(binary, result);
    BinaryOp rebuilt = (BinaryOp) result;
    // The left child was replaced (a new, equal Const), the right sibling shared by reference.
    assertNotSame(changingChild, rebuilt.left());
    assertEquals(changingChild, rebuilt.left());
    assertSame(unchangedSibling, rebuilt.right());
    assertEquals(BinaryOperator.STAR, rebuilt.op());
  }

  /// (c') WHEN only the RIGHT child of a {@link BinaryOp} changes, THE helper rebuilds the
  /// parent and shares the unchanged left child by reference. This complements the
  /// left-changed case above so both arms of `transformChildren`'s
  /// `left == b.left() && right == b.right()` short-circuit are exercised (left unchanged,
  /// right changed).
  @Test
  public void binaryOpWithOnlyRightChildChangedRebuildsParentAndSharesLeft() {
    ReplaceConstTransform t = new ReplaceConstTransform();

    Var unchangedLeft = new Var(List.of("x"));
    Const changingRight = new Const(9);
    BinaryOp binary = new BinaryOp(BinaryOperator.MINUS, unchangedLeft, changingRight);

    AnalyzedExpr result = AnalyzedExpr.transformChildren(binary, t);

    assertNotSame(binary, result);
    BinaryOp rebuilt = (BinaryOp) result;
    assertSame(unchangedLeft, rebuilt.left());
    assertNotSame(changingRight, rebuilt.right());
    assertEquals(changingRight, rebuilt.right());
  }

  /// (d) WHEN no argument of a {@link FuncCall} changes, THE helper returns the same node and
  /// the same argument-list instance — the lazy copy never allocates.
  @Test
  public void funcCallWithNoArgumentChangedReturnsSameNodeAndSameArgList() {
    IdentityTransform t = new IdentityTransform();

    List<AnalyzedExpr> args = List.of(new Var(List.of("a")), new Var(List.of("b")));
    FuncCall call = new FuncCall("f", args);

    AnalyzedExpr result = AnalyzedExpr.transformChildren(call, t);

    assertSame(call, result);
    assertSame(args, ((FuncCall) result).args());
  }

  /// (e) WHEN a middle argument of a {@link FuncCall} changes, THE helper returns a new node
  /// with a new argument list while the leading (already-walked) arguments are shared by
  /// reference.
  @Test
  public void funcCallWithMiddleArgumentChangedRebuildsListAndSharesLeadingArgs() {
    ReplaceConstTransform t = new ReplaceConstTransform();

    Var leading = new Var(List.of("a"));
    Const middle = new Const(2);
    Var trailing = new Var(List.of("c"));
    FuncCall call = new FuncCall("f", List.of(leading, middle, trailing));

    AnalyzedExpr result = AnalyzedExpr.transformChildren(call, t);

    assertNotSame(call, result);
    FuncCall rebuilt = (FuncCall) result;
    assertNotSame(call.args(), rebuilt.args());
    assertEquals(3, rebuilt.args().size());
    // Leading arg walked before the change: shared by reference. Middle: replaced (equal,
    // distinct). Trailing: walked after the change but unchanged by the transform, so still
    // the same instance.
    assertSame(leading, rebuilt.args().get(0));
    assertNotSame(middle, rebuilt.args().get(1));
    assertEquals(middle, rebuilt.args().get(1));
    assertSame(trailing, rebuilt.args().get(2));
  }

  /// (f) WHEN two arguments of a {@link FuncCall} change, THE helper allocates the new list on
  /// the first change and appends each subsequent changed argument to it — exercising the
  /// "argument changed after the list was already allocated" path that a single-change test
  /// leaves uncovered.
  @Test
  public void funcCallWithTwoArgumentsChangedAccumulatesIntoOneNewList() {
    ReplaceConstTransform t = new ReplaceConstTransform();

    Const first = new Const(1);
    Const second = new Const(2);
    Var trailing = new Var(List.of("c"));
    FuncCall call = new FuncCall("f", List.of(first, second, trailing));

    AnalyzedExpr result = AnalyzedExpr.transformChildren(call, t);

    assertNotSame(call, result);
    FuncCall rebuilt = (FuncCall) result;
    assertEquals(3, rebuilt.args().size());
    // Both leading consts were replaced (equal, distinct); the trailing var is shared.
    assertNotSame(first, rebuilt.args().get(0));
    assertEquals(first, rebuilt.args().get(0));
    assertNotSame(second, rebuilt.args().get(1));
    assertEquals(second, rebuilt.args().get(1));
    assertSame(trailing, rebuilt.args().get(2));
  }

  /// (g) WHEN a single-argument {@link FuncCall}'s only argument changes, THE helper returns a
  /// new node whose rebuilt argument list holds exactly the one replaced argument. This is the
  /// smallest list that still takes the rebuild path: the prefix copied on the first change is
  /// the empty {@code subList(0, 0)} and the accumulator ends as a one-element list.
  @Test
  public void funcCallWithSingleArgumentChangedRebuildsSingletonList() {
    ReplaceConstTransform t = new ReplaceConstTransform();

    Const only = new Const(7);
    FuncCall call = new FuncCall("f", List.of(only));

    AnalyzedExpr result = AnalyzedExpr.transformChildren(call, t);

    assertNotSame(call, result);
    FuncCall rebuilt = (FuncCall) result;
    assertNotSame(call.args(), rebuilt.args());
    assertEquals(1, rebuilt.args().size());
    assertNotSame(only, rebuilt.args().get(0));
    assertEquals(only, rebuilt.args().get(0));
  }

  /// (h) WHEN a {@link FuncCall} has no arguments at all, THE helper returns the same node by
  /// reference and the same (empty) argument-list instance — the lazy-copy loop never enters,
  /// so it never allocates a replacement empty list. Empty-argument calls are real: niladic SQL
  /// functions lower to a zero-argument {@code FuncCall}, so this boundary is exercised rather
  /// than hypothetical.
  @Test
  public void funcCallWithEmptyArgListReturnsSameNodeByReference() {
    IdentityTransform t = new IdentityTransform();

    List<AnalyzedExpr> args = List.of();
    FuncCall call = new FuncCall("now", args);

    AnalyzedExpr result = AnalyzedExpr.transformChildren(call, t);

    assertSame(call, result);
    assertSame(args, ((FuncCall) result).args());
  }

  /// Negative case: a transform that rebuilds an `equals`-but-distinct copy of an unchanged
  /// node is counted as "changed" and defeats the sharing, because the helper compares by
  /// reference identity (`==`), not value equality. This pins the reference-identity rule
  /// rather than the happy path: the rebuilt parent is a new instance even though it is
  /// `equals` to the input.
  @Test
  public void equalButRebuiltCopyDefeatsSharing() {
    ReplaceConstTransform t = new ReplaceConstTransform();

    Const original = new Const(5);
    BinaryOp binary = new BinaryOp(BinaryOperator.PLUS, original, new Const(6));

    AnalyzedExpr result = AnalyzedExpr.transformChildren(binary, t);

    // Both children are rebuilt to equal-but-distinct Consts, so the parent is rebuilt too:
    // a new instance that is nonetheless value-equal to the input.
    assertNotSame(binary, result);
    assertEquals(binary, result);
    // Assert the concrete variant rather than a bare instanceof, so a failure names the
    // actual rebuilt type.
    assertEquals(BinaryOp.class, result.getClass());
  }

  // ---- Transform defaults via dispatch (nested trees) ----

  /// Drives a transform through its real entry point {@link AnalyzedExpr#dispatch} over a tree
  /// nesting a {@link BinaryOp} and a {@link UnaryOp} inside an outer {@code BinaryOp}, so the
  /// {@link AnalyzedExprTransform} recurse-into-children defaults run for the compound variants.
  /// The replaced {@link Const} sits under the left subtree only, so the rebuild propagates up
  /// the left spine while the right operand is shared by reference — exercising the
  /// one-child-changed rebuild branch for both {@code BinaryOp} (left changed, right unchanged)
  /// and {@code UnaryOp} (operand changed).
  @Test
  public void dispatchAppliesTransformDefaultsAndRebuildsChangedSpine() {
    ReplaceConstTransform t = new ReplaceConstTransform();

    // Tree: ((NOT (changing-const)) + (unchanged-var))
    // Only the const under the NOT changes, so NOT and the outer PLUS are rebuilt on the left
    // spine, while the right Var is shared by reference.
    Const changing = new Const(1);
    UnaryOp leftBranch = new UnaryOp(UnaryOperator.NOT, changing);
    Var rightOperand = new Var(List.of("x"));
    BinaryOp root = new BinaryOp(BinaryOperator.PLUS, leftBranch, rightOperand);

    AnalyzedExpr result = AnalyzedExpr.dispatch(root, t);

    assertNotSame(root, result);
    BinaryOp rebuiltRoot = (BinaryOp) result;
    // Left spine rebuilt: the UnaryOp is a new instance with a new (equal) operand.
    assertNotSame(leftBranch, rebuiltRoot.left());
    UnaryOp rebuiltUnary = (UnaryOp) rebuiltRoot.left();
    assertEquals(UnaryOperator.NOT, rebuiltUnary.op());
    assertNotSame(changing, rebuiltUnary.operand());
    assertEquals(changing, rebuiltUnary.operand());
    // Right operand unchanged: shared by reference even though the root was rebuilt.
    assertSame(rightOperand, rebuiltRoot.right());
  }

  /// WHEN a transform leaves a nested tree entirely unchanged, applying it through
  /// {@link AnalyzedExpr#dispatch} returns every node — including the compound parents reached
  /// via the recurse-into-children defaults — by reference. This pins the no-change path of the
  /// {@code BinaryOp}/{@code UnaryOp}/{@code FuncCall} transform defaults.
  @Test
  public void dispatchWithIdentityTransformSharesEntireNestedTree() {
    IdentityTransform t = new IdentityTransform();

    UnaryOp inner = new UnaryOp(UnaryOperator.NOT, new Var(List.of("a")));
    FuncCall func = new FuncCall("f", List.of(new Var(List.of("b"))));
    BinaryOp root = new BinaryOp(BinaryOperator.PLUS, inner, func);

    assertSame(root, AnalyzedExpr.dispatch(root, t));
    assertSame(inner, AnalyzedExpr.dispatch(inner, t));
    assertSame(func, AnalyzedExpr.dispatch(func, t));
  }

  // ---- UnsupportedAnalyzedNodeException ----

  /// THE exception renders the unsupported AST node's class name into its message (the
  /// `CommandExecutionException` parent has no `(Class)` constructor, so the message is built
  /// from the class here).
  @Test
  public void unsupportedNodeExceptionCarriesClassNameInMessage() {
    UnsupportedAnalyzedNodeException ex = new UnsupportedAnalyzedNodeException(String.class);
    // The message is fully determined: the human-readable prefix plus the fully-qualified
    // class name. Asserting the whole string (not just contains(getName())) pins both the
    // prefix and the FQN — a dropped prefix or a getName()->getSimpleName() swap fails here.
    assertEquals("unsupported analyzed node: " + String.class.getName(), ex.getMessage());
  }

  /// THE copy constructor (matching the `CoreException`-subclass convention) preserves the
  /// source exception's message.
  @Test
  public void unsupportedNodeExceptionCopyConstructorPreservesMessage() {
    UnsupportedAnalyzedNodeException original =
        new UnsupportedAnalyzedNodeException(Integer.class);
    UnsupportedAnalyzedNodeException copy = new UnsupportedAnalyzedNodeException(original);
    // The (Class) constructor never sets dbName, so CoreException.getMessage() appends no
    // suffix and the copy round-trips its raw message exactly. A later slice that adds a
    // dbName-carrying constructor must add a sibling test asserting the copy does not
    // double-append the "DB Name=..." suffix on top of the already-decorated source message.
    assertEquals(original.getMessage(), copy.getMessage());
  }

  // ---- Param record shape ----

  /// WHEN a positional {@link Param} is created, THE record carries the parameter number and a
  /// null name. Structural equals ensures two positional Params with the same number are equal.
  @Test
  public void paramPositionalRecordShape() {
    Param p = new Param(0, null);
    assertEquals(0, p.paramNumber());
    assertEquals(null, p.paramName());
    assertEquals(new Param(0, null), p);
  }

  /// WHEN a named {@link Param} is created, THE record carries both the parameter number and the
  /// name. Structural equals checks both fields.
  @Test
  public void paramNamedRecordShape() {
    Param p = new Param(1, "foo");
    assertEquals(1, p.paramNumber());
    assertEquals("foo", p.paramName());
    assertEquals(new Param(1, "foo"), p);
    assertNotEquals(new Param(1, "bar"), p); // different name -> not equal
  }

  /// WHEN the IR tree is copied (shared), immutable Param nodes are shared by reference — they
  /// carry no mutable state and re-resolve at evaluation time.
  @Test
  public void paramCopySharesImmutableIr() {
    Param p = new Param(0, "x");
    BinaryOp tree = new BinaryOp(BinaryOperator.EQ, new Var(List.of("a")), p);
    AnalyzedExpr copy = AnalyzedExpr.transformChildren(tree, new IdentityTransform());
    // Identity transform: the whole tree is shared by reference.
    assertSame(tree, copy);
    assertSame(p, ((BinaryOp) copy).right());
  }
}
