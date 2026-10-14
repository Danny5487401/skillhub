## Approach

Keep component source in `web/src/shared/ui` and use explicit shadcn aliases for UI, composition,
utilities and hooks. Compare compatible upstream source before adapting individual components;
existing APIs, variants, native attributes, refs and event behavior remain the compatibility baseline.

CSS custom properties own semantic colors, fonts and density. Tailwind consumes these properties.
Regular density remains the default; `data-density="compact"` changes control height and surface
spacing without shrinking default control text. Explicit caller classes and styles remain effective.
Table headings and cells share horizontal padding. Portal content must retain usable independent sizing.

Preserve self-hosted Inter and JetBrains Mono and platform-appropriate fallback fonts. Provide semantic
status colors in both themes, visible focus and invalid styling, and reduced-motion treatment.

Keep the existing Tabs API and panel mounting behavior. Navigate enabled triggers in their rendered
order with arrow keys and Home/End; respect horizontal direction. A valid enabled selection is the
tab stop, otherwise the first enabled trigger is. Recomputing a tab stop must not change selection
or invoke `onValueChange`. An entirely disabled list has no actionable tab stop. Trigger IDs/aria-controls and panel IDs/aria-labelledby
are reserved for the generated association; other supported native attributes and refs remain available.

## Validation

Use component behavior tests and an isolated browser fixture for keyboard, density and validation
contracts. Verify light/dark and regular/compact combinations plus representative page layouts.
Keep test fixtures out of production entry points. Actual backend smoke results are reported separately
from UI fixtures; successful frontend tests alone do not establish backend integration health.

## Risks

Shared font and spacing changes affect many callers. Check existing variants and explicit class/style
overrides, table alignment, nested portals and responsive layouts before accepting the change.
No dependency migration or global component replacement is required.
