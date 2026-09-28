package io.github.risumanager.data.github

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.BuiltInVariants
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class GitHubSourceAdapterTest {
    private lateinit var server: MockWebServer
    private lateinit var adapter: GitHubSourceAdapter

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = GitHubSourceAdapter(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            apiBaseUrl = server.url("/"),
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun latestStableSkipsDraftsAndPrereleasesAndResolvesTagCommit() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {"tag_name":"v2.0.0-beta","name":"Beta","draft":false,"prerelease":true,"published_at":"2026-08-30T00:00:00Z","assets":[]},
                  {"tag_name":"v1.11.0","name":"Stable","draft":false,"prerelease":false,"published_at":"2026-08-29T00:00:00Z","assets":[]}
                ]
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setBody("{\"sha\":\"${"a".repeat(40)}\"}"))

        val release = adapter.latestRelease(BuiltInVariants.pocketRisu.repository)

        assertThat(release.tag).isEqualTo("v1.11.0")
        assertThat(release.revision.sha).isEqualTo("a".repeat(40))
        assertThat(server.takeRequest().path).isEqualTo("/repos/PocketRisu/PocketRisu/releases?per_page=30")
        assertThat(server.takeRequest().path).isEqualTo("/repos/PocketRisu/PocketRisu/commits/v1.11.0")
    }

    @Test fun releaseSelectionUsesNewestStableDate() {
        val releases = listOf(
            GitHubReleaseDto("v1", publishedAt = "2026-01-01T00:00:00Z"),
            GitHubReleaseDto("v2", publishedAt = "2026-02-01T00:00:00Z"),
        )
        assertThat(ReleaseSelection.latestStable(releases)?.tagName).isEqualTo("v2")
    }
}
