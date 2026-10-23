package com.smsforwarder.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.ui.components.SwipeMessageCard
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class HistorySort { BY_SENDER, BY_DAY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    messages: List<MessageEntity>,
    onBack: () -> Unit,
    onClearHistory: () -> Unit,
    onCopyOtp: (String) -> Unit,
    onCopyBody: (String) -> Unit,
    onPinToInbox: (MessageEntity) -> Unit,
    onDeleteMessage: (Long) -> Unit
) {
    var sort by remember { mutableStateOf(HistorySort.BY_SENDER) }
    var query by remember { mutableStateOf("") }
    var expandedMessageId by remember { mutableStateOf<Long?>(null) }
    var expandedSenders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val filtered = remember(messages, query) {
        val q = query.trim()
        if (q.isEmpty()) messages
        else messages.filter {
            it.sender.contains(q, ignoreCase = true) ||
                it.body.contains(q, ignoreCase = true)
        }
    }
    val senderGroups = remember(filtered) {
        filtered.groupBy { it.sender }
            .toList()
            .sortedByDescending { (_, msgs) -> msgs.maxOf { it.receivedAt } }
    }
    val dayGroups = remember(filtered) {
        filtered.groupBy { dayKey(it.receivedAt) }
            .toList()
            .sortedByDescending { it.second.maxOf { m -> m.receivedAt } }
    }

    LaunchedEffect(filtered) {
        if (expandedSenders.isEmpty() && filtered.isNotEmpty()) {
            expandedSenders = setOf(filtered.first().sender)
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) expandedMessageId = null
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all history?") },
            text = { Text("Removes older unpinned messages. Pinned and today’s inbox stay.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearHistory()
                    }
                ) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("History", fontWeight = FontWeight.Bold)
                        Text(
                            "Older unpinned",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete all history") },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteForever, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                confirmClear = true
                            },
                            enabled = messages.isNotEmpty()
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search sender or text") }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sort == HistorySort.BY_SENDER,
                        onClick = { sort = HistorySort.BY_SENDER },
                        label = { Text("By number") }
                    )
                    FilterChip(
                        selected = sort == HistorySort.BY_DAY,
                        onClick = { sort = HistorySort.BY_DAY },
                        label = { Text("By day") }
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (messages.isEmpty()) "No older messages."
                        else "No older messages match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else if (sort == HistorySort.BY_SENDER) {
                senderGroups.forEach { (sender, msgs) ->
                    val open = sender in expandedSenders
                    item(key = "g-$sender") {
                        SenderGroupHeader(
                            sender = sender,
                            count = msgs.size,
                            preview = msgs.first().body,
                            lastAt = msgs.maxOf { it.receivedAt },
                            expanded = open,
                            onClick = {
                                expandedSenders = if (open) {
                                    expandedSenders - sender
                                } else {
                                    expandedSenders + sender
                                }
                            }
                        )
                    }
                    if (open) {
                        val byDay = msgs.groupBy { dayKey(it.receivedAt) }
                            .toList()
                            .sortedByDescending { it.second.maxOf { m -> m.receivedAt } }
                        byDay.forEach { (label, dayMsgs) ->
                            item(key = "d-$sender-$label") {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                )
                            }
                            items(dayMsgs, key = { it.id }) { msg ->
                                HistorySwipeRow(
                                    msg = msg,
                                    showTimeOnly = true,
                                    expandedMessageId = expandedMessageId,
                                    onExpandedChange = {
                                        expandedMessageId = if (it) msg.id else null
                                    },
                                    onCopyOtp = onCopyOtp,
                                    onCopyBody = onCopyBody,
                                    onPinToInbox = onPinToInbox,
                                    onDeleteMessage = onDeleteMessage
                                )
                            }
                        }
                    }
                }
            } else {
                dayGroups.forEach { (label, dayMsgs) ->
                    item(key = "day-$label") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(dayMsgs, key = { it.id }) { msg ->
                        HistorySwipeRow(
                            msg = msg,
                            showTimeOnly = false,
                            expandedMessageId = expandedMessageId,
                            onExpandedChange = {
                                expandedMessageId = if (it) msg.id else null
                            },
                            onCopyOtp = onCopyOtp,
                            onCopyBody = onCopyBody,
                            onPinToInbox = onPinToInbox,
                            onDeleteMessage = onDeleteMessage
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySwipeRow(
    msg: MessageEntity,
    showTimeOnly: Boolean,
    expandedMessageId: Long?,
    onExpandedChange: (Boolean) -> Unit,
    onCopyOtp: (String) -> Unit,
    onCopyBody: (String) -> Unit,
    onPinToInbox: (MessageEntity) -> Unit,
    onDeleteMessage: (Long) -> Unit
) {
    SwipeMessageCard(
        msg = msg,
        expanded = expandedMessageId == msg.id,
        onExpandedChange = onExpandedChange,
        onCopyOtp = onCopyOtp,
        onCopyBody = onCopyBody,
        onTogglePin = {
            onExpandedChange(false)
            onPinToInbox(msg)
        },
        onDelete = {
            onExpandedChange(false)
            onDeleteMessage(msg.id)
        },
        forceUnpinnedActions = true,
        showTimeOnly = showTimeOnly
    )
}

@Composable
private fun SenderGroupHeader(
    sender: String,
    count: Int,
    preview: String,
    lastAt: Long,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val initials = remember(sender) {
        sender.filter { it.isLetterOrDigit() }.take(2).uppercase().ifBlank { "?" }
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                BoxCenter {
                    Text(
                        initials,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    sender,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        "$count",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    shortDayLabel(lastAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun BoxCenter(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { content() }
}

private fun dayKey(millis: Long): String {
    val cal = Calendar.getInstance()
    val today = Calendar.getInstance()
    cal.timeInMillis = millis
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(cal, today) -> "Today"
        sameDay(cal, yesterday) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(millis))
    }
}

private fun shortDayLabel(millis: Long): String {
    val cal = Calendar.getInstance()
    val today = Calendar.getInstance()
    cal.timeInMillis = millis
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(cal, today) -> "Today"
        sameDay(cal, yesterday) -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))
    }
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
