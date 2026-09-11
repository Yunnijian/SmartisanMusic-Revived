package com.smartisan.music.ui.cloud.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * 云音乐通用封面图。
 *
 * 基于 Coil 的 [AsyncImage]，走全局 ImageLoader 的两级缓存，列表滚动时同一张图不重复下载或解码。
 * 无 URL 时展示占位底色，保持布局约束避免跳动。
 */
@Composable
internal fun CloudMusicCoverImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val safeUrl = imageUrl
        ?.takeIf(String::isNotBlank)
        ?.toArtworkRequestUrl()

    if (safeUrl == null) {
        Box(modifier = modifier.background(CloudArtworkPlaceholderColor))
        return
    }

    // 背景占位 + Coil 上层覆盖，加载未完成时透出占位色。
    Box(modifier = modifier.background(CloudArtworkPlaceholderColor)) {
        AsyncImage(
            model = remember(safeUrl) {
                ImageRequest.Builder(context)
                    .data(safeUrl)
                    .size(CloudCoverArtworkSizePx)
                    .build()
            },
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** 强制 https 并追加网易云服务端缩放参数，控制下采样体积。 */
private fun String.toArtworkRequestUrl(): String {
    val normalizedUrl = replaceFirst("http://", "https://")
    val uri = runCatching { Uri.parse(normalizedUrl) }.getOrNull() ?: return normalizedUrl
    if (uri.scheme != "https") {
        return normalizedUrl
    }
    val builder = uri.buildUpon().clearQuery()
    uri.queryParameterNames
        .filterNot { name -> name == "param" }
        .forEach { name ->
            uri.getQueryParameters(name).forEach { value ->
                builder.appendQueryParameter(name, value)
            }
        }
    builder.appendQueryParameter("param", "${CloudCoverArtworkSizePx}y${CloudCoverArtworkSizePx}")
    return builder.build().toString()
}
