package com.splitstak.app.wear.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.splitstak.app.wear.data.ActionSender
import com.splitstak.app.wear.data.Exercise
import com.splitstak.app.wear.data.SetEntry
import com.splitstak.app.wear.data.Snapshot
import com.splitstak.app.wear.data.WatchState
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The main interaction surface — one exercise, one set displayed at a time.
 *
 * Three focusable rectangles, all driven by the same pattern:
 *   1. Tap a rectangle → orange pulsing border (focused).
 *   2. Spin the crown → adjusts the focused field. The crown indicator
 *      arrow pulses on the same side the physical crown is on, in sync
 *      with the focused box's border.
 *   3. Tap again → release focus, crown becomes idle.
 *
 * The three rectangles:
 *   - Exercise dome (top): a half-circle-topped frame whose curve traces
 *     the watch's screen edge. Contains name + target + "SET 2/3".
 *     Crown cycles exercises.
 *   - WT / RPS boxes (or mode-equivalent): crown adjusts the value.
 *
 * Touch handling on the inner rectangles uses `pointerInput` rather than
 * `clickable` so they don't steal Compose focus from the outer Box, which
 * is where the rotary input is wired up. A LaunchedEffect re-requests
 * focus on every state change as a belt-and-braces safety net.
 *
 * The done circle below the boxes is a plain tap toggle — no focus needed
 * because there's nothing to "adjust" on it.
 *
 * On hitting a PR, a full-face black overlay flashes "PR" for ~3s. Every
 * interaction calls [ActionSender], which optimistically mutates the
 * watch's local snapshot for instant feedback and sends the same action
 * to the phone over MessageClient for the source-of-truth update.
 */
@Composable
fun ActiveExerciseScreen(snapshot: Snapshot) {
    val context = LocalContext.current
    val selectedId by WatchState.widgetSelectedFlow.collectAsState()
    val exercise = snapshot.exercises.firstOrNull { it.id == selectedId }
        ?: snapshot.currentExercise()
        ?: return

    val isCardio = exercise.kind == "cardio"

    val setIdx = remember(exercise.id, exercise.sets) {
        val idx = exercise.sets.indexOfFirst { !it.d }
        if (idx >= 0) idx else (exercise.sets.size - 1).coerceAtLeast(0)
    }

    // Which rectangle is focused for crown input. Intentionally NOT keyed
    // on exercise.id — we want focus to persist while the user spins
    // through exercises with the crown.
    var focused by remember { mutableStateOf<String?>(null) }

    // Compose focus + rotary handling. The outer Box always holds focus so
    // crown events route to our handler regardless of which UI rectangle
    // is visually focused-for-editing.
    val focusRequester = remember { FocusRequester() }
    var rotaryAccum by remember { mutableStateOf(0f) }
    // Re-request focus on every focus-state change. Tapping a child
    // pointerInput zone doesn't move Compose focus, but this guards
    // against edge cases (initial mount, navigations away & back).
    LaunchedEffect(focused) {
        try {
            delay(50)
            focusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    val isLefty = remember { detectLefty(context) }

    // ONE shared pulse for the focused border + crown arrow, so everything
    // breathes in sync.
    val pulse = rememberInfiniteTransition(label = "focus-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "focus-pulse-alpha"
    )

    // PR overlay — rising edge of exercise.isPr while id stays constant.
    var showPrOverlay by remember { mutableStateOf(false) }
    val prevPr = remember(exercise.id) { mutableStateOf(exercise.isPr) }
    LaunchedEffect(exercise.id, exercise.isPr) {
        if (exercise.isPr && !prevPr.value) {
            showPrOverlay = true
            delay(3000)
            showPrOverlay = false
        }
        prevPr.value = exercise.isPr
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitstakColors.Bg)
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                val f = focused ?: return@onRotaryScrollEvent false
                rotaryAccum += event.verticalScrollPixels
                // One crown detent ≈ 40px of scroll. Exercise nav uses a
                // larger threshold so the user doesn't blow past multiple
                // exercises with a single roll.
                val threshold = if (f == "exercise") 70f else 40f
                while (abs(rotaryAccum) >= threshold) {
                    val sign = if (rotaryAccum > 0) 1 else -1
                    rotaryAccum -= sign * threshold
                    when (f) {
                        "exercise" -> ActionSender.nav(context, sign)
                        "weight" -> ActionSender.incWeight(context, exercise.id, setIdx, sign)
                        "reps"   -> ActionSender.incReps(context, exercise.id, setIdx, sign)
                        "hold"   -> ActionSender.incHold(context, exercise.id, setIdx, sign)
                        "ctime"  -> ActionSender.incTime(context, exercise.id, sign.toDouble())
                        "cdist"  -> ActionSender.incDistance(context, exercise.id, sign.toDouble())
                    }
                }
                true
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ExerciseDome(
                exercise = exercise,
                setIdx = setIdx,
                isCardio = isCardio,
                focused = focused == "exercise",
                pulseAlpha = pulseAlpha,
                onClick = {
                    focused = if (focused == "exercise") null else "exercise"
                }
            )

            BodyBoxes(
                exercise = exercise,
                setIdx = setIdx,
                isCardio = isCardio,
                focused = focused,
                pulseAlpha = pulseAlpha,
                onToggleFocus = { tag -> focused = if (focused == tag) null else tag }
            )

            val isDone: Boolean = if (isCardio) {
                exercise.cardio?.done == true
            } else {
                exercise.sets.getOrNull(setIdx)?.d == true
            }
            DoneCircle(
                done = isDone,
                onClick = {
                    if (isCardio) {
                        ActionSender.toggleDone(context, exercise.id, -1)
                    } else {
                        ActionSender.toggleDone(context, exercise.id, setIdx)
                    }
                }
            )

            ProgressDots(exercises = snapshot.exercises)
        }

        // Crown indicator arrow — pulses on whichever side the crown is
        // physically on, in sync with the focused box border.
        if (focused != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp),
                contentAlignment = if (isLefty) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Text(
                    text = if (isLefty) "‹" else "›",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = SplitstakColors.Accent,
                    modifier = Modifier.alpha(pulseAlpha)
                )
            }
        }

        // PR overlay
        if (showPrOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SplitstakColors.Bg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PR",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Black,
                    color = SplitstakColors.Accent
                )
            }
        }
    }
}

/**
 * Worn-on-right-wrist mode rotates the display 180°. Check both the
 * Display's rotation (current rendering) and the user setting (Wear OS
 * persists the lefty choice in USER_ROTATION).
 */
private fun detectLefty(context: Context): Boolean {
    val rot = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            context.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }
    }.getOrNull()
    if (rot == Surface.ROTATION_180) return true
    val user = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0)
    }.getOrDefault(0)
    return user == Surface.ROTATION_180
}

/**
 * Shape with a flat bottom + sides and a domed top whose curve rises
 * `domeRise` pixels above the rectangle. Approximates a circular arc
 * with a quadratic bezier — close enough to feel like it's tracing the
 * watch's screen edge at the dome's apex.
 */
private data class DomeShape(val domeRise: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val rise = with(density) { domeRise.toPx() }
        // Don't let rise exceed the box's height — fall back to a flat top
        // for unusually-short boxes.
        val r = minOf(rise, size.height)
        val path = Path().apply {
            moveTo(0f, size.height)
            lineTo(0f, r)
            // Bezier control point at (W/2, -r) puts the curve's apex
            // exactly at (W/2, 0) — the top edge of the bounding box.
            quadraticBezierTo(
                size.width / 2f, -r,
                size.width, r
            )
            lineTo(size.width, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun ExerciseDome(
    exercise: Exercise,
    setIdx: Int,
    isCardio: Boolean,
    focused: Boolean,
    pulseAlpha: Float,
    onClick: () -> Unit
) {
    val domeRise = 22.dp
    val shape = remember { DomeShape(domeRise) }
    val borderColor: Color = if (focused) {
        SplitstakColors.Accent.copy(alpha = pulseAlpha)
    } else {
        SplitstakColors.Border
    }

    val targetLine = buildString {
        if (exercise.target.isNotEmpty()) append(exercise.target)
        if (exercise.isPr) {
            if (isNotEmpty()) append(" · ")
            append("PR")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .background(SplitstakColors.Surface, shape = shape)
            .border(1.5.dp, borderColor, shape = shape)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            // Top padding clears the dome's narrow apex zone so text
            // sits in the flat-width portion of the shape.
            .padding(start = 6.dp, end = 6.dp, top = domeRise + 2.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = exercise.name.uppercase(),
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SplitstakColors.Text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (targetLine.isNotEmpty()) {
            Text(
                text = targetLine,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = SplitstakColors.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (isCardio) "CARDIO" else "SET ${setIdx + 1}/${exercise.sets.size}",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = SplitstakColors.TextDim,
            maxLines = 1
        )
    }
}

@Composable
private fun BodyBoxes(
    exercise: Exercise,
    setIdx: Int,
    isCardio: Boolean,
    focused: String?,
    pulseAlpha: Float,
    onToggleFocus: (String) -> Unit
) {
    val set = exercise.sets.getOrNull(setIdx) ?: SetEntry("", "", "", false)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            isCardio -> {
                val c = exercise.cardio
                ValueBox("MIN", c?.time ?: "", focused == "ctime", pulseAlpha) { onToggleFocus("ctime") }
                ValueBox("MI", c?.distance ?: "", focused == "cdist", pulseAlpha) { onToggleFocus("cdist") }
            }
            exercise.mode == "bodyweight" -> {
                ValueBox("RPS", set.r, focused == "reps", pulseAlpha) { onToggleFocus("reps") }
            }
            exercise.mode == "time" -> {
                ValueBox("SEC", set.t, focused == "hold", pulseAlpha) { onToggleFocus("hold") }
            }
            else -> {
                ValueBox("WT", set.w, focused == "weight", pulseAlpha) { onToggleFocus("weight") }
                ValueBox("RPS", set.r, focused == "reps", pulseAlpha) { onToggleFocus("reps") }
            }
        }
    }
}

@Composable
private fun ValueBox(
    label: String,
    value: String,
    focused: Boolean,
    pulseAlpha: Float,
    onClick: () -> Unit
) {
    val borderColor: Color = if (focused) {
        SplitstakColors.Accent.copy(alpha = pulseAlpha)
    } else {
        SplitstakColors.Border
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            color = SplitstakColors.TextFaint
        )
        Spacer(Modifier.height(1.dp))
        Box(
            modifier = Modifier
                .width(54.dp)
                .height(32.dp)
                .background(SplitstakColors.Surface)
                .border(1.5.dp, borderColor)
                .pointerInput(Unit) { detectTapGestures { onClick() } },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value.ifEmpty { "—" },
                fontFamily = FontFamily.Monospace,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = SplitstakColors.Text
            )
        }
    }
}

@Composable
private fun DoneCircle(done: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (done) SplitstakColors.Accent else SplitstakColors.Surface,
            contentColor = if (done) SplitstakColors.Bg else SplitstakColors.TextDim
        )
    ) {
        Text(
            text = if (done) "✓" else "○",
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (done) SplitstakColors.Bg else SplitstakColors.TextDim
        )
    }
}

@Composable
private fun ProgressDots(exercises: List<Exercise>) {
    if (exercises.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (ex in exercises) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(
                        if (ex.allComplete) SplitstakColors.Accent
                        else SplitstakColors.Border,
                        shape = CircleShape
                    )
            )
        }
    }
}
