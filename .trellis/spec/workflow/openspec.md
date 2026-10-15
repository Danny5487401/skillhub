# OpenSpec Workflow

OpenSpec remains the formal workflow for large SkillHub requirements.

Authoritative source:

- `openspec/`
- Existing example: `openspec/changes/add-skill-suites/`

## When to Use OpenSpec

Use or create `openspec/changes/<change-id>/` for large requirements or architecture decisions, such as:

- A substantial new product capability or large user-facing workflow.
- Architecture or module-boundary change.
- Breaking or broad public API, persistence, security, or lifecycle changes requiring coordinated design and acceptance.

An ordinary multi-step feature uses task records; changing one endpoint or adding one test does not
automatically require a new OpenSpec change. If an accepted OpenSpec already owns the affected behavior,
update that source rather than creating a competing contract. Preserve the user's existing task-creation choice.

## Expected Artifacts

Each large change should have:

- `proposal.md`: why the change exists and what problem it solves.
- `specs/<capability>/spec.md`: behavior deltas and acceptance scenarios.
- `design.md`: technical approach and important trade-offs.
- `tasks.md`: implementation and verification checklist.

## Trellis Relationship

Trellis tasks may link to an OpenSpec change. Trellis should not replace OpenSpec for formal
requirements. If task work changes accepted behavior, update the OpenSpec source first.
