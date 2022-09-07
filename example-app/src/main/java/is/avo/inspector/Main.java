package is.avo.inspector;

import java.util.Map;

public class Main {

    static AvoInspector avoInspector;

    public static void main(String[] args) {

        avoInspector = new AvoInspector("RZW8s5Zq7koXA4n9Tm2o", "10.0.0",
                "console example app", AvoInspectorEnv.Dev);

        Map<String, AvoEventSchemaType> schema = avoInspector.trackSchemaFromEvent("Hello, World",
                Map.of("A", "32", "C", "34", "T", "53"));

        System.out.println("Hello, World" + schema);
    }

}