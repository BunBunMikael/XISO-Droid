package dev.xdvdfs.android

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colors taken from the Blueprint (v3) theme the web app uses, so the Android app
 * looks like the same tool. The window colors are the ones in `xdvdfs-web/index.html`.
 */
@Immutable
class BpColors(
    val dark: Boolean,
    val outerBg: Color,
    val panelBg: Color,
    val accent: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val buttonBg: Color,
    val buttonBorder: Color,
    val inputBg: Color,
    val inputBorder: Color,
)

val LightBp = BpColors(
    dark = false,
    outerBg = Color(0xFFD3D3D3),
    panelBg = Color(0xFFEEEEEE),
    accent = Color(0xFF8DC103),
    text = Color(0xFF182026),
    muted = Color(0xFF5C7080),
    primary = Color(0xFF137CBD),
    success = Color(0xFF0F9960),
    warning = Color(0xFFD9822B),
    danger = Color(0xFFDB3737),
    buttonBg = Color(0xFFF5F8FA),
    buttonBorder = Color(0xFFBFCCD6),
    inputBg = Color(0xFFFFFFFF),
    inputBorder = Color(0xFFCED9E0),
)

val DarkBp = BpColors(
    dark = true,
    outerBg = Color(0xFF222222),
    panelBg = Color(0xFF303030),
    accent = Color(0xFF8DC103),
    text = Color(0xFFF5F8FA),
    muted = Color(0xFFA7B6C2),
    primary = Color(0xFF48AFF0),
    success = Color(0xFF3DCC91),
    warning = Color(0xFFFFB366),
    danger = Color(0xFFFF7373),
    buttonBg = Color(0xFF394B59),
    buttonBorder = Color(0xFF202B33),
    inputBg = Color(0xFF202B33),
    inputBorder = Color(0xFF10161A),
)

val LocalBp = staticCompositionLocalOf { LightBp }

enum class BpIntent { None, Primary, Success, Warning, Danger }

fun BpColors.intentColor(intent: BpIntent): Color = when (intent) {
    BpIntent.None -> Color(0xFF8A9BA8)
    BpIntent.Primary -> primary
    BpIntent.Success -> success
    BpIntent.Warning -> warning
    BpIntent.Danger -> danger
}

// -- text ------------------------------------------------------------------------

@Composable
fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = LocalBp.current.text, fontSize = 15.sp, lineHeight = 22.sp)
}

@Composable
fun H5(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = LocalBp.current.text,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
    )
}

// -- callout ---------------------------------------------------------------------

/** Blueprint's `Callout`: a tinted box, optionally with a title in the intent color. */
@Composable
fun Callout(
    intent: BpIntent,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bp = LocalBp.current
    val tint = bp.intentColor(intent)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(tint.copy(alpha = if (bp.dark) 0.2f else 0.15f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Text(title, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

// -- buttons ---------------------------------------------------------------------

@Composable
fun BpButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bp = LocalBp.current
    val shape = RoundedCornerShape(3.dp)
    val fade = if (enabled) 1f else 0.45f
    Row(
        modifier
            .clip(shape)
            .background(bp.buttonBg.copy(alpha = fade))
            .border(1.dp, bp.buttonBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = bp.muted.copy(alpha = fade),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = bp.text.copy(alpha = fade), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

// -- progress --------------------------------------------------------------------

/** Blueprint's `ProgressBar` with animated stripes while [animate] is set. */
@Composable
fun BpProgressBar(progress: Float, animate: Boolean, modifier: Modifier = Modifier) {
    val bp = LocalBp.current
    val transition = rememberInfiniteTransition(label = "stripes")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
        label = "phase",
    )
    val track = bp.muted.copy(alpha = 0.25f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        drawRect(track)

        val fill = size.width * progress.coerceIn(0f, 1f)
        if (fill <= 0f) return@Canvas
        drawRect(bp.primary, size = Size(fill, size.height))

        if (animate && progress < 1f) {
            val stripe = 16.dp.toPx()
            val slant = size.height
            var x = -slant - stripe * 2 + phase * stripe * 2
            clipRect(left = 0f, top = 0f, right = fill, bottom = size.height) {
                while (x < fill) {
                    val path = Path().apply {
                        moveTo(x, size.height)
                        lineTo(x + slant, 0f)
                        lineTo(x + slant + stripe, 0f)
                        lineTo(x + stripe, size.height)
                        close()
                    }
                    drawPath(path, Color.White.copy(alpha = 0.25f))
                    x += stripe * 2
                }
            }
        }
    }
}

// -- tabs ------------------------------------------------------------------------

@Composable
fun BpTabs(selected: Int, titles: List<String>, onSelect: (Int) -> Unit) {
    val bp = LocalBp.current
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val h = 1.dp.toPx()
                drawRect(bp.muted.copy(alpha = 0.3f), topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
            },
    ) {
        titles.forEachIndexed { index, title ->
            val isSelected = index == selected
            Row(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .heightIn(min = 48.dp)
                    .drawBehind {
                        if (isSelected) {
                            val h = 3.dp.toPx()
                            drawRect(bp.primary, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    color = if (isSelected) bp.primary else bp.text,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

// -- misc ------------------------------------------------------------------------

/** A small chevron that points right when collapsed and down when expanded. */
@Composable
fun Chevron(expanded: Boolean, modifier: Modifier = Modifier) {
    val color = LocalBp.current.muted
    Canvas(modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (expanded) {
                moveTo(w * 0.15f, h * 0.35f)
                lineTo(w * 0.5f, h * 0.7f)
                lineTo(w * 0.85f, h * 0.35f)
            } else {
                moveTo(w * 0.35f, h * 0.15f)
                lineTo(w * 0.7f, h * 0.5f)
                lineTo(w * 0.35f, h * 0.85f)
            }
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Read-only, selectable box used for error messages (the web app uses a textarea). */
@Composable
fun ReadOnlyBox(text: String, modifier: Modifier = Modifier) {
    val bp = LocalBp.current
    val shape = RoundedCornerShape(3.dp)
    SelectionContainer {
        Column(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bp.inputBg)
                .border(1.dp, bp.inputBorder, shape)
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                text,
                color = bp.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
