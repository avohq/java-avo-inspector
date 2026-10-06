# Changelog

## 2.0.0

Implements [avohq/spec-first-inspector-server-sdk](https://github.com/avohq/spec-first-inspector-server-sdk) v3.0.1: the `/inspector/v2/track` endpoint with the `api-key`, `env` and `X-Avo-Client` headers, gzip for bodies of 1 KiB or more, batching with `flush()` and `destroy()`, stream ids and gateway options (`TrackOptions`). This is a breaking release; see [Upgrading from 1.x to 2.0](README.md#upgrading-from-1x-to-20) in the README. Every existing constructor and method is still there.

New API:

- **`AvoInspectorOptions`** builder and `new AvoInspector(AvoInspectorOptions)`, for the batching options (`batchSize`, `batchFlushSeconds`, `maxQueueSize`, `disableBatchTimer`) and an env given as a string.
- **`trackSchemaFromEvent(eventName, properties, streamId, TrackOptions)`**, for `Map` and `JSONObject` properties, plus an overload that also takes an `AvoInspectorTarget`.
- **`flush()`, `flush(timeoutMs)` and `destroy()`.** A negative timeout means the 10-second default. `flush` returns `true` if the instance has nothing buffered, waiting or in flight when it returns, and `false` if the timeout ran out first (`flush(0)` starts the sends and returns `true` only if nothing was pending; after `destroy()` it returns `true`). It never throws.
- **The `Inspector` interface declares the new methods as default methods**, so your own `Inspector` implementations keep compiling. The defaults ignore the stream id and options and delegate to the 1.x methods; `flush()` and `destroy()` do nothing, and the `flush` defaults return `true`.
- **A custom `Inspector` that declared its own `void flush()` (or `flush(long)`) no longer compiles**, because the interface now declares `boolean flush()` and `boolean flush(long)`. Change it to return `boolean`: `true` when everything was sent.
- **`AvoInspectorVersion.VERSION` and `SPEC_VERSION`.** `libVersion` on the wire comes from `VERSION`.
- **The jar declares `Automatic-Module-Name: is.avo.inspector`** for consumers on the module path.

Wire and schema:

- **One event object per tracked event.** The `sessionStarted` element and the `sessionId` and `avoFunction` fields are gone.
- **Lists are typed from their first element** (`list(int)`), with the element types listed as `children`, and property order follows the input. `0.0` is `float`.
- **Schema extraction is bounded.** It stops at 10 levels of nesting, at a map, list or array that contains itself, and after 10,000 expanded maps and lists per event (a value shared by many properties would otherwise expand exponentially), the same limits as the Node SDK; such a value is reported as `"object"` with no children (as a list element, the type string `"object"`). In 1.x a cyclic payload overflowed the stack. A primitive array is typed from its component type without walking its elements.
- **`AvoEventSchemaType` `toString()`, `equals()` and `hashCode()` keep their 1.x results**, including the 1.x names for typed arrays and for values 1.x did not recognise (`BigDecimal`, `Set`, a nested `JSONObject` and so on). The wire and the logs use the new type names.

Delivery and robustness:

- **Events are buffered outside dev** and sent when `batchSize` events are queued, when the oldest is `batchFlushSeconds` old, or on `flush()`. Dev sends every event immediately. When more than `maxQueueSize` events are buffered the oldest are dropped. `batchSize` larger than `maxQueueSize` prints a warning.
- **One shared pool of 16 daemon threads sends for every instance**, and each instance runs at most 4 sends at once, so creating many instances cannot exhaust threads. A send that cannot be started, or that fails with an `Error`, is dropped and reported as `dropped N event(s) (internal error)` together with the internal-error line, never left pending or silent. Batches waiting their turn may hold up to 10,000 events, separately from `maxQueueSize`, which bounds only the unsent buffer; past that the oldest waiting events are dropped. Failed requests are never retried. See "High-volume and backfill scripts" in the README.
- **Each target batches separately.** Events for different `AvoInspectorTarget`s (API key and app name) have their own buffers, so each target's batches fill to `batchSize` instead of splitting a mixed batch into many small requests. `maxQueueSize` bounds the total. At most 100 targets are buffered at once; a new one sends the least recently used target's buffer first, so nothing is dropped and track calls stay fast with thousands of targets.
- **Each request has a 10-second budget**, covering connect, write and read.
- **Redirects are not followed.** A 3xx counts as a failed response, so the `api-key` header is never forwarded to another host.
- **The API key is checked for control characters**, in the constructor, in `AvoInspectorTarget` and again before each send. A key with a control character other than tab is refused, and the send is dropped rather than the key rewritten. Blank means empty or Unicode whitespace only.
- **`AvoInspectorTarget` validates its arguments** at construction, with the same messages as the `AvoInspector` constructor: the API key, a non-null app name, and a non-blank app version.
- **A shutdown hook flushes pending events on a normal exit or SIGTERM**, for up to 10 seconds. It is registered while some instance has buffered or in-flight events, and removed a few seconds after they have all been sent (or at once by `destroy()`). An instance with pending events is kept reachable until they are sent; an idle one can be garbage-collected without `destroy()`. The SDK's threads are daemon threads that exit when idle, so the SDK never holds the JVM open and leaves no hook or thread behind once idle.
- **Undeploying without `destroy()` leaves nothing behind.** Every class the SDK's own threads need is loaded up front, so sends and timers still work after a webapp's class loader is closed, and the old class loader becomes collectable a few seconds after the last send. Call `destroy()` on undeploy anyway (see the README).
- **Logs never contain property values.** The dev "Supplied event … with params" line printed raw values (emails, say), and because the logging flag is process-wide a dev instance could make prod instances print them. Logs now show event names, property names and types only, and an internal error is logged with the exception's class name only, never its message (which could quote a value).
- **Logging never changes whether an event is tracked.** A property whose `toString()` throws no longer makes a dev track call throw from the log line.
- **An event without a name is sent as `Missing Event Name`.** A `null`, empty or whitespace-only event name used to go out without its `eventName` field; the event is now sent like any other under that placeholder, its schema is returned, and a warning is printed at most once every 10 seconds. It never throws, in dev either.
- **Nothing escapes a track call outside dev**, not even an `Error` such as `StackOverflowError` raised while handling an event: the call logs it and returns an empty schema. In dev it is rethrown as the documented `RuntimeException`. An interrupt raised inside the SDK leaves the thread's interrupt flag set.
- **The sampling rate changes only on a 200 that carries a numeric `samplingRate` in [0, 1].** A `{"success":false}` response no longer throws on the send thread.
- **Failed sends, dropped events, non-200 responses and internal errors are always printed to stderr**, whatever `enableLogging`, each kind at most once every 10 seconds; the count of what was suppressed is carried into the next line of that kind, or printed by `flush()` once its 10 seconds have passed and right away by `destroy()` and the shutdown hook, with the real number of seconds it covers (the same rule as the Node and Go SDKs, with no timer); the `:`-in-stream-id warning is limited the same way; the API key and request bodies are never logged. The dev log line "Saved event" is now "Queued event", printed only for events that pass sampling, with the schema as the JSON sent on the wire.

Build:

- Built with Gradle 8.12 and compiled for Java 8 (`--release 8` on newer JDKs). The JUnit 4 tests now actually run.
- **`org.jetbrains:annotations` is no longer a runtime dependency.** Its annotations are only needed to compile the SDK, so the published POM lists just `org.json`.
