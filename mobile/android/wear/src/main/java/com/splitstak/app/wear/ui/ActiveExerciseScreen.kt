package com.splitstak.app.wear.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.splitstak.app.wear.RotaryDispatcher
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
 * Layout pins a true semicircle (the top half of a circle inscribed in
 * the box's 2:1 bounding rect) to the top of the screen. Its curvature
 * matches the watch's bezel because both the semicircle and the watch
 * face are circles of the same diameter. The remaining bottom half of
 * the screen holds the value boxes, the done circle, and the progress
 * dots.
 *
 * Three focusable shapes, all driven by the same pattern:
 *   1. Tap a shape → orange pulsing border (focused).
 *   2. Spin the crown → adjusts the focused field.
 *   3. Tap again → release focus.
 *
 * Rotary input is read from [RotaryDispatcher] (a SharedFlow fed by
 * MainActivity.dispatchGenericMotionEvent) inside ONE persistent
 * LaunchedEffect that uses rememberUpdatedState to read the current
 * focus/exercise/setIdx without re-subscribing on every state change.
 *
 * The PR overlay flashes only when a set is newly marked done AND the
 * exercise is currently in PR territory — adjusting weight upward no
 * longer triggers a false PR celebration. Tap the overlay to dismiss.
 * The "PR" pill on the exercise card is a separate, persistent badge
 * that stays visible while the PR condition holds.
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

    // Focus state — intentionally NOT keyed on exercise.id so it
    // persists while the user spins through exercises with the crown.
    var focused by remember { mutableStateOf<String?>(null) }

    val isLefty = remember { detectLefty(context) }

    // Slow, smooth breathing pulse — read as State to defer to
    // graphicsLayer at draw time and avoid recomposing on every frame.
    val pulse = rememberInfiniteTransition(label = "focus-pulse")
    val pulseAlphaState: State<Float> = pulse.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "focus-pulse-alpha"
    )

    // ONE persistent rotary collector. rememberUpdatedState lets the
    // collector read current focus/exercise/setIdx values without
    // tearing down the collector on every state change.
    val focusedRef = rememberUpdatedState(focused)
    val exerciseIdRef = rememberUpdatedState(exercise.id)
    val setIdxRef = rememberUpdatedState(setIdx)
    LaunchedEffect(Unit) {
        var accum = 0f
        RotaryDispatcher.events.collect { delta ->
            val f = focusedRef.value ?: return@collect
            val exId = exerciseIdRef.value
            val sIdx = setIdxRef.value
            accum += delta
            // Per-mode detent thresholds. Lower for inc-style adjustments
            // so the crown feels responsive; higher for exercise nav so
            // a single roll doesn't skip past three exercises.
            val threshold = if (f == "exercise") 60f else 32f
            while (abs(accum) >= threshold) {
                val sign = if (accum > 0) 1 else -1
                accum -= sign * threshold
                when (f) {
                    "exercise" -> ActionSender.nav(context, sign)
                    "weight" -> ActionSender.incWeight(context, exId, sIdx, sign)
                    "reps"   -> ActionSender.incReps(context, exId, sIdx, sign)
                    "hold"   -> ActionSender.incHold(context, exId, sIdx, sign)
                    "ctime"  -> ActionSender.incTime(context, exId, sign.toDouble())
                    "cdist"  -> ActionSender.incDistance(context, exId, sign.toDouble())
                }
            }
        }
    }

    // PR overlay — fires only on the rising edge of done-count
    // (a set was just newly completed) AND while exercise.isPr is true.
    var showPrOverlay by remember { mutableStateOf(false) }
    val doneCount = remember(exercise) {
        exercise.sets.count { it.d } + (if (exercise.cardio?.done == true) 1 else 0)
    }
    val prevDoneCount = remember(exercise.id) { mutableStateOf(doneCount) }
    LaunchedEffect(exercise.id, doneCount, exercise.isPr) {
        if (doneCount > prevDoneCount.value && exercise.isPr) {
            showPrOverlay = true
            delay(2500)
            showPrOverlay = false
        }
        prevDoneCount.value = doneCount
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitstakColors.Bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TOP HALF: semicircle pinned to screen top, exactly tracing
            // the watch's bezel curvature.
            ExerciseSemicircle(
                exercise = exercise,
                setIdx = setIdx,
                isCardio = isCardio,
                focused = focused == "exercise",
                pulseAlphaState = pulseAlphaState,
                onClick = {
                    focused = if (focused == "exercise") null else "exercise"
                }
            )

            // BOTTOM HALF: body content centered vertically in remaining
            // space.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BodyBoxes(
                    exercise = exercise,
                    setIdx = setIdx,
                    isCardio = isCardio,
                    focused = focused,
                    pulseAlphaState = pulseAlphaState,
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
        }

        // Crown indicator arrow — pulses on whichever side the crown is
        // on, sharing the same pulse clock as the focused border.
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
                    modifier = Modifier.graphicsLayer { alpha = pulseAlphaState.value }
                )
            }
        }

        // PR overlay — tap to dismiss.
        if (showPrOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SplitstakColors.Bg)
                    .pointerInput(Unit) {
                        detectTapGestures { showPrOverlay = false }
                    },
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
 * Display's rotation and the user setting fallback.
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
 * True semicircle: top half of a circle inscribed in the box's bounding
 * rect. The Box MUST be sized 2:1 (width : height) for the arc to fit
 * exactly — fillMaxWidth() + aspectRatio(2f) does that. The arc's
 * curvature then matches the watch's bezel because both are circles of
 * the same diameter (full screen width).
 */
private object SemicircleShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        // Inscribed-circle bounds: square (0,0) to (w,w). Arc from
        // 180° (left of circle = (0, w/2)) sweeping +180° clockwise
        // passes through 270° (top = (w/2, 0)) and lands at 360°/0°
        // (right = (w, w/2)). When h = w/2 the arc's endpoints coincide
        // exactly with the box's bottom corners.
        val path = Path().apply {
            moveTo(0f, h)
            arcTo(
                rect = Rect(0f, 0f, w, w),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun ExerciseSemicircle(
    exercise: Exercise,
    setIdx: Int,
    isCardio: Boolean,
    focused: Boolean,
    pulseAlphaState: State<Float>,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.97f)
            .aspectRatio(2f)
            .background(SplitstakColors.Surface, shape = SemicircleShape)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
    ) {
        // Static dark border — always visible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.5.dp, SplitstakColors.Border, shape = SemicircleShape)
        )
        // Pulsing orange border — only when focused. graphicsLayer
        // reads pulseAlphaState at draw time, not recomposition time,
        // so the animation doesn't cause expensive recomposition.
        if (focused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = pulseAlphaState.value }
                    .border(1.5.dp, SplitstakColors.Accent, shape = SemicircleShape)
            )
        }
        // Content — clear the narrow top portion of the arc with a
        // top padding so text sits in the wider lower portion.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 18.dp, top = 56.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = exercise.name.uppercase(),
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SplitstakColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (exercise.target.isNotEmpty()) {
                Text(
                    text = exercise.target,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = SplitstakColors.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = if (isCardio) "CARDIO" else "SET ${setIdx + 1}/${exercise.sets.size}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = SplitstakColors.TextDim,
                    maxLines = 1
                )
                if (exercise.isPr) {
                    PrPill()
                }
            }
        }
    }
}

@Composable
private fun PrPill() {
    Box(
        modifier = Modifier
            .border(1.dp, SplitstakColors.Accent)
            .padding(horizontal = 3.dp, vertical = 1.dp)
    ) {
        Text(
            text = "PR",
            fontFamily = FontFamily.SansSerif,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = SplitstakColors.Accent
        )
    }
}

@Composable
private fun BodyBoxes(
    exercise: Exercise,
    setIdx: Int,
    isCardio: Boolean,
    focused: String?,
    pulseAlphaState: State<Float>,
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
                ValueBox("MIN", c?.time ?: "", focused == "ctime", pulseAlphaState) { onToggleFocus("ctime") }
                ValueBox("MI", c?.distance ?: "", focused == "cdist", pulseAlphaState) { onToggleFocus("cdist") }
            }
            exercise.mode == "bodyweight" -> {
                ValueBox("RPS", set.r, focused == "reps", pulseAlphaState) { onToggleFocus("reps") }
            }
            exercise.mode == "time" -> {
                ValueBox("SEC", set.t, focused == "hold", pulseAlphaState) { onToggleFocus("hold") }
            }
            else -> {
                ValueBox("WT", set.w, focused == "weight", pulseAlphaState) { onToggleFocus("weight") }
                ValueBox("RPS", set.r, focused == "reps", pulseAlphaState) { onToggleFocus("reps") }
            }
        }
    }
}

@Composable
private fun ValueBox(
    label: String,
    value: String,
    focused: Boolean,
    pulseAlphaState: State<Float>,
    onClick: () -> Unit
) {
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
                .pointerInput(Unit) { detectTapGestures { onClick() } }
        ) {
            // Static dark border underlay.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.5.dp, SplitstakColors.Border)
            )
            // Pulsing accent border overlay when focused.
            if (focused) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = pulseAlphaState.value }
                        .border(1.5.dp, SplitstakColors.Accent)
                )
            }
            // Value text — sits on top.
            Box(
                modifier = Modifier.fillMaxSize(),
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
