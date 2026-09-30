# Changelog

## 2.0.0

Implements [avohq/spec-first-inspector-server-sdk](https://github.com/avohq/spec-first-inspector-server-sdk) v3.0.1: the `/inspector/v2/track` endpoint with the `api-key`, `env` and `X-Avo-Client` headers, gzip for bodies of 1 KiB or more, batching with `flush()` and `destroy()`, stream ids and gateway options (`TrackOptions`). This is a breaking release; see [Upgrading from 1.x to 2.0](README.md#upgrading-from-1x-to-20) in the README. Every existing constructor and method is still there.

New API:

- **`AvoInspectorOptions`** builder and `new AvoInspector(AvoInspectorOptions)`, for the batching options (`batchSize`, `batchFlushSeconds`, `maxQueueSize`, `disableBatchTimer`) and an env given as a string.
- **`trackSchemaFromEvent(eventName, properties, streamId, TrackOptions)`**, for `Map` and `JSONObject` properties, plus an overload that also takes an `AvoInspectorTarget`.
- **`flush()`, `flush(timeoutMs)` and `destroy()`.** A negative timeout means the 10-second default.
- **The `Inspector` interface declares the new methods as default methods**, so your own `Inspector` implementations keep compiling. The defaults ignore the stream id and options and delegate to the 1.x methods; `flush()` and `destroy()` do nothing.
- **`AvoInspectorVersion.VERSION` and `SPEC_VERSION`.** `libVersion` on the wire comes from `VERSION`.
- **The jar declares `Automatic-Module-Name: is.avo.inspector`** for consumers on the module path.

Wire and schema:

- **One event object per tracked event.** The `sessionStarted` element and the `sessionId` and `avoFunction` fields are gone.
- **Lists are typed from their first element** (`list(int)`), with the element types listed as `children`, and property order follows the input. `0.0` is `float`.
- **Schema extraction is bounded.** It stops at 10 levels of nesting and at a map, list or array that contains itself; such a value is reported as `"object"` with no children (as a list element, the type string `"object"`). In 1.x a cyclic payload overflowed the stack. A primitive array is typed from its component type without walking its elements.
- **`AvoEventSchemaType` `toString()`, `equals()` and `hashCode()` keep their 1.x results**, including the 1.x names for typed arrays and for values 1.x did not recognise (`BigDecimal`, `Set`, a nested `JSONObject` and so on). The wire and the logs use the new type names.

Delivery and robustness:

- **Events are buffered outside dev** and sent when `batchSize` events are queued, when the oldest is `batchFlushSeconds` old, or on `flush()`. Dev sends every event immediately. When more than `maxQueueSize` events are buffered the oldest are dropped. `batchSize` larger than `maxQueueSize` prints a warning.
- **One shared pool of 16 daemon threads sends for every instance**, and each instance runs at most 4 sends at once, so creating many instances cannot exhaust threads. A send that cannot be started is dropped and logged, never left pending. Sends waiting their turn hold at most `maxQueueSize` events; past that the oldest waiting sends are dropped. Failed requests are never retried.
- **Each request has a 10-second budget**, covering connect, write and read.
- **Redirects are not followed.** A 3xx counts as a failed response, so the `api-key` header is never forwarded to another host.
- **The API key is checked for control characters**, in the constructor, in `AvoInspectorTarget` and again before each send. A key with a control character other than tab is refused, and the send is dropped rather than the key rewritten. Blank means empty or Unicode whitespace only.
- **`AvoInspectorTarget` validates its arguments** at construction, with the same messages as the `AvoInspector` constructor: the API key, a non-null app name, and a non-blank app version.
- **A shutdown hook flushes pending events on a normal exit or SIGTERM**, for up to 10 seconds. It is registered while some instance has buffered or in-flight events, and removed a few seconds after they have all been sent (or at once by `destroy()`). An instance with pending events is kept reachable until they are sent; an idle one can be garbage-collected without `destroy()`. The SDK's threads are daemon threads that exit when idle, so the SDK never holds the JVM open and leaves no hook or thread behind once idle.
- **The sampling rate changes only on a 200 that carries a numeric `samplingRate` in [0, 1].** A `{"success":false}` response no longer throws on the send thread.
- **Failed sends and internal errors are always printed to stderr**, whatever `enableLogging`; the API key is never logged. The dev log line "Saved event" is now "Queued event", printed only for events that pass sampling, with the schema as the JSON sent on the wire.

Build:

- Built with Gradle 8.12 and compiled for Java 8 (`--release 8` on newer JDKs). The JUnit 4 tests now actually run.
- **`org.jetbrains:annotations` is no longer a runtime dependency.** Its annotations are only needed to compile the SDK, so the published POM lists just `org.json`.
