package com.example.videoenhancer.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.videoenhancer.player.smb.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SmbBrowserDialog(
    onDismiss: () -> Unit,
    onSelectVideo: (Uri, String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SmbBrowserRepository() }
    val historyStore = remember { SmbHistoryStore(context) }
    val savedServers by historyStore.savedServersFlow.collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    var host by remember { mutableStateOf("192.168.1.100") }
    var shareName by remember { mutableStateOf("video") }
    var username by remember { mutableStateOf("guest") }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(true) }

    var activeConfig by remember { mutableStateOf<SmbServerConfig?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var fileList by remember { mutableStateOf<List<SmbFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadDirectory(config: SmbServerConfig, path: String, saveToHistory: Boolean = false) {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            repository.listDirectory(config, path)
                .onSuccess { items ->
                    if (saveToHistory) historyStore.saveServer(config, rememberPassword)
                    activeConfig = config
                    currentPath = path
                    fileList = items
                }
                .onFailure { err ->
                    errorMessage = err.localizedMessage ?: "NASへの接続に失敗しました"
                }
            isLoading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activeConfig != null) {
                        IconButton(
                            onClick = {
                                if (currentPath.isEmpty()) {
                                    activeConfig = null
                                    errorMessage = null
                                } else {
                                    val parent = currentPath.substringBeforeLast('\\', "")
                                    loadDirectory(activeConfig!!, parent)
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    } else {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (activeConfig == null) "NAS / SMB サーバー接続" else "//${activeConfig!!.host}/${activeConfig!!.shareName}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (activeConfig != null) {
                            Text(
                                text = if (currentPath.isEmpty()) "/" else "/${currentPath.replace('\\', '/')}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる")
                    }
                }

                HorizontalDivider()

                if (activeConfig == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (savedServers.isNotEmpty()) {
                            Text("接続履歴（タップで即接続）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(savedServers, key = { it.id }) { saved ->
                                    InputChip(
                                        selected = false,
                                        onClick = {
                                            host = saved.host
                                            shareName = saved.shareName
                                            username = saved.username
                                            password = saved.password
                                            loadDirectory(saved.toServerConfig(), "", saveToHistory = true)
                                        },
                                        label = { Text("${saved.username}@${saved.host}/${saved.shareName}", fontSize = 12.sp) },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { coroutineScope.launch { historyStore.removeServer(saved.id) } },
                                                modifier = Modifier.size(18.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "削除", modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = { host = it },
                                label = { Text("NAS IPアドレス / ホスト名") },
                                singleLine = true,
                                modifier = Modifier.weight(1.5f)
                            )
                            OutlinedTextField(
                                value = shareName,
                                onValueChange = { shareName = it },
                                label = { Text("共有フォルダ名") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("ユーザー名") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("パスワード (空欄可)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (errorMessage != null) {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(Modifier.weight(1f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { rememberPassword = !rememberPassword }
                            ) {
                                Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
                                Text("パスワードも保存する", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(12.dp))
                                }
                                Button(
                                    enabled = !isLoading && host.isNotBlank() && shareName.isNotBlank(),
                                    onClick = {
                                        loadDirectory(
                                            SmbServerConfig(host.trim(), shareName.trim().trim('/'), username.trim(), password),
                                            "",
                                            saveToHistory = true
                                        )
                                    }
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("フォルダを開く")
                                }
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            errorMessage != null -> {
                                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { loadDirectory(activeConfig!!, currentPath) }) { Text("再読み込み") }
                                }
                            }
                            fileList.isEmpty() -> Text("動画ファイルがありません", modifier = Modifier.align(Alignment.Center))
                            else -> {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(fileList, key = { it.relativePath }) { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (item.isDirectory) {
                                                        loadDirectory(activeConfig!!, item.relativePath)
                                                    } else {
                                                        onSelectVideo(item.toPlaybackUri(activeConfig!!), item.name)
                                                    }
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.VideoFile,
                                                contentDescription = null,
                                                tint = if (item.isDirectory) Color(0xFFFBBF24) else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (!item.isDirectory) {
                                                    val mb = item.sizeBytes / (1024.0 * 1024.0)
                                                    val sizeStr = if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
                                                    else String.format(Locale.US, "%.1f MB", mb)
                                                    Text(sizeStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            if (item.isDirectory) {
                                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                                            }
                                        }
                                        HorizontalDivider(thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.2f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
