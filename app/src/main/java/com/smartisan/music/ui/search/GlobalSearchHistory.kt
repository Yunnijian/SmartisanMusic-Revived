package com.smartisan.music.ui.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.ui.components.SmartisanDrawableBackground
import com.smartisan.music.ui.components.collectSmartisanPressedAsState

@Composable
internal fun SearchHistoryPage(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        Box(modifier = modifier.fillMaxSize()) {
            SmartisanDrawableBackground(
                drawableRes = R.drawable.account_background,
                modifier = Modifier.matchParentSize(),
            )
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        SmartisanDrawableBackground(
            drawableRes = R.drawable.account_background,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        start = SearchSectionHorizontalPadding,
                        top = SearchHistoryTopPadding,
                        end = 20.dp,
                    )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.search_history),
                    style = SearchSectionTitleStyle,
                    modifier = Modifier.padding(start = 7.dp),
                )
                Image(
                    painter = painterResource(R.drawable.search_clear),
                    contentDescription = stringResource(R.string.clear_history),
                    modifier =
                        Modifier.size(20.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onClearHistory,
                            ),
                )
            }
            FlowRow(
                modifier = Modifier.padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(SearchHistoryRowSpacing),
                verticalArrangement = Arrangement.spacedBy(SearchHistoryRowSpacing),
            ) {
                history.forEach { entry ->
                    SearchHistoryChip(
                        text = entry,
                        onClick = { onHistoryClick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryChip(
    text: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectSmartisanPressedAsState()

    Box(
        modifier =
            Modifier.height(SearchHistoryChipHeight)
                .defaultMinSize(minWidth = 48.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        SmartisanDrawableBackground(
            drawableRes =
                if (pressed) R.drawable.search_badge_grey_p else R.drawable.search_badge_grey,
            modifier = Modifier.matchParentSize(),
        )
        Text(
            text = text,
            style =
                TextStyle(
                    color = colorResource(R.color.text_tertiary),
                    fontSize = 13.5.sp,
                ),
            modifier = Modifier.padding(horizontal = 14.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
