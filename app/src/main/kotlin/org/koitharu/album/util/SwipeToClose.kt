package org.koitharu.album.util

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.launch

fun Modifier.slideUpToClose(
    onClose: () -> Unit,
    onSwipeUp: () -> Unit,
) = composed {
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }
    graphicsLayer {
        val maxDistance = size.height
        val progress = if (maxDistance > 0) (offsetY.value / maxDistance) else 0f
        translationY = offsetY.value
        alpha = (1f - progress).coerceIn(0f, 1f)
    }.pointerInput(Unit) {
        var dragDistance = 0f
        detectDragGestures(
            orientationLock = Orientation.Vertical,
            onDragStart = { _, _, _ ->
                dragDistance = 0f
                velocityTracker.resetTracking()
            },
            onDragCancel = {
                velocityTracker.resetTracking()
                scope.launch { offsetY.animateTo(0f) }
            },
            onDrag = { change, dragAmount ->
                velocityTracker.addPointerInputChange(change)
                change.consume()
                dragDistance += dragAmount.y
                scope.launch {
                    offsetY.snapTo(dragDistance.coerceIn(0f, size.height.toFloat()))
                }
            },
            onDragEnd = {
                val velocity = velocityTracker.calculateVelocity()
                val dismissThreshold = size.height / 4f
                val infoThreshold = size.height / 10f
                when {
                    dragDistance >= dismissThreshold ||
                            (dragDistance > 0f && velocity.y >= viewConfiguration.minimumFlingVelocity) -> onClose()

                    dragDistance <= -infoThreshold -> {
                        scope.launch { offsetY.animateTo(0f) }
                        onSwipeUp()
                    }

                    else -> scope.launch { offsetY.animateTo(0f) }
                }
            }
        )
    }
}
