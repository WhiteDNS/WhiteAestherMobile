package com.whitedns.whiteaesther.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.InstalledApp
import com.whitedns.whiteaesther.data.InstalledApps
import com.whitedns.whiteaesther.data.SplitTunnelMode
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import com.whitedns.whiteaesther.ui.theme.AetherType

@Composable
fun SplitTunnelScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val colors = AetherTheme.colors
    val context = LocalContext.current
    val rules = settings.splitTunnel
    val self = context.packageName

    // Reading labels and icons for every app touches the package manager once
    // per entry, so it is done on first composition rather than on every state
    // change -- selecting an app should not re-read the whole device.
    val apps = remember { InstalledApps.launchable(context).filterNot { it.packageName == self } }
    var query by rememberSaveable { mutableStateOf("") }

    val visible = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(query, ignoreCase = true) }
        }
    }

    ScreenColumn {
        CrumbBar("Traffic · Apps", onBack = onBack)
        PageTitle(
            "Which apps go through",
            "Leave this on all apps unless something needs to see your real address.",
        )

        AetherCard {
            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SplitTunnelMode.entries.forEach { mode ->
                    OptionRow(
                        code = when (mode) {
                            SplitTunnelMode.ALL -> "1"
                            SplitTunnelMode.ONLY -> "2"
                            SplitTunnelMode.EXCEPT -> "3"
                        },
                        title = mode.label,
                        subtitle = when (mode) {
                            SplitTunnelMode.ALL ->
                                "Everything is covered. What most people want."
                            SplitTunnelMode.ONLY ->
                                "Only what you pick. Anything installed later stays outside."
                            SplitTunnelMode.EXCEPT ->
                                "Everything but what you pick. For banking and local apps."
                        },
                        selected = rules.mode == mode,
                        onClick = {
                            onSettingsChange(settings.copy(splitTunnel = rules.copy(mode = mode)))
                        },
                    )
                }
            }
        }

        if (rules.mode == SplitTunnelMode.ALL) {
            Note(
                "Every app on the phone goes through the tunnel. Pick one of the other two if " +
                    "an app needs to see your real address -- a bank that blocks foreign " +
                    "addresses, or something that only works from inside the country.",
            )
            return@ScreenColumn
        }

        rules.validationError(self)?.let { problem ->
            Spacer(Modifier.height(12.dp))
            AttentionCard(
                tone = colors.signalFailed,
                title = "Nothing would be carried",
                body = problem,
            )
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead(
                if (rules.mode == SplitTunnelMode.ONLY) "Apps to carry" else "Apps to leave out",
                rules.summary(),
            )
            Column(Modifier.padding(horizontal = 15.dp).padding(bottom = 11.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("split-search"),
                    placeholder = { Text("Search apps", style = AetherType.Body, color = colors.text3) },
                    textStyle = AetherType.Body.copy(color = colors.text),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.ink1,
                        unfocusedContainerColor = colors.ink1,
                    ),
                )
            }

            if (visible.isEmpty()) {
                Divider()
                Note(
                    if (apps.isEmpty()) {
                        "No apps to list. This device reports nothing with a launcher icon."
                    } else {
                        "Nothing matches \"$query\"."
                    },
                    modifier = Modifier.padding(horizontal = 15.dp),
                )
            }

            visible.forEach { app ->
                Divider()
                AppRow(
                    app = app,
                    checked = app.packageName in rules.packages,
                    onToggle = { on ->
                        val next = if (on) {
                            rules.packages + app.packageName
                        } else {
                            rules.packages - app.packageName
                        }
                        onSettingsChange(settings.copy(splitTunnel = rules.copy(packages = next)))
                    },
                )
            }
        }

        Note(
            "WhiteAesther itself is never in this list. Routing the app through its own tunnel " +
                "would send the connection it is building back into itself.",
        )
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = AetherTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 15.dp, vertical = 11.dp)
            .testTag("split-app-${app.packageName}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.ink3)
                .border(1.dp, colors.line, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val icon = app.icon
            if (icon != null) {
                Icon(
                    painter = remember(app.packageName) { DrawablePainter(icon) },
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                )
            } else {
                Text(
                    app.label.take(1).uppercase(),
                    style = AetherType.RowTitle,
                    color = colors.text2,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = AetherType.RowTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (app.system) "System app" else app.packageName,
                style = AetherType.Small,
                color = colors.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AetherSwitch(checked = checked, onCheckedChange = onToggle)
    }
}

/**
 * Draws a launcher icon, which arrives as a platform Drawable rather than
 * anything Compose can paint directly.
 */
private class DrawablePainter(
    private val drawable: android.graphics.drawable.Drawable,
) : Painter() {
    private val image = runCatching { drawable.toBitmap().asImageBitmap() }.getOrNull()

    override val intrinsicSize = androidx.compose.ui.geometry.Size.Unspecified

    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        val bitmap = image ?: return
        drawImage(bitmap, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
    }
}
