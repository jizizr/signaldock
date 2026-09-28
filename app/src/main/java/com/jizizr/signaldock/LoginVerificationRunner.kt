package com.jizizr.signaldock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The owner supplies an application lifetime; a screen only observes the result. */
internal class LoginVerificationRunner(
    private val scope: CoroutineScope,
    private val describeFailure: (Throwable) -> String,
) {
    sealed interface State {
        data object Idle : State
        data object Running : State
        data object Success : State
        class Failed(val message: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state = mutableState.asStateFlow()

    @Synchronized fun reset() {
        if (mutableState.value != State.Running) mutableState.value = State.Idle
    }

    @Synchronized fun <T> start(
        acquire: suspend () -> T,
        verify: suspend (T) -> Unit,
        save: suspend (T) -> Unit,
    ): Job? {
        if (mutableState.value == State.Running) return null
        mutableState.value = State.Running
        return scope.launch {
            try {
                val candidate = acquire()
                verify(candidate)
                save(candidate)
                mutableState.value = State.Success
            } catch (cancelled: CancellationException) {
                mutableState.value = State.Idle
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = State.Failed(describeFailure(error))
            }
        }
    }
}
