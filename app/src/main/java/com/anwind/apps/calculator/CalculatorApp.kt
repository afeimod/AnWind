package com.anwind.apps.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope

val CalculatorApp = AppDef(
    id = "calculator",
    displayName = "计算器",
    iconAsset = "emoji:🧮",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 320.dp,
    defaultHeight = 440.dp,
    pinnedToDesktop = true
) { scope ->
    CalculatorContent(scope)
}

@Composable
private fun CalculatorContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    var display by remember { mutableStateOf("0") }
    var expression by remember { mutableStateOf("") }
    var operand1 by remember { mutableStateOf<Double?>(null) }
    var pendingOp by remember { mutableStateOf<String?>(null) }
    var justEvaluated by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
            .padding(12.dp)
    ) {
        // 显示区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(theme.buttonBackgroundColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = expression,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp,
                textAlign = TextAlign.End
            )
            Text(
                text = display,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End
            )
        }
        Spacer(Modifier.height(8.dp))

        // 按钮网格
        val buttons = listOf(
            listOf("C", "±", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "⌫", "=")
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { label ->
                        CalcButton(
                            label = label,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when (label) {
                                    "C" -> {
                                        display = "0"; expression = ""
                                        operand1 = null; pendingOp = null
                                    }
                                    "±" -> display = if (display.startsWith("-")) display.removePrefix("-") else "-$display"
                                    "%" -> display = (display.toDoubleOrNull()?.div(100) ?: 0.0).toString()
                                    "⌫" -> display = if (display.length > 1) display.dropLast(1) else "0"
                                    "+", "−", "×", "÷" -> {
                                        operand1 = display.toDoubleOrNull()
                                        pendingOp = label
                                        expression = "$display $label"
                                        justEvaluated = false
                                    }
                                    "=" -> {
                                        val op2 = display.toDoubleOrNull()
                                        val result = if (operand1 != null && op2 != null && pendingOp != null) {
                                            when (pendingOp) {
                                                "+" -> operand1!! + op2
                                                "−" -> operand1!! - op2
                                                "×" -> operand1!! * op2
                                                "÷" -> if (op2 != 0.0) operand1!! / op2 else Double.NaN
                                                else -> op2
                                            }
                                        } else op2 ?: 0.0
                                        display = formatResult(result)
                                        expression = "$expression = $display"
                                        operand1 = null
                                        pendingOp = null
                                        justEvaluated = true
                                    }
                                    "." -> {
                                        if (justEvaluated) { display = "0."; justEvaluated = false }
                                        else if (!display.contains(".")) display += "."
                                    }
                                    else -> {
                                        if (display == "0" || justEvaluated) {
                                            display = label
                                            justEvaluated = false
                                        } else {
                                            display += label
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalcButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    val isOp = label in listOf("+", "−", "×", "÷", "=")
    val isFunc = label in listOf("C", "±", "%", "⌫")
    val bg = when {
        isOp -> theme.accentColor
        isFunc -> theme.buttonBackgroundColor.copy(alpha = 0.6f)
        else -> theme.buttonBackgroundColor
    }
    val fg = if (isOp) Color.White else (if (theme.isDark) Color.White else Color.Black)

    Box(
        modifier = modifier
            .height(56.dp)
            .background(bg, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 18.sp,
            fontWeight = if (isOp) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private fun formatResult(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "Error"
    return if (d == d.toLong().toDouble()) d.toLong().toString()
           else d.toString()
}
