package com.example.terrainrunner.content

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.example.terrainrunner.R
import com.example.terrainrunner.RidgeSession
import com.pico.spatial.ui.design.*
import com.pico.spatial.ui.design.R as SpatialIcons

internal enum class TrailPage { HOME, FREE }
internal const val BACK_HOME = "返回主页"

@Composable
internal fun DeskHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = PicoTheme.typography.titleMedium)
        Text(subtitle, style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
    }
}

@Composable
private fun SpeedControl(s: RidgeSession) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = s::slower, enabled = s.canSlow) {
            Icon(painterResource(SpatialIcons.drawable.ic_sui_number_field_minus), "减速", Modifier.size(26.dp))
        }
        Column(
            modifier = Modifier.width(60.dp).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("速度", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary)
            Text(s.speed.toString().removeSuffix(".0") + "×", style = PicoTheme.typography.titleSmall, maxLines = 1)
        }
        IconButton(onClick = s::faster, enabled = s.canAccelerate) {
            Icon(painterResource(SpatialIcons.drawable.ic_sui_number_field_plus), "加速", Modifier.size(26.dp))
        }
    }
}

@Composable
private fun ControlToggle(label: String, active: Boolean, state: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.semantics { toggleableState = ToggleableState(active); stateDescription = state },
        contentPadding = PaddingValues(horizontal = 6.dp),
        colors = if (active) ButtonDefaults.buttonColors(
            containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight
        ) else ButtonDefaults.buttonColors()
    ) { Text(label, maxLines = 1) }
}

@Composable
private fun RunControls(s: RidgeSession) {
    val padding = PaddingValues(horizontal = 6.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SpeedControl(s)
        Spacer(Modifier.width(16.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlToggle(if (s.paused) "继续" else "暂停", s.paused,
                if (s.paused) "已暂停" else "跑动中", Modifier.weight(1f)) { s.paused = !s.paused }
            ControlToggle("数据板", s.boardVisible, if (s.boardVisible) "已显示" else "已隐藏",
                Modifier.weight(1.15f), s::toggleBoard)
            Button(onClick = s::openRunners, modifier = Modifier.weight(1f), contentPadding = padding) {
                Text("主角", maxLines = 1)
            }
        }
    }
}

@Composable
internal fun TrailMenu(s: RidgeSession, page: TrailPage, open: (TrailPage) -> Unit, onStart: () -> Unit) {
    when (page) {
        TrailPage.HOME -> {
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(painterResource(R.drawable.icon_3d_layer_1), contentDescription = null, modifier = Modifier.size(52.dp))
                DeskHeading(stringResource(R.string.app_name), "插旗寻星，捏出山路，看跑者过桥翻山。")
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStart, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    leadingIcon = { Image(painterResource(R.drawable.ic_trail_star), null, Modifier.size(22.dp)) }
                ) { Text("今日寻星") }
                Button(
                    onClick = { open(TrailPage.FREE) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    leadingIcon = { Icon(painterResource(R.drawable.ic_trail_ridge), null, Modifier.size(22.dp)) }
                ) { Text("自由玩山野") }
            }
            RunControls(s)
        }
        TrailPage.FREE -> {
            DeskHeading("自由玩山野", "插旗决定方向，捏山改变地形。", Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = s::editPoints, modifier = Modifier.weight(1f)) { Text("插旗带路") }
                Button(onClick = s::editMode, modifier = Modifier.weight(1f)) { Text("捏山修路") }
            }
            Button(onClick = { open(TrailPage.HOME) }) { Text(BACK_HOME) }
        }
    }
}
