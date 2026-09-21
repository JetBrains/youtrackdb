# Track 12: Bare-property index ORDER BY matching

## Purpose

Refuse index-served ORDER BY when the sort key is a shadowed alias or carries a
modifier the index cannot evaluate (BG1908 / BG1909).

## Plan of work

1. Sort-only: require `modifier == null` and pass-through-or-exact field match;
   refuse shadowed aliases.
2. WHERE `fullySorted` path: refuse ORDER BY items with modifiers (separate
   predicate from deferral / sort-only).
3. Tests: shadow → correct projected order (in-memory); modifier / link chain →
   no index values step; bare property → index; nested → plan-shape only.

## Acceptance

- BG1908 / BG1909 shapes return correct values or safe non-index plans.
- No row-order asserts on unresolved nested-projection semantics.
