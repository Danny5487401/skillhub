## Why

Shared controls need a consistent foundation for future page work. The current shadcn configuration
does not explicitly resolve the UI directory, CSS and Tailwind disagree on the sans-serif font,
and shared controls do not expose a consistent density or validation treatment. Tabs also need
complete basic keyboard navigation while preserving optional controlled and uncontrolled values.

## What Changes

- Resolve shadcn aliases to the existing shared directories without replacing component APIs.
- Align self-hosted fonts and semantic light/dark tokens, and add regular/compact container density.
- Apply shared density, focus and invalid states to existing controls, cards and tables.
- Complete Tabs keyboard navigation and retain an entry point when selection is absent or invalid.
- Add behavior-focused regression coverage and verify representative existing pages.

## Capabilities

### New Capabilities

- `shared-ui-foundation`: shared theme, density, component compatibility and keyboard behavior.

## Impact

Changes are limited to frontend configuration, theme, shared UI components and regression tests.
React 19, Tailwind 3, existing Radix primitives and current business behavior are retained.
This change does not redesign business pages or add a global shortcut system.
