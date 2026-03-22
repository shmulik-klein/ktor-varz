# VarZ for Ktor

A lightweight metrics plugin for Ktor that provides a `/varz` endpoint displaying server metrics.

## Features

- **Request metrics**: Total requests, active requests, requests per second
- **Latency metrics**: Min, max, average, and percentiles (p50, p90, p95, p99)
- **Status codes**: Breakdown of 2xx, 3xx, 4xx, 5xx responses
- **HTTP methods**: GET, POST, PUT, DELETE, PATCH counts
- **JVM stats**: Heap memory, threads, GC, CPU usage
- **Request sizes**: Average request/response bytes, slow requests, timeouts

## Endpoints

| Endpoint | Description |
|----------|-------------|
| `/varz` | HTML dashboard |
| `/varz/json` | JSON metrics |
| `/varz/prometheus` | Prometheus format |

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("klein.shmulik:ktor-varz:0.1.0")
}
```

## Usage

```kotlin
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import klein.shmulik.varz.*

fun main() {
    embeddedServer(Netty, port = 8080) {
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
                call.respondText("Hello!")
            }
        }
    }.start(wait = true)
}
```

## Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | `true` | Enable HTML endpoint |
| `jsonEnabled` | Boolean | `true` | Enable JSON endpoint |
| `prometheusEnabled` | Boolean | `false` | Enable Prometheus endpoint |
| `refreshEnabled` | Boolean | `false` | Auto-refresh HTML page |
| `refreshIntervalSeconds` | Int | `30` | Refresh interval |
| `showMemory` | Boolean | `true` | Show memory stats |
| `showThreads` | Boolean | `true` | Show thread counts |
| `showJvm` | Boolean | `true` | Show JVM stats |
| `showPercentiles` | Boolean | `true` | Show latency percentiles |
| `showMethods` | Boolean | `true` | Show HTTP method counts |
| `showRequestSizes` | Boolean | `true` | Show request size metrics |

## Metrics

### Overview
- **Total Requests**: Cumulative request count
- **RPS**: Requests per second
- **Active**: Currently processing requests
- **Uptime**: Server running time

### Latency (in milliseconds)
- **Avg**: Average response time
- **Min**: Minimum response time
- **Max**: Maximum response time
- **p50/p90/p95/p99**: Percentile latencies

### Status Codes
- **2xx**: Successful responses
- **3xx**: Redirects
- **4xx**: Client errors
- **5xx**: Server errors

## License

MIT
