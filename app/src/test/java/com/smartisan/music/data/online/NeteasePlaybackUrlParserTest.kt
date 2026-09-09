package com.smartisan.music.data.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlaybackUrlParserTest {

    @Test
    fun previewDurationIsRejectedWhenOriginalSongIsMuchLonger() {
        assertTrue(
            isNeteasePreviewDuration(
                returnedDurationMs = 30_000L,
                originalDurationMs = 210_000L,
            ),
        )
    }

    @Test
    fun fullDurationIsAccepted() {
        assertFalse(
            isNeteasePreviewDuration(
                returnedDurationMs = 185_040L,
                originalDurationMs = 185_040L,
            ),
        )
    }

    @Test
    fun missingReturnedDurationDoesNotRejectPlayableUrl() {
        assertFalse(
            isNeteasePreviewDuration(
                returnedDurationMs = null,
                originalDurationMs = 185_040L,
            ),
        )
    }

    @Test
    fun shortOriginalSongDoesNotLookLikePreview() {
        assertFalse(
            isNeteasePreviewDuration(
                returnedDurationMs = 30_000L,
                originalDurationMs = 45_000L,
            ),
        )
    }

    @Test
    fun playbackUrlResponseAcceptsFullUrlFromArrayData() {
        val result = parseNeteasePlaybackUrlResponse(
            response = """
            {
              "code": 200,
              "data": [
                {
                  "id": 123,
                  "url": "http://m801.music.126.net/song.flac",
                  "type": "flac",
                  "time": 210000
                }
              ]
            }
            """.trimIndent(),
            originalDurationMs = 210_000L,
        )

        assertEquals(NeteasePlaybackParseStatus.Success, result.status)
        assertEquals("https://m801.music.126.net/song.flac", result.playbackUrl?.url)
        assertEquals("audio/flac", result.playbackUrl?.mimeType)
    }

    @Test
    fun playbackUrlResponseRejectsExplicitPreviewClip() {
        val result = parseNeteasePlaybackUrlResponse(
            response = """
            {
              "code": 200,
              "data": [
                {
                  "id": 123,
                  "url": "https://m801.music.126.net/preview.mp3",
                  "type": "mp3",
                  "time": 30000,
                  "freeTrialInfo": {"start": 0, "end": 30000}
                }
              ]
            }
            """.trimIndent(),
            originalDurationMs = 210_000L,
        )

        assertEquals(NeteasePlaybackParseStatus.Preview, result.status)
        assertNull(result.playbackUrl)
    }

    @Test
    fun playbackUrlResponseTreatsNoPermissionAsLoginRequired() {
        val result = parseNeteasePlaybackUrlResponse(
            response = """
            {
              "code": 200,
              "data": {
                "id": 123,
                "url": null,
                "code": 404,
                "fee": 1,
                "freeTrialPrivilege": {"cannotListenReason": 1}
              }
            }
            """.trimIndent(),
            originalDurationMs = 210_000L,
        )

        assertEquals(NeteasePlaybackParseStatus.RequiresLogin, result.status)
        assertNull(result.playbackUrl)
    }

    @Test
    fun unresolvedOnlineMediaItemNeedsPlaybackUrlRefresh() {
        assertTrue(
            shouldRefreshOnlinePlaybackUrlState(
                isOnline = true,
                hasPlaybackUrl = false,
                resolvedAtMs = 0L,
            ),
        )
    }

    @Test
    fun resolvedOnlineMediaItemRefreshesAfterUrlBecomesStale() {
        val resolvedAtMs = System.currentTimeMillis()

        assertFalse(
            shouldRefreshOnlinePlaybackUrlState(
                isOnline = true,
                hasPlaybackUrl = true,
                resolvedAtMs = resolvedAtMs,
                nowMs = resolvedAtMs,
            ),
        )
        assertTrue(
            shouldRefreshOnlinePlaybackUrlState(
                isOnline = true,
                hasPlaybackUrl = true,
                resolvedAtMs = resolvedAtMs,
                nowMs = resolvedAtMs + 16 * 60 * 1000L,
            ),
        )
    }

    @Test
    fun accountProfileResponseParsesNicknameAndUserId() {
        val profile = parseNeteaseAccountProfileResponse(
            """
            {
              "code": 200,
              "profile": {
                "userId": 42,
                "nickname": "Smartisan User",
                "avatarUrl": "https://p1.music.126.net/avatar.jpg"
              }
            }
            """.trimIndent(),
        )

        assertEquals(42L, profile?.userId)
        assertEquals("Smartisan User", profile?.nickname)
        assertEquals("https://p1.music.126.net/avatar.jpg", profile?.avatarUrl)
    }

    @Test
    fun loggedOutAccountProfileResponseIsEmpty() {
        assertNull(
            parseNeteaseAccountProfileResponse(
                """{"code":200,"account":null,"profile":null}""",
            ),
        )
    }

    @Test
    fun accountActionResponseAcceptsSuccessCode() {
        val result = parseNeteaseAccountActionResponse("""{"code":200}""")

        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertEquals(200, result.code)
    }

    @Test
    fun accountActionResponseTreats301AsLoginRequired() {
        val result = parseNeteaseAccountActionResponse("""{"code":301,"msg":"need login"}""")

        assertEquals(NeteaseAccountActionStatus.RequiresLogin, result.status)
        assertEquals(301, result.code)
    }

    @Test
    fun accountActionResponseRejectsUnexpectedCode() {
        val result = parseNeteaseAccountActionResponse("""{"code":400}""")

        assertEquals(NeteaseAccountActionStatus.Failed, result.status)
        assertEquals(400, result.code)
    }

    @Test
    fun likedTrackIdsResponseParsesTopLevelIds() {
        val result = parseNeteaseLikedTrackIdsResponse("""{"code":200,"ids":[10,20,0]}""")

        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertEquals(setOf("10", "20"), result.trackIds)
    }

    @Test
    fun likedTrackIdsResponseParsesNestedDataIds() {
        val result = parseNeteaseLikedTrackIdsResponse("""{"code":200,"data":{"ids":[30,40]}}""")

        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertEquals(setOf("30", "40"), result.trackIds)
    }

    @Test
    fun likedTrackIdsResponseTreats301AsLoginRequired() {
        val result = parseNeteaseLikedTrackIdsResponse("""{"code":301}""")

        assertEquals(NeteaseAccountActionStatus.RequiresLogin, result.status)
        assertTrue(result.trackIds.isEmpty())
    }

    @Test
    fun likedTrackIdsResolverPrefersNonEmptyDirectResult() {
        val resolved = resolveNeteaseLikedTrackIds(
            directResult = NeteaseLikedTrackIdsResult(
                status = NeteaseAccountActionStatus.Success,
                trackIds = setOf("10", "20"),
            ),
            playlistTrackIds = setOf("30", "40"),
        )

        assertEquals(setOf("10", "20"), resolved)
    }

    @Test
    fun likedTrackIdsResolverFallsBackWhenDirectResultIsEmpty() {
        val resolved = resolveNeteaseLikedTrackIds(
            directResult = NeteaseLikedTrackIdsResult(
                status = NeteaseAccountActionStatus.Success,
                trackIds = emptySet(),
            ),
            playlistTrackIds = setOf("30", "40"),
        )

        assertEquals(setOf("30", "40"), resolved)
    }

    @Test
    fun likedTrackIdsResolverFallsBackWhenDirectResultFails() {
        val resolved = resolveNeteaseLikedTrackIds(
            directResult = NeteaseLikedTrackIdsResult(
                status = NeteaseAccountActionStatus.Failed,
                trackIds = emptySet(),
            ),
            playlistTrackIds = setOf("50"),
        )

        assertEquals(setOf("50"), resolved)
    }

    @Test
    fun likedTrackIdsResolverReturnsNullWhenAllSourcesFail() {
        val resolved = resolveNeteaseLikedTrackIds(
            directResult = NeteaseLikedTrackIdsResult(
                status = NeteaseAccountActionStatus.Failed,
                trackIds = emptySet(),
            ),
            playlistTrackIds = null,
        )

        assertNull(resolved)
    }

    @Test
    fun likedTrackIdsResolverKeepsSuccessfulEmptyDirectResult() {
        val resolved = resolveNeteaseLikedTrackIds(
            directResult = NeteaseLikedTrackIdsResult(
                status = NeteaseAccountActionStatus.Success,
                trackIds = emptySet(),
            ),
            playlistTrackIds = null,
        )

        assertEquals(emptySet<String>(), resolved)
    }

    @Test
    fun effectiveCookiesDropSessionLoginWhenPersistedLoginIsGone() {
        val cookies = buildNeteaseEffectiveCookies(
            persistedCookies = emptyMap(),
            sessionCookies = mapOf(
                "MUSIC_U" to "stale-login",
                "__csrf" to "csrf-token",
            ),
        )

        assertFalse(cookies.containsKey("MUSIC_U"))
        assertEquals("csrf-token", cookies["__csrf"])
    }

    @Test
    fun effectiveCookiesAllowSessionLoginRefreshWhenPersistedLoginExists() {
        val cookies = buildNeteaseEffectiveCookies(
            persistedCookies = mapOf("MUSIC_U" to "persisted-login"),
            sessionCookies = mapOf(
                "MUSIC_U" to "refreshed-login",
                "__csrf" to "csrf-token",
            ),
        )

        assertEquals("refreshed-login", cookies["MUSIC_U"])
        assertEquals("csrf-token", cookies["__csrf"])
    }

    @Test
    fun playlistTrackIdsAreTrimmedAndDeduplicated() {
        val trackIds = normalizeNeteasePlaylistTrackIds(
            listOf(" 10 ", "", "20", "10", "abc"),
        )

        assertEquals(listOf("10", "20", "abc"), trackIds)
    }

    @Test
    fun playlistTrackIdsJsonKeepsNumericIdsAsNumbers() {
        val trackIdsJson = buildNeteasePlaylistTrackIdsJson(
            listOf(" 10 ", "abc", "10", "20"),
        )

        assertEquals("""[10,"abc",20]""", trackIdsJson)
    }

    @Test
    fun playlistDeleteIdsJsonKeepsNumericIdsAsNumbers() {
        val playlistIdsJson = buildNeteasePlaylistIdsJson(
            listOf(" 123 ", "456", "123"),
        )

        assertEquals("[123,456]", playlistIdsJson)
    }

    @Test
    fun dailyRecommendedTracksResponseParsesDailySongs() {
        val result = parseNeteaseDailyRecommendedTracksResponse(
            """
            {
              "code": 200,
              "data": {
                "dailySongs": [
                  {
                    "id": 101,
                    "name": "Daily One",
                    "dt": 210000,
                    "ar": [{"name": "Artist A"}],
                    "al": {"name": "Album A", "picUrl": "http://p1.music.126.net/a.jpg"}
                  },
                  {
                    "id": 102,
                    "name": "Daily Two",
                    "dt": 180000,
                    "ar": [{"name": "Artist B"}],
                    "al": {"name": "Album B"}
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertEquals(listOf("101", "102"), result.tracks.map(OnlineTrack::trackId))
        assertEquals("Daily One", result.tracks.first().title)
        assertEquals("Artist A", result.tracks.first().artist)
    }

    @Test
    fun dailyRecommendedTracksResponseTreats301AsLoginRequired() {
        val result = parseNeteaseDailyRecommendedTracksResponse("""{"code":301}""")

        assertEquals(NeteaseAccountActionStatus.RequiresLogin, result.status)
        assertTrue(result.tracks.isEmpty())
    }

    @Test
    fun accountAlbumsResponseParsesNeriPlayerNestedResourceList() {
        val albums = parseNeteaseAccountAlbumsResponse(
            """
            {
              "code": 200,
              "data": {
                "mainCollectInfo": {
                  "mineAllTabDto": {
                    "dataList": [
                      {
                        "dataInfo": {
                          "picUrl": "http://p1.music.126.net/cover.jpg",
                          "data": {
                            "id": 321,
                            "name": "Collected Album",
                            "size": 12,
                            "artist": {"name": "Album Artist"},
                            "publishTime": 1700000000000
                          }
                        }
                      }
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, albums.size)
        assertEquals("321", albums.single().albumId)
        assertEquals("Collected Album", albums.single().title)
        assertEquals("Album Artist", albums.single().artist)
        assertEquals("https://p1.music.126.net/cover.jpg", albums.single().artworkUrl)
        assertEquals(12, albums.single().trackCount)
        assertEquals(1_700_000_000_000L, albums.single().publishTimeMs)
    }

    @Test
    fun accountAlbumsResponseRejectsNonSuccessCode() {
        val albums = parseNeteaseAccountAlbumsResponse("""{"code":301}""")

        assertTrue(albums.isEmpty())
    }

    @Test
    fun accountRadiosResponseParsesSubscribedRadios() {
        val radios = parseNeteaseAccountRadiosResponse(
            """
            {
              "code": 200,
              "djRadios": [
                {
                  "id": 700,
                  "name": "Subscribed Podcast",
                  "category": "音乐播客",
                  "picUrl": "https://p1.music.126.net/radio.jpg",
                  "programCount": 42,
                  "playCount": 120000,
                  "dj": {"nickname": "Host Name"}
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, radios.size)
        assertEquals("700", radios.single().radioId)
        assertEquals("Subscribed Podcast", radios.single().title)
        assertEquals("音乐播客", radios.single().category)
        assertEquals("Host Name", radios.single().creator)
        assertEquals("https://p1.music.126.net/radio.jpg", radios.single().artworkUrl)
        assertEquals(42, radios.single().programCount)
        assertEquals(120_000L, radios.single().playCount)
    }

    @Test
    fun accountRadiosResponseRejectsNonSuccessCode() {
        val radios = parseNeteaseAccountRadiosResponse("""{"code":301}""")

        assertTrue(radios.isEmpty())
    }

    @Test
    fun playlistCreateResponseParsesCreatedPlaylist() {
        val result = parseNeteasePlaylistCreateResponse(
            """
            {
              "code": 200,
              "playlist": {
                "id": 900,
                "name": "新歌单",
                "trackCount": 0,
                "specialType": 0,
                "subscribed": false,
                "creator": {"userId": 42}
              }
            }
            """.trimIndent(),
        )

        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertEquals("900", result.playlist?.playlistId)
        assertEquals("新歌单", result.playlist?.title)
        assertTrue(result.playlist?.isEditable == true)
    }

    @Test
    fun playlistCreateResponseTreats301AsLoginRequired() {
        val result = parseNeteasePlaylistCreateResponse("""{"code":301}""")

        assertEquals(NeteaseAccountActionStatus.RequiresLogin, result.status)
        assertNull(result.playlist)
    }

    @Test
    fun playlistCreateResponseKeepsFailureCode() {
        val result = parseNeteasePlaylistCreateResponse("""{"code":505}""")

        assertEquals(NeteaseAccountActionStatus.Failed, result.status)
        assertEquals(505, result.code)
    }

    @Test
    fun userPlaylistResponseMarksLikedSongsPlaylist() {
        val playlists = parseNeteaseUserPlaylistsResponse(
            """
            {
              "more": false,
              "playlist": [
                {
                  "id": 100,
                  "name": "Smartisan User喜欢的音乐",
                  "trackCount": 37,
                  "specialType": 5
                },
                {
                  "id": 200,
                  "name": "普通歌单",
                  "trackCount": 8,
                  "specialType": 0
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("100", playlists.first().playlistId)
        assertEquals("Smartisan User喜欢的音乐", playlists.first().name)
        assertEquals(37, playlists.first().trackCount)
        assertTrue(playlists.first().isLikedSongs)
        assertFalse(playlists.last().isLikedSongs)
    }

    @Test
    fun userPlaylistResponseParsesEditableCreatedPlaylists() {
        val playlists = parseNeteaseUserPlaylistsResponse(
            """
            {
              "code": 200,
              "playlist": [
                {
                  "id": 100,
                  "name": "我喜欢的音乐",
                  "trackCount": 37,
                  "specialType": 5,
                  "subscribed": false,
                  "creator": {"userId": 42}
                },
                {
                  "id": 200,
                  "name": "自己创建的歌单",
                  "trackCount": 8,
                  "specialType": 0,
                  "subscribed": false,
                  "creator": {"userId": 42}
                },
                {
                  "id": 300,
                  "name": "收藏的歌单",
                  "trackCount": 9,
                  "specialType": 0,
                  "subscribed": true,
                  "creator": {"userId": 7}
                }
              ]
            }
            """.trimIndent(),
        )

        assertFalse(playlists[0].isEditableBy(42L))
        assertTrue(playlists[1].isEditableBy(42L))
        assertEquals(42L, playlists[1].creatorUserId)
        assertFalse(playlists[2].isEditableBy(42L))
        assertTrue(playlists[2].subscribed)
    }

    @Test
    fun playlistDetailResponsePreservesTrackIdsBeyondEmbeddedTracks() {
        val trackIds = (1..105).joinToString(",") { id -> """{"id":$id}""" }

        val detail = parseNeteasePlaylistDetailResponse(
            """
            {
              "code": 200,
              "playlist": {
                "trackCount": 105,
                "tracks": [
                  {
                    "id": 1,
                    "name": "First song",
                    "duration": 180000,
                    "artists": [{"name": "Artist A"}],
                    "album": {"name": "Album A", "picUrl": "https://p1.music.126.net/a.jpg"}
                  },
                  {
                    "id": 2,
                    "name": "Second song",
                    "duration": 210000,
                    "artists": [{"name": "Artist B"}],
                    "album": {"name": "Album B"}
                  }
                ],
                "trackIds": [$trackIds]
              }
            }
            """.trimIndent(),
        )

        assertEquals(105, detail.trackCount)
        assertEquals(105, detail.trackIds.size)
        assertEquals("1", detail.trackIds.first())
        assertEquals("105", detail.trackIds.last())
        assertEquals(listOf("1", "2"), detail.tracks.map(OnlineTrack::trackId))
    }

}
