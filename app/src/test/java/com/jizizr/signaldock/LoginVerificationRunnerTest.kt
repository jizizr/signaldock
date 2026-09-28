package com.jizizr.signaldock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LoginVerificationRunnerTest {
    @Test fun leavingLoginScreenDoesNotCancelVerificationOrSaveEarly() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val screenScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val verification = CompletableDeferred<Unit>()
            val runner = LoginVerificationRunner(appScope) { "failed" }
            var saved = "old session"
            var work: Job? = null
            screenScope.launch {
                work = runner.start(acquire = { "new session" }, verify = { verification.await() }, save = { saved = it })
                awaitCancellation()
            }
            assertEquals("old session", saved)
            assertEquals(LoginVerificationRunner.State.Running, runner.state.value)
            screenScope.cancel()
            assertTrue(work!!.isActive)
            verification.complete(Unit)
            work!!.join()
            assertEquals("new session", saved)
            assertEquals(LoginVerificationRunner.State.Success, runner.state.value)
        } finally {
            appScope.cancel()
            screenScope.cancel()
        }
    }

    @Test fun failedAcquisitionOrRecognitionNeverReplacesExistingSession() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            for (failAtAcquire in listOf(true, false)) {
                val runner = LoginVerificationRunner(appScope) { "failed" }
                var saved = "old session"
                runner.start(acquire = { if (failAtAcquire) error("exchange") else "new session" },
                    verify = { error("recognition") }, save = { saved = it })!!.join()
                assertEquals("old session", saved)
                assertTrue(runner.state.value is LoginVerificationRunner.State.Failed)
            }
        } finally { appScope.cancel() }
    }

    @Test fun repeatedCallbacksAndScreenResetCannotStartAnotherExchange() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CompletableDeferred<Unit>()
            val runner = LoginVerificationRunner(appScope) { "failed" }
            var exchanges = 0
            var saves = 0
            val work = runner.start(acquire = { exchanges++; "session" }, verify = { gate.await() }, save = { saves++ })!!
            runner.reset()
            assertEquals(LoginVerificationRunner.State.Running, runner.state.value)
            assertNull(runner.start(acquire = { exchanges++; "duplicate" }, verify = {}, save = { saves++ }))
            gate.complete(Unit)
            work.join()
            assertEquals(1, exchanges)
            assertEquals(1, saves)
        } finally { appScope.cancel() }
    }

    @Test fun storageFailureMustNotReportLoginSuccess() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val runner = LoginVerificationRunner(appScope) { "storage failed" }
            runner.start(acquire = { "session" }, verify = {}, save = { error("disk") })!!.join()
            assertEquals("storage failed", (runner.state.value as LoginVerificationRunner.State.Failed).message)
        } finally { appScope.cancel() }
    }
}
