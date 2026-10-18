package com.financeapp.presentation.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.financeapp.R
import com.financeapp.presentation.theme.Spacing
import kotlinx.coroutines.launch

private data class Page(val emoji: String, val title: Int, val body: Int)

private val pages = listOf(
    Page("💸", R.string.onb_1_title, R.string.onb_1_body),
    Page("🔔", R.string.onb_2_title, R.string.onb_2_body),
    Page("📊", R.string.onb_3_title, R.string.onb_3_body)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i -> OnboardingPage(pages[i]) }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.l)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.clearAndSetSemantics { }) {
                    pages.indices.forEach { i ->
                        val selected = pager.currentPage == i
                        val w by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dot")
                        Box(
                            Modifier
                                .height(8.dp)
                                .width(w)
                                .clip(CircleShape)
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
                Button(
                    onClick = {
                        if (last) vm.finish(onFinish)
                        else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text(stringResource(if (last) R.string.action_start else R.string.action_next)) }
                // Keep the layout stable: the skip button's slot exists on every page.
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    if (!last) TextButton(onClick = { vm.finish(onFinish) }) { Text(stringResource(R.string.action_skip)) }
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(p: Page) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(120.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(p.emoji, fontSize = 56.sp, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        Spacer(Modifier.height(Spacing.xxl))
        Text(
            stringResource(p.title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(p.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
