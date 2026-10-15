## ADDED Requirements

### Requirement: Web version display name
The system SHALL accept an optional display name on version publish without changing the uploaded package or stable slug.

#### Scenario: Explicit web name overrides package suggestion
- **WHEN** the package declares a display name and the user enters another nonblank name of at most 200 characters
- **THEN** the version snapshot uses the trimmed web name, while package file contents and frontmatter retain the original values (download ZIP compression bytes may differ)

#### Scenario: Package defaults and CLI compatibility
- **WHEN** no nonblank web name is submitted
- **THEN** metadata.display-name is used, falling back to name; invalid new package metadata remains rejected

#### Scenario: Safe prefill
- **WHEN** the user changes packages during parsing or edits the suggested name
- **THEN** an older response cannot populate the current package and prefill cannot overwrite user input

### Requirement: Version lifecycle and preview isolation
The system SHALL keep display names and summaries associated with their version through approval, rollback and promotion.

#### Scenario: Pending update
- **WHEN** an existing skill has a pending updated version
- **THEN** its management card displays that version's title and summary with one main status, and public readers continue to see the published title and summary

#### Scenario: Approval, rollback and promotion
- **WHEN** a version is approved, the latest version is yanked, or a chosen source version is promoted
- **THEN** title and summary match the relevant approved, remaining or chosen version snapshot, including a web override

### Requirement: Stable coordinate and clear private preview
The detail page SHALL show the full copyable @namespace/slug coordinate and label unpublished owner previews explicitly.

#### Scenario: Copy coordinate
- **WHEN** the user copies the skill identifier
- **THEN** the clipboard contains the full stable coordinate regardless of display name
