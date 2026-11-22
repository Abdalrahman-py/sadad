# UI theme: palette to Material 3 roles

Ticket 201 (#371). Code reads common colours from `MaterialTheme.colorScheme`. The brand roles that Material 3 has no slot for live in `SadadBrand`, read with `sadadBrand` (`app/src/main/kotlin/com/sadad/app/ui/theme/SadadTheme.kt`). `sadadPalette` is deprecated and stays until ticket 208 removes the last reads. There is no dynamic (wallpaper) colour.

## Palette field to role

| `SadadPalette` field | Read instead | Notes |
| --- | --- | --- |
| `bg` | `colorScheme.surface` / `background` | The page. |
| `surface` | `colorScheme.surfaceContainer` | Filled fields, soft buttons, light `surfaceVariant`. |
| `soft` | `colorScheme.surfaceContainerHighest` | Also light `secondaryContainer`. |
| `card` | `colorScheme.surfaceContainerLowest` | Cards, panels, sticky action bars. |
| `primary` | `colorScheme.primary` | Filled buttons. Also `sadadBrand.heroStart`. |
| `accent` | `colorScheme.secondary` | Focus, cursor. |
| `primary` (light) / `accent` (dark) | `sadadBrand.link` | Text and outlined button ink. Dark `primary` is 2.95:1 on the page, too low for text. |
| `text` | `colorScheme.onSurface` | |
| `muted` | `colorScheme.onSurfaceVariant` | Light role is `#586C60` (4.65:1 on `soft`); the palette's `#5B6F63` was 4.44:1. |
| `line` | `colorScheme.outlineVariant` | Dividers and decorative borders. |
| (new) | `colorScheme.outline` | Field and switch borders, at least 3:1 on every surface. |
| `warn` | `colorScheme.tertiary` | |
| `debt`, `debtSoft` | `colorScheme.error`, `errorContainer`, or `sadadBrand.debt`, `debtSoft` | |
| `owes`, `owesSoft` | `sadadBrand.owes`, `owesSoft` | «عليه». |
| `credit`, `creditSoft` | `sadadBrand.credit`, `creditSoft` | «له». `creditSoft` is also `tertiaryContainer`. |
| `heroEnd`, `onHeroMuted` | `sadadBrand.heroEnd`, `onHeroMuted` | The hero gradient; `sadadBrand.onHero` is white. |
| `badge` | `sadadBrand.badge` | |
| (literals in `SadadApp.kt`) | `sadadBrand.syncPending`, `syncOffline` | Same values. `SadadApp.kt` moves to them in ticket 202. |
| `isDark` | `sadadBrand.isDark` | |

## Roles

Where each stock part draws (material3 1.4.0): `NavigationBar` and menus on `surfaceContainer`, dialogs and `SearchBar` on `surfaceContainerHigh`, bottom sheets on `surfaceContainerLow`, filled cards on `surfaceContainerHighest`. The extended FAB is `primaryContainer`; the nav-bar indicator, selected chips and tonal buttons are `secondaryContainer`.

The container steps are distinct and ordered in both themes, so light `surfaceContainerHigh` is no longer white: light dialogs and sheets take a pale green (`#DEF1E8`).

| Role | Light | Dark |
| --- | --- | --- |
| `primary` | `#064B42` | `#256C5A` |
| `onPrimary` | `#FFFFFF` | `#FFFFFF` |
| `primaryContainer` | `#B5E6D2` | `#1F4D43` |
| `onPrimaryContainer` | `#064B42` | `#C6F0E0` |
| `secondary` | `#159D89` | `#78DEC0` |
| `onSecondary` | `#00201A` | `#121514` |
| `secondaryContainer` | `#D6EFE4` | `#2E3833` |
| `onSecondaryContainer` | `#064B42` | `#EDF1EE` |
| `tertiary` | `#9A5A13` | `#FFD08A` |
| `onTertiary` | `#FFFFFF` | `#121514` |
| `tertiaryContainer` | `#FFF1DD` | `#4A3A22` |
| `onTertiaryContainer` | `#9A5A13` | `#FFD08A` |
| `error` | `#AB323A` | `#F59A9F` |
| `onError` | `#FFFFFF` | `#121514` |
| `errorContainer` | `#FFE1E1` | `#583B3D` |
| `onErrorContainer` | `#AB323A` | `#EDF1EE` |
| `surface` | `#F5F8F3` | `#121514` |
| `onSurface` | `#142C24` | `#EDF1EE` |
| `onSurfaceVariant` | `#586C60` | `#A3ADA8` |
| `inverseSurface` | `#142C24` | `#EDF1EE` |
| `inverseOnSurface` | `#F5F8F3` | `#121514` |
| `inversePrimary` | `#D6EFE4` | `#064B42` |
| `surfaceContainerLowest` | `#FFFFFF` | `#1A1E1C` |
| `surfaceContainerLow` | `#EEF6F1` | `#1D2220` |
| `surfaceContainer` | `#E7F3ED` | `#202624` |
| `surfaceContainerHigh` | `#DEF1E8` | `#242B28` |
| `surfaceContainerHighest` | `#D6EFE4` | `#29302D` |
| `outline` | `#6E857A` | `#75827C` |
| `outlineVariant` | `#D5E5D9` | `#2E3532` |

`surfaceTint` is transparent and `scrim` is black in both themes.

## Contrast

Ratios are WCAG 2 contrast. `ThemeRolesTest` checks every pair below, plus `onSurface` and `onSurfaceVariant` on every container step.

| Pair | Light | Dark |
| --- | --- | --- |
| `onPrimary` on `primary` | 10.03:1 | 6.23:1 |
| `onPrimaryContainer` on `primaryContainer` | 7.27:1 | 7.70:1 |
| `onSecondary` on `secondary` | 5.09:1 | 11.38:1 |
| `onSecondaryContainer` on `secondaryContainer` | 8.28:1 | 10.65:1 |
| `onTertiary` on `tertiary` | 5.46:1 | 12.81:1 |
| `onTertiaryContainer` on `tertiaryContainer` | 4.91:1 | 7.63:1 |
| `onError` on `error` | 6.46:1 | 8.75:1 |
| `onErrorContainer` on `errorContainer` | 5.27:1 | 8.74:1 |
| `onSurface` on `surface` | 13.85:1 | 16.11:1 |
| `onSurfaceVariant` on `surfaceContainerHighest` | 4.65:1 | 5.85:1 |
| `inverseOnSurface` on `inverseSurface` | 13.85:1 | 16.11:1 |
| `inversePrimary` on `inverseSurface` | 12.24:1 | 8.80:1 |
| `outline` on `surface` | 3.70:1 | 4.58:1 |
| `outline` on `surfaceContainerLowest` | 3.96:1 | 4.20:1 |
| `outline` on `surfaceContainerLow` | 3.60:1 | 4.03:1 |
| `outline` on `surfaceContainer` | 3.48:1 | 3.84:1 |
| `outline` on `surfaceContainerHigh` | 3.37:1 | 3.61:1 |
| `outline` on `surfaceContainerHighest` | 3.27:1 | 3.37:1 |

## Components

- `SadadButton` wraps `Button` (PRIMARY, DANGER), `FilledTonalButton` (SOFT), `OutlinedButton` (SURFACE) and `TextButton` (TEXT). It passes `ButtonDefaults.*Colors` from the roles above, so the fills stay as they were, and one shape, `SadadButtonShape` (12 dp). material3 1.4 buttons default to a full pill, which no `MaterialTheme.shapes` slot reaches. Press feedback is the M3 ripple; there is no press scale.
- `SadadPanel` is an `OutlinedCard` on `surfaceContainerLowest` with an `outlineVariant` border.
- `StickyActions` is a `Surface` on `surfaceContainerLowest`.
- `UndoSnackbar` is a stock `Snackbar`; its action inks in `SnackbarDefaults.actionColor` (`inversePrimary`).
- `Appear` renders its content at once.
- Compose reads the system "remove animations" setting (animator duration scale) as a `MotionDurationScale`. At 0 the animations that stay end at once (`MotionTest`).
