package com.example.forex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forex.ui.components.MetricColumn
import com.example.forex.ui.viewmodel.ForexViewModel
import java.util.Locale

@Composable
fun RiskCalculatorScreen(
    viewModel: ForexViewModel,
    modifier: Modifier = Modifier
) {
    var balanceText by remember { mutableStateOf("10000") }
    var riskPercentText by remember { mutableStateOf("1.0") }
    var stopLossPipsText by remember { mutableStateOf("25") }
    var pipValueText by remember { mutableStateOf("10") } // $10 per pip for 1 standard lot on EUR/USD

    val balance = balanceText.toDoubleOrNull() ?: 10000.0
    val riskPercent = riskPercentText.toDoubleOrNull() ?: 1.0
    val stopLossPips = stopLossPipsText.toDoubleOrNull() ?: 25.0
    val pipValuePerLot = pipValueText.toDoubleOrNull() ?: 10.0

    // Calculations
    val totalRiskDollar = balance * (riskPercent / 100.0)
    val standardLots = if (stopLossPips > 0 && pipValuePerLot > 0) totalRiskDollar / (stopLossPips * pipValuePerLot) else 0.0
    val miniLots = standardLots * 10
    val microLots = standardLots * 100

    val tp1Reward = totalRiskDollar * 1.5
    val tp2Reward = totalRiskDollar * 3.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Calculate,
                contentDescription = null,
                tint = Color(0xFF10B981),
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "Position Size & Risk Calculator",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "Calculate exact lot sizes and risk exposure to prevent over-leveraging your trading account.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp
        )

        // Inputs Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("ACCOUNT & TRADE PARAMETERS", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = balanceText,
                        onValueChange = { balanceText = it },
                        label = { Text("Account Balance ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_balance")
                    )

                    OutlinedTextField(
                        value = riskPercentText,
                        onValueChange = { riskPercentText = it },
                        label = { Text("Risk Per Trade (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_risk_percent")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = stopLossPipsText,
                        onValueChange = { stopLossPipsText = it },
                        label = { Text("Stop Loss (Pips)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_stop_loss_pips")
                    )

                    OutlinedTextField(
                        value = pipValueText,
                        onValueChange = { pipValueText = it },
                        label = { Text("Pip Value ($/Lot)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Calculation Results Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("RECOMMENDED POSITION SIZE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF10B981))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    MetricColumn("STANDARD LOTS", String.format(Locale.US, "%.2f", standardLots), Color(0xFF38BDF8))
                    MetricColumn("MINI LOTS", String.format(Locale.US, "%.1f", miniLots), Color(0xFFA855F7))
                    MetricColumn("MICRO LOTS", String.format(Locale.US, "%.0f", microLots), Color(0xFFF59E0B))
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("MAX ACCOUNT RISK", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("$${String.format(Locale.US, "%.2f", totalRiskDollar)}", color = Color(0xFFEF4444), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("ESTIMATED REWARD (1:1.5 / 1:3)", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("+$${String.format(Locale.US, "%.2f", tp1Reward)} / +$${String.format(Locale.US, "%.2f", tp2Reward)}", color = Color(0xFF10B981), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
