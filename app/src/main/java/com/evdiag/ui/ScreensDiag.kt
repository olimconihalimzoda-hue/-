package com.evdiag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evdiag.data.FileShare
import java.util.Locale

private fun fmtPct(v: Double?): String = if (v == null) "—" else "%.0f%%".format(Locale.US, v)

private fun fmtDuration(sec: Long): String = "%d:%02d".format(Locale.US, sec / 60, sec % 60)

@Composable
fun DiagnosticsScreen(vm: DiagViewModel) {
    var sub by remember { mutableIntStateOf(0) }
    val titles = listOf("Ошибки", "Батарея", "Live", "Запись")
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub, containerColor = Color.Transparent) {
            titles.forEachIndexed { i, t ->
                Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t, maxLines = 1, softWrap = false, fontSize = 13.sp) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (sub) {
                0 -> DtcTab(vm)
                1 -> BatteryTab(vm)
                2 -> LiveTab(vm)
                else -> SessionTab(vm)
            }
        }
    }
}

@Composable
private fun DtcTab(vm: DiagViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Прочитать", Icons.Rounded.Search, onClick = { vm.readDtc() }, enabled = vm.connected && !vm.busy)
            ActionButton("Сбросить", Icons.Rounded.Delete, onClick = { confirm = true }, enabled = vm.connected && !vm.busy, filled = false)
        }
        if (vm.dtcReadDone && vm.dtcs.isEmpty()) AppCard { Text("Ошибок не найдено.") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.dtcs) { d ->
                AppCard {
                    Text(d.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(d.description)
                    InfoText(listOfNotNull(d.source, d.status).joinToString(" · "))
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Сбросить ошибки?") },
            text = { Text("Коды неисправностей будут стёрты в автомобиле. Если причина не устранена, они появятся снова.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.clearDtc()
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun BatteryTab(vm: DiagViewModel) {
    val p = vm.profile
    val battery = p?.fields.orEmpty().filter { it.group == "battery" }
    val hasCells = p?.fields.orEmpty().any { it.group == "cells" }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActionButton("Обновить", Icons.Rounded.Refresh, onClick = { vm.readBattery() }, enabled = vm.connected && !vm.busy)
        if (battery.isEmpty() && !hasCells) {
            AppCard {
                Text("В этом профиле нет данных батареи.")
                InfoText("Откройте «Проверка» → «Сбор данных», сохраните лог и отправьте его разработчику: по нему создаётся профиль для этой модели.")
            }
        } else {
            AppCard {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BatteryGauge(vm.numByRole("soc"))
                }
                TileGrid(vm.indicators())
            }
            val cells = vm.cellsList()
            if (cells.isNotEmpty()) {
                AppCard {
                    SectionTitle("Ячейки")
                    CellBars(cells)
                    InfoText(
                        "ячеек: ${cells.size} · мин %.3f В · макс %.3f В".format(Locale.US, cells.min(), cells.max()),
                    )
                }
                AppCard {
                    SectionTitle("3D-вид батареи")
                    Pack3D(cells)
                    InfoText("Тяните пальцем влево-вправо, чтобы повернуть. Высота и цвет столбика показывают напряжение ячейки.")
                }
            }
        }
    }
}

@Composable
private fun LiveTab(vm: DiagViewModel) {
    val fields = vm.profile?.fields.orEmpty().filter { it.group == "live" && it.request !in vm.deadRequests }
    val hidden = vm.deadRequests.size
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vm.live) {
            ActionButton("Остановить", Icons.Rounded.Stop, onClick = { vm.stopLive() })
        } else {
            ActionButton("Запустить", Icons.Rounded.PlayArrow, onClick = { vm.startLive() }, enabled = vm.connected)
        }
        if (hidden > 0) InfoText("Скрыто параметров, которые эта машина не отдаёт: $hidden.")
        for (f in fields) {
            AppCard {
                Text(f.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(vm.values[f.id] ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                LineChart(vm.series[f.id].orEmpty())
            }
        }
    }
}

@Composable
private fun SessionTab(vm: DiagViewModel) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard {
            SectionTitle("Запись сессии")
            InfoText(
                "Во время зарядки или поездки приложение раз в секунду записывает SOC, напряжение и ток. " +
                    "По набранной энергии и изменению SOC оценивается ёмкость батареи (приблизительно). " +
                    "Нужен профиль с полями soc, packV и current.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.sessionActive) {
                    ActionButton("Остановить", Icons.Rounded.Stop, onClick = { vm.stopSession() })
                } else {
                    ActionButton("Начать запись", Icons.Rounded.PlayArrow, onClick = { vm.startSession() }, enabled = vm.connected)
                }
                ActionButton(
                    "CSV", Icons.Rounded.Share,
                    onClick = {
                        val f = vm.exportSessionCsv()
                        if (f != null) {
                            FileShare.file(ctx, f, "text/csv", "Запись сессии")
                        } else {
                            vm.toast = "Нет данных для экспорта"
                        }
                    },
                    filled = false,
                )
            }
        }
        val s = vm.sessionInfo
        if (s != null) {
            AppCard {
                val est = s.estimate
                TileGrid(
                    listOf(
                        "Время" to fmtDuration(s.seconds),
                        "SOC" to "${fmtPct(s.socStart)} → ${fmtPct(s.socNow)}",
                        (if (s.energyKwh >= 0.0) "Заряжено" else "Израсходовано") to
                            "%.2f кВт·ч".format(Locale.US, kotlin.math.abs(s.energyKwh)),
                        "Ёмкость (оценка)" to (if (est != null) "%.1f кВт·ч".format(Locale.US, est.kwh) else "нужно ≥5% по SOC"),
                    ),
                )
                val soh = est?.sohPercent
                if (soh != null) Text("Оценка SOH по ёмкости: %.0f%%".format(Locale.US, soh), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
