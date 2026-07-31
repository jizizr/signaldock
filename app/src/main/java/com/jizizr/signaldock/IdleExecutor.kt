package com.jizizr.signaldock

import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executor for bursty work such as one screenshot or one network request.
 * Threads are reclaimed after the burst, so enabled background services do not
 * retain a pool of sleeping worker threads indefinitely.
 */
internal fun newIdleExecutor(
    name: String,
    threads: Int,
    threadPriority: Int = Process.THREAD_PRIORITY_DEFAULT,
): ExecutorService {
    require(threads > 0) { "threads must be positive" }
    return ThreadPoolExecutor(
        threads,
        threads,
        15L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        NamedPriorityThreadFactory(name, threadPriority),
    ).apply {
        allowCoreThreadTimeOut(true)
    }
}

private class NamedPriorityThreadFactory(
    private val name: String,
    private val priority: Int,
) : ThreadFactory {
    private val sequence = AtomicInteger(1)

    override fun newThread(task: Runnable): Thread = Thread({
        Process.setThreadPriority(priority)
        task.run()
    }, "$name-${sequence.getAndIncrement()}")
}
