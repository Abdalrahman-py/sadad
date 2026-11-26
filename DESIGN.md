---
name: Sadad Admin
description: Dark-only Arabic RTL admin console for running the Sadad ledger service
colors:
  ground: "#08090a"
  surface: "#0f1011"
  raised: "#16171a"
  hover: "#ffffff0a"
  active: "#ffffff0f"
  border: "#232427"
  border-strong: "#34353a"
  rule: "#1b1c1e"
  ink: "#f7f8f8"
  muted: "#d0d6e0"
  faint: "#8a8f98"
  sadad-green: "#1ba892"
  amber: "#f5a524"
  red: "#eb5757"
  red-fill: "#c93636"
  blue: "#4c9ffe"
  grey: "#62666d"
typography:
  title:
    fontFamily: "Tajawal, Inter, system-ui, sans-serif"
    fontSize: "20px"
    fontWeight: 700
    lineHeight: 1.25
  kpi:
    fontFamily: "Inter, Tajawal, system-ui, sans-serif"
    fontSize: "26px"
    fontWeight: 500
    lineHeight: 1.1
    fontFeature: "\"tnum\" 1"
  section:
    fontFamily: "Tajawal, Inter, system-ui, sans-serif"
    fontSize: "14.5px"
    fontWeight: 700
    lineHeight: 1.3
  body:
    fontFamily: "Inter, Tajawal, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.55
    fontFeature: "\"tnum\" 1, \"cv11\" 1"
  small:
    fontFamily: "Inter, Tajawal, system-ui, sans-serif"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "Inter, Tajawal, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: 1.4
rounded:
  tag: "4px"
  control: "6px"
  panel: "8px"
  sheet: "10px"
  palette: "12px"
  pill: "999px"
spacing:
  xs: "6px"
  sm: "8px"
  md: "12px"
  gap: "16px"
  panel: "18px"
  page: "28px"
components:
  button:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "32px"
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ground}"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "32px"
  button-approve:
    backgroundColor: "{colors.sadad-green}"
    textColor: "{colors.ground}"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "32px"
  button-danger:
    backgroundColor: "{colors.red-fill}"
    textColor: "#ffffff"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "32px"
  button-ghost:
    textColor: "{colors.muted}"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "32px"
  button-ghost-hover:
    backgroundColor: "{colors.hover}"
    textColor: "{colors.ink}"
  input:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0 12px"
    height: "32px"
  panel:
    rounded: "{rounded.panel}"
  nav-item:
    textColor: "{colors.muted}"
    rounded: "{rounded.control}"
    padding: "0 10px"
    height: "30px"
  nav-item-current:
    backgroundColor: "{colors.active}"
    textColor: "{colors.ink}"
  chip-active:
    backgroundColor: "{colors.sadad-green}"
    textColor: "{colors.ground}"
    typography: "{typography.label}"
    rounded: "{rounded.tag}"
    padding: "0 8px"
    height: "22px"
  chip-waiting:
    backgroundColor: "{colors.amber}"
    textColor: "{colors.ground}"
    typography: "{typography.label}"
    rounded: "{rounded.tag}"
    padding: "0 8px"
    height: "22px"
  chip-frozen:
    backgroundColor: "{colors.blue}"
    textColor: "{colors.ground}"
    typography: "{typography.label}"
    rounded: "{rounded.tag}"
    padding: "0 8px"
    height: "22px"
  chip-archived:
    backgroundColor: "{colors.active}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.tag}"
    padding: "0 8px"
    height: "22px"
  tab-selected:
    textColor: "{colors.ink}"
    padding: "10px 12px"
---

# Design System: Sadad Admin

## Overview

**Creative North Star: "The Quiet Operator's Console"**

The category-standard dark admin, played straight at the craft level of Linear, Vercel and Stripe. One operator runs a small real service from this screen, often with a shop owner waiting on the phone, so the console stays quiet and lets the queue speak: a near-black ground, an inset sheet that holds the work, thin rules instead of boxes, and colour reserved for status. Values were measured from Linear's live app and Vercel Geist's dark tokens, then adjusted for Arabic.

Density is that of a working tool: 32px controls, 14px body, tables first. The page is right-to-left Arabic set in Tajawal, while every digit, code and amount runs in Inter with tabular figures and isolated left-to-right direction. Depth comes from three tonal steps of near-black and hairline borders; shadows exist only under things that float.

The world refuses vanity tiles, decorative icon circles, gradients and glow. The brand green is not decoration: it is the colour of "active" and "approved", and nothing else.

**Key Characteristics:**
- Dark only; `color-scheme: dark`, no light theme.
- Inset sheet shell: sidebar on the ground, work inside one bordered sheet.
- Ink-white primary action; green only for positive status and approve.
- Solid status colours, each with one meaning, always paired with a word.
- Tajawal for Arabic headings and text, Inter tabular digits for all data.
- Flat surfaces; shadow only on menus, toasts, dialogs and the drawer.

## Colors

A near-black neutral ladder with cool-grey text and five solid status hues that never stand in for one another.

### Primary
- **Sadad Green** (sadad-green): the app's brand colour. Active and approved status chips and dots, the approve button, a completed step, a switch that is on, success help text, and text selection (at about 33% alpha). Never a background wash, never a link colour, never the main call to action.

### Secondary
- **Waiting Amber** (amber): waiting and ending soon. Status chips, the sidebar task count badge, warning text in table cells.
- **Expired Red** (red): expired, rejected, danger. Status chips, the danger button, error help text, overdue table text. The danger panel tints its border to a dark red (#3a1d1d) and its heading to a light red (#ff8a8a).
- **Frozen Blue** (blue): frozen by an admin. Status chips and dots, the icon in the read-only banner.

### Neutral
- **Ground** (ground): page background, the sidebar, inputs and the secret-code card (inputs sit darker than the sheet around them).
- **Sheet** (surface): the inset sheet, its sticky header, dialogs.
- **Raised** (raised): default buttons, menus, the command palette, banners, message previews.
- **Hover / Active veils** (hover, active): 4% and 6% white veils for hovered rows and items, and current or selected items.
- **Border** (border): the sheet edge, panel edges, header and tab underlines.
- **Strong Border** (border-strong): controls, menus, dialogs, keyboard hints, separators in menus.
- **Rule** (rule): row dividers inside tables, lists and key-value blocks; quieter than a border.
- **Ink** (ink): headings, names, values, and the primary button fill.
- **Muted** (muted): body copy, nav items, labels, column-adjacent descriptions.
- **Faint** (faint): secondary meta, table headers, icons at rest, placeholders, timestamps.
- **Archive Grey** (grey): archived, revoked or off status dots, empty-cell dashes, breadcrumb chevrons. Not used for readable text.

### Named Rules
**The One Meaning Rule.** Green is active or approved, amber is waiting or ending, red is expired or danger, blue is frozen by an admin, grey is archived or revoked. A status colour never appears for any other reason, and never without its word beside it.

**The Ink Primary Rule.** The main action on a page is an ink-white button on dark text. Green is earned by status; it does not mark the most important button.

## Typography

**Heading Font:** Tajawal (with Inter, system-ui)
**Body and Data Font:** Inter (with Tajawal for Arabic glyphs, system-ui)

**Character:** Tajawal gives the Arabic headings a firm, upright weight; Inter carries the Latin, the digits and the codes with tabular figures (`tnum`) and its straight-sided `cv11` alternates, so columns line up and codes read unambiguously.

### Hierarchy
- **Title** (700, 20px, 1.25): page title in the title row; the sign-in heading.
- **KPI** (500, 26px, 1.1, tabular): the value in each KPI cell; 22px on phones. A smaller grey unit may follow at 14px.
- **Section** (700, 14.5px, 1.3): panel headings. Dialog headings step up to 17px, the sheet header title to 15px.
- **Body** (400, 14px, 1.55): all running text, table cells, nav (nav and names at weight 500).
- **Small** (400, 12.5px): table headers, second lines in rows, help text, counts.
- **Label** (500, 12px): status chips, badge counts, sidebar and menu group labels, KPI hints.
- **Code** (600, 28px, letter-spacing 0.14em, Inter, LTR): the one-time temporary code in the secret card only.

### Named Rules
**The Tabular Digits Rule.** Every number, code, date and username is set in Inter with tabular figures, isolated left-to-right inside the RTL page, and numeric columns align to the left edge.

**The No Uppercase Rule.** Labels are sentence-case Arabic at 12px; no tracked capitals anywhere except the spacing of the temporary code.

## Layout

A two-column shell: a sticky sidebar (236px, on the ground, at the right in RTL) and a fluid canvas. The canvas holds one inset sheet with 8px of ground showing around it, 10px corners and a 1px border. A 48px sticky sheet header carries breadcrumbs and the search trigger (300px, with a keyboard hint). Page content sits in a centred column of at most 1240px, padded 24px 28px 56px, stacked with a 22px gap.

One gap token (16px) separates panels and grid columns. Inside panels the rhythm is 18px horizontal padding, 14px header padding, 10-11px table-cell padding. The task page uses a main column plus a 300px side column; forms use a two-column grid at 18px gap, or a single centred 600px column for create flows.

Responsive steps: under 1100px the KPI strip folds to 2x2; under 1000px all grids go single-column; under 760px the sidebar becomes a 280px off-canvas drawer behind a scrim, a 52px top bar appears, the sheet dissolves into the ground, page padding drops to 16px and row actions grow to 40px touch targets. Tables respond to their container, not the viewport: below 620px of panel width (900px for wide store lists) each row becomes a two-line card with labelled meta.

## Elevation & Depth

Flat, tonal layering. Depth is conveyed by three near-black steps (ground, sheet, raised), 4-6% white veils for hover and selection, and hairline borders; panels sit on the sheet with a 1% white fill and no shadow. Shadows appear only on layers that float above the page.

### Shadow Vocabulary
- **Float** (`box-shadow: 0 12px 32px #00000080`): menus and the toast.
- **Modal** (`box-shadow: 0 24px 64px #000000b3`): dialogs and the command palette, over a 60-70% black backdrop.
- **Drawer** (`box-shadow: 0 0 48px #000000b3`): the phone sidebar drawer when open.

### Named Rules
**The Float-Only Shadow Rule.** If it sits in the page flow, it has no shadow. Shadows mark things that cover other things.

## Shapes

Small, consistent radii that grow with the size of the container: 4px for chips, keyboard hints and skeleton bars; 6px for buttons, inputs, nav items and banners; 8px for panels, menus, the segmented control and the toast; 10px for the sheet and dialogs; 12px for the command palette. Status dots, avatars and the switch are full circles or pills. Borders are always 1px; the only 2px line is the selected-tab underline and the focus outline. Icons are Lucide line icons at 16px with a 1.75 stroke (13-14px inside dense controls), never set in decorative circles or tiles.

## Components

### Buttons
Quiet, compact, and ranked by fill.
- **Shape:** gently rounded (6px), 32px tall; small variant 30px, icon variant square.
- **Default:** raised fill, strong border, ink text, weight 500. Hover lifts to #1d1e21 with an 18% white border.
- **Primary:** ink fill on ground text; hover goes pure white. One per view.
- **Approve:** green fill, near-black text; for approving a device or confirming a positive change only.
- **Danger:** red-fill (#c93636) with white text (5.2:1), for destructive confirmations inside dialogs and the danger zone. Plain red (#eb5757) is for dots and text only; white on it fails AA.
- **Ghost:** transparent, muted text; hover shows the 4% veil.
- **Motion:** 150ms ease on colour and border; a 1px press on active. Disabled at 45% opacity.
- **Split button:** a default button joined to a menu trigger, inner corners squared.

### Chips and Status
- **Status dot + word:** an 8px solid dot beside the plain word; the default way to show state in tables and feeds.
- **Chip:** 22px, 4px radius, solid status fill with dark text (archived uses a 8% white fill with ink text), 12px weight 500. Used where a state needs more weight than a dot.

### Cards / Containers
- **Panel:** 8px radius, 1px border, 1% white fill, no shadow. Header row at least 52px with a 14.5px heading, a muted count and trailing actions; body padding 18px; optional footer note above a border.
- **KPI strip:** one panel split into four cells by vertical rules, each a label, a large tabular value and a faint hint; the whole cell is a button with the hover veil.
- **Secret card:** ground fill, strong border, rows of label, LTR value and copy button; holds the temporary code.
- **Danger panel:** the panel with a dark red border and light red heading; rows of explanation and a trailing button.

### Inputs / Fields
- **Style:** ground fill (darker than the sheet), strong border, 6px radius, 32px tall, 12px padding, faint placeholder.
- **Focus:** border turns ink; no glow.
- **Field:** label at weight 500 above, 6px gap, small faint help text; help turns green for ok and red for errors.
- **Segmented control:** ground track with strong border and 8px radius, 28px segments; the pressed segment gets an 8% white fill.
- **Switch:** 36x20 pill, dark track, green when on.

### Navigation
- **Sidebar:** brand mark and wordmark, then 30px nav items (muted, weight 500, 6px radius) with faint 16px icons. Hover shows the 4% veil, the current page the 6% veil with ink text and icon. Group labels are faint 12px. A task count badge sits at the item's end in amber. The user row at the foot opens a menu upward.
- **Tabs:** underline tabs; muted text, selected tab ink with a 2px ink underline and its count lifted to muted.
- **Command palette:** 600px raised dialog opened from the header search; 52px search row, 40px items, active item on the 6% veil, keyboard hints in the footer.
- **Phone:** a 52px top bar with brand and menu button; the sidebar slides in from the right over a scrim.

### Data Table
The signature surface. Headers are faint 12.5px weight 400 with no fill over a border; cells pad 11px 14px (18px at the outer edges) over quiet rule dividers. Names are ink weight 500 with a faint second line. Clickable rows take the hover veil; ids reveal a copy button on hover. Stamped (archived) rows fade to faint with a struck-through name. Numeric columns are tabular and left-aligned. Narrow containers turn rows into two-line cards.

## Do's and Don'ts

### Do:
- **Do** take every colour from the tokens; the production build exposes them as CSS variables under a strict `style-src 'self'` CSP, with fonts and icons self-hosted.
- **Do** pair every status colour with its word, and keep each colour to its one meaning.
- **Do** make the ink-white button the single primary action of a view.
- **Do** set digits, codes, dates and usernames in Inter with tabular figures and isolated LTR direction.
- **Do** put work inside the inset sheet and panels; separate rows with rules, not boxes.
- **Do** keep controls at 32px on desktop and grow row actions to 40px on phones.
- **Do** honour reduced motion: all transitions and animations switch off.

### Don't:
- **Don't** add a light theme; the only light fills are the ink primary button, the toast and the selected step.
- **Don't** use gradients, glow, vanity metric tiles, or decorative icons set in circles or tiles.
- **Don't** use Sadad green for decoration, links or the main call to action.
- **Don't** put a shadow on anything that sits in the page flow.
- **Don't** introduce inline styles or runtime style tags; they break the CSP.
