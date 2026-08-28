package com.whitedns.whiteaesther.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import com.whitedns.whiteaesther.ui.theme.AetherType

// ---------------------------------------------------------------- icons ----

/**
 * The design's own icon set, built from the same path data as the prototype so
 * the two stay in step. Drawn black and recoloured by [Icon]'s tint.
 */
private fun strokeIcon(name: String, vararg pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        pathData.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

object AetherIcons {
    val Home = strokeIcon(
        "home",
        "M3.5 10.4 12 3.6l8.5 6.8V19a1.6 1.6 0 0 1-1.6 1.6H5.1A1.6 1.6 0 0 1 3.5 19v-8.6Z",
        "M9.6 20.6v-6h4.8v6",
    )
    val Routes = strokeIcon(
        "routes",
        "M6 3.4a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z",
        "M18 9.6a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z",
        "M6 15.8a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z",
        "M8.1 6.9 15.9 10.9M15.9 13.1 8.1 17.1",
    )
    val Traffic = strokeIcon(
        "traffic",
        "M8.5 20.4V4.2M8.5 4.2 4.9 7.8M8.5 4.2l3.6 3.6",
        "M15.5 3.6v16.2M15.5 19.8l3.6-3.6M15.5 19.8l-3.6-3.6",
    )
    val Settings = strokeIcon(
        "settings",
        "M4 7.6h7.4M17 7.6h3M4 16.4h3M12.6 16.4h7.4",
        "M14.2 5.2a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z",
        "M9.8 14a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z",
    )
    val Power = strokeIcon("power", "M12 3v9", "M6.5 6.8a8 8 0 1 0 11 0")
    val Radar = strokeIcon(
        "radar",
        "M12 9.6a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z",
        "M12 3v3M12 18v3M3 12h3M18 12h3",
        "M12 4.5a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15Z",
    )
    val ShieldCheck = strokeIcon(
        "shield-check",
        "M12 3 5 6v6c0 4.4 3 8.2 7 9 4-.8 7-4.6 7-9V6l-7-3Z",
        "m9.4 12.1 1.9 1.9 3.5-3.7",
    )
    val ShieldAlert = strokeIcon(
        "shield-alert",
        "M12 3 5 6v6c0 4.4 3 8.2 7 9 4-.8 7-4.6 7-9V6l-7-3Z",
        "M12 8.5v4M12 16h.01",
    )
    val Back = strokeIcon("back", "M19 12H5M11 18l-6-6 6-6")
    val Chevron = strokeIcon("chevron", "m9 18 6-6-6-6")
    val ChevronDown = strokeIcon("chevron-down", "m6 9 6 6 6-6")
    val Check = strokeIcon("check", "M20 6 9 17l-5-5")
    val Pin = strokeIcon(
        "pin",
        "M12 21s7-5.4 7-11a7 7 0 1 0-14 0c0 5.6 7 11 7 11Z",
        "M12 7.4a2.6 2.6 0 1 0 0 5.2 2.6 2.6 0 0 0 0-5.2Z",
    )
    val Shield = strokeIcon("shield", "M12 3 5 6v6c0 4.4 3 8.2 7 9 4-.8 7-4.6 7-9V6l-7-3Z")
    val Sparkle = strokeIcon(
        "sparkle",
        "m12 3 1.9 4.6L18.5 9.5l-4.6 1.9L12 16l-1.9-4.6L5.5 9.5l4.6-1.9L12 3Z",
    )
    val Pulse = strokeIcon("pulse", "M3 12h4l3 8 4-16 3 8h4")
    val Lock = strokeIcon("lock", "M4 10h16v10H4zM8 10V7a4 4 0 0 1 8 0v3")
    val Sliders = strokeIcon(
        "sliders",
        "M4 7.6h7.4M17 7.6h3M4 16.4h3M12.6 16.4h7.4",
        "M14.2 5.2a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z",
        "M9.8 14a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z",
    )
    val Proxy = strokeIcon(
        "proxy",
        "M3 4.5h18v6H3zM3 13.5h18v6H3z",
        "M7 7.5h.01M7 16.5h.01",
    )
    val Info = strokeIcon("info", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z", "M12 16v-4.5M12 8h.01")
    val Warning = strokeIcon(
        "warning",
        "M10.3 3.9 2.5 17.4A2 2 0 0 0 4.2 20.4h15.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z",
        "M12 9v4M12 17h.01",
    )
    val Key = strokeIcon("key", "M8.5 4.5a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z", "m11.5 11.5 8 8M17 17l2-2M14.5 14.5l2-2")
    val Send = strokeIcon("send", "M21 3 10.5 13.5M21 3l-6.8 18-3.7-7.5L3 9.8 21 3Z")
    val Copy = strokeIcon("copy", "M9 9h12v12H9zM5 15V5a2 2 0 0 1 2-2h10")
    /** The launcher globe, scaled to the 24 grid. Ring heavier than the grid. */
    val Globe = ImageVector.Builder(
        name = "globe",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        fun stroke(width: Float, data: String) = addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        stroke(1.85f, "M12 4.67a7.33 7.33 0 1 0 0 14.66 7.33 7.33 0 1 0 0-14.66Z")
        stroke(1.45f, "M4.67 12h14.66M12 4.67v14.66")
        stroke(1.45f, "M12 4.67c-2.07 2.07-3.07 4.67-3.07 7.33s1 5.26 3.07 7.33")
        stroke(1.45f, "M12 4.67c2.07 2.07 3.07 4.67 3.07 7.33s-1 5.26-3.07 7.33")
    }.build()

    val Telegram = strokeIcon(
        "telegram",
        "M21.5 4.3 2.9 11.4a.6.6 0 0 0 .05 1.13l4.6 1.44 1.77 5.3a.6.6 0 0 0 1 .24l2.5-2.5 4.6 3.4a.6.6 0 0 0 .95-.36l3.1-15a.6.6 0 0 0-.82-.68Z",
        "m7.55 13.97 11.2-7.4-7.9 8.5",
    )
}

// ----------------------------------------------------------- primitives ----

/** One unmistakable outline for every remote/keyboard focus target. */
@Composable
internal fun Modifier.controllerFocus(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    enabled: Boolean = true,
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    return then(
        if (enabled && focused) {
            Modifier.border(2.dp, AetherTheme.colors.brand, shape)
        } else {
            Modifier
        },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = AetherType.Label,
        color = AetherTheme.colors.text3,
    )
}

@Composable
fun PageTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, style = AetherType.PageTitle, color = AetherTheme.colors.text)
        if (subtitle != null) {
            Spacer(Modifier.height(5.dp))
            Text(subtitle, style = AetherType.Body, color = AetherTheme.colors.text2)
        }
    }
}

@Composable
fun CrumbBar(text: String, onBack: (() -> Unit)? = null) {
    val backInteraction = remember { MutableInteractionSource() }
    val backShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(backShape)
                    .border(1.dp, AetherTheme.colors.line, backShape)
                    .background(AetherTheme.colors.ink2)
                    .clickable(backInteraction, LocalIndication.current, onClick = onBack)
                    .controllerFocus(backInteraction, backShape)
                    .testTag("back-button"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AetherIcons.Back, "Back", Modifier.size(18.dp), AetherTheme.colors.text2)
            }
        }
        Text(text, style = AetherType.RowTitle, color = AetherTheme.colors.text3)
    }
}

/** The standard bordered surface every section sits on. */
@Composable
fun AetherCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AetherTheme.colors.ink2)
            .border(1.dp, AetherTheme.colors.line, RoundedCornerShape(20.dp)),
        content = content,
    )
}

typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
fun CardHead(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 2.dp)) {
        Text(title, style = AetherType.CardTitle, color = AetherTheme.colors.text)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = AetherType.Small, color = AetherTheme.colors.text2)
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AetherTheme.colors.lineSoft),
    )
}

@Composable
fun RowCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    trailing: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(interaction, LocalIndication.current, onClick = onClick)
                        .controllerFocus(interaction, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AetherTheme.colors.ink3)
                .border(1.dp, AetherTheme.colors.line, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(18.dp), iconTint ?: AetherTheme.colors.text2)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = AetherType.RowTitle, color = AetherTheme.colors.text)
            Text(subtitle, style = AetherType.Small, color = AetherTheme.colors.text2)
        }
        if (trailing) {
            Icon(AetherIcons.Chevron, null, Modifier.size(17.dp), AetherTheme.colors.text3)
        }
    }
}

/** Read-only key/value. Values that line up in a column use the mono face. */
@Composable
fun FactRow(key: String, value: String, mono: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(key, style = AetherType.Body, color = AetherTheme.colors.text2)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = if (mono) AetherType.Data else AetherType.RowTitle,
            color = AetherTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AetherType.RowTitle, color = AetherTheme.colors.text)
            Text(subtitle, style = AetherType.Small, color = AetherTheme.colors.text2)
        }
        trailing()
    }
}

@Composable
fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .controllerFocus(interaction, shape)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AetherType.RowTitle, color = AetherTheme.colors.text)
            Text(subtitle, style = AetherType.Small, color = AetherTheme.colors.text2)
        }
        AetherSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun AetherSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.controllerFocus(interaction, RoundedCornerShape(16.dp)),
        interactionSource = interaction,
        colors = SwitchDefaults.colors(
            checkedTrackColor = AetherTheme.colors.brand,
            checkedThumbColor = AetherTheme.colors.onBrand,
            checkedBorderColor = AetherTheme.colors.brand,
            uncheckedTrackColor = AetherTheme.colors.ink3,
            uncheckedThumbColor = AetherTheme.colors.text3,
            uncheckedBorderColor = AetherTheme.colors.line,
        ),
    )
}

/** Segmented control -- two or three mutually exclusive choices. */
@Composable
fun <T> SegGroup(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .clip(RoundedCornerShape(13.dp))
            .background(AetherTheme.colors.ink3)
            .border(1.dp, AetherTheme.colors.line, RoundedCornerShape(13.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            val interaction = remember(option) { MutableInteractionSource() }
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(shape)
                    .background(if (active) AetherTheme.colors.ink2 else Color.Transparent)
                    .then(
                        if (active) {
                            Modifier.border(
                                1.dp,
                                AetherTheme.colors.brand.copy(alpha = 0.36f),
                                shape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable(interaction, LocalIndication.current) { onSelect(option) }
                    .controllerFocus(interaction, shape)
                    .testTag("seg-${label(option).lowercase().replace(' ', '-')}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = AetherType.RowTitle.copy(fontSize = 14.sp),
                    color = if (active) AetherTheme.colors.brand else AetherTheme.colors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The plain-language option card a first-time user reaches for. */
@Composable
fun ChoiceCard(
    icon: ImageVector,
    name: String,
    description: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.brand.copy(alpha = 0.08f) else colors.ink2)
            .border(
                1.dp,
                if (selected) colors.brand.copy(alpha = 0.55f) else colors.line,
                shape,
            )
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .controllerFocus(interaction, shape)
            .testTag("choice-${name.lowercase().replace(' ', '-')}")
            .padding(13.dp),
    ) {
        Column {
            Icon(icon, null, Modifier.size(20.dp), if (selected) colors.brand else colors.text3)
            Spacer(Modifier.height(8.dp))
            Text(name, style = AetherType.RowTitle, color = colors.text)
            Spacer(Modifier.height(2.dp))
            Text(description, style = AetherType.Small.copy(fontSize = 12.5.sp()), color = colors.text2)
            if (tag != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    tag.uppercase(),
                    style = AetherType.Label.copy(fontSize = 9.5.sp()),
                    color = if (selected) colors.brand else colors.text3,
                )
            }
        }
        if (selected) {
            Icon(
                AetherIcons.Check,
                null,
                Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp),
                colors.brand,
            )
        }
    }
}

private fun Double.sp() = androidx.compose.ui.unit.TextUnit(
    this.toFloat(),
    androidx.compose.ui.unit.TextUnitType.Sp,
)

/** Radio-style row for the advanced pickers. */
@Composable
fun OptionRow(
    code: String?,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.brand.copy(alpha = 0.07f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) colors.brand.copy(alpha = 0.44f) else Color.Transparent,
                shape,
            )
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .controllerFocus(interaction, shape)
            .testTag("option-${title.lowercase().replace(' ', '-')}")
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (code != null) {
            Box(
                Modifier
                    .defaultMinSize(minWidth = 38.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.ink3)
                    .border(
                        1.dp,
                        if (selected) colors.brand.copy(alpha = 0.46f) else colors.line,
                        RoundedCornerShape(9.dp),
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    code,
                    style = AetherType.Data.copy(fontSize = 11.5.sp()),
                    color = if (selected) colors.brand else colors.text2,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = AetherType.RowTitle, color = colors.text)
            Text(subtitle, style = AetherType.Small, color = colors.text2)
        }
        Box(
            Modifier
                .size(19.dp)
                .clip(CircleShape)
                .border(
                    1.8.dp,
                    if (selected) colors.brand else colors.line,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(colors.brand),
                )
            }
        }
    }
}

/** Collapsed for newcomers; opened everywhere at once by the global switch. */
@Composable
fun AdvancedSection(
    badge: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "advanced-chevron")
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interaction, LocalIndication.current, onClick = onToggle)
                .controllerFocus(interaction, shape)
                .testTag("advanced-toggle")
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                AetherIcons.ChevronDown,
                null,
                Modifier
                    .size(16.dp)
                    .rotate(rotation),
                AetherTheme.colors.text2,
            )
            Text("Advanced", style = AetherType.RowTitle, color = AetherTheme.colors.text2)
            Spacer(Modifier.weight(1f))
            SectionLabel(badge)
        }
        AnimatedVisibility(expanded) {
            Column(content = content)
        }
    }
}

@Composable
fun AttentionCard(
    tone: Color,
    title: String,
    body: String,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tone.copy(alpha = 0.09f))
            .border(1.dp, tone.copy(alpha = 0.38f), RoundedCornerShape(20.dp))
            .padding(15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AetherIcons.Warning, null, Modifier.size(20.dp), tone)
        Column {
            Text(title, style = AetherType.RowTitle, color = AetherTheme.colors.text)
            Spacer(Modifier.height(2.dp))
            Text(body, style = AetherType.Small, color = AetherTheme.colors.text2)
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(11.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { (label, action) ->
                        val interaction = remember(label) { MutableInteractionSource() }
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .border(1.dp, tone.copy(alpha = 0.42f), CircleShape)
                                .clickable(interaction, LocalIndication.current, onClick = action)
                                .controllerFocus(interaction, CircleShape)
                                .padding(horizontal = 13.dp, vertical = 7.dp),
                        ) {
                            Text(label, style = AetherType.Small, color = tone)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(shape)
            .background(if (enabled) colors.brand else colors.brand.copy(alpha = 0.4f))
            .clickable(interaction, LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerFocus(interaction, shape, enabled)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(17.dp), colors.onBrand)
        Text(text, style = AetherType.RowTitle, color = colors.onBrand, maxLines = 1)
    }
}

@Composable
fun OutlineButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(shape)
            .background(colors.ink2)
            .border(1.dp, colors.line, shape)
            .clickable(interaction, LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerFocus(interaction, shape, enabled)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val tint = if (enabled) colors.text else colors.text3
        if (icon != null) Icon(icon, null, Modifier.size(17.dp), tint)
        Text(text, style = AetherType.RowTitle, color = tint, maxLines = 1)
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 2.dp, vertical = 10.dp),
        style = AetherType.Small.copy(fontSize = 12.5.sp()),
        color = AetherTheme.colors.text3,
    )
}
