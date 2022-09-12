# Avo Inspector SDK for Java

[![](https://jitpack.io/v/avohq/java-avo-inspector.svg)](https://jitpack.io/#avohq/java-avo-inspector)

# Avo documentation

This is a quick start guide.
For more information about the Inspector project please read [Avo documentation](https://www.avo.app/docs/implementation/inspector/sdk/java).

# Installation

We host the library on JitPack.io, so

add the following to the build.gradle file:

```
    repositories {
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

# Enabling logs

Logs are enabled by default in the dev mode and disabled in prod mode based on the init flag.

```java
AvoInspector.enableLogging(true);
```

# Sending event schemas for events reported outside of Avo Functions

Whenever you send tracking event call one of the following methods:

Read more in the [Avo documentation](https://www.avo.app/docs/implementation/devs-101#inspecting-events)

### 1.

This methods get actual tracking event parameters, extract schema automatically and send it to the Avo Inspector backend.
It is the easiest way to use the library, just call this method at the same place you call your analytics tools' track methods with the same parameters.

```java
avoInspector.trackSchemaFromEvent("Event name", new HashMap<String, Object>() {{
                        put("String Prop", "Prop Value");
                        put("Float Name", 1.0);
                        put("Bool Name", true);
                    }});
```
Second parameter can also be a `JSONObject`.

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

## Author

Avo (https://www.avo.app), friends@avo.app

## License

AvoInspector is available under the MIT license.
