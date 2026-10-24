package com.sadad.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.ui.theme.sadadPalette

/*
 * The Java app's view helpers (`NativeScreens.java`, tag `pre-kotlin-app-2026-10`) as composables:
 * `button`, `input`/`addField`, `card`/`shape`, `pageHeading`, `sectionTitle`, `emptyCard`,
 * `switchRow`, the hero and the logo. Screens build from these, not from raw Material parts, so
 * the look stays the one the shop owners know.
 */

private val ButtonShape = RoundedCornerShape(10.dp)
private val FieldShape = RoundedCornerShape(13.dp)
private val RowShape = RoundedCornerShape(14.dp)
private val CardShape = RoundedCornerShape(10.dp)

/** The page ground: [bg] to [bgEnd], top to bottom, as `pageBackground()`. */
@Composable
fun Modifier.sadadPageBackground(): Modifier {
  val p = sadadPalette
  val brush = remember(p) { Brush.verticalGradient(listOf(p.bg, p.bgEnd)) }
  return background(brush)
}

/** The rounded-square logo on the page colour, its radius 9.5% of its side as the Java app drew. */
@Composable
fun SadadLogo(size: Dp, dark: Boolean, modifier: Modifier = Modifier) {
  Image(
      painter = painterResource(if (dark) R.drawable.sadad_logo_dark else R.drawable.sadad_logo),
      contentDescription = stringResource(R.string.brand_logo),
      modifier =
          modifier.size(size).clip(RoundedCornerShape(size * 0.095f)).background(sadadPalette.bg),
  )
}

/** The press feedback every Java button had: scale to 0.97 while held. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
  val pressed by source.collectIsPressedAsState()
  val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(if (pressed) 90 else 100))
  return scale
}

/** A full-width filled button: 48dp tall, 10dp corners, bold 14sp. */
@Composable
fun SadadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SadadButtonStyle = SadadButtonStyle.PRIMARY,
    enabled: Boolean = true,
    leading: Painter? = null,
    trailing: Painter? = null,
) {
  val p = sadadPalette
  val source = remember { MutableInteractionSource() }
  val (fill, ink, border) =
      when (style) {
        SadadButtonStyle.PRIMARY -> Triple(p.primary, Color.White, null)
        SadadButtonStyle.SOFT -> Triple(p.soft, if (p.bg.isDark()) p.text else p.primary, null)
        SadadButtonStyle.SURFACE -> Triple(p.surface, p.text, BorderStroke(1.dp, p.line))
        SadadButtonStyle.DANGER -> Triple(p.debt, Color.White, null)
      }
  Surface(
      onClick = onClick,
      enabled = enabled,
      shape = ButtonShape,
      color = if (enabled) fill else fill.copy(alpha = 0.45f),
      contentColor = ink,
      border = border,
      interactionSource = source,
      modifier = modifier.scale(pressScale(source)).defaultMinSize(minHeight = 48.dp),
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      leading?.let { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp)) }
      Text(text, style = MaterialTheme.typography.labelLarge)
      trailing?.let { Icon(it, contentDescription = null, modifier = Modifier.size(28.dp)) }
    }
  }
}

enum class SadadButtonStyle {
  /** `primary` fill, white text: the one action of a screen. */
  PRIMARY,
  /** `soft` fill: a secondary action such as «عرض سجل الحركات». */
  SOFT,
  /** `surface` fill with a `line` border: list-like actions (settings rows, sort). */
  SURFACE,
  DANGER,
}

/** A bold 13sp label above a filled 13dp-corner field, as `addField(title, input(hint))`. */
@Composable
fun SadadField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    readOnly: Boolean = false,
    fieldModifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth()) {
    if (label.isNotEmpty()) {
      Text(
          label,
          style = MaterialTheme.typography.labelMedium,
          color = sadadPalette.text,
          modifier = Modifier.padding(top = 3.dp, bottom = 6.dp),
      )
    }
    SadadTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        readOnly = readOnly,
        modifier = fieldModifier.fillMaxWidth().semantics { contentDescription = label },
    )
    supportingText?.let {
      Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = if (isError) MaterialTheme.colorScheme.error else sadadPalette.muted,
          modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

/** The filled field alone (search boxes, inline fields): `surface` fill, `line` border. */
@Composable
fun SadadTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    readOnly: Boolean = false,
) {
  val p = sadadPalette
  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = modifier,
      enabled = enabled,
      readOnly = readOnly,
      singleLine = singleLine,
      minLines = if (singleLine) 1 else minLines.coerceAtLeast(3),
      isError = isError,
      textStyle = MaterialTheme.typography.bodyLarge,
      placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyLarge) } },
      leadingIcon = leadingIcon,
      trailingIcon = trailingIcon,
      keyboardOptions = keyboardOptions,
      visualTransformation = visualTransformation,
      shape = FieldShape,
      colors =
          OutlinedTextFieldDefaults.colors(
              focusedContainerColor = p.surface,
              unfocusedContainerColor = p.surface,
              disabledContainerColor = p.surface,
              errorContainerColor = p.surface,
              focusedBorderColor = p.accent,
              unfocusedBorderColor = p.line,
              disabledBorderColor = p.line,
              focusedTextColor = p.text,
              unfocusedTextColor = p.text,
              focusedPlaceholderColor = p.muted,
              unfocusedPlaceholderColor = p.muted,
              cursorColor = p.accent,
          ),
  )
}

/** The search box: a filled field with the search glyph at its end, as `buildContacts()`. */
@Composable
fun SadadSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
  SadadTextField(
      value = value,
      onValueChange = onValueChange,
      placeholder = placeholder,
      modifier = modifier.fillMaxWidth(),
      trailingIcon = {
        Icon(
            painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = sadadPalette.muted,
            modifier = Modifier.size(20.dp),
        )
      },
  )
}

/** A rounded `surface` row with its title and a switch, as `switchRow()`. */
@Composable
fun SadadSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  val p = sadadPalette
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .clip(RowShape)
              .background(p.surface)
              .border(1.dp, p.line, RowShape)
              .toggleable(
                  value = checked,
                  enabled = enabled,
                  role = Role.Switch,
                  onValueChange = onCheckedChange,
              )
              .padding(horizontal = 14.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = p.primary,
                checkedTrackColor = p.soft,
                checkedBorderColor = p.soft,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = p.line,
                uncheckedBorderColor = p.line,
            ),
    )
  }
}

/** A `surface` card with a `line` border and 10dp corners, as `card(surface)`. */
@Composable
fun SadadCard(
    modifier: Modifier = Modifier,
    color: Color = sadadPalette.surface,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
  val p = sadadPalette
  val border = if (color == p.surface) BorderStroke(1.dp, p.line) else null
  val inner: @Composable () -> Unit = {
    Column(Modifier.padding(contentPadding), content = content)
  }
  if (onClick == null)
      Surface(
          modifier = modifier,
          shape = CardShape,
          color = color,
          border = border,
          shadowElevation = 1.dp,
          content = inner,
      )
  else
      Surface(
          onClick = onClick,
          modifier = modifier,
          shape = CardShape,
          color = color,
          border = border,
          shadowElevation = 1.dp,
          content = inner,
      )
}

/** The page title row: the back chevron at the start, then the 23sp title, then its subtitle. */
@Composable
fun SadadPageHeading(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    titleModifier: Modifier = Modifier,
) {
  val p = sadadPalette
  Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (onBack != null) {
        val back = stringResource(R.string.common_back)
        Box(
            modifier =
                Modifier.size(38.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
          // The arrow points to the start of a right-to-left page, as the Java app drew it.
          val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
          Icon(
              painterResource(R.drawable.ic_back_whatsapp),
              contentDescription = null,
              tint = p.text,
              modifier = Modifier.size(24.dp).scale(scaleX = if (rtl) 1f else -1f, scaleY = 1f),
          )
        }
        Spacer(Modifier.width(10.dp))
      }
      Text(
          title,
          style = MaterialTheme.typography.headlineSmall,
          color = p.text,
          modifier = titleModifier.weight(1f).semantics { heading() },
      )
    }
    if (!subtitle.isNullOrEmpty()) {
      Text(
          subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = p.muted,
          modifier = Modifier.padding(top = 5.dp, bottom = 15.dp),
      )
    } else Spacer(Modifier.height(10.dp))
  }
}

/** A 17sp bold section title with an optional 12sp note, as `sectionTitle()`. */
@Composable
fun SadadSectionTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
  Column(modifier.fillMaxWidth().padding(top = 5.dp, bottom = 9.dp)) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = sadadPalette.text,
        modifier = Modifier.semantics { heading() },
    )
    if (!subtitle.isNullOrEmpty())
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = sadadPalette.muted,
            modifier = Modifier.padding(top = 3.dp),
        )
  }
}

/** The empty-list card: a ring, a bold title and a hint, centred, as `emptyCard()`. */
@Composable
fun SadadEmptyCard(title: String, detail: String, modifier: Modifier = Modifier) {
  val p = sadadPalette
  SadadCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp, 23.dp)) {
    Text(
        "◌",
        style = MaterialTheme.typography.headlineMedium,
        color = p.accent,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        title,
        style =
            MaterialTheme.typography.titleSmall.copy(
                fontSize = MaterialTheme.typography.titleSmall.fontSize * 16 / 15
            ),
        color = p.text,
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = p.muted,
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
  }
}

/**
 * Equal-width filter buttons; the chosen one is `primary` with white text, as the contact chips.
 */
@Composable
fun <T> SadadSegments(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
  val p = sadadPalette
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    options.forEach { (value, label) ->
      val chosen = value == selected
      val source = remember { MutableInteractionSource() }
      Surface(
          onClick = { onSelect(value) },
          shape = ButtonShape,
          color = if (chosen) p.primary else p.surface,
          contentColor = if (chosen) Color.White else p.muted,
          border = if (chosen) null else BorderStroke(1.dp, p.line),
          interactionSource = source,
          modifier =
              Modifier.weight(1f)
                  .scale(pressScale(source))
                  .defaultMinSize(minHeight = 48.dp)
                  .semantics { this.selected = chosen },
      ) {
        Box(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), Alignment.Center) {
          Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
      }
    }
  }
}

/** A round `soft` icon button in the top bar (bell, account), 38dp as the Java header. */
@Composable
fun SadadRoundIcon(
    painter: Painter,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
) {
  val p = sadadPalette
  Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
    Box(
        modifier =
            Modifier.size(38.dp)
                .clip(CircleShape)
                .background(p.soft)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painter,
          contentDescription = null,
          tint = if (p.bg.isDark()) p.text else p.primary,
          modifier = Modifier.size(22.dp),
      )
    }
    if (badge > 0) {
      Box(
          modifier =
              Modifier.align(Alignment.TopEnd)
                  .size(18.dp)
                  .shadow(2.dp, CircleShape)
                  .background(p.badge, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Text(
            if (badge > 9) "9+" else badge.toString(),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                ),
            color = Color.White,
        )
      }
    }
  }
}

/** The dark hero card: `primary` to [heroEnd] diagonally, 18dp corners, elevation 4. */
@Composable
fun SadadHeroCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable RowScope.() -> Unit,
) {
  val p = sadadPalette
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .shadow(4.dp, shape)
              .clip(shape)
              .drawWithCache {
                val brush =
                    Brush.linearGradient(
                        listOf(p.primary, p.heroEnd),
                        start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        end = androidx.compose.ui.geometry.Offset(0f, size.height),
                    )
                onDrawBehind { drawRect(brush) }
              }
              .padding(horizontal = 20.dp, vertical = 22.dp),
      verticalAlignment = Alignment.CenterVertically,
      content = content,
  )
}

/**
 * A white figure card with a muted title, a coloured 21sp figure and a slot below, as
 * `monthMetric`.
 */
@Composable
fun SadadMetricCard(
    title: String,
    figure: String,
    figureColor: Color,
    modifier: Modifier = Modifier,
    below: @Composable ColumnScope.() -> Unit = {},
) {
  val p = sadadPalette
  SadadCard(modifier, color = p.card, contentPadding = PaddingValues(15.dp, 13.dp, 15.dp, 10.dp)) {
    Text(
        title,
        style =
            MaterialTheme.typography.bodySmall.copy(
                fontSize = MaterialTheme.typography.bodySmall.fontSize * 11 / 12
            ),
        color = p.muted,
    )
    Text(
        figure,
        style = MaterialTheme.typography.titleLarge,
        color = figureColor,
        modifier = Modifier.padding(top = 5.dp, bottom = 8.dp),
    )
    below()
  }
}

private fun Color.isDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f
