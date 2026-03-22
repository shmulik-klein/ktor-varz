package klein.shmulik

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.http.*
import io.ktor.server.request.*
import klein.shmulik.varz.*

fun main() {
    embeddedServer(Netty, port = 8001) {
        install(VarzPlugin) {
            enabled = true
            jsonEnabled = true
            prometheusEnabled = true
            refreshEnabled = true
            refreshIntervalSeconds = 30
            showMemory = true
            showThreads = true
        }
        
        val metrics = attributes.getOrNull(VarzMetricsKey)!!
        
        intercept(ApplicationCallPipeline.Plugins) {
            val startTime = System.nanoTime()
            metrics.activeRequests.incrementAndGet()
            val contentLength = try { call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() } catch (e: Exception) { null }
            val requestMethod = call.request.local.method.value
            
            proceed()
            
            val latencyNanos = System.nanoTime() - startTime
            val status = call.response.status()?.value ?: 0
            
            metrics.recordRequest(
                latencyNanos = latencyNanos,
                status = status,
                method = requestMethod,
                requestBytes = contentLength ?: 0L,
                responseBytes = 0
            )
            metrics.activeRequests.decrementAndGet()
        }
        
        routing {
            varz()
            
            get("/") {
                call.respondText("Hello from Ktor! Try /varz for metrics.")
            }
            
            get("/error") {
                call.respondText("Error!", status = HttpStatusCode.InternalServerError)
            }
        }
    }.start(wait = true)
}
