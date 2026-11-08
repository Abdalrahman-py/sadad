---
name: Sadad Admin Dashboard
description: A plain black-and-white report that a reviewer marks in red.
colors:
  red: "#c4161c"
  ink: "#000000"
  paper: "#ffffff"
  muted: "#595959"
  hair: "#d0d0d0"
  hover: "#f2f2f2"
typography:
  display:
    fontFamily: "Tajawal, \"Noto Sans Arabic\", system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 700
    lineHeight: 1.2
  headline:
    fontFamily: "Tajawal, \"Noto Sans Arabic\", system-ui, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 700
    lineHeight: 1.3
  lead:
    fontFamily: "Tajawal, \"Noto Sans Arabic\", system-ui, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 400
    lineHeight: 1.6
  title:
    fontFamily: "Tajawal, \"Noto Sans Arabic\", system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 700
    lineHeight: 1.6
  body:
    fontFamily: "Tajawal, \"Noto Sans Arabic\", system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: "Tajawal, \"Noto Sans Arabic\", system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.6
  table-head:
    fontFamily: "Tajawal, \"Noto Sans Arabic\", system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 700
    lineHeight: 1.6
  data:
    fontFamily: "ui-monospace, \"SF Mono\", Menlo, Consolas, monospace"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.6
rounded:
  none: "0"
spacing:
  quarter: "0.25rem"
  half: "0.5rem"
  row: "0.75rem"
  gap: "1rem"
  dialog: "1.5rem"
  page-inline: "2rem"
  gap-lg: "1.5rem"
  control: "2.25rem"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
    typography: "{typography.title}"
    rounded: "{rounded.none}"
    padding: "0 1rem"
    height: "{spacing.control}"
  button-primary-hover:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
  button-outline:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.title}"
    rounded: "{rounded.none}"
    padding: "0 1rem"
    height: "{spacing.control}"
  button-outline-hover:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
  button-danger:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.red}"
    typography: "{typography.title}"
    rounded: "{rounded.none}"
    padding: "0 1rem"
    height: "{spacing.control}"
  button-danger-hover:
    backgroundColor: "{colors.red}"
    textColor: "{colors.paper}"
  button-danger-solid:
    backgroundColor: "{colors.red}"
    textColor: "{colors.paper}"
    typography: "{typography.title}"
    rounded: "{rounded.none}"
    padding: "0 1rem"
    height: "{spacing.control}"
  button-danger-solid-hover:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.red}"
  button-disabled:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.muted}"
  text-field:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: "0.25rem 0.75rem"
    height: "{spacing.control}"
  nav-link:
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: "0 0.75rem"
    height: "{spacing.control}"
  nav-link-selected:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
  count:
    textColor: "{colors.red}"
    typography: "{typography.title}"
  secret-band:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
    rounded: "{rounded.none}"
    padding: "{spacing.gap}"
  toast:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
    rounded: "{rounded.none}"
  toast-error:
    backgroundColor: "{colors.red}"
    textColor: "{colors.paper}"
  dialog:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.none}"
    padding: "{spacing.dialog}"
    width: "min(32rem, calc(100vw - 2rem))"
---

# Design System: Sadad Admin Dashboard

This record covers the admin dashboard in `server/public/` only. The Android app's design record stays in `docs/ux-redesign/`.

## Overview

**Creative North Star: "The Marked Report"**

The dashboard is a plain black-and-white report that a reviewer marks in red. It has no side menu, no stat tiles and no soft cards. Pages are dense: a compact heading, one sentence that says what is waiting, then rows with the action at the end of each row. Stores use a split view: the list stays on screen and a store opens beside it.

Colour is a debt of attention. Red appears only where a person has to act or where an action destroys something, so clearing the queue removes the red from the Tasks screen. Everything else is black text on white, separated by thin rules. The page changes in instant steps, the way a marked sheet is replaced by a cleaner one.

The density is that of a working document: short rows, plain words for state, and underlined links. The layout is Arabic first and right to left, and a phone can finish every task a laptop can.

**Key Characteristics:**
- White ground, black Tajawal, one red.
- Thin rules and square corners instead of cards and shadows.
- State is a plain word, and red only when it needs a person.
- Selected means inverted to a black bar.
- Every change is instant.

## Colors

Black on white with one red, and two greys that do quiet work.

### Primary
- **Reviewer's Red** (#c4161c): The only accent. It marks waiting tasks, the task count, ended or ending subscriptions, form errors, destructive buttons and the error toast. It measures 6.04:1 on white, so it is safe for small text.

### Neutral
- **Ink** (#000000): All text, solid primary buttons, selected states, structural rules, and the secret and toast bands. Primary actions are ink, never red.
- **Paper** (#ffffff): The page and every control background, and the text on inverted bars.
- **Pencil Grey** (#595959): Secondary lines, hints, placeholders and disabled text. It measures 7:1 on white.
- **Hairline Grey** (#d0d0d0): The rule between rows, and the border of disabled controls. It is never used for text.
- **Row Tint** (#f2f2f2): The hover background on table rows, and nothing else.

### Named Rules
**The Debt of Attention Rule.** Red means a person must act, or the action destroys something. It never decorates. At zero tasks the Tasks screen carries no red at all. Destructive buttons on a store's settings stay red, because they warn about the action and do not report a debt.

**The Weight Is Severity Rule.** Bold red is due now: a waiting task, an ended subscription, a form error. Regular red is coming soon or secondary: a subscription about to end, a device limit that is full.

## Typography

**Display Font:** Tajawal (with Noto Sans Arabic, system-ui, sans-serif)
**Body Font:** Tajawal (with Noto Sans Arabic, system-ui, sans-serif)
**Label/Mono Font:** ui-monospace, SF Mono, Menlo, Consolas, monospace, for machine strings only

**Character:** One family carries the whole report. Hierarchy comes from a large jump in size and from bold against regular. Tajawal ships with the build as four woff2 files, Arabic and Latin in regular and bold. Numbers are tabular everywhere, so counts line up in columns.

### Hierarchy
- **Display** (700, 1.5rem, 1.2): The one page heading.
- **Headline** (700, 1.25rem, 1.3): Section headings and dialog titles. Also the brand name and the numbers in store facts.
- **Lead** (400, 1.25rem, 1.6): The one sentence under the page heading.
- **Title** (700, 1rem, 1.6): Sub-headings, field labels, button text and store names in rows.
- **Body** (400, 1rem, 1.6): Row content, inputs and dialog text.
- **Label** (400, 0.875rem, 1.6): Second lines in a row, hints, footnotes and the user line in the top bar.
- **Table Head** (700, 0.875rem, 1.6): Column headings.
- **Data** (400, 0.875rem, monospace): Device IDs and other machine strings. The one-time password sets it larger and bold at 1.125rem so it can be read out.

### Named Rules
**The Two Weights Rule.** Only regular (400) and bold (700) exist. Those are the two weights that ship, and synthetic bold is turned off.

**The Joined Script Rule.** No letter spacing and no uppercase styling. Arabic letters join, and spacing breaks them apart.

**The Machine String Rule.** Monospace is for strings a machine made: IDs, keys, passwords. It never sets a heading or a sentence.

## Layout

Pages use the full window width with a 1.5rem side margin, shared by the top bar and the toast. Sections stack with a 1.5rem gap. Stores split into a list pane (24 to 34rem) and a detail pane, each with its own scroll, so the list keeps its place. With no store open the list takes the full width and shows every column. At 60rem and below only one pane shows at a time.

Every page starts the same way: heading and lead sentence at the start edge, page actions at the end edge, and on inner pages a plain link back to the parent above the heading. Forms sit in a 40rem column or in a 3 to 2 split. Task rows use a four-part grid: what is needed, the store, the details, and the actions in a fixed 24rem column.

Below 60rem, task rows and the split collapse to one column. Below 40rem, the side margin becomes 1rem, the section gap 1.5rem, the control height 2.75rem, the top bar stops sticking, paired fields stack, and the search box takes the full width.

**The Priority Drop Rule.** On a phone a table drops its lowest-priority columns and, where the reader still needs them, folds those values into a small line under the row's main cell. State and action stay.

**The Store Number Rule.** A store is shown with its number wherever it appears in a queue or list.

**The Logical Edge Rule.** Every margin, padding, border and alignment uses a logical property (start and end, not left and right), so right to left is correct by construction. Latin strings such as emails and usernames are isolated with their own direction.

## Elevation & Depth

The system is flat. Nothing casts a shadow, and depth comes from rules and from inversion. The top bar is held apart by a 2px ink rule and stays stuck to the top on a laptop. The dialog is a white sheet with a 2px ink frame over a 50% black scrim, and that scrim is the only transparency in the system.

### Shadow Vocabulary
- **Check mark inset** (`box-shadow: inset 0 0 0 3px #fff`): Draws the white gap inside a ticked box. It is a drawing device, not elevation, and it is the only use of the property.

### Named Rules
**The No Cast Shadow Rule.** A surface is separated by a rule or by inversion, never by a shadow beneath it.

**The Instant Step Rule.** There are no transitions. Hover, selection, disclosure and row removal all happen in one step. The one moving thing is the 2px progress line at the top of the window, shown while data refreshes; reduced motion holds it still.

**The No-Jump Rule.** Content never moves because data is loading. A first load shows placeholder rows at row height. A refresh keeps the old rows on screen. A store opens at once from its list row and from the in-memory cache, then refreshes behind the view. A one-tap action disables its row, says it is saving, and clears the row as soon as the server agrees.

## Shapes

Every corner is square (0), enforced for every element. The form language is the straight line, in three strengths: a 1px hairline grey rule between rows, a 1px ink rule for structure (the top of a list, the table head, the tab line, control borders), and a 2px ink rule for the shell (the top bar and the dialog frame). The ticked box is a square inside a square. The disclosure arrow is two drawn strokes.

**The Square Rule.** No rounded corners on anything, including inputs and focus rings.

## Components

Controls are square, blunt and plainly labelled. Each one states what it does in words.

### Buttons
- **Shape:** Square corners (0), 1px border, 2.5rem minimum height (2.75rem on a phone), bold text, 1rem side padding.
- **Primary:** Solid ink with paper text. At most one per row or page head, for the action most likely to be taken next.
- **Hover / Focus:** Hover swaps the fill and the text in one step, on every variant. Focus is a 2px ink outline with a 2px offset.
- **Outline:** Paper with an ink border, for every other action.
- **Destructive:** Red border and red text at rest. Inside a confirming dialog the final action is solid red.
- **Disabled:** Hairline grey border, Pencil Grey text, no hover change.
- **Text button:** No box, underlined like a link, for quiet actions in the top bar and in toasts.

### Cards / Containers
There are no cards. Grouped content is a list of rows: a 1px ink rule on top, a hairline rule under each row, and 0.75rem of padding above and below the row content. Support-mode notices sit between two 1px ink rules with no fill.

### Tables
Column heads are bold label text over a 1px ink rule. Cells are top-aligned with hairline rules between rows. Rows take the Row Tint on hover. Tables scroll inside their own frame when they cannot fit.

### Inputs / Fields
- **Style:** Full width, 1px ink border, paper background, square, with the label above in bold and a small hint below.
- **Focus:** The shared 2px ink outline with a 2px offset.
- **Error / Disabled:** The input border and hint turn red, and the error line is bold red. Disabled fields take a hairline border and Pencil Grey text.
- **Checkbox:** A 1.125rem ink square. Ticked, it fills with ink inside a white gap.
- **File picker:** Drawn as an outline button, with the focus ring on its wrapper.

### Navigation
The top bar holds the brand, three text links, then the role, email, refresh and sign out at the end edge. Store tabs (account, ledger, activity log) sit on a 1px ink rule; settings and recovery open as links from the store header. Filters are segmented text buttons. All three share one behaviour: plain text at rest, underlined on hover, inverted when selected. On a phone the bar wraps to two lines.

**The Inversion Rule.** Selected is an inverted black bar and nothing else. No underline tab, no tinted pill, no accent colour.

### Count
A bold red number beside a nav link or a tab, shown only when it is above zero. On a selected bar it turns white. It falls with the queue, and at zero it disappears.

### Task Row
The signature component. The need is stated first in red, then the store and its number, then the details, then the buttons. Clearing a task removes the row and its red mark at once, and the nav count and the lead sentence fall with it.

### State Word
State is a plain word in the row's text. Ink means fine, Pencil Grey means inactive, regular red means soon, bold red means ended. It has no background and no border.

### Secret Band
A full-width ink band that holds a one-time password, its copy button and a dismiss button. Colours invert inside it, including buttons, text selection and the focus ring. The password shows once, so this is the loudest thing in the system.

**The Must Not Miss Rule.** A solid block is reserved for what a person must not miss. Only the one-time secret takes a solid band in the page.

### Toast
A full-width band fixed to the bottom of the screen, on the same measure as the page. Ink for a notice, solid red for an error. It holds a sentence and a dismiss text button.

### Dialog
A 32rem white sheet with a 2px ink frame over the scrim. Title, one sentence, fields, then actions at the end edge with cancel first. Dangerous account actions ask for a typed username, and most ask for a recorded reason. The final button stays disabled until what the dialog asks for is filled in.

## Do's and Don'ts

### Do:
- **Do** use #c4161c as the only accent, and only for something a person must act on or an action that destroys.
- **Do** make the primary button solid ink, and keep red buttons for destructive actions.
- **Do** write state as a plain word in the row's text.
- **Do** underline links, and thicken the underline to 2px on hover.
- **Do** invert to a black bar to show what is selected.
- **Do** keep text contrast at 4.5:1 or better, and keep the 2px focus outline visible on every background.
- **Do** use logical properties for every edge.
- **Do** put the action at the end of the row, and keep it there on a phone.

### Don't:
- **Don't** use the Android app's green, or add a second accent.
- **Don't** add cards, chips or rounded corners.
- **Don't** cast a shadow under anything.
- **Don't** put a tinted panel behind content. The Row Tint is for table row hover only.
- **Don't** add a transition or an animation.
- **Don't** add icons where a word works. The drawn disclosure arrow is the only one the build needs.
- **Don't** add a side menu or a row of stat tiles.
- **Don't** add letter spacing or uppercase styling.
- **Don't** add a font weight other than 400 or 700.
