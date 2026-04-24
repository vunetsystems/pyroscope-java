# Pyroscope Java Agent

Continuous profiling agent for Java applications. Integrates with [Pyroscope](https://pyroscope.io) / [Grafana Profiles](https://grafana.com/products/cloud/profiles-for-continuous-profiling/) to collect CPU, allocation, and lock contention profiles with minimal overhead.

Built on top of [async-profiler](https://github.com/jvm-profiling-tools/async-profiler).

---

## Table of Contents

- [Features](#features)
- [Distribution](#distribution)
- [Quick Start](#quick-start)
- [Installation](#installation)
- [Configuration](#configuration)
- [Labels & Context](#labels--context)
- [OpenTelemetry Integration](#opentelemetry-integration)
- [Profiler Types](#profiler-types)
- [Building from Source](#building-from-source)
- [Examples](#examples)
- [Maintainers](#maintainers)

---

## Features

- **CPU profiling** via async-profiler (Linux x64/ARM64, macOS x64/ARM64)
- **Windows support** via JVM Flight Recorder (JFR)
- **Allocation profiling** — track heap allocations above a configurable threshold
- **Lock contention profiling** — identify threads blocked on monitors
- **Dynamic labels** — annotate profiles with per-request metadata (user ID, request ID, etc.)
- **OpenTelemetry integration** — correlate profiles with traces via `trace_id` / `span_id`
- **Zero-code-change setup** — attach as a JVM agent with no application modifications needed
- **Programmatic API** — start/stop profiling and set labels from application code

---

## Distribution

The agent is distributed as a single JAR file `pyroscope.jar` that bundles native async-profiler libraries for:

- Linux x64
- Linux ARM64
- macOS x64
- macOS ARM64

On Windows, the agent falls back to the JVM built-in JFR profiler automatically.

Download the latest release from the [releases page](https://github.com/pyroscope-io/pyroscope-java/releases).

---

## Quick Start

```bash
java -javaagent:pyroscope.jar \
  -DPYROSCOPE_APPLICATION_NAME=my-app \
  -DPYROSCOPE_SERVER_ADDRESS=http://localhost:4040 \
  -jar myapp.jar
```

That's it — the agent starts profiling and uploads data to your Pyroscope server every 10 seconds.

---

## Installation

### As a JVM Agent

Attach the agent at startup using the `-javaagent` flag:

```bash
java -javaagent:/path/to/pyroscope.jar \
  -DPYROSCOPE_APPLICATION_NAME=my-service \
  -DPYROSCOPE_SERVER_ADDRESS=http://pyroscope:4040 \
  -jar myapp.jar
```

### Using a Properties File

Create a `pyroscope.properties` file:

```properties
PYROSCOPE_APPLICATION_NAME=my-service
PYROSCOPE_SERVER_ADDRESS=http://localhost:4040
PYROSCOPE_PROFILER_EVENT=itimer
PYROSCOPE_PROFILING_INTERVAL=10ms
PYROSCOPE_UPLOAD_INTERVAL=10s
PYROSCOPE_LOG_LEVEL=info
```

Then reference it at startup:

```bash
java -javaagent:pyroscope.jar \
  -Dpyroscope.config=/path/to/pyroscope.properties \
  -jar myapp.jar
```

### Programmatic API

```java
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.javaagent.EventType;

Config config = new Config.Builder()
    .setApplicationName("my-service")
    .setServerAddress("http://localhost:4040")
    .setProfilingEvent(EventType.ITIMER)
    .build();

PyroscopeAgent.start(config);

// ... your application runs ...

PyroscopeAgent.stop();
```

---

## Configuration

Configuration is resolved in the following priority order:
1. System properties (`-Dproperty=value`)
2. Environment variables (`PYROSCOPE_*`)
3. Properties file (`-Dpyroscope.config=/path/to/file`)
4. Defaults

### Core Settings

| Variable | Default | Description |
|---|---|---|
| `PYROSCOPE_APPLICATION_NAME` | *(required)* | Application name shown in Pyroscope UI |
| `PYROSCOPE_SERVER_ADDRESS` | `http://localhost:4040` | Pyroscope server endpoint |
| `PYROSCOPE_AGENT_ENABLED` | `true` | Enable or disable the agent |
| `PYROSCOPE_LOG_LEVEL` | `info` | Log verbosity: `debug`, `info`, `warn`, `error` |

### Profiling Settings

| Variable | Default | Description |
|---|---|---|
| `PYROSCOPE_PROFILER_TYPE` | `ASYNC` | Profiler backend: `ASYNC` or `JFR` |
| `PYROSCOPE_PROFILER_EVENT` | `ITIMER` | CPU profiling event: `ITIMER`, `CTIMER`, `CPU` |
| `PYROSCOPE_PROFILING_INTERVAL` | `10ms` | Sampling frequency |
| `PYROSCOPE_PROFILER_ALLOC` | *(disabled)* | Allocation tracking threshold (e.g. `512k`) |
| `PYROSCOPE_PROFILER_LOCK` | *(disabled)* | Lock contention threshold (e.g. `10ms`) |
| `PYROSCOPE_JAVA_STACK_DEPTH_MAX` | `2048` | Maximum stack trace depth |

### Export Settings

| Variable | Default | Description |
|---|---|---|
| `PYROSCOPE_UPLOAD_INTERVAL` | `10s` | How often to upload profiles |
| `PYROSCOPE_FORMAT` | `JFR` | Export format: `JFR` or `PPROF` |
| `PYROSCOPE_PUSH_QUEUE_CAPACITY` | `8` | In-memory snapshot buffer size |
| `PYROSCOPE_INGEST_MAX_TRIES` | `8` | Upload retry attempts (exponential backoff) |
| `PYROSCOPE_PROFILE_EXPORT_TIMEOUT` | `10s` | Timeout per upload request |

### Authentication

| Variable | Default | Description |
|---|---|---|
| `PYROSCOPE_AUTH_TOKEN` | *(optional)* | Bearer token for Grafana Cloud |
| `PYROSCOPE_BASIC_AUTH_USER` | *(optional)* | Basic auth username |
| `PYROSCOPE_BASIC_AUTH_PASSWORD` | *(optional)* | Basic auth password |

### Static Labels

Attach fixed key-value metadata to all profiles from this instance:

```bash
-DPYROSCOPE_LABELS=env=production,region=us-east-1,version=2.1.0
```

---

## Labels & Context

Labels allow you to slice profiles by arbitrary dimensions in the Pyroscope UI — for example, by request ID, user ID, or endpoint.

### Static Labels

Set once at startup, applied to all profiles:

```java
import io.pyroscope.labels.v2.Pyroscope;

Pyroscope.setStaticLabels(Map.of(
    "region", "us-east-1",
    "version", "2.1.0"
));
```

### Scoped Context (Per-Request)

Use `ScopedContext` to attach labels for the duration of a code block. Labels are removed automatically when the block exits.

```java
import io.pyroscope.labels.v2.ScopedContext;
import io.pyroscope.labels.v2.LabelsSet;

// Try-with-resources (recommended)
try (ScopedContext ctx = new ScopedContext(
        new LabelsSet("request_id", "abc123", "user_id", "42"))) {
    handleRequest();
} // labels removed here

// Or with a lambda
Pyroscope.LabelsWrapper.run(
    new LabelsSet("endpoint", "/checkout"),
    () -> processCheckout()
);
```

### Constant Context (Long-lived / High-cardinality)

For contexts that outlive a single request:

```java
ConstantContext ctx = Pyroscope.LabelsWrapper.registerConstant("worker-thread-1");
// ... later ...
ctx.close();
```

---

## OpenTelemetry Integration

The agent can inject `trace_id` and `span_id` into profiles so you can jump from a slow trace span directly to the matching flame graph in Pyroscope.

### Setup

Download `pyroscope-otel-extension.jar` from the [releases page](https://github.com/pyroscope-io/pyroscope-java/releases), then add it to the OpenTelemetry Java agent:

```bash
java \
  -javaagent:opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=pyroscope-otel-extension.jar \
  -DPYROSCOPE_APPLICATION_NAME=my-traced-service \
  -DPYROSCOPE_SERVER_ADDRESS=http://pyroscope:4040 \
  -jar myapp.jar
```

No code changes are required. The `PyroscopeSpanProcessor` automatically injects trace context into each profile sample.

### Manual Setup (without OTel agent)

If you manage spans manually:

```java
import io.pyroscope.otel.PyroscopeSpanProcessor;

SdkTracerProvider provider = SdkTracerProvider.builder()
    .addSpanProcessor(new PyroscopeSpanProcessor())
    .build();
```

---

## Profiler Types

### ASYNC (default — Linux & macOS)

Uses async-profiler via native JNI. Provides accurate CPU profiling without safepoint bias. Supports:
- `ITIMER` — interval timer (default, works without root)
- `CTIMER` — CPU timer (better accuracy on some systems)
- `CPU` — perf_events based (requires `perf_event_paranoid=1`)

### JFR (Windows & fallback)

Uses the JVM built-in Java Flight Recorder. Works on all platforms but requires Java 11+. Enable explicitly:

```bash
-DPYROSCOPE_PROFILER_TYPE=JFR
```

You can supply a custom JFR configuration file:

```bash
-DPYROSCOPE_JFR_PROFILER_SETTINGS=/path/to/custom.jfc
```

---

## Building from Source

### Prerequisites

- JDK 8+ (JDK 11+ required for the demo/OTel module)
- Gradle (wrapper included)

### Build the Agent JAR

```bash
./gradlew shadowJar
```

Output: `agent/build/libs/pyroscope.jar`

### Build the OTel Extension JAR

```bash
./gradlew :demo:shadowJar
```

Output: `demo/build/libs/pyroscope-otel-extension.jar`

### Run Tests

```bash
./gradlew test
```

### Run Integration Tests

```bash
make itest
```

---

## Examples

### Docker Compose

A complete example with a demo app and Pyroscope server:

```yaml
services:
  pyroscope:
    image: grafana/pyroscope:latest
    ports:
      - "4040:4040"

  app:
    image: my-java-app
    environment:
      PYROSCOPE_APPLICATION_NAME: my-app
      PYROSCOPE_SERVER_ADDRESS: http://pyroscope:4040
      PYROSCOPE_PROFILER_EVENT: itimer
      PYROSCOPE_PROFILING_INTERVAL: 10ms
```

See the [`examples/`](./examples) directory for complete Docker Compose configurations.

### Allocation Profiling

Track heap allocations to find GC pressure:

```bash
java -javaagent:pyroscope.jar \
  -DPYROSCOPE_APPLICATION_NAME=my-app \
  -DPYROSCOPE_PROFILER_ALLOC=512k \
  -jar myapp.jar
```

### Lock Contention Profiling

Find threads blocked waiting for monitors:

```bash
java -javaagent:pyroscope.jar \
  -DPYROSCOPE_APPLICATION_NAME=my-app \
  -DPYROSCOPE_PROFILER_LOCK=10ms \
  -jar myapp.jar
```

### Grafana Cloud

```bash
java -javaagent:pyroscope.jar \
  -DPYROSCOPE_APPLICATION_NAME=my-app \
  -DPYROSCOPE_SERVER_ADDRESS=https://profiles-prod-001.grafana.net \
  -DPYROSCOPE_AUTH_TOKEN=<your-grafana-cloud-token> \
  -jar myapp.jar
```

---

## Maintainers

This package is maintained by [@grafana/pyroscope-java](https://github.com/orgs/grafana/teams/pyroscope-java).
Mention this team on issues or PRs for feedback.