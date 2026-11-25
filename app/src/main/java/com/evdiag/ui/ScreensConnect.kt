package com.evdiag.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.evdiag.data.FileShare
import com.evdiag.domain.Level
import com.evdiag.transport.BluetoothTransport
import com.evdiag.transport.SimulatorTransport
import com.evdiag.transport.WifiTransport

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(vm: DiagViewModel) {
    val ctx = LocalContext.current
    var host by remember { mutableStateOf("192.168.0.10") }
    var expanded by remember { mutableStateOf(false) }
    var permGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permGranted = it }
    val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    val green = levelColor(Level.GREEN)
    val amber = levelColor(Level.YELLOW)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderBanner("EV Diag", "Диагностика электромобилей и проверка батареи")

        AppCard {
            SectionTitle("Автомобиль")
            val p = vm.profile
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p?.name ?: "—", style = MaterialTheme.typography.titleSmall)
                    val ok = p?.verified == true
                    Pill(if (ok) "Проверен на машине" else "Не проверен на машине", if (ok) green else amber)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть" else "Сменить") }
            }
            if (expanded) {
                for (pr in vm.profiles) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                vm.selectProfile(pr)
                                expanded = false
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pr.id == p?.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(pr.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val notes = p?.notes.orEmpty()
            if (notes.isNotEmpty()) InfoText(notes)
        }

        vm.vin?.let { v ->
            AppCard {
                SectionTitle("VIN")
                Text(v, style = MaterialTheme.typography.titleSmall)
                val hint = vm.vinHint
                if (hint != null && hint.id != vm.profile?.id) {
                    InfoText("По VIN подходит профиль: ${hint.name}")
                    ActionButton(
                        "Выбрать этот профиль", Icons.Rounded.DirectionsCar,
                        onClick = { vm.selectProfile(hint) }, filled = false,
                    )
                }
            }
        }

        AppCard {
            SectionTitle("Bluetooth-адаптер")
            if (!permGranted) {
                ActionButton(
                    "Разрешить Bluetooth", Icons.Rounded.Bluetooth,
                    onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                )
            } else {
                val bonded = adapter?.bondedDevices?.toList().orEmpty()
                if (bonded.isEmpty()) {
                    InfoText("Нет спаренных устройств. Сначала спарьте адаптер в настройках Bluetooth телефона (PIN обычно 1234 или 0000).")
                }
                for (d in bonded) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !vm.busy) { vm.connect(BluetoothTransport(d)) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(d.name ?: "Устройство", style = MaterialTheme.typography.bodyMedium)
                            InfoText(d.address)
                        }
                    }
                }
            }
        }

        AppCard {
            SectionTitle("Wi-Fi адаптер")
            OutlinedTextField(
                host, { host = it },
                label = { Text("IP-адрес (порт 35000)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ActionButton(
                "Подключиться по Wi-Fi", Icons.Rounded.Wifi,
                onClick = { vm.connect(WifiTransport(host)) }, enabled = !vm.busy, filled = false,
            )
        }

        AppCard {
            SectionTitle("Без автомобиля")
            InfoText("Симулятор отдаёт вымышленные данные: на нём можно проверить интерфейс, проверку и отчёт.")
            ActionButton(
                "Запустить симулятор", Icons.Rounded.Bolt,
                onClick = {
                    vm.profiles.firstOrNull { it.id == "demo_ev" }?.let { vm.selectProfile(it) }
                    vm.connect(SimulatorTransport())
                },
                enabled = !vm.busy, filled = false,
            )
        }

        AppCard {
            SectionTitle("Проверка адаптера")
            InfoText(
                "Для бензиновой или дизельной машины выберите выше профиль «Любой автомобиль (стандартный OBD-II)». " +
                    "Проверка только читает данные и ничего не меняет.",
            )
            ActionButton(
                "Проверить адаптер и связь", Icons.Rounded.Search,
                onClick = { vm.runAdapterCheck() },
                enabled = vm.connected && !vm.busy, filled = false,
            )
            for (ln in vm.adapterReport) {
                Text(ln, style = MaterialTheme.typography.bodySmall)
            }
            if (vm.adapterReport.isNotEmpty()) {
                ActionButton(
                    "Отправить разработчику", Icons.Rounded.Share,
                    onClick = { FileShare.file(ctx, vm.saveAdapterReport(), "text/plain", "Проверка адаптера EV Diag") },
                    filled = false,
                )
            }
            if (!vm.connected) InfoText("Сначала подключитесь к адаптеру.")
        }

        if (vm.connected) {
            ActionButton("Отключиться", Icons.Rounded.Stop, onClick = { vm.disconnect() }, modifier = Modifier.fillMaxWidth())
        }
    }
}
