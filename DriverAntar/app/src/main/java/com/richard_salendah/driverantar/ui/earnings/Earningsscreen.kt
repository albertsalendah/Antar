package com.richard_salendah.driverantar.ui.earnings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.richard_salendah.driverantar.data.model.DailyEarning
import com.richard_salendah.driverantar.data.model.EarningsSummary
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

private val AmberColor = Color(0xFFFFC107)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarningsScreen(
    viewModel: EarningsViewModel,
    onBack: () -> Unit
) {
    val summary   = viewModel.summary
    val daily     = viewModel.dailyEarnings
    val isLoading = viewModel.isLoading
    val error     = viewModel.errorMessage

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("Pendapatan Saya") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh    = { viewModel.load() },
            modifier     = Modifier.padding(padding).fillMaxSize()
        ) {
            when {
                isLoading && summary == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null && summary == null -> {
                    Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Gagal memuat pendapatan",
                                style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(error,
                                style     = MaterialTheme.typography.bodySmall,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { viewModel.load() }) { Text("Coba Lagi") }
                        }
                    }
                }
                summary != null -> {
                    EarningsContent(
                        summary  = summary,
                        daily    = daily,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// ── Content ───────────────────────────────────────────────────────────────────

@Composable
private fun EarningsContent(
    summary: EarningsSummary,
    daily: List<DailyEarning>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier            = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RatingCard(avgRating = summary.avg_rating, ratingCount = summary.rating_count)

        Text(
            "Ringkasan Pendapatan",
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // ── 7-day bar chart ───────────────────────────────────────────────────
        if (daily.isNotEmpty()) {
            DailyChartCard(daily = daily)
        }

        // ── Period summary cards ──────────────────────────────────────────────
        EarningsPeriodCard(
            label     = "Hari Ini",
            total     = summary.today_total,
            tripCount = summary.today_trips,
            highlight = true
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EarningsPeriodCard(
                label     = "Minggu Ini",
                total     = summary.week_total,
                tripCount = summary.week_trips,
                modifier  = Modifier.weight(1f)
            )
            EarningsPeriodCard(
                label     = "Bulan Ini",
                total     = summary.month_total,
                tripCount = summary.month_trips,
                modifier  = Modifier.weight(1f)
            )
        }

        HorizontalDivider()
        AllTimeCard(total = summary.all_time_total, tripCount = summary.all_time_trips)
        Spacer(Modifier.height(32.dp))
    }
}

// ── Daily chart card ──────────────────────────────────────────────────────────

@Composable
private fun DailyChartCard(daily: List<DailyEarning>) {
    val maxTotal  = daily.maxOf { it.total }.coerceAtLeast(1.0)
    val bestIdx   = daily.indexOfFirst { it.total > 0 && it.total == maxTotal }
    val bestDay   = daily.getOrNull(bestIdx)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "7 Hari Terakhir",
                style      = MaterialTheme.typography.labelLarge,
                color      = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            DailyBarChart(
                data     = daily,
                maxTotal = maxTotal,
                bestIdx  = bestIdx,
                modifier = Modifier.fillMaxWidth().height(180.dp)
            )

            // Best day callout
            if (bestDay != null && bestDay.total > 0) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint     = AmberColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Terbaik minggu ini: ${dayFullName(bestDay.date)}  •  ${formatRupiah(bestDay.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Bar chart (Canvas) ────────────────────────────────────────────────────────

@Composable
private fun DailyBarChart(
    data: List<DailyEarning>,
    maxTotal: Double,
    bestIdx: Int,
    modifier: Modifier = Modifier
) {
    val primaryColor  = MaterialTheme.colorScheme.primary
    val surfaceVar    = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle    = MaterialTheme.typography.labelSmall.copy(color = surfaceVar)
    val amountStyle   = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        color      = surfaceVar
    )
    val textMeasurer  = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val w        = size.width
        val h        = size.height
        val labelH   = 28.dp.toPx()
        val amountH  = 22.dp.toPx()
        val chartH   = h - labelH - amountH
        val slotW    = w / data.size
        val barPad   = slotW * 0.22f
        val barW     = slotW - barPad * 2
        val cornerR  = CornerRadius(4.dp.toPx())
        val minBarH  = 4.dp.toPx()

        data.forEachIndexed { i, day ->
            val fraction = (day.total / maxTotal).toFloat()
            val barH     = if (day.total > 0) (chartH * fraction).coerceAtLeast(minBarH) else 0f
            val left     = i * slotW + barPad
            val barTop   = amountH + chartH - barH

            // Bar
            if (barH > 0) {
                drawRoundRect(
                    color        = if (i == bestIdx) AmberColor else primaryColor,
                    topLeft      = Offset(left, barTop),
                    size         = Size(barW, barH),
                    cornerRadius = cornerR
                )
            } else {
                // Zero line for empty days
                drawLine(
                    color       = surfaceVar.copy(alpha = 0.2f),
                    start       = Offset(left, amountH + chartH - 2.dp.toPx()),
                    end         = Offset(left + barW, amountH + chartH - 2.dp.toPx()),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Amount label above bar (only when > 0)
            if (day.total > 0) {
                val amtText   = formatKShort(day.total)
                val amtLayout = textMeasurer.measure(amtText, amountStyle)
                val amtX      = left + barW / 2f - amtLayout.size.width / 2f
                val amtY      = barTop - amtLayout.size.height - 2.dp.toPx()
                drawText(amtLayout, topLeft = Offset(amtX, amtY.coerceAtLeast(0f)))
            }

            // Day label below bar
            val dayText   = dayAbbrev(day.date)
            val dayLayout = textMeasurer.measure(dayText, labelStyle)
            val dayX      = left + barW / 2f - dayLayout.size.width / 2f
            val dayY      = h - labelH + 6.dp.toPx()
            drawText(dayLayout, topLeft = Offset(dayX, dayY))
        }
    }
}

// ── Existing card composables (unchanged) ─────────────────────────────────────

@Composable
private fun RatingCard(avgRating: Double?, ratingCount: Int) {
    Card(
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Rating Rata-rata",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(4.dp))
                if (avgRating != null && ratingCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null,
                            tint     = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("%.1f".format(avgRating),
                            style      = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("dari $ratingCount penilaian",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Text("Belum ada penilaian",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (avgRating != null) {
                Column(horizontalAlignment = Alignment.End) {
                    repeat(5) { i ->
                        Icon(Icons.Filled.Star, null,
                            tint     = if (i < avgRating.roundToInt())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EarningsPeriodCard(
    label: String,
    total: Double,
    tripCount: Int,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val containerColor = if (highlight) MaterialTheme.colorScheme.secondaryContainer
    else           MaterialTheme.colorScheme.surfaceVariant
    val contentColor   = if (highlight) MaterialTheme.colorScheme.onSecondaryContainer
    else           MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor)
            Text(formatRupiah(total),
                style      = if (highlight) MaterialTheme.typography.headlineSmall
                else           MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = if (highlight) MaterialTheme.colorScheme.secondary
                else           MaterialTheme.colorScheme.onSurface)
            Text("$tripCount trip",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor)
        }
    }
}

@Composable
private fun AllTimeCard(total: Double, tripCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column {
                Text("Total Sepanjang Waktu",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(formatRupiah(total),
                    style      = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 28.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$tripCount",
                    style      = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.primary)
                Text("total trip",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** "Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min" */
private fun dayAbbrev(dateStr: String): String = try {
    val cal = Calendar.getInstance().apply {
        time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)!!
    }
    when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY    -> "Sen"
        Calendar.TUESDAY   -> "Sel"
        Calendar.WEDNESDAY -> "Rab"
        Calendar.THURSDAY  -> "Kam"
        Calendar.FRIDAY    -> "Jum"
        Calendar.SATURDAY  -> "Sab"
        else               -> "Min"
    }
} catch (e: Exception) { "" }

/** "Senin", "Selasa", … for the best-day callout */
private fun dayFullName(dateStr: String): String = try {
    val cal = Calendar.getInstance().apply {
        time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)!!
    }
    when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY    -> "Senin"
        Calendar.TUESDAY   -> "Selasa"
        Calendar.WEDNESDAY -> "Rabu"
        Calendar.THURSDAY  -> "Kamis"
        Calendar.FRIDAY    -> "Jumat"
        Calendar.SATURDAY  -> "Sabtu"
        else               -> "Minggu"
    }
} catch (e: Exception) { "" }

/** 45000 → "45K", 1200000 → "1.2J" — compact labels for bar chart */
private fun formatKShort(amount: Double): String = when {
    amount >= 1_000_000 -> "%.1fJ".format(amount / 1_000_000)
    amount >= 1_000     -> "%.0fK".format(amount / 1_000)
    else                -> "%.0f".format(amount)
}

private fun formatRupiah(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${fmt.format(amount.roundToInt())}"
}