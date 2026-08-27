package com.anwind.apps.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    defaultWidth = 340.dp,
    defaultHeight = 540.dp,
    pinnedToDesktop = true
) { scope ->
    CalculatorContent(scope)
}

/**
 * 计算器 - Win11 风格重构
 *
 * - 顶部显示区：表达式 + 当前数字
 * - 按钮区：4 列 x 6 行
 *   - 数字按钮：浅灰背景
 *   - 运算符按钮：强调色背景
 *   - 功能按钮（C/±/%/⌫）：中灰背景
 * - 圆角按钮、阴影
 */
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
            .padding(8.dp)
    ) {
        // ===== 显示区 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(theme.cardBackgroundColor)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom
        ) {
            // 历史表达式
            Text(
                text = expression,
                color = theme.secondaryTextColor,
                fontSize = 13.sp,
                textAlign = TextAlign.End
            )
            Spacer(Modifier.height(4.dp))
            // 当前数字
            Text(
                text = display,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 42.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End
            )
        }
        Spacer(Modifier.height(10.dp))

        // ===== 按钮网格 =====
        val buttons = listOf(
            listOf("%", "CE", "C", "⌫"),
            listOf("¹⁄ₓ", "x²", "√", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ".", "=")
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
                                    "CE" -> { display = "0" }
                                    "±" -> display = if (display.startsWith("-")) display.removePrefix("-") else "-$display"
                                    "%" -> display = (display.toDoubleOrNull()?.div(100) ?: 0.0).toString()
                                    "⌫" -> display = if (display.length > 1) display.dropLast(1) else "0"
                                    "¹⁄ₓ" -> {
                                        val d = display.toDoubleOrNull() ?: 0.0
                                        display = if (d != 0.0) formatResult(1.0 / d) else "Error"
                                    }
                                    "x²" -> {
                                        val d = display.toDoubleOrNull() ?: 0.0
                                        display = formatResult(d * d)
                                    }
                                    "√" -> {
                                        val d = display.toDoubleOrNull() ?: 0.0
                                        display = if (d >= 0) formatResult(kotlin.math.sqrt(d)) else "Error"
                                    }
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
    val isFunc = label in listOf("C", "CE", "±", "%", "⌫", "¹⁄ₓ", "x²", "√")
    val isEquals = label == "="
    val bg = when {
        isEquals -> theme.accentColor
        isOp -> theme.buttonBackgroundColor
        isFunc -> theme.cardBackgroundColor
        else -> theme.buttonBackgroundColor
    }
    val fg = when {
        isEquals -> Color.White
        isFunc -> theme.accentColor
        else -> if (theme.isDark) Color.White else Color.Black
    }
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
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
