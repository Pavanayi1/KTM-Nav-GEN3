package com.navigator.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.navigator.app.logging.AppLogger
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.OpenDashIcons

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lines by AppLogger.lines.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(Ktm.Screen).systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                OpenDashIcons.ChevronLeft, contentDescription = "Back", tint = Ktm.TextSecondary,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp).size(22.dp),
            )
            Text(
                "LOGS", color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic, fontSize = 28.sp, modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Text(
                "SHARE", color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, Ktm.Orange, RoundedCornerShape(7.dp))
                    .clickable {
                        val file = AppLogger.currentLogFile() ?: return@clickable
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Navigator Gen3 log"))
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines) { line ->
                Text(line, color = Ktm.TextSecondary, fontFamily = JetBrainsMono, fontSize = 11.sp)
            }
        }
    }
}
