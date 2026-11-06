package com.example.smartscheduler.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.example.smartscheduler.settings.AppSettings
import com.example.smartscheduler.settings.FabStyle
import com.example.smartscheduler.ui.components.*
import com.example.smartscheduler.ui.theme.AppTheme
import com.example.smartscheduler.update.AppVersionRow
import com.example.smartscheduler.update.rememberAppUpdateViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {
    val fabStyle = settings.fabStyle
    fun setFabStyle(style: FabStyle) = settings.setFabStyle(style)
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val c = AppTheme.colors
    val fabStyle by viewModel.fabStyle.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().background(c.ground),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
    ) {
        item {
            Text(
                "ตั้งค่า",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp).semantics { heading() }
            )
        }

        item {
            SectionTitle("เมนูปุ่มเพิ่ม (+)", Modifier.padding(top = 0.dp))
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FabStyle.values().forEach { style ->
                    FabStyleCard(
                        style = style,
                        selected = style == fabStyle,
                        onClick = { viewModel.setFabStyle(style) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Hint("กดปุ่ม + ทีเดียว หรือกดค้างแล้วลากนิ้วไปที่ไอคอน ปล่อยเพื่อเลือก", Modifier.padding(top = 8.dp))
        }

        item { CategoriesSection() }

        item {
            SectionTitle("เกี่ยวกับแอป", Modifier.padding(top = 12.dp))
            Panel { AppVersionRow(rememberAppUpdateViewModel()) }
        }
    }
}

@Composable
private fun FabStyleCard(style: FabStyle, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.line, RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FabStylePreview(style, accent = if (selected) c.study else c.muted)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(style.label, style = MaterialTheme.typography.titleSmall)
        }
        Hint(style.detail)
    }
}
