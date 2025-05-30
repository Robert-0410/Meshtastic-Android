/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.geeksville.mesh.ui.metrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geeksville.mesh.R
import com.geeksville.mesh.TelemetryProtos.Telemetry
import com.geeksville.mesh.model.MetricsViewModel
import com.geeksville.mesh.model.TimeFrame
import com.geeksville.mesh.ui.common.components.BatteryInfo
import com.geeksville.mesh.ui.common.components.OptionLabel
import com.geeksville.mesh.ui.common.components.SlidingSelector
import com.geeksville.mesh.ui.common.theme.Orange
import com.geeksville.mesh.ui.metrics.CommonCharts.DATE_TIME_FORMAT
import com.geeksville.mesh.ui.metrics.CommonCharts.MAX_PERCENT_VALUE
import com.geeksville.mesh.ui.metrics.CommonCharts.MS_PER_SEC
import com.geeksville.mesh.util.GraphUtil
import com.geeksville.mesh.util.GraphUtil.createPath
import com.geeksville.mesh.util.GraphUtil.plotPoint

private enum class Device(val color: Color) {
    BATTERY(Color.Green),
    CH_UTIL(Color.Magenta),
    AIR_UTIL(Color.Cyan)
}

private val LEGEND_DATA = listOf(
    LegendData(nameRes = R.string.battery, color = Device.BATTERY.color, isLine = true),
    LegendData(nameRes = R.string.channel_utilization, color = Device.CH_UTIL.color),
    LegendData(nameRes = R.string.air_utilization, color = Device.AIR_UTIL.color),
)

@Composable
fun DeviceMetricsScreen(
    viewModel: MetricsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var displayInfoDialog by remember { mutableStateOf(false) }
    val selectedTimeFrame by viewModel.timeFrame.collectAsState()
    val data = state.deviceMetricsFiltered(selectedTimeFrame)

    Column {

        if (displayInfoDialog) {
            LegendInfoDialog(
                pairedRes = listOf(
                    Pair(R.string.channel_utilization, R.string.ch_util_definition),
                    Pair(R.string.air_utilization, R.string.air_util_definition)
                ),
                onDismiss = { displayInfoDialog = false }
            )
        }

        DeviceMetricsChart(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction = 0.33f),
            data.reversed(),
            selectedTimeFrame,
            promptInfoDialog = { displayInfoDialog = true }
        )

        SlidingSelector(
            TimeFrame.entries.toList(),
            selectedTimeFrame,
            onOptionSelected = { viewModel.setTimeFrame(it) }
        ) {
            OptionLabel(stringResource(it.strRes))
        }

        /* Device Metric Cards */
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(data) { telemetry -> DeviceMetricsCard(telemetry) }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun DeviceMetricsChart(
    modifier: Modifier = Modifier,
    telemetries: List<Telemetry>,
    selectedTime: TimeFrame,
    promptInfoDialog: () -> Unit
) {

    ChartHeader(amount = telemetries.size)
    if (telemetries.isEmpty()) return

    val (oldest, newest) = remember(key1 = telemetries) {
        Pair(
            telemetries.minBy { it.time },
            telemetries.maxBy { it.time }
        )
    }
    val timeDiff = newest.time - oldest.time

    TimeLabels(
        oldest = oldest.time,
        newest = newest.time
    )

    Spacer(modifier = Modifier.height(16.dp))

    val graphColor = MaterialTheme.colorScheme.onSurface

    val scrollState = rememberScrollState()
    val screenWidth = LocalWindowInfo.current.containerSize.width
    val dp by remember(key1 = selectedTime) {
        mutableStateOf(selectedTime.dp(screenWidth, time = timeDiff.toLong()))
    }

    Row {
        Box(
            contentAlignment = Alignment.TopStart,
            modifier = Modifier
                .horizontalScroll(state = scrollState, reverseScrolling = true)
                .weight(weight = 1f)
        ) {

            /*
             * The order of the colors are with respect to the ChUtil.
             * 25 - 49  Orange
             * 50 - 100 Red
             */
            HorizontalLinesOverlay(
                modifier.width(dp),
                lineColors = listOf(graphColor, Orange, Color.Red, graphColor, graphColor),
            )

            TimeAxisOverlay(
                modifier.width(dp),
                oldest = oldest.time,
                newest = newest.time,
                selectedTime.lineInterval()
            )

            /* Plot Battery Line, ChUtil, and AirUtilTx */
            Canvas(modifier = modifier.width(dp)) {

                val height = size.height
                val width = size.width
                for (i in telemetries.indices) {
                    val telemetry = telemetries[i]

                    /* x-value time */
                    val xRatio = (telemetry.time - oldest.time).toFloat() / timeDiff
                    val x = xRatio * width

                    /* Channel Utilization */
                    plotPoint(
                        drawContext = drawContext,
                        color = Device.CH_UTIL.color,
                        x = x,
                        value = telemetry.deviceMetrics.channelUtilization,
                        divisor = MAX_PERCENT_VALUE
                    )

                    /* Air Utilization Transmit */
                    plotPoint(
                        drawContext = drawContext,
                        color = Device.AIR_UTIL.color,
                        x = x,
                        value = telemetry.deviceMetrics.airUtilTx,
                        divisor = MAX_PERCENT_VALUE
                    )
                }

                /* Battery Line */
                var index = 0
                while (index < telemetries.size) {
                    val path = Path()
                    index = createPath(
                        telemetries = telemetries,
                        index = index,
                        path = path,
                        oldestTime = oldest.time,
                        timeRange = timeDiff,
                        width = width,
                        timeThreshold = selectedTime.timeThreshold()
                    ) { i ->
                        val telemetry = telemetries.getOrNull(i) ?: telemetries.last()
                        val ratio = telemetry.deviceMetrics.batteryLevel / MAX_PERCENT_VALUE
                        val y = height - (ratio * height)
                        return@createPath y
                    }
                    drawPath(
                        path = path,
                        color = Device.BATTERY.color,
                        style = Stroke(
                            width = GraphUtil.RADIUS,
                            cap = StrokeCap.Round
                        )
                    )
                }
            }
        }
        YAxisLabels(
            modifier = modifier.weight(weight = .1f),
            graphColor,
            minValue = 0f,
            maxValue = 100f
        )
    }
    Spacer(modifier = Modifier.height(16.dp))

    Legend(legendData = LEGEND_DATA, promptInfoDialog = promptInfoDialog)

    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun DeviceMetricsCard(telemetry: Telemetry) {
    val deviceMetrics = telemetry.deviceMetrics
    val time = telemetry.time * MS_PER_SEC
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Surface {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    /* Time, Battery, and Voltage */
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = DATE_TIME_FORMAT.format(time),
                            style = TextStyle(fontWeight = FontWeight.Bold),
                            fontSize = MaterialTheme.typography.labelLarge.fontSize
                        )

                        BatteryInfo(
                            batteryLevel = deviceMetrics.batteryLevel,
                            voltage = deviceMetrics.voltage
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    /* Channel Utilization and Air Utilization Tx */
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val text = stringResource(R.string.channel_air_util).format(
                            deviceMetrics.channelUtilization,
                            deviceMetrics.airUtilTx
                        )
                        Text(
                            text = text,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.labelLarge.fontSize
                        )
                    }
                }
            }
        }
    }
}
