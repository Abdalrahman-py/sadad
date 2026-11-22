package com.sadad.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.ui.theme.sadadBrand
import com.sadad.app.ui.theme.sadadPalette

/*
 * The Java app's view helpers (`NativeScreens.java`, tag `pre-kotlin-app-2026-10`) as composables:
 * `button`, `input`/`addField`, `pageHeading` and the logo. Screens build from these, not from raw
 * Material parts, so the look stays the one the shop owners know.
 */

// The radii: 12 for buttons and fields, 16 for blocks, 24 for panels.
private val FieldShape = RoundedCornerShape(12.dp)

/** The page ground: flat `bg`. */
@Composable fun Modifier.sadadPageBackground(): Modifier = background(sadadPalette.bg)

/** The 1dp [color] line along a bar's top edge, where the bar meets the page. */
fun Modifier.topHairline(color: Color): Modifier = drawBehind {
  drawRect(color, size = Size(size.width, 1.dp.toPx()))
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

/**
 * The one corner every button keeps: material3 1.4 buttons default to a full pill, which no theme
 * shape reaches.
 */
val SadadButtonShape: Shape = RoundedCornerShape(12.dp)

/**
 * A full-width button as the boards draw it: 52dp tall, 12dp corners, `labelLarge`, on the stock M3
 * button of its [style]. Disabled it turns `surfaceContainerHighest` with `onSurfaceVariant` text,
 * never a faded primary. The press feedback is M3's ripple and state layer.
 */
@Composable
fun SadadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SadadButtonStyle = SadadButtonStyle.PRIMARY,
    enabled: Boolean = true,
    leading: Painter? = null,
    trailing: Painter? = null,
    height: Dp = 52.dp,
) {
  val c = MaterialTheme.colorScheme
  val link = sadadBrand.link
  val off = c.surfaceContainerHighest
  val offInk = c.onSurfaceVariant
  val sized = modifier.defaultMinSize(minHeight = height)
  val padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
  val content: @Composable RowScope.() -> Unit = {
    leading?.let {
      Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
    trailing?.let {
      Spacer(Modifier.width(8.dp))
      Icon(it, contentDescription = null, modifier = Modifier.size(28.dp))
    }
  }
  when (style) {
    SadadButtonStyle.PRIMARY,
    SadadButtonStyle.DANGER -> {
      val danger = style == SadadButtonStyle.DANGER
      Button(
          onClick = onClick,
          modifier = sized,
          enabled = enabled,
          shape = SadadButtonShape,
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = if (danger) c.error else c.primary,
                  contentColor = if (danger) c.onError else c.onPrimary,
                  disabledContainerColor = off,
                  disabledContentColor = offInk,
              ),
          contentPadding = padding,
          content = content,
      )
    }
    SadadButtonStyle.SOFT ->
        FilledTonalButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = SadadButtonShape,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = c.surfaceContainer,
                    contentColor = link,
                    disabledContainerColor = off,
                    disabledContentColor = offInk,
                ),
            contentPadding = padding,
            content = content,
        )
    SadadButtonStyle.SURFACE ->
        OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = SadadButtonShape,
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = c.surfaceContainerLowest,
                    contentColor = link,
                    disabledContainerColor = off,
                    disabledContentColor = offInk,
                ),
            // The light divider tone: `outline` is for fields and switches.
            border = if (enabled) BorderStroke(1.dp, c.outlineVariant) else null,
            contentPadding = padding,
            content = content,
        )
    SadadButtonStyle.TEXT ->
        TextButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = SadadButtonShape,
            colors =
                ButtonDefaults.textButtonColors(contentColor = link, disabledContentColor = offInk),
            contentPadding = padding,
            content = content,
        )
  }
}

enum class SadadButtonStyle {
  /** `primary` fill, white text: the one action of a screen. */
  PRIMARY,
  /** `surface` fill, link ink: a secondary action such as «تعديل المبلغ». */
  SOFT,
  /** `card` fill with a `line` border: list-like actions (undo, sort). */
  SURFACE,
  DANGER,
  /** No fill: a link-weight action such as «إلغاء». */
  TEXT,
}

/** The old app's filled selection field, with a native accessible menu and unchanged values. */
@Composable
fun <T> SadadChoiceField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  var expanded by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth()) {
    if (label.isNotEmpty()) {
      Text(
          label,
          style = MaterialTheme.typography.labelMedium,
          color = sadadPalette.text,
          modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
      )
    }
    Box(Modifier.fillMaxWidth()) {
      SadadButton(
          text = options.firstOrNull { it.first == selected }?.second.orEmpty(),
          onClick = { expanded = true },
          style = SadadButtonStyle.SURFACE,
          enabled = enabled,
          modifier = modifier.fillMaxWidth(),
          trailing = painterResource(R.drawable.ic_chevron_down_wide),
      )
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { (value, text) ->
          DropdownMenuItem(
              text = { Text(text) },
              onClick = {
                expanded = false
                onSelect(value)
              },
          )
        }
      }
    }
  }
}

/** A `labelMedium` label above a filled 12dp-corner field, as `addField(title, input(hint))`. */
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
          modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
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
      minLines = if (singleLine) 1 else minLines,
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

/** The page title row: the back chevron at the start, then the 24sp title, then its subtitle. */
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
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              painterResource(R.drawable.ic_back),
              contentDescription = null,
              tint = p.text,
              modifier = Modifier.size(24.dp),
          )
        }
        Spacer(Modifier.width(12.dp))
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
          modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
      )
    } else Spacer(Modifier.height(12.dp))
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
      Surface(
          onClick = { onSelect(value) },
          shape = SadadButtonShape,
          color = if (chosen) p.primary else p.surface,
          contentColor = if (chosen) Color.White else p.muted,
          border = if (chosen) null else BorderStroke(1.dp, p.line),
          modifier =
              Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).semantics {
                this.selected = chosen
              },
      ) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), Alignment.Center) {
          Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
      }
    }
  }
}
