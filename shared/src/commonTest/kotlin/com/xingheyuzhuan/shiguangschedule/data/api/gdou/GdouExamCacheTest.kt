package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.xingheyuzhuan.shiguangschedule.data.repository.GdouJwxtRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.GdouJwxtRepository.Keys
import com.xingheyuzhuan.shiguangschedule.tool.SecureCrypto
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GdouExamCacheTest {
    @Test
    fun restoresPersistedResultsAndKeepsTermsSeparate() = runBlocking {
        withRepository { store, http, repo ->
            assertEquals(GdouExamCacheState(CURRENT, null), repo.examCacheFlow.first())
            repo.persistExams(CURRENT, listOf(EXAM))
            repo.persistExams(PREVIOUS, emptyList())

            val reopened = GdouJwxtRepository(store, SecureCrypto(), http)
            assertEquals(GdouExamCacheState(CURRENT, listOf(EXAM)), reopened.examCacheFlow.first())
            reopened.setTerm(PREVIOUS.term)
            assertEquals(GdouExamCacheState(PREVIOUS, emptyList()), reopened.examCacheFlow.first())
            reopened.setTerm(CURRENT.term)
            assertEquals(GdouExamCacheState(CURRENT, listOf(EXAM)), reopened.examCacheFlow.first())
        }
    }

    @Test
    fun onlyMatchingAccountCanWriteAndLogoutRemovesCache() = runBlocking {
        withRepository { store, _, repo ->
            repo.persistExams(CURRENT.copy(username = "different-student"), listOf(EXAM))
            assertNull(repo.examCacheFlow.first().exams)
            repo.persistExams(CURRENT, listOf(EXAM))
            repo.logout()
            repo.persistExams(CURRENT, listOf(EXAM))
            assertEquals(GdouExamCacheState(null, null), repo.examCacheFlow.first())
            assertNull(store.data.first()[Keys.EXAM_CACHE])
        }
    }

    @Test
    fun failedExamRequestDoesNotEraseCacheOrBlockSuccessfulCourseRead() = runBlocking {
        withRepository { _, _, repo ->
            repo.persistExams(CURRENT, listOf(EXAM))
            val failure = IllegalStateException("synthetic exam failure")
            val (schedule, exams) = fetchGdouTermData({ SCHEDULE }, { throw failure })
            exams.getOrNull()?.let { repo.persistExams(CURRENT, it) }
            assertSame(SCHEDULE, schedule)
            assertSame(failure, exams.exceptionOrNull())
            assertEquals(listOf(EXAM), repo.examCacheFlow.first().exams)
        }
    }

    @Test
    fun startsBothRequestsBeforeEitherCompletes() = runBlocking {
        val courseStarted = CompletableDeferred<Unit>()
        val examStarted = CompletableDeferred<Unit>()
        val result = withTimeout(2_000) {
            fetchGdouTermData(
                fetchSchedule = { courseStarted.complete(Unit); examStarted.await(); SCHEDULE },
                fetchExams = { examStarted.complete(Unit); courseStarted.await(); listOf(EXAM) }
            )
        }
        assertSame(SCHEDULE, result.first)
        assertEquals(listOf(EXAM), result.second.getOrThrow())
    }

    @Test
    fun courseFailureCancelsTheExamRequestBeforeRouteCanChange() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        assertFailsWith<IllegalStateException> {
            withTimeout(2_000) {
                fetchGdouTermData(
                    fetchSchedule = { started.await(); error("synthetic course failure") },
                    fetchExams = {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                )
            }
        }
        assertTrue(stopped.isCompleted)
    }

    @Test
    fun examCancellationIsNotAnOrdinaryFailure() = runBlocking {
        assertFailsWith<CancellationException> {
            fetchGdouTermData({ SCHEDULE }, { throw CancellationException("test cancellation") })
        }
        Unit
    }

    private suspend fun withRepository(block: suspend (DataStore<Preferences>, GdouHttpClient, GdouJwxtRepository) -> Unit) {
        val store = MemoryPreferences()
        val engine = MockEngine { error("reading or persisting the cache must not use the network") }
        val http = GdouHttpClient(engine)
        try {
            store.edit { it[Keys.USERNAME] = CURRENT.username }
            val repo = GdouJwxtRepository(store, SecureCrypto(), http)
            repo.setTerm(CURRENT.term)
            block(store, http, repo)
        } finally {
            http.client.close()
            engine.close()
        }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }

    private companion object {
        val CURRENT = GdouExamKey("test-student", GdouTerm("2026", "3"))
        val PREVIOUS = CURRENT.copy(term = GdouTerm("2025", "12"))
        val EXAM = GdouExam("Test course", "Final", "2027-01-08", "08:00", "A101", "Campus", "12", "")
        val SCHEDULE = GdouFetchedSchedule(emptyList(), null)
    }
}
