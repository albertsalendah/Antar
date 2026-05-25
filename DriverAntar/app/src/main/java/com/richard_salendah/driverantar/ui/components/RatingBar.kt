package com.richard_salendah.driverantar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val StarColor = Color(0xFFFFC107) // amber

/**
 * Five-star graphic with fractional fill support.
 *
 * e.g. rating=4.7 renders: ★ ★ ★ ★ ★½  (well, 4 full + 1 half)
 *
 * @param rating      0.0–5.0
 * @param ratingCount shown in parentheses after the stars; pass 0 to hide
 * @param starSize    size of each star icon
 * @param showCount   whether to show the numeric score + count label
 */
@Composable
fun RatingBar(
    rating: Double,
    ratingCount: Int = 0,
    starSize: Dp = 16.dp,
    showCount: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier            = modifier,
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ── 5 stars ───────────────────────────────────────────────────────────
        repeat(5) { index ->
            val threshold = index + 1
            val icon = when {
                rating >= threshold        -> Icons.Filled.Star        // full
                rating >= threshold - 0.5  -> Icons.Filled.StarHalf    // half
                else                       -> Icons.Outlined.StarOutline // empty
            }
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = StarColor,
                modifier           = Modifier.size(starSize)
            )
        }

        // ── Numeric label e.g. "4.7 (23)" ────────────────────────────────────
        if (showCount && ratingCount > 0) {
            Text(
                text  = "%.1f (%d)".format(rating, ratingCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Compact single-line chip for tight spaces (map top bar, list rows).
 * Shows: ★ 4.7 (23)
 */
@Composable
fun RatingChip(
    rating: Double,
    ratingCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier              = modifier,
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector        = Icons.Filled.Star,
            contentDescription = null,
            tint               = StarColor,
            modifier           = Modifier.size(12.dp)
        )
        Text(
            text  = "%.1f (%d)".format(rating, ratingCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}