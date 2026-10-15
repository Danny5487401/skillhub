# Grill With Docs

Clarify requirements when misunderstood terms, boundaries, or trade-offs would make implementation risky.
`$grill-with-docs` is an optional local aid when installed; this workflow does not require any Skill installation.

## Position in the Workflow

Run this before OpenSpec or implementation planning when any of these are true:

- The user describes a large feature in broad terms.
- Domain terms are overloaded or not yet canonical.
- The design has several meaningful branches.
- The decision may create ADR-worthy trade-offs.
- Future agents will need the reasoning, not just the final tasks.

Recommended sequence:

1. Clarify the unsettled requirements directly, or use an available clarification Skill
2. OpenSpec change for large requirements
3. Trellis task for multi-step execution context when authorized; small edits can proceed directly
4. Backend/frontend/workflow specs for implementation

## What the Skill Does

Some local setups compose two skills behind `$grill-with-docs`; inspect the installed entry rather than assuming it exists:

- `$grilling`: asks frontier questions as a decision tree until the design is no longer silently
  assuming key choices.
- `$domain-modeling`: sharpens domain language, updates `CONTEXT.md` when terms are resolved, and
  creates ADRs only for hard-to-reverse, surprising, trade-off decisions.

If these skills are unavailable, clarify directly: identify the concrete user outcome, ambiguous terms,
affected actors and systems, success/failure cases, constraints and unresolved trade-offs. Ask only questions
whose answers materially change the work. Summarize decisions and select task size using the development flow.

## SkillHub Documentation Policy

- Do not create `CONTEXT.md` just to satisfy a process. Create it only when a SkillHub-specific term
  is resolved and worth reusing.
- Do not create `docs/adr/` just to record a normal implementation choice. ADRs require a real,
  costly trade-off.
- OpenSpec remains the place for formal product behavior, acceptance scenarios, and task breakdown.
- `CONTEXT.md` is glossary only. It is not an implementation spec or scratch pad.

## Handoff Output

At the end of a grill session, capture:

- Settled decisions.
- Remaining open decisions.
- Canonical terms and avoided synonyms, if any.
- ADR candidates accepted or explicitly skipped.
- Whether the next artifact is an OpenSpec change, a Trellis task, or a direct edit.
