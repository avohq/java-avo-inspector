# Avo Inspector SDK for Java

[![](https://jitpack.io/v/avohq/java-avo-inspector.svg)](https://jitpack.io/#avohq/java-avo-inspector)

# Avo documentation

This is a quick start guide.
For more information about the Inspector project please read [Avo documentation](https://www.avo.app/docs/implementation/inspector/sdk/java).

Implements [avohq/spec-first-inspector-server-sdk](https://github.com/avohq/spec-first-inspector-server-sdk) v3.0.1
(`AvoInspectorVersion.SPEC_VERSION`). Requires Java 8 or newer.

# Installation

We host the library on JitPack.io, so

add the following to the build.gradle file:

```
    repositories {
      mavenCentral()
      ...
      maven { url 'https://jitpack.io' }
    }
```

and:

```
    dependencies {
        implementation 'com.github.avohq.java-avo-inspector:TAG'
    }
```

Use the latest GitHub release tag to get the latest version of the library.

# Initialization

Obtain the API key at [Avo.app](https://www.avo.app/welcome)

```java
import is.avo.inspector.AvoInspector;
import is.avo.inspector.AvoInspectorEnv;

AvoInspector avoInspector = new AvoInspector("MY_API_KEY", "1.0.0", "My App Name", AvoInspectorEnv.Dev);
```

Or with the options builder, which also takes the batching options:

```java
import is.avo.inspector.AvoInspectorOptions;

AvoInspector avoInspector = new AvoInspector(AvoInspectorOptions.builder()
        .apiKey("MY_API_KEY")
        .env(AvoInspectorEnv.Prod)
        .appVersion("1.0.0")
        .appName("My App Name")       // optional, defaults to ""
        .batchSize(30)                // optional, default 30; always 1 in dev
        .batchFlushSeconds(30)        // optional, default 30
        .maxQueueSize(1000)           // optional, default 1000
        .disableBatchTimer(false)     // optional, default false; set true in serverless
        .build());
```

Both constructors throw `IllegalArgumentException` when the API key is blank or contains a CR, LF or
NUL character, or when the app version is blank. A missing or unknown env falls back to dev with a
warning.

# Enabling logs

Logs are enabled by default in the dev mode and disabled in prod mode based on the init flag.
The flag is process-wide: it applies to every instance. Do not enable it in production.

```java
AvoInspector.enableLogging(true);
```

# Sending event schemas

Whenever you send tracking event call one of the following methods.

Example usage:

```java
void trackAppOpened(Map<String, ?> appOpenedEventParams) {
    tracker.track("App Opened", appOpenedEventParams);
    this.avoInspector.trackSchemaFromEvent("App Opened", appOpenedEventParams);
}
```

Read more in the [Avo documentation](https://www.avo.app/docs/implementation/devs-101#inspecting-events)

### 1.

These methods get actual tracking event parameters, extract schema automatically and send it to the Avo Inspector backend.
It is the easiest way to use the library, just call this method at the same place you call your analytics tools' track methods with the same parameters.

```java
avoInspector.trackSchemaFromEvent("Event name", new HashMap<String, Object>() {{
                        put("String Prop", "Prop Value");
                        put("Float Name", 1.0);
                        put("Bool Name", true);
                    }});
```
Second parameter can also be a `JSONObject`.

The method returns the extracted schema as soon as the event is queued; it never waits for the
network. Property order follows the map's iteration order, so use a `LinkedHashMap` if order
matters to you (a `JSONObject` does not keep insertion order).

#### Stream id and gateway options

Pass a stream id (any correlation id you choose; `null` sends `""`) and, when you use a
gateway-scoped API key, the gateway coordinates. Java has no named arguments, so the three
coordinates are grouped in one `TrackOptions` object:

```java
avoInspector.trackSchemaFromEvent("Purchase", properties, "stream-id", TrackOptions.builder()
        .outputReference("meta-x7k2q")   // gateway output the event was bound for; omit for the gateway checkpoint
        .originHint("android")           // low-cardinality source label; never a user id
        .originAppVersion("4.2.0")       // that source's app version
        .build());
```

Values are trimmed and blank values are ignored. When `originHint` is set without
`originAppVersion`, the event is sent with a `null` app version (the instance's version belongs to
a different source).

#### Override Avo source

You can track a schema for an Avo source different from the one you've initialised Avo Inspector instance with by providing an additional `AvoInspectorTarget` parameter to the `trackSchemaFromEvent` call.

```java
avoInspector.trackSchemaFromEvent("Event name", new HashMap<String, Object>() {{
                        put("String Prop", "Prop Value");
                        put("Float Name", 1.0);
                        put("Bool Name", true);
                    }}, new AvoInspectorTarget("Another-Api-Key", "Another-App-Name", "Another-App-Version"));
```

Events for different targets are sent in separate requests.

### 2.

If you prefer to extract data schema manually you would use this method to send the extracted event schema.

```java
avoInspector.trackSchema("Event name", new HashMap<String, AvoEventSchemaType>() {{
            put("String Prop", new AvoEventSchemaType.AvoString());
            put("Float Name", new AvoEventSchemaType.AvoFloat());
            put("Bool Name", new AvoEventSchemaType.AvoBoolean());
        }});
```

# Extracting event schema manually

```java
Map<String, AvoEventSchemaType> schema = avoInspector.extractSchema(new HashMap<String, Object>() {{
            put("String Prop", "Prop Value");
            put("Float Name", 1.0);
            put("Bool Name", true);
        }});
```

`extractSchema` never throws and makes no network calls.

# Batching, flush and shutdown

Outside dev, events are buffered in memory and sent in batches: when `batchSize` events are
buffered, when the oldest buffered event is `batchFlushSeconds` old, or when you call `flush()`.
In dev every event is sent immediately. When more than `maxQueueSize` events are buffered the
oldest are dropped.

Delivery is at-most-once. Nothing is written to disk, failed requests are not retried, and the
background threads are daemon threads that do not keep the JVM alive. **Call `flush()` before your
process exits, and before a serverless handler returns**, or buffered and in-flight events are lost:

```java
avoInspector.flush();        // sends the buffer, waits up to 10 seconds for in-flight requests
avoInspector.flush(2000);    // custom timeout in milliseconds
```

`flush()` never throws, and the instance stays usable afterwards. In serverless functions also
set `disableBatchTimer(true)`.

`destroy()` stops the instance: buffered events are discarded unsent, in-flight requests are
abandoned and later track calls do nothing.

The SDK is safe to use from multiple threads.

# Conformance

`./scripts/run-conformance.sh` builds the conformance harness (runner contract 1.1.0) and runs the
spec's conformance suite against it. Set `SPEC_DIR` to use a local checkout of the spec repository
instead of fetching it into `.spec-repo/`. Requires Node.js.

# Releasing

Update `AvoInspectorVersion.VERSION` together with `version` in `build.gradle` on every release;
the build fails when they differ. The version is sent with every event as `libVersion`.

## Author

Avo (https://www.avo.app), friends@avo.app

## License

AvoInspector is available under the MIT license.
