package io.github.risumanager.data.github

import io.github.risumanager.domain.model.ChannelDefinition
import io.github.risumanager.domain.model.ChannelType
import io.github.risumanager.domain.model.ReleaseAsset
import io.github.risumanager.domain.model.RisuRelease
import io.github.risumanager.domain.model.SourceRevision
import io.github.risumanager.domain.model.VariantDefinition
import io.github.risumanager.domain.source.SourceRepository
import io.github.risumanager.domain.validation.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class GitHubSourceAdapter(
    private val client: OkHttpClient,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
    private val apiBaseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
) : SourceRepository {
    private data class CachedRelease(val fetchedAt: Long, val value: RisuRelease)
    private val releaseCache = ConcurrentHashMap<String, CachedRelease>()

    override suspend fun resolve(
        variant: VariantDefinition,
        channel: ChannelDefinition,
        requestedCommit: String?,
    ): SourceRevision {
        require(Validators.isRepository(variant.repository)) { "Invalid built-in repository" }
        return when (channel.type) {
            ChannelType.GITHUB_RELEASE -> latestRelease(variant.repository).revision
            ChannelType.GIT_BRANCH -> {
                val branch = requireNotNull(channel.ref)
                require(Validators.isSafeGitRef(branch)) { "Unsafe branch" }
                commit(variant.repository, branch).copy(releaseName = channel.displayName)
            }
            ChannelType.SPECIFIC_COMMIT -> {
                val input = requireNotNull(requestedCommit) { "A commit is required" }
                require(Validators.isCommitInput(input)) { "Commit must contain 7-40 hexadecimal characters" }
                commit(variant.repository, input).copy(releaseName = "Pinned commit")
            }
        }
    }

    override suspend fun latestRelease(repository: String): RisuRelease {
        require(Validators.isRepository(repository)) { "Invalid repository" }
        releaseCache[repository]?.takeIf { now() - it.fetchedAt < CACHE_MILLIS }?.let { return it.value }
        val releases = get<List<GitHubReleaseDto>>(url(repository, "releases") {
            addQueryParameter("per_page", "30")
        })
        val dto = ReleaseSelection.latestStable(releases)
            ?: throw GitHubException("NO_STABLE_RELEASE", "No non-draft stable release exists for $repository")
        val revision = commit(repository, dto.tagName).copy(
            displayVersion = dto.tagName,
            releaseName = dto.name.ifBlank { dto.tagName },
            releaseNotes = dto.body,
            publishedAt = dto.publishedAt,
        )
        val release = RisuRelease(
            tag = dto.tagName,
            name = dto.name.ifBlank { dto.tagName },
            revision = revision,
            prerelease = dto.prerelease,
            draft = dto.draft,
            assets = dto.assets.map { asset ->
                ReleaseAsset(
                    name = asset.name,
                    downloadUrl = asset.browserDownloadUrl,
                    sizeBytes = asset.size,
                    sha256 = asset.digest?.removePrefix("sha256:"),
                    contentType = asset.contentType,
                )
            },
        )
        releaseCache[repository] = CachedRelease(now(), release)
        return release
    }

    override suspend fun branchExists(repository: String, branch: String): Boolean {
        if (!Validators.isRepository(repository) || !Validators.isSafeGitRef(branch)) return false
        return try {
            commit(repository, branch)
            true
        } catch (exception: GitHubException) {
            if (exception.code == "NOT_FOUND") false else throw exception
        }
    }

    private suspend fun commit(repository: String, ref: String): SourceRevision {
        val dto = get<GitHubCommitDto>(url(repository, "commits", ref))
        if (!Validators.isFullCommitSha(dto.sha)) {
            throw GitHubException("INVALID_RESPONSE", "GitHub returned an invalid commit SHA")
        }
        return SourceRevision(sha = dto.sha.lowercase(), checkoutRef = dto.sha.lowercase())
    }

    private suspend inline fun <reified T> get(url: HttpUrl): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "RisuManager/0.1.0")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = if (response.code == 404) "NOT_FOUND" else "GITHUB_HTTP_${response.code}"
                    throw GitHubException(code, "GitHub request failed with HTTP ${response.code}")
                }
                val body = response.body?.string() ?: throw GitHubException("EMPTY_RESPONSE", "GitHub returned no content")
                json.decodeFromString<T>(body)
            }
        } catch (exception: GitHubException) {
            throw exception
        } catch (exception: IOException) {
            throw GitHubException("NETWORK_UNAVAILABLE", exception.message ?: "GitHub is unavailable", exception)
        } catch (exception: Exception) {
            throw GitHubException("INVALID_RESPONSE", exception.message ?: "GitHub response could not be parsed", exception)
        }
    }

    private fun url(repository: String, vararg path: String, configure: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val (owner, name) = repository.split('/', limit = 2)
        return apiBaseUrl.newBuilder()
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(name)
            .apply { path.forEach(::addPathSegment) }
            .apply(configure)
            .build()
    }

    private companion object {
        const val CACHE_MILLIS = 10 * 60 * 1000L
    }
}

class GitHubException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

object ReleaseSelection {
    fun latestStable(releases: List<GitHubReleaseDto>): GitHubReleaseDto? = releases
        .asSequence()
        .filterNot { it.draft || it.prerelease }
        .maxByOrNull { it.publishedAt.orEmpty() }
}

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long,
    @SerialName("content_type") val contentType: String? = null,
    val digest: String? = null,
)

@Serializable
private data class GitHubCommitDto(val sha: String)
