package com.evdiag.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.evdiag.data.InspectionRecord
import com.evdiag.data.FileShare
import com.evdiag.domain.Level
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InspectionScreen(vm: DiagViewModel) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderBanner("Проверка перед покупкой", "Диагностика, оценка батареи и PDF-отчёт для клиента")

        AppCard {
            SectionTitle("Данные проверки")
            OutlinedTextField(
                vm.clientName, { vm.clientName = it },
                label = { Text("Клиент") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    vm.mileage, { vm.mileage = it },
                    label = { Text("Пробег, км") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    vm.price, { vm.price = it },
                    label = { Text("Стоимость") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            ActionButton(
                "Запустить проверку", Icons.Rounded.CheckCircle,
                onClick = { vm.runInspection() },
                enabled = vm.connected && !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!vm.connected) InfoText("Сначала подключитесь к адаптеру на вкладке «Подключение» или запустите симулятор.")
        }

        if (vm.inspectionDone) {
            val v = vm.verdict()
            VerdictCard(v)
            AppCard {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BatteryGauge(vm.numByRole("soc"))
                }
                TileGrid(vm.indicators())
                val avg = vm.modelAverage()
                if (avg != null) {
                    InfoText("Средний SOH по вашим проверкам этой модели: %.0f%% (проверок: %d)".format(Locale.US, avg.first, avg.second))
                }
            }
            val cells = vm.cellsList()
            if (cells.isNotEmpty()) {
                AppCard {
                    SectionTitle("Ячейки батареи")
                    CellBars(cells)
                }
            }
            AppCard {
                SectionTitle("Ошибки")
                if (vm.dtcs.isEmpty()) {
                    Text("Ошибок не найдено.")
                } else {
                    for (d in vm.dtcs) Text("${d.code} — ${d.description}")
                }
            }
            AppCard {
                SectionTitle("Отчёт")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Создать PDF", Icons.Rounded.Description, onClick = { vm.createReport() })
                    val f = vm.lastReport
                    if (f != null) {
                        ActionButton(
                            "Поделиться", Icons.Rounded.Share,
                            onClick = { FileShare.file(ctx, f, "application/pdf", "Отчёт о проверке") },
                            filled = false,
                        )
                    }
                }
                val f2 = vm.lastReport
                if (f2 != null) InfoText("Файл: ${f2.name}")
            }
        }

        AppCard {
            SectionTitle("Сбор данных для профиля")
            InfoText(
                "Опрашивает автомобиль только на чтение и сохраняет лог. Отправьте файл разработчику: " +
                    "по нему создаётся профиль батареи для этой модели. В файл попадёт VIN автомобиля.",
            )
            if (vm.collecting) {
                LinearProgressIndicator(progress = { vm.collectProgress }, modifier = Modifier.fillMaxWidth())
                InfoText(vm.collectLabel)
                ActionButton("Остановить", Icons.Rounded.Stop, onClick = { vm.cancelCollect() }, filled = false)
            } else {
                ActionButton(
                    "Запустить сбор", Icons.Rounded.Search,
                    onClick = { vm.collectData() },
                    enabled = vm.connected && !vm.busy,
                )
            }
            val lf = vm.lastCollectFile
            if (lf != null && !vm.collecting) {
                ActionButton(
                    "Поделиться логом", Icons.Rounded.Share,
                    onClick = { FileShare.file(ctx, lf, "text/plain", "Лог EV Diag") },
                    filled = false,
                )
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: DiagViewModel) {
    val ctx = LocalContext.current
    var toDelete by remember { mutableStateOf<InspectionRecord?>(null) }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { HeaderBanner("История проверок", "Все отчёты и выручка в одном месте") }
        item {
            val total = vm.records.sumOf { it.price }
            val sohList = vm.records.mapNotNull { it.soh }
            TileGrid(
                listOf(
                    "Проверок" to vm.records.size.toString(),
                    "Выручка" to "%.0f".format(Locale.US, total),
                    "Средний SOH" to (if (sohList.isEmpty()) "—" else "%.0f%%".format(Locale.US, sohList.average())),
                    "Красных итогов" to vm.records.count { it.level == "RED" }.toString(),
                ),
            )
        }
        item {
            ActionButton(
                "Экспорт в CSV", Icons.Rounded.Share,
                onClick = { FileShare.file(ctx, vm.exportHistoryCsv(), "text/csv", "История проверок") },
                enabled = vm.records.isNotEmpty(), filled = false,
            )
        }
        if (vm.records.isEmpty()) {
            item { AppCard { Text("Пока нет проверок. Создайте отчёт на вкладке «Проверка»: он появится здесь.") } }
        }
        items(vm.records, key = { it.id }) { r ->
            val pdf = File(r.pdfPath).takeIf { it.exists() }
            RecordCard(
                r, fmt,
                onShare = if (pdf != null) ({ FileShare.file(ctx, pdf, "application/pdf", "Отчёт о проверке") }) else null,
                onDelete = { toDelete = r },
            )
        }
    }

    val del = toDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить запись?") },
            text = { Text("Запись исчезнет из истории. PDF-файл на телефоне останется.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRecord(del)
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun RecordCard(r: InspectionRecord, fmt: SimpleDateFormat, onShare: (() -> Unit)?, onDelete: () -> Unit) {
    val level = runCatching { Level.valueOf(r.level) }.getOrDefault(Level.GRAY)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(levelColor(level)))
            Column(Modifier.weight(1f)) {
                Text(r.profile, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                InfoText(listOf(r.client.ifBlank { "Клиент не указан" }, fmt.format(Date(r.ts))).joinToString(" · "))
                InfoText(
                    listOfNotNull(
                        r.vin.takeIf { it.isNotBlank() },
                        r.soh?.let { "SOH %.0f%%".format(Locale.US, it) },
                        r.spreadMv?.let { "разброс %.0f мВ".format(Locale.US, it) },
                        if (r.price > 0.0) "%.0f".format(Locale.US, r.price) else null,
                    ).joinToString(" · "),
                )
            }
            if (onShare != null) {
                IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, contentDescription = "Поделиться") }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Удалить") }
        }
    }
}
