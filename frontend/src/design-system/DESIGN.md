# Support Control design system

This directory defines the visual and interaction contract for the internal
customer-support workspace. The direction is a modern enterprise-finance console:
calm, dense, legible, and explicit about risk. It must not resemble a consumer
dashboard or a marketing site.

## Core principles

1. Operational information comes before decoration.
2. Colour reinforces a written status; colour is never the only signal.
3. One primary action is visible per task area. Destructive supervisor actions
   use the danger treatment and are visually separated from normal case work.
4. Loading, empty, error, success, disabled, selected, hover, and focus states
   are designed states, not incidental browser behaviour.
5. Lists are capped at 10 by the platform contract. The UI must say when the
   cap is reached and help the operator refine the query.
6. A case detail is a contextual destination, never a top-level workspace.

## Visual rules

### Colour

- Canvas: cool neutral grey, so white work surfaces remain distinct.
- Navigation: deep navy, providing a stable product frame.
- Product accent: restrained red, reserved for selection and primary action.
- Positive: green; warning: amber; negative/breach: red; information: blue;
  neutral/terminal: slate.
- Tone variables provide separate surface, border, text, accent, and figure
  roles. Never use a bright mark colour as body text.

All literal colours live in `theme/glass.css`. The legacy filename is kept to
preserve the design-system import contract; its content is the Operations Console
theme and contains no glass effects.

### Typography

- Use the operating-system sans-serif stack for fast, familiar rendering.
- Page titles are 26px/semibold; section titles are 15–17px/semibold.
- Body copy is 13px; labels and table headings are 11px semibold uppercase.
- IDs and machine values use the mono stack. Numeric columns use tabular figures.
- Avoid decorative display faces.

### Spacing and density

- The spacing scale follows a 4px rhythm.
- Controls are 38px high; compact controls are 32px.
- Table rows use 12–14px vertical padding and remain horizontally scrollable at
  narrow widths.
- Page sections use 20–32px separation. Related fields use 12–16px.

### Shape, borders, and elevation

- Radii are restrained: 3px controls/badges, 5px buttons, 8px panels.
- Panels use a single neutral border and at most a subtle 1px/2px shadow.
- No backdrop blur, textured wallpaper, large floating shadows, or gradients.
- Metric tiles use a narrow top rule to convey tone without flooding the card.

## Layout

- Desktop: a persistent 232px navy navigation rail and a fluid content canvas,
  capped at 1500px.
- Below 860px: navigation becomes a horizontal header and two-column page splits
  stack.
- Below 640px: page padding tightens, form grids become one column, and tables
  scroll rather than compressing their content into unreadable cells.
- Page headers contain title, concise operating rule, metadata, and contextual
  actions. They are separated from the work area by a quiet divider.

## Interaction rules

- Entire case rows are clickable and keyboard activatable with Enter or Space.
  Do not add a duplicate `View` button.
- Every interactive row and control has hover, focus-visible, disabled, and
  selected feedback.
- The URL for a case is `/cases/{caseId}`. Returning from detail restores the
  source URL, filter query, and scroll position where possible.
- Search accepts case ID, application ID, or customer name. Clearing it returns
  to the priority queue. Empty results provide constructive alternatives.
- Only legal lifecycle actions are rendered. CLOSED cases show an immutable
  terminal state; supervisor actions never expose operations the API forbids.
- External applicant lookup failures affect only the applicant panel. The local
  case and timeline remain usable, with a retry action.

## Components and tokens

Screens import components from the barrel:

```jsx
import {
  AppShell,
  PageHeader,
  DataTable,
  Badge,
  Alert,
} from "./design-system";
```

Shared values come from:

```text
tokens/tokens.css   spacing, type, radii, controls, layout
theme/glass.css     Operations Console semantic colours and surfaces
styles/*.css        reusable component behaviour
```

Application-specific CSS may compose components but should consume `--ds-*`
tokens. If a pattern appears on three screens, promote it into the design system.

## Tone mapping

The design system does not know business vocabulary. `src/status.js` maps domain
values to the five tones:

| Tone | Meaning | Support examples |
| --- | --- | --- |
| positive | completed or healthy | RESOLVED |
| warning | attention required | OPEN, PENDING_CUSTOMER, P2 |
| negative | urgent or failed | breached, P1 |
| info | newly in progress | NEW |
| neutral | terminal/no judgement | CLOSED, P3 |

The status word always remains visible inside a badge or beside a mark.

## Accessibility checklist

- Landmarks identify navigation, main content, and footer.
- Page title uses `h1`; sections follow a coherent heading hierarchy.
- Forms have visible labels, native required/disabled semantics, and inline
  server errors.
- Loading messages use live regions; errors and success messages use alerts.
- Focus is never removed without an accessible replacement.
- Text and interactive states meet WCAG AA contrast on their actual surfaces.
- Motion is limited to short colour transitions and is disabled for
  `prefers-reduced-motion`.
