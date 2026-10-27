package com.sadad.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.R
import com.sadad.app.ui.theme.sadadPalette
import kotlinx.coroutines.delay

/*
 * The blocks the boards repeat across screens: the white panel, the step intro, the brand block,
 * the keypad and the PIN dots. Screens build from these so one change reaches every board.
 */

/**
 * The white panel of the sign-in and password boards: `card` fill, `line` border, 26dp corners and
 * the boards' `0 12px 32px rgba(6,75,66,0.08)` shadow.
 */
@Composable
fun SadadPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    shadow: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
  val p = sadadPalette
  val shape = RoundedCornerShape(radius)
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .then(if (shadow && !p.isDark) Modifier.softShadow(radius) else Modifier)
              .background(p.card, shape)
              .border(1.dp, p.line, shape)
              .padding(contentPadding),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      content = content,
  )
}

/** A CSS-like soft shadow: `Modifier.shadow` scales the colour by the system alpha and vanishes. */
fun Modifier.softShadow(
    radius: Dp,
    color: Color = Color(0x14064B42),
    blur: Dp = 32.dp,
    dy: Dp = 12.dp,
): Modifier = drawBehind {
  drawIntoCanvas { canvas ->
    val paint =
        Paint().asFrameworkPaint().apply {
          this.color = Color.Transparent.toArgb()
          setShadowLayer(blur.toPx() / 2, 0f, dy.toPx(), color.toArgb())
        }
    val r = radius.toPx()
    canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
  }
}

/** «الخطوة 1 من 2 · أول دخول», then the title and its one-line explanation. */
@Composable
fun StepIntro(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
) {
  val p = sadadPalette
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (eyebrow != null) {
      Text(eyebrow, color = p.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        color = p.text,
        fontSize = 23.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
    )
    if (subtitle != null) {
      Text(subtitle, color = p.muted, fontSize = 14.sp, lineHeight = 21.sp)
    }
  }
}

/** The logo, «سدد» and the tagline, centred, as the sign-in board opens. */
@Composable
fun SadadBrand(modifier: Modifier = Modifier, logoSize: Dp = 72.dp, tagline: Boolean = true) {
  val p = sadadPalette
  Column(
      modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SadadLogo(logoSize, dark = p.isDark)
    Text(
        stringResource(R.string.brand_name),
        color = if (p.isDark) p.accent else p.primary,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
    )
    if (tagline) {
      Text(
          stringResource(R.string.brand_tagline),
          color = p.muted,
          fontSize = 15.sp,
          lineHeight = 22.sp,
          textAlign = TextAlign.Center,
      )
    }
  }
}

/** One key of [SadadKeypad]: a digit or the dot, delete, or an action such as the fingerprint. */
sealed interface KeypadKey {
  data class Char(val label: String, val description: String = label) : KeypadKey

  data class Delete(val description: String) : KeypadKey

  data class Action(val icon: Int, val description: String, val onClick: () -> Unit) : KeypadKey

  /** An empty cell that keeps the grid when an action is not offered. */
  data object Blank : KeypadKey
}

/**
 * The 3×4 keypad of quick add and the lock: white 13dp keys with a `line` underline, read left to
 * right inside the right-to-left page. With [plainSpecials] delete and actions sit on the page
 * colour, as the lock board draws them.
 */
@Composable
fun SadadKeypad(
    keys: List<KeypadKey>,
    onChar: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 52.dp,
    fontSize: Int = 24,
    plainSpecials: Boolean = false,
    tagFor: (KeypadKey) -> String? = { null },
) {
  val p = sadadPalette
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      keys.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          row.forEach { key ->
            val special = key !is KeypadKey.Char
            val plain = plainSpecials && special
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(90), label = "key")
            val tint by
                animateColorAsState(
                    if (pressed) p.soft else if (plain) Color.Transparent else p.card,
                    tween(120),
                    label = "keyTint",
                )
            if (key is KeypadKey.Blank) {
              Box(Modifier.weight(1f).height(keyHeight))
              return@forEach
            }
            val description =
                when (key) {
                  is KeypadKey.Char -> key.description
                  is KeypadKey.Delete -> key.description
                  is KeypadKey.Action -> key.description
                  KeypadKey.Blank -> ""
                }
            val tag = tagFor(key)
            Surface(
                onClick = {
                  when (key) {
                    is KeypadKey.Char -> onChar(key.label)
                    is KeypadKey.Delete -> onDelete()
                    is KeypadKey.Action -> key.onClick()
                    KeypadKey.Blank -> Unit
                  }
                },
                color = tint,
                contentColor = if (p.isDark) p.text else if (plainSpecials) p.primary else p.text,
                shape = KeyShape,
                interactionSource = source,
                modifier =
                    Modifier.weight(1f)
                        .height(keyHeight)
                        .scale(scale)
                        .then(if (plain) Modifier else Modifier.keyUnderline(p.line))
                        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                        .semantics {
                          contentDescription = description
                          role = Role.Button
                        },
            ) {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (key) {
                  is KeypadKey.Char ->
                      Text(
                          key.label,
                          fontSize = fontSize.sp,
                          fontWeight = FontWeight.Medium,
                          color = LocalContentColor.current,
                      )
                  is KeypadKey.Delete ->
                      Icon(painterResource(R.drawable.ic_backspace), null, Modifier.size(26.dp))
                  is KeypadKey.Action -> Icon(painterResource(key.icon), null, Modifier.size(28.dp))
                  KeypadKey.Blank -> Unit
                }
              }
            }
          }
        }
      }
    }
  }
}

/** The boards' `box-shadow: 0 1px 0 var(--line)` under each key. */
private fun Modifier.keyUnderline(line: Color) = drawBehind {
  val r = 13.dp.toPx()
  drawRoundRect(
      line,
      topLeft = androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx()),
      size = size,
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
  )
}

private val KeyShape = RoundedCornerShape(13.dp)

/** The PIN dots: [count] rings, the first [filled] filled; each new dot pops in. */
@Composable
fun PinDots(filled: Int, count: Int, description: String, modifier: Modifier = Modifier) {
  val p = sadadPalette
  val ink = if (p.isDark) p.accent else p.primary
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(
        modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      repeat(count) { i ->
        val on = i < filled
        val grow by animateFloatAsState(if (on) 1f else 0f, tween(140), label = "dot")
        Box(Modifier.size(14.dp).border(2.dp, ink, CircleShape), Alignment.Center) {
          Box(Modifier.size(14.dp).scale(grow).background(ink, CircleShape))
        }
      }
    }
  }
}

/** Fades and lifts [content] in once, the boards' entrance for a screen's main blocks. */
@Composable
fun Appear(delayMillis: Int = 0, content: @Composable () -> Unit) {
  val state = remember { MutableTransitionState(false).apply { targetState = true } }
  AnimatedVisibility(
      visibleState = state,
      enter =
          fadeIn(tween(320, delayMillis)) + slideInVertically(tween(320, delayMillis)) { it / 12 },
  ) {
    content()
  }
}

/**
 * The boards' dark toast at the foot of a screen: [message] slides up, stays about three seconds,
 * then [onDismiss] clears it. A failure is [error] so it is read out at once.
 */
@Composable
fun SadadToastHost(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
  val p = sadadPalette
  // Keep the last text while the toast fades out.
  var shown by remember { mutableStateOf(message) }
  if (message != null && message != shown) shown = message
  LaunchedEffect(message) {
    if (message != null) {
      delay(TOAST_MILLIS)
      onDismiss()
    }
  }
  AnimatedVisibility(
      visible = message != null,
      enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 2 },
      exit = fadeOut(tween(200)),
      modifier = modifier,
  ) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (error) p.debt else if (p.isDark) p.soft else Color(0xFF142C24),
                RoundedCornerShape(13.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
          painterResource(if (error) R.drawable.ic_alert else R.drawable.ic_check),
          contentDescription = null,
          tint =
              if (error && !p.isDark) Color.White
              else if (p.isDark) p.accent else Color(0xFF78DEC0),
          modifier = Modifier.size(20.dp),
      )
      Text(
          shown.orEmpty(),
          color = if (error && !p.isDark) Color.White else if (p.isDark) p.text else Color.White,
          fontSize = 14.sp,
          lineHeight = 20.sp,
          modifier = Modifier.weight(1f),
      )
    }
  }
}

private const val TOAST_MILLIS = 3_000L
