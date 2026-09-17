package com.smartisan.music.ui.cloud.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.data.playlist.PlaylistCreateResult
import com.smartisan.music.data.playlist.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 歌单「更多」弹层的可见性状态，由列表页持有、[CloudMusicPlaylistActionsOverlay] 消费。 */
internal class CloudPlaylistActionsState {
    var pendingPlaylist by mutableStateOf<OnlinePlaylist?>(null)
    var actionsVisible by mutableStateOf(false)

    fun show(playlist: OnlinePlaylist) {
        pendingPlaylist = playlist
        actionsVisible = true
    }
}

@Composable
internal fun rememberCloudPlaylistActionsState(): CloudPlaylistActionsState {
    return remember { CloudPlaylistActionsState() }
}

/**
 * 歌单「更多」的完整动作集：导入为播放列表。
 *
 * 把整个在线歌单拉成本地播放列表：以歌单名建本地歌单（重名自动加序号），
 * 曲目按在线 mediaId 一次性写入，随后由播放列表页并入在线物料展示与播放。
 */
@Composable
internal fun CloudMusicPlaylistActionsOverlay(
    state: CloudPlaylistActionsState,
    repository: OnlineMusicProviderRepository,
    onImported: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlistRepository = remember(context) { PlaylistRepository.getInstance(context) }

    fun importPlaylist(playlist: OnlinePlaylist) {
        scope.launch {
            val tracks = try {
                repository.playlistTracks(playlist)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                emptyList()
            }
            if (tracks.isEmpty()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.cloud_music_action_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val name = playlistRepository.suggestPlaylistName(playlist.title)
            val result = playlistRepository.createPlaylist(
                name = name,
                initialMediaIds = tracks.map { track -> track.mediaId },
            )
            val messageRes = when (result) {
                is PlaylistCreateResult.Success -> R.string.cloud_music_imported_to_playlist
                else -> R.string.cloud_music_action_failed
            }
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
            if (result is PlaylistCreateResult.Success) {
                onImported()
            }
        }
    }

    val actions = listOf(
        CloudMusicTrackAction(
            label = stringResource(R.string.cloud_music_import_to_playlist),
            destructive = false,
            onClick = {
                val playlist = state.pendingPlaylist
                state.actionsVisible = false
                if (playlist != null) {
                    importPlaylist(playlist)
                }
            },
        ),
    )
    CloudMusicTrackActionsOverlay(
        visible = state.actionsVisible,
        trackTitle = state.pendingPlaylist?.title.orEmpty(),
        actions = actions,
        onDismiss = { state.actionsVisible = false },
        modifier = modifier,
    )
}
