package com.evdiag.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evdiag.data.FileShare
import com.evdiag.domain.Level

@Composable
fun MoreScreen(vm: DiagViewModel) {
    var sub by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = sub != null) { sub = null }
    when (sub) {
        "console" -> SubPage("Консоль", { sub = null }) { ConsoleScreen(vm) }
        "brand" -> SubPage("Мой бренд", { sub = null }) { BrandScreen(vm) }
        "profiles" -> SubPage("Профили автомобилей", { sub = null }) { ProfilesScreen(vm) }
        "about" -> SubPage("О приложении", { sub = null }) { AboutScreen() }
        else -> MoreMenu { sub = it }
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    AppCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                InfoText(subtitle)
            }
        }
    }
}

@Composable
private fun MoreMenu(open: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderBanner("Ещё", "Бренд мастера, профили автомобилей и инструменты")
        MenuRow(Icons.Rounded.Edit, "Мой бренд", "Имя, компания, телефон и логотип для отчётов") { open("brand") }
        MenuRow(Icons.Rounded.DirectionsCar, "Профили автомобилей", "Обновление из интернета и импорт из файла") { open("profiles") }
        MenuRow(Icons.Rounded.Code, "Консоль", "Команды чтения и журнал обмена с адаптером") { open("console") }
        MenuRow(Icons.Rounded.Info, "О приложении", "Версия, ограничения и конфиденциальность") { open("about") }
    }
}

@Composable
private fun BrandScreen(vm: DiagViewModel) {
    var name by remember { mutableStateOf(vm.inspectorName) }
    var comp by remember { mutableStateOf(vm.company) }
    var ph by remember { mutableStateOf(vm.phone) }
    var price by remember { mutableStateOf(vm.price) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setLogo(uri)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard {
            SectionTitle("Данные в шапке отчёта")
            OutlinedTextField(name, { name = it }, label = { Text("Ваше имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(comp, { comp = it }, label = { Text("Компания / название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ph, { ph = it }, label = { Text("Телефон") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(price, { price = it }, label = { Text("Стоимость проверки по умолчанию") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ActionButton("Сохранить", Icons.Rounded.Edit, onClick = { vm.saveBrand(name, comp, ph, price) })
        }
        AppCard {
            SectionTitle("Логотип")
            InfoText(if (vm.logoFile != null) "Логотип загружен и будет в шапке отчёта." else "Логотип не выбран.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Выбрать", Icons.Rounded.Image, onClick = { picker.launch(arrayOf("image/*")) }, filled = false)
                if (vm.logoFile != null) {
                    ActionButton("Убрать", Icons.Rounded.Delete, onClick = { vm.clearLogo() }, filled = false)
                }
            }
        }
    }
}

@Composable
private fun ProfilesScreen(vm: DiagViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importProfile(uri)
    }
    val green = levelColor(Level.GREEN)
    val amber = levelColor(Level.YELLOW)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard {
            SectionTitle("Обновление из интернета")
            InfoText(
                "Приложение скачает файл index.json и перечисленные в нём профили. Адрес можно указать на папку profiles " +
                    "вашего репозитория GitHub. Из профилей принимаются только команды чтения.",
            )
            OutlinedTextField(
                vm.updateUrl, { vm.updateUrl = it },
                label = { Text("Адрес папки с профилями (https://…/)") },
                modifier = Modifier.fillMaxWidth(),
            )
            ActionButton(
                if (vm.updating) "Обновление…" else "Обновить профили", Icons.Rounded.CloudDownload,
                onClick = { vm.updateProfiles() }, enabled = !vm.updating,
            )
        }
        AppCard {
            SectionTitle("Импорт из файла")
            InfoText("Выберите файл профиля .json: он добавится в приложение без пересборки.")
            ActionButton("Выбрать файл", Icons.Rounded.Share, onClick = { picker.launch(arrayOf("*/*")) }, filled = false)
        }
        AppCard {
            SectionTitle("Установленные профили (${vm.profiles.size})")
            for (p in vm.profiles) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Pill(if (p.verified) "проверен" else "заготовка", if (p.verified) green else amber)
                }
            }
        }
    }
}

@Composable
private fun AboutScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard {
            SectionTitle("EV Diag 0.2")
            InfoText("Диагностика электромобилей через адаптер ELM327 (Bluetooth или Wi-Fi).")
        }
        AppCard {
            SectionTitle("Важно")
            Text("• Оценка «светофор» ориентировочная: пороги SOH и разброса ячеек не являются нормами производителя.")
            Text("• Данные батареи доступны только для моделей с проверенным профилем. Профили-заготовки могут быть неточными.")
            Text("• Отчёт носит справочный характер и не заменяет диагностику в сервисе.")
            Text("• Кнопка «Сбросить» стирает коды ошибок в автомобиле.")
        }
        AppCard {
            SectionTitle("Конфиденциальность")
            Text("Приложение не отправляет данные на сервер. Отчёты, логи и история хранятся на телефоне. Интернет используется только для загрузки профилей по адресу, который вы указали.")
        }
    }
}

@Composable
private fun ConsoleScreen(vm: DiagViewModel) {
    val ctx = LocalContext.current
    var cmd by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoText("Разрешены AT-команды и команды чтения (01, 02, 03, 07, 09, 0A, 19, 21, 22). Остальные блокируются.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                cmd, { cmd = it },
                modifier = Modifier.weight(1f),
                label = { Text("Команда, напр. 010D") },
                singleLine = true,
            )
            ActionButton(
                "OK", Icons.Rounded.PlayArrow,
                onClick = {
                    vm.sendRaw(cmd)
                    cmd = ""
                },
                enabled = vm.connected && !vm.busy,
            )
        }
        ActionButton(
            "Поделиться журналом", Icons.Rounded.Share,
            onClick = { FileShare.file(ctx, vm.saveLog(), "text/plain", "Журнал EV Diag") },
            filled = false,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(vm.logLines.takeLast(300)) {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
