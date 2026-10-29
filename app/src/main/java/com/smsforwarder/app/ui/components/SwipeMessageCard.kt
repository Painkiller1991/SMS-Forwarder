package com.smsforwarder.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.data.MessageEntity
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

val ActionCopyBlue = Color(0xFF1565C0)
val ActionPinAmber = Color(0xFFF9A825)
val ActionDeleteRed = Color(0xFFC62828)

@Composable
fun SwipeMessageCard(
    msg: MessageEntity,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCopyOtp: (String) -> Unit,
    onCopyBody: (String) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    /** When true (History), always show Delete and Pin means pin-to-inbox. */
    forceUnpinnedActions: Boolean = false,
    showTimeOnly: Boolean = false
) {
    val density = LocalDensity.current
    val treatAsPinned = msg.pinned && !forceUnpinnedActions
    val actionCount = if (treatAsPinned) 2 else 3
    val revealPx = with(density) {
        (64.dp * actionCount + 8.dp * (actionCount + 1)).toPx()
    }
    val offsetX = remember(msg.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(expanded, revealPx) {
        offsetX.animateTo(if (expanded) -revealPx else 0f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(msg.id, revealPx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val open = offsetX.value <= -revealPx / 2f
                        onExpandedChange(open)
                        scope.launch {
                            offsetX.animateTo(if (open) -revealPx else 0f)
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            offsetX.snapTo(
                                (offsetX.value + dragAmount).coerceIn(-revealPx, 0f)
                            )
                        }
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionTile(
                icon = Icons.Default.ContentCopy,
                contentDescription = "Copy message",
                container = ActionCopyBlue,
                contentColor = Color.White,
                onClick = {
                    onCopyBody(msg.body)
                    onExpandedChange(false)
                }
            )
            Spacer(Modifier.width(8.dp))
            ActionTile(
                icon = Icons.Default.PushPin,
                contentDescription = if (treatAsPinned) "Unpin" else "Pin",
                container = ActionPinAmber,
                contentColor = Color(0xFF212121),
                onClick = onTogglePin
            )
            if (!treatAsPinned) {
                Spacer(Modifier.width(8.dp))
                ActionTile(
                    icon = Icons.Default.Delete,
                    contentDescription = "Delete",
                    container = ActionDeleteRed,
                    contentColor = Color.White,
                    onClick = onDelete
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!showTimeOnly) {
                        Text(
                            msg.sender,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (msg.pinned && !forceUnpinnedActions) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = ActionPinAmber.copy(alpha = 0.25f)
                            ) {
                                Text(
                                    "Pinned",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF57F17)
                                )
                            }
                        }
                        Text(
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.receivedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(msg.body, color = MaterialTheme.colorScheme.onSurface)
                val otpText = msg.otp?.takeIf { it.isNotBlank() }
                    ?: com.smsforwarder.app.otp.OtpParser.extract(msg.body)
                if (!otpText.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { onCopyOtp(otpText) },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "OTP  $otpText",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight(),
        shape = MaterialTheme.shapes.medium,
        color = container
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
