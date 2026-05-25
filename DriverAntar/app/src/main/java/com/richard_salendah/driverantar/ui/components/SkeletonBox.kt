package com.richard_salendah.driverantar.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single pulsing skeleton rectangle — the building block for all skeleton layouts.
 */
@Composable
fun SkeletonBox(
    modifier:     Modifier = Modifier,
    cornerRadius: Dp       = 6.dp
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue  = 0.55f,
        animationSpec = infiniteRepeatable(
            animation  = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    )
}

/**
 * Skeleton placeholder for an IncomingTrips card.
 */
@Composable
fun SkeletonTripCard(modifier: Modifier = Modifier) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Badge row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBox(modifier = Modifier.width(72.dp).height(22.dp), cornerRadius = 50.dp)
                SkeletonBox(modifier = Modifier.width(56.dp).height(22.dp), cornerRadius = 50.dp)
                Spacer(Modifier.weight(1f))
                SkeletonBox(modifier = Modifier.width(48.dp).height(16.dp))
            }
            Spacer(Modifier.height(14.dp))
            // Pickup
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.8f).height(14.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.6f).height(14.dp))
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            // Footer
            Row {
                Column {
                    SkeletonBox(modifier = Modifier.width(60.dp).height(12.dp))
                    Spacer(Modifier.height(6.dp))
                    SkeletonBox(modifier = Modifier.width(90.dp).height(20.dp))
                }
                Spacer(Modifier.weight(1f))
                SkeletonBox(modifier = Modifier.width(72.dp).height(36.dp), cornerRadius = 8.dp)
            }
        }
    }
}

/**
 * Skeleton placeholder for a TripHistory card.
 */
@Composable
fun SkeletonHistoryCard(modifier: Modifier = Modifier) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonBox(modifier = Modifier.width(72.dp).height(20.dp), cornerRadius = 50.dp)
                SkeletonBox(modifier = Modifier.width(60.dp).height(20.dp), cornerRadius = 50.dp)
                Spacer(Modifier.weight(1f))
                SkeletonBox(modifier = Modifier.width(80.dp).height(14.dp))
            }
            Spacer(Modifier.height(12.dp))
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.9f).height(14.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.65f).height(12.dp))
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Row {
                SkeletonBox(modifier = Modifier.width(100.dp).height(20.dp))
                Spacer(Modifier.weight(1f))
                SkeletonBox(modifier = Modifier.width(60.dp).height(32.dp), cornerRadius = 8.dp)
            }
        }
    }
}