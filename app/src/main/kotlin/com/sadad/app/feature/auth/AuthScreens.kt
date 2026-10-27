package com.sadad.app.feature.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadad.app.R
import com.sadad.app.ui.components.Appear
import com.sadad.app.ui.components.SadadBrand
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadField
import com.sadad.app.ui.components.SadadPanel
import com.sadad.app.ui.components.ScreenHeader
import com.sadad.app.ui.components.StepIntro
import com.sadad.app.ui.theme.sadadPalette

object AuthTags {
  const val USERNAME = "auth:username"
  const val PASSWORD = "auth:password"
  const val LOGIN_BUTTON = "auth:login-button"
  const val PASSWORD_CURRENT = "auth:password-current"
  const val PASSWORD_NEW = "auth:password-new"
  const val PASSWORD_CONFIRM = "auth:password-confirm"
  const val PASSWORD_SAVE = "auth:password-save"
  const val STAFF_NAME = "auth:staff-name"
  const val DEVICE_LABEL = "auth:device-label"
  const val APPROVAL_SAVE = "auth:approval-save"
  const val APPROVAL_CHECK_AGAIN = "auth:approval-check-again"
}

/** The Auth board: the brand block, the sign-in panel, then support and the device footnote. */
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},
    /**
     * Set when another store is being added from the Store tab: there is a screen to go back to.
     */
    onBack: (() -> Unit)? = null,
    viewModel: LoginViewModel = authViewModel { graph, saved, context ->
      LoginViewModel(graph, saved, context)
    },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  AuthDestination(state.destination, onNavigate)
  if (state.destination != null) return
  val context = LocalContext.current
  val p = sadadPalette
  var shown by rememberSaveable { mutableStateOf(false) }

  Column(modifier.fillMaxSize()) {
    if (onBack != null) ScreenHeader(stringResource(R.string.login_add_store), onBack = onBack)
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 18.dp,
                    end = 18.dp,
                    top = if (onBack != null) 16.dp else 56.dp,
                    bottom = 32.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
      Appear { SadadBrand() }
      Appear(delayMillis = 80) {
        SadadPanel {
          StepIntro(
              title = stringResource(R.string.title_login),
              subtitle = stringResource(R.string.login_subtitle),
          )
          SadadField(
              label = stringResource(R.string.login_username),
              value = state.username,
              onValueChange = viewModel::onUsernameChange,
              placeholder = "store.owner",
              enabled = !state.busy,
              fieldModifier = Modifier.testTag(AuthTags.USERNAME),
          )
          SadadField(
              label = stringResource(R.string.login_password),
              value = state.password,
              onValueChange = viewModel::onPasswordChange,
              placeholder = "••••••••",
              enabled = !state.busy,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              visualTransformation =
                  if (shown) VisualTransformation.None else PasswordVisualTransformation(),
              trailingIcon = { EyeToggle(shown) { shown = !shown } },
              fieldModifier = Modifier.testTag(AuthTags.PASSWORD),
          )
          AuthError(state.error)
          SadadButton(
              text = stringResource(R.string.login_submit),
              onClick = viewModel::signIn,
              enabled = !state.busy,
              leading = painterResource(R.drawable.ic_lock),
              modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(AuthTags.LOGIN_BUTTON),
          )
        }
      }
      Appear(delayMillis = 160) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(stringResource(R.string.login_no_account), color = p.muted, fontSize = 13.sp)
          SadadButton(
              text = stringResource(R.string.login_support),
              onClick = { openSupport(context) },
              style = SadadButtonStyle.TEXT,
              height = 44.dp,
          )
          Text(
              stringResource(R.string.login_footnote),
              color = p.muted,
              fontSize = 12.sp,
              lineHeight = 19.sp,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(top = 8.dp, start = 28.dp, end = 28.dp),
          )
        }
      }
    }
  }
}

/** The PasswordChange board: step 1 of 2, three fields and the live checklist. */
@Composable
fun PasswordChangeScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},
    viewModel: PasswordChangeViewModel = authViewModel { graph, _, context ->
      PasswordChangeViewModel(graph, context)
    },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  AuthDestination(state.destination, onNavigate)
  if (state.destination != null) return
  val p = sadadPalette
  var shown by rememberSaveable { mutableStateOf(false) }
  val rules =
      listOf(
          stringResource(R.string.password_rule_length) to
              (state.new.length in
                  PasswordChangeViewModel.PASSWORD_MIN..PasswordChangeViewModel.PASSWORD_MAX),
          stringResource(R.string.password_rule_different) to
              (state.new.isNotEmpty() && state.new != state.current),
          stringResource(R.string.password_rule_match) to
              (state.confirm.isNotEmpty() && state.confirm == state.new),
      )
  val ready = rules.all { it.second }

  Column(
      modifier =
          modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Appear {
      StepIntro(
          eyebrow = stringResource(R.string.password_step),
          title = stringResource(R.string.title_password_change),
          subtitle = stringResource(R.string.password_change_hint),
      )
    }
    Appear(delayMillis = 80) {
      SadadPanel(
          shadow = false,
          contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
      ) {
        PasswordField(
            value = state.current,
            onValueChange = viewModel::onCurrentChange,
            label = stringResource(R.string.password_current),
            tag = AuthTags.PASSWORD_CURRENT,
            enabled = !state.busy,
            shown = shown,
        )
        PasswordField(
            value = state.new,
            onValueChange = viewModel::onNewChange,
            label = stringResource(R.string.password_new),
            tag = AuthTags.PASSWORD_NEW,
            enabled = !state.busy,
            shown = shown,
        )
        PasswordField(
            value = state.confirm,
            onValueChange = viewModel::onConfirmChange,
            label = stringResource(R.string.password_confirm),
            tag = AuthTags.PASSWORD_CONFIRM,
            enabled = !state.busy,
            shown = shown,
        )
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = shown, role = Role.Checkbox, onValueChange = { shown = it }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Checkbox(
              checked = shown,
              onCheckedChange = null,
              colors =
                  CheckboxDefaults.colors(
                      checkedColor = p.primary,
                      uncheckedColor = p.muted,
                  ),
              modifier = Modifier.padding(horizontal = 8.dp),
          )
          Text(stringResource(R.string.password_show_all), color = p.text, fontSize = 14.sp)
        }
        PasswordRules(rules)
        AuthError(state.error)
        SadadButton(
            text = stringResource(R.string.password_save),
            onClick = viewModel::change,
            enabled = !state.busy && ready,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.PASSWORD_SAVE),
        )
      }
    }
  }
}

/** Each rule ticks green the moment it holds, and says «متحقق» or «غير متحقق» aloud. */
@Composable
private fun PasswordRules(rules: List<Pair<String, Boolean>>) {
  val p = sadadPalette
  val heading = stringResource(R.string.password_rules)
  val met = stringResource(R.string.password_rule_met)
  val unmet = stringResource(R.string.password_rule_unmet)
  Column(
      Modifier.fillMaxWidth()
          .background(p.bg, RoundedCornerShape(13.dp))
          .padding(horizontal = 14.dp, vertical = 12.dp)
          .semantics { contentDescription = heading },
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    rules.forEach { (label, ok) ->
      val ink by
          animateColorAsState(
              if (ok) (if (p.isDark) p.accent else p.primary) else p.muted,
              tween(200),
              label = "rule",
          )
      Row(
          Modifier.clearAndSetSemantics {
            contentDescription = "$label — ${if (ok) met else unmet}"
          },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
            painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_circle),
            null,
            tint = ink,
            modifier = Modifier.size(18.dp),
        )
        Text(label, color = ink, fontSize = 13.sp, lineHeight = 20.sp)
      }
    }
  }
}

/** The Approval board: step 2 of 2, the three activation steps, then details or the re-check. */
@Composable
fun AwaitingApprovalScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},
    viewModel: AwaitingApprovalViewModel = authViewModel { graph, _, context ->
      AwaitingApprovalViewModel(graph, context)
    },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  AuthDestination(state.destination, onNavigate)
  if (state.destination != null || state.loading) return
  val p = sadadPalette

  Column(modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 28.dp)) {
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Appear {
        StepIntro(
            eyebrow = stringResource(R.string.approval_step),
            title = stringResource(R.string.title_awaiting_approval),
            subtitle = stringResource(R.string.approval_subtitle),
        )
      }
      Appear(delayMillis = 80) {
        ApprovalSteps(
            needsDetails = state.needsDetails,
            ownerSub = state.statusText ?: stringResource(R.string.approval_step_owner_sub),
        )
      }
      if (state.needsDetails) {
        Appear(delayMillis = 160) {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SadadField(
                label = stringResource(R.string.approval_staff_name),
                value = state.staffName,
                onValueChange = viewModel::onStaffNameChange,
                enabled = !state.busy,
                fieldModifier = Modifier.testTag(AuthTags.STAFF_NAME),
            )
            SadadField(
                label = stringResource(R.string.approval_device_label),
                value = state.deviceLabel,
                onValueChange = viewModel::onDeviceLabelChange,
                enabled = !state.busy,
                fieldModifier = Modifier.testTag(AuthTags.DEVICE_LABEL),
            )
            Text(
                stringResource(R.string.approval_details_hint),
                color = p.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
          }
        }
      }
      AuthError(state.error, TextAlign.Center)
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      AnimatedVisibility(state.busy, enter = fadeIn(), exit = fadeOut()) {
        CircularProgressIndicator(
            color = if (p.isDark) p.accent else p.primary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(28.dp),
        )
      }
      if (state.needsDetails) {
        SadadButton(
            text = stringResource(R.string.approval_save),
            onClick = viewModel::save,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.APPROVAL_SAVE),
        )
      } else {
        SadadButton(
            text = stringResource(R.string.approval_check_again),
            onClick = viewModel::checkAgain,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.APPROVAL_CHECK_AGAIN),
        )
      }
    }
  }
}

/** The numbered steps card: done steps filled green, the current one ringed in amber. */
@Composable
private fun ApprovalSteps(needsDetails: Boolean, ownerSub: String) {
  val p = sadadPalette
  val description = stringResource(R.string.approval_steps)
  Column(
      Modifier.fillMaxWidth()
          .background(p.card, RoundedCornerShape(18.dp))
          .border(1.dp, p.line, RoundedCornerShape(18.dp))
          .padding(18.dp)
          .semantics { contentDescription = description },
  ) {
    StepRow(
        label = stringResource(R.string.approval_step_signed_in),
        sub = null,
        done = true,
        current = false,
        line = true,
    )
    StepRow(
        label = stringResource(R.string.approval_step_registered),
        sub =
            stringResource(
                if (needsDetails) R.string.approval_step_details_sub
                else R.string.approval_step_registered_sub
            ),
        done = !needsDetails,
        current = needsDetails,
        line = true,
    )
    StepRow(
        label = stringResource(R.string.approval_step_owner),
        sub = ownerSub,
        done = false,
        current = !needsDetails,
        line = false,
    )
  }
}

@Composable
private fun StepRow(label: String, sub: String?, done: Boolean, current: Boolean, line: Boolean) {
  val p = sadadPalette
  val green = if (p.isDark) p.accent else p.primary
  val ring by
      animateColorAsState(
          when {
            done -> green
            current -> p.credit
            else -> p.line
          },
          tween(300),
          label = "stepRing",
      )
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Box(
          Modifier.size(28.dp)
              .background(if (done) green else p.card, CircleShape)
              .border(2.dp, ring, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        if (done) {
          Icon(
              painterResource(R.drawable.ic_check),
              null,
              tint = if (p.isDark) p.bg else androidx.compose.ui.graphics.Color.White,
              modifier = Modifier.size(16.dp),
          )
        }
      }
      if (line) Box(Modifier.width(2.dp).height(28.dp).background(p.line))
    }
    Column(Modifier.padding(top = 3.dp, bottom = 12.dp)) {
      Text(
          label,
          color = if (done || current) p.text else p.muted,
          fontSize = 15.sp,
          lineHeight = 22.sp,
          fontWeight = FontWeight.Bold,
      )
      if (sub != null) Text(sub, color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
  }
}

@Composable
private fun AuthDestination(destination: Any?, onNavigate: (Any) -> Unit) {
  LaunchedEffect(destination) { destination?.let(onNavigate) }
}

/** The refusal under the fields, in the debt red on its soft ground. */
@Composable
private fun AuthError(text: String?, textAlign: TextAlign? = null) {
  val p = sadadPalette
  AnimatedVisibility(text != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
    Text(
        text = text.orEmpty(),
        color = p.owes,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = textAlign,
        modifier =
            Modifier.fillMaxWidth()
                .background(p.owesSoft, RoundedCornerShape(13.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
    )
  }
}

/** The eye at the field's end that shows or hides what was typed. */
@Composable
private fun EyeToggle(shown: Boolean, onToggle: () -> Unit) {
  IconButton(onClick = onToggle) {
    Icon(
        painterResource(if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye),
        contentDescription =
            stringResource(if (shown) R.string.password_hide else R.string.password_show),
        tint = sadadPalette.muted,
        modifier = Modifier.size(22.dp),
    )
  }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    enabled: Boolean,
    shown: Boolean,
) {
  SadadField(
      label = label,
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      visualTransformation =
          if (shown) VisualTransformation.None else PasswordVisualTransformation(),
      fieldModifier = Modifier.testTag(tag),
  )
}

private fun openSupport(context: Context) {
  val number = context.getString(R.string.support_whatsapp_number)
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")))
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(context, R.string.support_unavailable, Toast.LENGTH_SHORT).show()
  }
}
