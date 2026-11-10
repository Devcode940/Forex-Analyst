package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcode940.ForexAnalyst.data.model.AlertCondition
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.ui.components.AlertItemCard
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel

@Composable
fun AlertsScreen(
    viewModel: ForexViewModel,
    modifier: Modifier = Modifier
) {
    val alertsEntities by viewModel.customAlerts.collectAsState()
    val pairs by viewModel.pairs.collectAsState()
    val selectedPair by viewModel.selectedPair.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }

    val activeAlertsList = remember(alertsEntities) {
        alertsEntities.map { entity ->
            val condition = try { AlertCondition.valueOf(entity.conditionType) } catch (e: Exception) { AlertCondition.PRICE_ABOVE }
            CustomAlert(
                id = entity.id,
                pairSymbol = entity.pairSymbol,
                condition = condition,
                targetValue = entity.targetValue,
                isTriggered = entity.isTriggered,
                isActive = entity.isActive,
                note = entity.note,
                createdAt = entity.createdAt
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Price & Technical Alerts",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = { showCreateDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("add_alert_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Alert", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (activeAlertsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No custom alerts configured", color = Color.Gray, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Tap 'New Alert' to set price triggers for EUR/USD, GBP/USD, XAU/USD, etc.", color = Color(0xFF64748B), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(activeAlertsList, key = { it.id }) { alert ->
                    AlertItemCard(
                        alert = alert,
                        onToggleActive = { active -> viewModel.toggleAlert(alert.id, active) },
                        onDelete = { viewModel.deleteAlert(alert.id) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateAlertDialog(
            pairSymbol = selectedPair.symbol,
            currentPrice = selectedPair.currentPrice,
            onDismiss = { showCreateDialog = false },
            onConfirm = { symbol, condition, price, note ->
                viewModel.createAlert(symbol, condition, price, note)
                showCreateDialog = false
            }
        )
    }
}

@Composable
fun CreateAlertDialog(
    pairSymbol: String,
    currentPrice: Double,
    onDismiss: () -> Unit,
    onConfirm: (String, AlertCondition, Double, String) -> Unit
) {
    var targetPriceText by remember { mutableStateOf(currentPrice.toString()) }
    var selectedCondition by remember { mutableStateOf(AlertCondition.PRICE_ABOVE) }
    var noteText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        title = {
            Text("Create Alert for $pairSymbol", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Current Price: $currentPrice", color = Color(0xFF94A3B8), fontSize = 12.sp)

                Text("Condition:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Column {
                    AlertCondition.values().forEach { cond ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = cond == selectedCondition,
                                onClick = { selectedCondition = cond },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8))
                            )
                            Text(cond.title, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = targetPriceText,
                    onValueChange = { targetPriceText = it },
                    label = { Text("Target Price / Level") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("alert_target_input")
                )

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note / Strategy Label") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val price = targetPriceText.toDoubleOrNull() ?: currentPrice
                    onConfirm(pairSymbol, selectedCondition, price, noteText)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                modifier = Modifier.testTag("confirm_create_alert")
            ) {
                Text("Set Alert", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}
