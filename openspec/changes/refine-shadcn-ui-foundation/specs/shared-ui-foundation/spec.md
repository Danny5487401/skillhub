## ADDED Requirements

### Requirement: Component generation uses existing shared directories
The project SHALL configure shadcn aliases to generate UI primitives into `src/shared/ui` and
resolve imports using the existing TypeScript and Vite aliases.

#### Scenario: Preview a new component
- **WHEN** a representative component is previewed with the compatible CLI
- **THEN** its UI file resolves into `src/shared/ui`, without a nested `ui/ui` directory
- **AND** dependencies and source changes can be reviewed before applying them

### Requirement: Shared theme and density are consistent
The frontend SHALL use consistent self-hosted font families and semantic light/dark colors.
Shared controls SHALL support regular and compact container density without reducing default text size.

#### Scenario: Switch density
- **WHEN** a container switches from regular to compact
- **THEN** default controls change from 36px to 32px and card/table spacing follows the shared density
- **AND** table heading and data text remain horizontally aligned
- **AND** caller-provided class and style overrides remain effective

#### Scenario: Validation and reduced motion
- **WHEN** a form control is invalid or receives keyboard focus
- **THEN** it has visible invalid or focus treatment appropriate to the selected theme
- **WHEN** reduced motion is requested
- **THEN** nonessential shared control motion is suppressed

### Requirement: Existing component contracts remain compatible
Shared component changes SHALL retain existing variants, native attributes, refs, events, controlled
and uncontrolled values, form semantics, portal behavior and panel mounting behavior.

#### Scenario: Reuse an existing button variant
- **WHEN** a page renders an existing variant such as `soft`
- **THEN** the variant remains available and existing caller attributes are honored

### Requirement: Tabs are keyboard accessible
Tabs SHALL support arrow-key navigation, Home/End and disabled-item skipping using the current
horizontal direction. At least one enabled trigger SHALL provide a keyboard entry when available.

#### Scenario: Navigate enabled tabs
- **WHEN** the user presses a navigation key on a tab
- **THEN** focus and selection follow the intended enabled trigger in rendered order
- **AND** disabled triggers are skipped

#### Scenario: Missing or invalid selection
- **WHEN** selection is absent, invalid, removed or disabled and an enabled trigger exists
- **THEN** the first enabled trigger remains reachable with Tab
- **AND** establishing that entry does not change selection or invoke the selection callback

#### Scenario: All triggers are disabled
- **WHEN** every trigger is disabled
- **THEN** no trigger is exposed as an actionable keyboard entry

### Requirement: Regression evidence is repeatable
The change SHALL include repeatable component regression tests and pass relevant frontend checks.
Browser fixtures SHALL be separate from production entry points.

#### Scenario: Validate component behavior independently
- **WHEN** the component fixture is tested without a backend
- **THEN** keyboard and density assertions run against actual shared components
- **AND** the result is described as component verification rather than backend acceptance
