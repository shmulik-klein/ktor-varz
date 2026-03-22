package klein.shmulik.varz

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.server.request.*
import kotlinx.serialization.json.*
import java.text.DecimalFormat

val VarzMetricsKey = AttributeKey<VarzMetrics>("VarzMetrics")

val VarzPlugin = createApplicationPlugin("Varz", ::VarzConfig) {
    val metrics = VarzMetrics()
    application.attributes.put(VarzMetricsKey, metrics)
    
    onCall { call ->
        val startTime = System.nanoTime()
        metrics.activeRequests.incrementAndGet()
        val contentLength = try { call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() } catch (e: Exception) { null }
        val requestMethod = call.request.local.method.value
        
        call.attributes.put(AttributeKey<Long>("varzStartTime"), startTime)
        call.attributes.put(AttributeKey<Long>("varzReqSize"), contentLength ?: 0L)
        call.attributes.put(AttributeKey<String>("varzMethod"), requestMethod)
    }
    
    onCallRespond { call ->
        val startTime = call.attributes.getOrNull(AttributeKey<Long>("varzStartTime")) ?: return@onCallRespond
        val requestMethod = call.attributes.getOrNull(AttributeKey<String>("varzMethod")) ?: "UNKNOWN"
        val requestBytes = call.attributes.getOrNull(AttributeKey<Long>("varzReqSize")) ?: 0L
        
        val latencyNanos = System.nanoTime() - startTime
        val status = call.response.status()?.value ?: 0
        
        metrics.recordRequest(
            latencyNanos = latencyNanos,
            status = status,
            method = requestMethod,
            requestBytes = requestBytes,
            responseBytes = 0
        )
        metrics.activeRequests.decrementAndGet()
    }
}

fun Routing.varz(config: VarzConfig = VarzConfig()) {
    val metrics = application.attributes.getOrNull(VarzMetricsKey) ?: return
    
    if (config.enabled) {
        get("/varz") {
            call.respondText(
                generateHtml(metrics, config),
                contentType = ContentType.Text.Html
            )
        }
    }
    
    if (config.jsonEnabled) {
        get("/varz/json") {
            val percentiles = metrics.getPercentiles()
            val methods = metrics.getMethodCounts()
            val jvm = if (config.showJvm) metrics.getJvmStats() else null
            val sizes = if (config.showRequestSizes) metrics.getRequestSizes() else null
            
            val response = VarzJsonResponse(
                requestCount = metrics.requestCount.get(),
                activeRequests = metrics.activeRequests.get(),
                uptimeMillis = metrics.getUptimeMillis(),
                requestsPerSecond = metrics.getRequestsPerSecond(),
                averageLatencyNanos = metrics.getAverageLatencyNanos(),
                minLatencyNanos = if (metrics.minLatencyNanos.get() == Long.MAX_VALUE) 0L else metrics.minLatencyNanos.get(),
                maxLatencyNanos = metrics.maxLatencyNanos.get(),
                percentiles = PercentilesJson(percentiles.p50, percentiles.p90, percentiles.p95, percentiles.p99),
                status2xx = metrics.status2xx.get(),
                status3xx = metrics.status3xx.get(),
                status4xx = metrics.status4xx.get(),
                status5xx = metrics.status5xx.get(),
                methods = MethodCountsJson(methods.get, methods.post, methods.put, methods.delete, methods.patch, methods.other),
                memory = if (config.showMemory) {
                    val heap = metrics.getJvmStats()
                    MemoryJson(heap.heapUsed, heap.heapTotal, heap.heapMax, heap.nonHeapUsed)
                } else null,
                jvm = jvm?.let { JvmStatsJson(it.threadCount, it.daemonThreadCount, it.peakThreadCount, it.gcCount, it.gcTimeMillis, it.cpuUsage, it.uptimeMillis) },
                requestSizes = sizes?.let { RequestSizesJson(it.totalRequestBytes, it.totalResponseBytes, it.avgRequestBytes, it.avgResponseBytes, it.slowRequests, it.timeoutCount, it.errorCount) },
                errorRate = if (config.showRequestSizes) metrics.getErrorRate() else null
            )
            call.respondText(Json.encodeToString(VarzJsonResponse.serializer(), response), contentType = ContentType.Application.Json)
        }
    }
    
    if (config.prometheusEnabled) {
        get("/varz/prometheus") {
            call.respondText(
                generatePrometheus(metrics, config),
                contentType = ContentType.Text.Plain
            )
        }
    }
}

@kotlinx.serialization.Serializable
data class VarzJsonResponse(
    val requestCount: Long,
    val activeRequests: Int,
    val uptimeMillis: Long,
    val requestsPerSecond: Double,
    val averageLatencyNanos: Long,
    val minLatencyNanos: Long,
    val maxLatencyNanos: Long,
    val percentiles: PercentilesJson,
    val status2xx: Long,
    val status3xx: Long,
    val status4xx: Long,
    val status5xx: Long,
    val methods: MethodCountsJson,
    val memory: MemoryJson? = null,
    val jvm: JvmStatsJson? = null,
    val requestSizes: RequestSizesJson? = null,
    val errorRate: Double? = null
)

@kotlinx.serialization.Serializable
data class PercentilesJson(val p50: Double, val p90: Double, val p95: Double, val p99: Double)

@kotlinx.serialization.Serializable
data class MethodCountsJson(val get: Long, val post: Long, val put: Long, val delete: Long, val patch: Long, val other: Long)

@kotlinx.serialization.Serializable
data class MemoryJson(val heapUsed: Long, val heapTotal: Long, val heapMax: Long, val nonHeapUsed: Long)

@kotlinx.serialization.Serializable
data class JvmStatsJson(
    val threadCount: Int,
    val daemonThreadCount: Int,
    val peakThreadCount: Int,
    val gcCount: Long,
    val gcTimeMillis: Long,
    val cpuUsage: Double,
    val uptimeMillis: Long
)

@kotlinx.serialization.Serializable
data class RequestSizesJson(
    val totalRequestBytes: Long,
    val totalResponseBytes: Long,
    val avgRequestBytes: Double,
    val avgResponseBytes: Double,
    val slowRequests: Long,
    val timeoutCount: Long,
    val errorCount: Long
)

private fun generateHtml(metrics: VarzMetrics, config: VarzConfig): String {
    val df = DecimalFormat("#.##")
    val uptimeSec = metrics.getUptimeMillis() / 1000
    val uptimeStr = formatUptime(uptimeSec)
    
    val avgLatencyMs = metrics.getAverageLatencyNanos() / 1_000_000.0
    val minLatencyMs = if (metrics.minLatencyNanos.get() == Long.MAX_VALUE) 0.0 else metrics.minLatencyNanos.get() / 1_000_000.0
    val maxLatencyMs = metrics.maxLatencyNanos.get() / 1_000_000.0
    val percentiles = metrics.getPercentiles()
    val methods = metrics.getMethodCounts()
    val jvm = metrics.getJvmStats()
    val sizes = metrics.getRequestSizes()
    
    return buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html><head>")
        appendLine("<title>VarZ - Server Metrics</title>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        if (config.refreshEnabled) {
            appendLine("<meta http-equiv=\"refresh\" content=\"${config.refreshIntervalSeconds}\">")
        }
        appendLine("<style>")
        appendLine("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }")
        appendLine("h1 { color: #333; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }")
        appendLine("h2 { color: #555; font-size: 1em; margin: 15px 0 5px 0; }")
        appendLine(".metric { background: white; border-radius: 8px; padding: 15px; margin: 10px 0; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }")
        appendLine(".value { font-size: 1.8em; font-weight: bold; color: #4CAF50; }")
        appendLine(".label { color: #888; font-size: 0.85em; }")
        appendLine(".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; }")
        appendLine(".status-2xx { color: #4CAF50; }")
        appendLine(".status-3xx { color: #2196F3; }")
        appendLine(".status-4xx { color: #FF9800; }")
        appendLine(".status-5xx { color: #f44336; }")
        appendLine(".section { background: white; border-radius: 8px; padding: 20px; margin: 20px 0; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }")
        appendLine("table { width: 100%; border-collapse: collapse; }")
        appendLine("td { padding: 8px; border-bottom: 1px solid #eee; }")
        appendLine("td:first-child { font-weight: 500; }")
        appendLine("td:last-child { text-align: right; color: #4CAF50; font-weight: bold; }")
        appendLine("</style></head><body>")
        appendLine("<h1>VarZ - Server Metrics</h1>")
        
        appendLine("<div class='section'>")
        appendLine("<h2>Overview</h2>")
        appendLine("<div class='grid'>")
        appendLine("<div class='metric'><h2>Total Requests</h2><div class='value'>${metrics.requestCount.get()}</div></div>")
        appendLine("<div class='metric'><h2>RPS</h2><div class='value'>${df.format(metrics.getRequestsPerSecond())}</div></div>")
        appendLine("<div class='metric'><h2>Active</h2><div class='value'>${metrics.activeRequests.get()}</div></div>")
        appendLine("<div class='metric'><h2>Uptime</h2><div class='value'>$uptimeStr</div></div>")
        appendLine("</div></div>")
        
        appendLine("<div class='section'>")
        appendLine("<h2>Latency</h2>")
        appendLine("<div class='grid'>")
        appendLine("<div class='metric'><h2>Avg</h2><div class='value'>${df.format(avgLatencyMs)}ms</div></div>")
        appendLine("<div class='metric'><h2>Min</h2><div class='value'>${df.format(minLatencyMs)}ms</div></div>")
        appendLine("<div class='metric'><h2>Max</h2><div class='value'>${df.format(maxLatencyMs)}ms</div></div>")
        appendLine("</div>")
        if (config.showPercentiles) {
            appendLine("<h2>Percentiles</h2>")
            appendLine("<table>")
            appendLine("<tr><td>p50</td><td>${df.format(percentiles.p50)}ms</td></tr>")
            appendLine("<tr><td>p90</td><td>${df.format(percentiles.p90)}ms</td></tr>")
            appendLine("<tr><td>p95</td><td>${df.format(percentiles.p95)}ms</td></tr>")
            appendLine("<tr><td>p99</td><td>${df.format(percentiles.p99)}ms</td></tr>")
            appendLine("</table>")
        }
        appendLine("</div>")
        
        appendLine("<div class='section'>")
        appendLine("<h2>Status Codes</h2>")
        appendLine("<div class='grid'>")
        appendLine("<div class='metric'><h2>2xx</h2><div class='value status-2xx'>${metrics.status2xx.get()}</div></div>")
        appendLine("<div class='metric'><h2>3xx</h2><div class='value status-3xx'>${metrics.status3xx.get()}</div></div>")
        appendLine("<div class='metric'><h2>4xx</h2><div class='value status-4xx'>${metrics.status4xx.get()}</div></div>")
        appendLine("<div class='metric'><h2>5xx</h2><div class='value status-5xx'>${metrics.status5xx.get()}</div></div>")
        appendLine("</div></div>")
        
        if (config.showMethods) {
            appendLine("<div class='section'>")
            appendLine("<h2>HTTP Methods</h2>")
            appendLine("<table>")
            appendLine("<tr><td>GET</td><td>${methods.get}</td></tr>")
            appendLine("<tr><td>POST</td><td>${methods.post}</td></tr>")
            appendLine("<tr><td>PUT</td><td>${methods.put}</td></tr>")
            appendLine("<tr><td>DELETE</td><td>${methods.delete}</td></tr>")
            appendLine("<tr><td>PATCH</td><td>${methods.patch}</td></tr>")
            appendLine("<tr><td>Other</td><td>${methods.other}</td></tr>")
            appendLine("</table></div>")
        }
        
        if (config.showRequestSizes) {
            appendLine("<div class='section'>")
            appendLine("<h2>Request/Response Sizes</h2>")
            appendLine("<div class='grid'>")
            appendLine("<div class='metric'><h2>Avg Request</h2><div class='value'>${df.format(sizes.avgRequestBytes)}B</div></div>")
            appendLine("<div class='metric'><h2>Avg Response</h2><div class='value'>${df.format(sizes.avgResponseBytes)}B</div></div>")
            appendLine("<div class='metric'><h2>Slow Requests</h2><div class='value'>${sizes.slowRequests}</div></div>")
            appendLine("<div class='metric'><h2>Timeouts</h2><div class='value'>${sizes.timeoutCount}</div></div>")
            appendLine("<div class='metric'><h2>Error Rate</h2><div class='value'>${df.format(metrics.getErrorRate())}%</div></div>")
            appendLine("</div></div>")
        }
        
        if (config.showJvm) {
            appendLine("<div class='section'>")
            appendLine("<h2>JVM & Memory</h2>")
            if (config.showMemory) {
                appendLine("<h2>Heap Memory</h2>")
                appendLine("<div class='grid'>")
                val heapUsedMb = jvm.heapUsed / 1024 / 1024
                val heapTotalMb = jvm.heapTotal / 1024 / 1024
                val heapMaxMb = jvm.heapMax / 1024 / 1024
                appendLine("<div class='metric'><h2>Used</h2><div class='value'>${heapUsedMb}MB</div></div>")
                appendLine("<div class='metric'><h2>Total</h2><div class='value'>${heapTotalMb}MB</div></div>")
                appendLine("<div class='metric'><h2>Max</h2><div class='value'>${heapMaxMb}MB</div></div>")
                appendLine("</div>")
            }
            appendLine("<h2>Threads</h2>")
            appendLine("<div class='grid'>")
            appendLine("<div class='metric'><h2>Live</h2><div class='value'>${jvm.threadCount}</div></div>")
            appendLine("<div class='metric'><h2>Daemon</h2><div class='value'>${jvm.daemonThreadCount}</div></div>")
            appendLine("<div class='metric'><h2>Peak</h2><div class='value'>${jvm.peakThreadCount}</div></div>")
            appendLine("</div>")
            appendLine("<h2>Garbage Collection</h2>")
            appendLine("<div class='grid'>")
            appendLine("<div class='metric'><h2>Count</h2><div class='value'>${jvm.gcCount}</div></div>")
            appendLine("<div class='metric'><h2>Time</h2><div class='value'>${jvm.gcTimeMillis}ms</div></div>")
            appendLine("</div>")
            appendLine("<h2>CPU & Uptime</h2>")
            appendLine("<div class='grid'>")
            appendLine("<div class='metric'><h2>CPU Load</h2><div class='value'>${df.format(jvm.cpuUsage * 100)}%</div></div>")
            appendLine("<div class='metric'><h2>Process Uptime</h2><div class='value'>${formatUptime(jvm.uptimeMillis / 1000)}</div></div>")
            appendLine("</div>")
            appendLine("</div>")
        }
        
        appendLine("</body></html>")
    }
}

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        days > 0 -> "${days}d ${hours}h ${mins}m"
        hours > 0 -> "${hours}h ${mins}m ${secs}s"
        mins > 0 -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}

private fun generatePrometheus(metrics: VarzMetrics, config: VarzConfig): String {
    val sb = StringBuilder()
    val jvm = metrics.getJvmStats()
    val sizes = metrics.getRequestSizes()
    val percentiles = metrics.getPercentiles()
    val methods = metrics.getMethodCounts()
    
    sb.appendLine("# HELP varz_requests_total Total number of requests")
    sb.appendLine("# TYPE varz_requests_total counter")
    sb.appendLine("varz_requests_total ${metrics.requestCount.get()}")
    
    sb.appendLine("# HELP varz_requests_per_second Requests per second")
    sb.appendLine("# TYPE varz_requests_per_second gauge")
    sb.appendLine("varz_requests_per_second ${metrics.getRequestsPerSecond()}")
    
    sb.appendLine("# HELP varz_active_requests Current number of active requests")
    sb.appendLine("# TYPE varz_active_requests gauge")
    sb.appendLine("varz_active_requests ${metrics.activeRequests.get()}")
    
    sb.appendLine("# HELP varz_latency_seconds_avg Average latency in seconds")
    sb.appendLine("# TYPE varz_latency_seconds_avg gauge")
    sb.appendLine("varz_latency_seconds_avg ${metrics.getAverageLatencyNanos() / 1_000_000_000.0}")
    
    sb.appendLine("# HELP varz_latency_seconds_min Min latency in seconds")
    sb.appendLine("# TYPE varz_latency_seconds_min gauge")
    val minLatency = if (metrics.minLatencyNanos.get() == Long.MAX_VALUE) 0.0 else metrics.minLatencyNanos.get() / 1_000_000_000.0
    sb.appendLine("varz_latency_seconds_min $minLatency")
    
    sb.appendLine("# HELP varz_latency_seconds_max Max latency in seconds")
    sb.appendLine("# TYPE varz_latency_seconds_max gauge")
    sb.appendLine("varz_latency_seconds_max ${metrics.maxLatencyNanos.get() / 1_000_000_000.0}")
    
    sb.appendLine("# HELP varz_latency_seconds_p50 Latency p50 in seconds")
    sb.appendLine("# TYPE varz_latency_seconds_p50 gauge")
    sb.appendLine("varz_latency_seconds_p50 ${percentiles.p50 / 1000.0}")
    
    sb.appendLine("# HELP varz_latency_seconds_p90 Latency p90 in seconds")
    sb.appendLine("# TYPE varz_latency_seconds_p90 gauge")
    sb.appendLine("varz_latency_seconds_p90 ${percentiles.p90 / 1000.0}")
    
    sb.appendLine("# HELP varz_latency_seconds_p99 Latency p99 in seconds")
    sb.appendLine("# TYPE varz_latency_seconds_p99 gauge")
    sb.appendLine("varz_latency_seconds_p99 ${percentiles.p99 / 1000.0}")
    
    sb.appendLine("# HELP varz_status_codes_total HTTP status codes")
    sb.appendLine("# TYPE varz_status_codes_total counter")
    sb.appendLine("varz_status_2xx_total ${metrics.status2xx.get()}")
    sb.appendLine("varz_status_3xx_total ${metrics.status3xx.get()}")
    sb.appendLine("varz_status_4xx_total ${metrics.status4xx.get()}")
    sb.appendLine("varz_status_5xx_total ${metrics.status5xx.get()}")
    
    sb.appendLine("# HELP varz_http_methods_total HTTP methods")
    sb.appendLine("# TYPE varz_http_methods_total counter")
    sb.appendLine("varz_method_get_total ${methods.get}")
    sb.appendLine("varz_method_post_total ${methods.post}")
    sb.appendLine("varz_method_put_total ${methods.put}")
    sb.appendLine("varz_method_delete_total ${methods.delete}")
    sb.appendLine("varz_method_patch_total ${methods.patch}")
    sb.appendLine("varz_method_other_total ${methods.other}")
    
    sb.appendLine("# HELP varz_jvm_heap_bytes JVM heap memory")
    sb.appendLine("# TYPE varz_jvm_heap_bytes gauge")
    sb.appendLine("varz_jvm_heap_used_bytes ${jvm.heapUsed}")
    sb.appendLine("varz_jvm_heap_total_bytes ${jvm.heapTotal}")
    sb.appendLine("varz_jvm_heap_max_bytes ${jvm.heapMax}")
    
    sb.appendLine("# HELP varz_jvm_threads JVM threads")
    sb.appendLine("# TYPE varz_jvm_threads gauge")
    sb.appendLine("varz_jvm_threads_live ${jvm.threadCount}")
    sb.appendLine("varz_jvm_threads_daemon ${jvm.daemonThreadCount}")
    sb.appendLine("varz_jvm_threads_peak ${jvm.peakThreadCount}")
    
    sb.appendLine("# HELP varz_jvm_gc_total Garbage collection counts")
    sb.appendLine("# TYPE varz_jvm_gc_total counter")
    sb.appendLine("varz_jvm_gc_count ${jvm.gcCount}")
    sb.appendLine("varz_jvm_gc_time_millis ${jvm.gcTimeMillis}")
    
    sb.appendLine("# HELP varz_errors_total Error counts")
    sb.appendLine("# TYPE varz_errors_total counter")
    sb.appendLine("varz_errors_total ${sizes.errorCount}")
    sb.appendLine("varz_slow_requests_total ${sizes.slowRequests}")
    sb.appendLine("varz_timeouts_total ${sizes.timeoutCount}")
    
    return sb.toString()
}
