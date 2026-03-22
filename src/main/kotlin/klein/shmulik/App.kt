package klein.shmulik

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
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
            showJvm = true
            showPercentiles = true
            showMethods = true
            showRequestSizes = true
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
