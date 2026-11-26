# Completion listener values

Core provides query and write-commit listener interfaces in
`com.jetbrains.youtrackdb.internal.common.profiler.monitoring`.
These interfaces are internal, not part of the supported public Java API.
Callbacks run synchronously. Slow listeners delay the calling operation.

## Values and lifetime

`QueryDetails` and `TransactionDetails` are callback-scoped views.
Do not retain either view.
Copy owned identity strings, caller summaries, timing and outcome values during the callback.
`QueryCompletion.capture(details)` copies only these scalar query values.
It never reads query text, a plan or Context.

Producers must capture entry-bound identity and transaction choices before execution.
Completion must use that capture, not a later transaction.

Every new accessor defaults to an empty optional.
Empty means unavailable, not zero or success.
A present row count of zero means that the query returned no rows.
A present threshold of zero is a valid override.

Start and finish are real epoch timestamps in nanoseconds.
They differ from EXACT active execution duration, which excludes consumer pauses.
The callback parameters continue to carry the measured duration.

A query without a transaction has a `null` transaction tracking identifier.
The explicit tracking identifier is also absent.
Do not use `0`, an empty string or another placeholder.
With a transaction, the tracking identifier can use its numeric fallback.
Only an explicitly supplied identifier belongs in an explicit-only tracking attribute.

`CompletionOutcome` holds success or failure plus the failure class name.
Success has no error type.
Unknown outcome stays absent.
Never retain an exception, its message or its cause chain.
Never put raw query text or parameter values in a completion snapshot.

Caller summaries and identifiers are caller-owned data, not automatically sanitized text.
Consumers must apply their privacy policy before storage or export.

Live plans, lazy query text, results, transactions and Context values remain callback-scoped.
Context can contain arbitrary host objects.
It is deliberately absent from `QueryCompletion`.
Optional text and plan inspection must remain demand-driven.

## Context dependency

Core depends on `io.opentelemetry:opentelemetry-context` 1.66.0.
The root OpenTelemetry bill of materials manages this version and its
`opentelemetry-common` dependency.
Core includes no OpenTelemetry software development kit or exporter.

The embedded artifact excludes both libraries from its shaded contents.
Its published dependencies supply the original packages.
Host and core therefore share one Context class and the same Context storage.
Applications must not add another relocated copy.

Both dependencies declare the Apache License, Version 2.0.
Their Maven metadata supplies the license link.
The published jars contain no license or notice entries.
The Maven license declarations apply to both artifacts.
