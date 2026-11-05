---
version: 1
slug: "server-public-src-views-app-ts"
primary_target: "server/public/src/views/app.ts"
related_targets: ["server/public/src/styles.css"]
---

Scope: the whole admin dashboard in `server/public/` (all screens). Visitor mode: Operate.

Task: clear the queue first, look up a store second. Laptop first; a phone must still finish every task. Must stay: all function, all Arabic copy, roles, CSP.

## Direction contract

THESIS: The dashboard is a plain black-and-white report that a reviewer marks in red. It refuses the category default of sidebar, stat tiles and soft cards. Colour is a debt of attention: at zero tasks, no colour is left on the page.

OWN-WORLD: White ground, black Tajawal, one red (#c4161c). Hairline rules, square corners, no shadows, no fills, no icons beyond what text cannot do. Links are underlined. Selected is an inverted black bar. Buttons are square: solid black for the primary action, black outline otherwise, red only when destructive. State is a plain word, red only when it needs a person. Raises: red never decorates (warm app); no easing, every change is an instant step (acetate); inversion is the only selected state (Datamatics); tables drop columns by priority on a phone and keep the action (transit); every store carries its number (catalog).

STORY: The admin opens the page, reads one sentence saying how much is waiting, clears rows from top to bottom, and sees the red disappear.

FIRST VIEWPORT: One top line: brand, text nav, user, sign out. Heading المهام at large scale at the start edge, one sentence below it. Then one full-width task table ranked by urgency, with the action at the row end. One line of platform numbers below it. The primary action is the black button in the first row.

FORM: The Marked Report, position 7 on the ordered list, seed key d5972c14. Code-led; no image generation in this harness.

Signature interaction: clearing a task removes its row and its red mark at once, and the count in the nav and the sentence fall with it.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
