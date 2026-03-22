package klein.shmulik.varz

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentLinkedDeque

class VarzConfig {
    var enabled: Boolean = true
    var jsonEnabled: Boolean = true
    var prometheusEnabled: Boolean = false
    var refreshEnabled: Boolean = false
    var refreshIntervalSeconds: Int = 30
    
    var showMemory: Boolean = true
    var showThreads: Boolean = true
    var showJvm: Boolean = true
    var showPercentiles: Boolean = true
    var showMethods: Boolean = true
    var showRequestSizes: Boolean = true
}

data class Percentiles(
    val p50: Double = 0.0,
    val p90: Double = 0.0,
    val p95: Double = 0.0,
    val p99: Double = 0.0
)

data class MethodCounts(
    val get: Long = 0,
    val post: Long = 0,
    val put: Long = 0,
    val delete: Long = 0,
    val patch: Long = 0,
    val other: Long = 0
)

data class JvmStats(
    val heapUsed: Long = 0,
    val heapTotal: Long = 0,
    val heapMax: Long = 0,
    val nonHeapUsed: Long = 0,
    val gcCount: Long = 0,
    val gcTimeMillis: Long = 0,
    val threadCount: Int = 0,
    val daemonThreadCount: Int = 0,
    val peakThreadCount: Int = 0,
    val cpuUsage: Double = 0.0,
    val uptimeMillis: Long = 0
)

data class RequestSizes(
    val totalRequestBytes: Long = 0,
    val totalResponseBytes: Long = 0,
    val avgRequestBytes: Double = 0.0,
    val avgResponseBytes: Double = 0.0,
    val slowRequests: Long = 0,
    val timeoutCount: Long = 0,
    val errorCount: Long = 0
)

class VarzMetrics {
    private val startTime = System.currentTimeMillis()
    
    val requestCount = AtomicLong(0)
    val activeRequests = AtomicInteger(0)
    val totalLatencyNanos = AtomicLong(0)
    val minLatencyNanos = AtomicLong(Long.MAX_VALUE)
    val maxLatencyNanos = AtomicLong(0)
    val latencyHistogram = ConcurrentLinkedDeque<Long>()
    
    val status2xx = AtomicLong(0)
    val status3xx = AtomicLong(0)
    val status4xx = java.util.concurrent.atomic.AtomicLong(0)
    val status5xx = java.util.concurrent.atomic.AtomicLong(0)
    
    val methodGet = java.util.concurrent.atomic.AtomicLong(0)
    val methodPost = java.util.concurrent.atomic.AtomicLong(0)
    val methodPut = java.util.concurrent.atomic.AtomicLong(0)
    val methodDelete = java.util.concurrent.atomic.AtomicLong(0)
    val methodPatch = java.util.concurrent.atomic.AtomicLong(0)
    val methodOther = java.util.concurrent.atomic.AtomicLong(0)
    
    val totalRequestBytes = java.util.concurrent.atomic.AtomicLong(0)
    val totalResponseBytes = java.util.concurrent.atomic.AtomicLong(0)
    val slowRequests = java.util.concurrent.atomic.AtomicLong(0)
    val timeoutCount = java.util.concurrent.atomic.AtomicLong(0)
    val errorCount = java.util.concurrent.atomic.AtomicLong(0)
    
    private val slowRequestThresholdNanos = 1_000_000_000L
    
    fun recordRequest(
        latencyNanos: Long,
        status: Int,
        method: String,
        requestBytes: Long,
        responseBytes: Long,
        timedOut: Boolean = false
    ) {
        requestCount.incrementAndGet()
        totalLatencyNanos.addAndGet(latencyNanos)
        
        minLatencyNanos.updateAndGet { current -> 
            if (latencyNanos < current) latencyNanos else current 
        }
        maxLatencyNanos.updateAndGet { current -> 
            if (latencyNanos > current) latencyNanos else current 
        }
        
        latencyHistogram.addLast(latencyNanos)
        if (latencyHistogram.size > 10000) latencyHistogram.removeFirst()
        
        when (status) {
            in 200..299 -> status2xx.incrementAndGet()
            in 300..399 -> status3xx.incrementAndGet()
            in 400..499 -> status4xx.incrementAndGet()
            in 500..599 -> status5xx.incrementAndGet()
        }
        
        when (method.uppercase()) {
            "GET" -> methodGet.incrementAndGet()
            "POST" -> methodPost.incrementAndGet()
            "PUT" -> methodPut.incrementAndGet()
            "DELETE" -> methodDelete.incrementAndGet()
            "PATCH" -> methodPatch.incrementAndGet()
            else -> methodOther.incrementAndGet()
        }
        
        totalRequestBytes.addAndGet(requestBytes)
        totalResponseBytes.addAndGet(responseBytes)
        
        if (latencyNanos > slowRequestThresholdNanos) slowRequests.incrementAndGet()
        if (timedOut) timeoutCount.incrementAndGet()
        if (status >= 400) errorCount.incrementAndGet()
    }
    
    fun getAverageLatencyNanos(): Long {
        val count = requestCount.get()
        return if (count > 0) totalLatencyNanos.get() / count else 0
    }
    
    fun getUptimeMillis(): Long = System.currentTimeMillis() - startTime
    
    fun getPercentiles(): Percentiles {
        val sorted: List<Long> = latencyHistogram.sorted()
        if (sorted.isEmpty()) return Percentiles()
        
        fun percentile(p: Double): Double {
            val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index].toDouble() / 1_000_000.0
        }
        
        return Percentiles(
            p50 = percentile(0.50),
            p90 = percentile(0.90),
            p95 = percentile(0.95),
            p99 = percentile(0.99)
        )
    }
    
    fun getMethodCounts(): MethodCounts = MethodCounts(
        get = methodGet.get(),
        post = methodPost.get(),
        put = methodPut.get(),
        delete = methodDelete.get(),
        patch = methodPatch.get(),
        other = methodOther.get()
    )
    
    fun getJvmStats(): JvmStats {
        val runtime = Runtime.getRuntime()
        val mem = ManagementFactory.getMemoryMXBean()
        val gc = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount }
        val gcTime = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
        val threads = ManagementFactory.getThreadMXBean()
        val os = ManagementFactory.getOperatingSystemMXBean()
        
        return JvmStats(
            heapUsed = runtime.totalMemory() - runtime.freeMemory(),
            heapTotal = runtime.totalMemory(),
            heapMax = runtime.maxMemory(),
            nonHeapUsed = mem.nonHeapMemoryUsage.used,
            gcCount = gc,
            gcTimeMillis = gcTime,
            threadCount = threads.threadCount,
            daemonThreadCount = threads.daemonThreadCount,
            peakThreadCount = threads.peakThreadCount,
            cpuUsage = if (os is com.sun.management.OperatingSystemMXBean) os.processCpuLoad else 0.0,
            uptimeMillis = ManagementFactory.getRuntimeMXBean().uptime
        )
    }
    
    fun getRequestSizes(): RequestSizes {
        val count = requestCount.get()
        return RequestSizes(
            totalRequestBytes = totalRequestBytes.get(),
            totalResponseBytes = totalResponseBytes.get(),
            avgRequestBytes = if (count > 0) totalRequestBytes.get().toDouble() / count else 0.0,
            avgResponseBytes = if (count > 0) totalResponseBytes.get().toDouble() / count else 0.0,
            slowRequests = slowRequests.get(),
            timeoutCount = timeoutCount.get(),
            errorCount = errorCount.get()
        )
    }
    
    fun getErrorRate(): Double {
        val count = requestCount.get()
        return if (count > 0) errorCount.get().toDouble() / count * 100 else 0.0
    }
    
    fun getRequestsPerSecond(): Double {
        val uptime = getUptimeMillis()
        return if (uptime > 0) requestCount.get().toDouble() / (uptime / 1000.0) else 0.0
    }
}