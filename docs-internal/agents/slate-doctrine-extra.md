# YTDB doctrine additions

## Model routing

1. Always name both `model` and `effort`. Naming one alone takes the other from a default you did not choose.
2. The orchestrator MUST decide the restart question on every continuation of an existing thread. Supply `freshContext`
   because omission is a tool error.
   An empty list refuses a restart and preserves the live transcript.
   A non-empty list of existing episode identifiers permits a restart and seeds its replacement
   when Slate finds a restart cheaper.

Project setup and acceptance checks live in `docs-internal/dev-workflow/machine-local-setup.md`.
