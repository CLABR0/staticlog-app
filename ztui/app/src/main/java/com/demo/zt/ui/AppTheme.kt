package com.demo.zt.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** 原版 FixedTopAppBar 的等价实现。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors()
    )
}

/** 原版 AppSpacing 的等价常量。 */
object AppSpacing {
    val screenBottomPadding = 24.dp
    val screenHorizontal = 16.dp
    val screenHorizontalPadding = 16.dp
    val cardPadding = 16.dp
    val topBarContentSpacing = 8.dp
    val sectionGap = 12.dp
    val itemGap = 8.dp
}

/** 原版 AppShape 的等价形状。 */
object AppShape {
    object shapes {
        val iconSmall = RoundedCornerShape(8.dp)
        val card = RoundedCornerShape(20.dp)
        val cardMedium = RoundedCornerShape(16.dp)
        val cardLarge = RoundedCornerShape(24.dp)
        val buttonSmall14 = RoundedCornerShape(14.dp)
        val buttonSmall = RoundedCornerShape(10.dp)
        val button = RoundedCornerShape(14.dp)
    }
}