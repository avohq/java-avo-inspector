package is.avo.inspector;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

class AvoNetworkCallsHandler {

    String envName;

    volatile double samplingRate = 1.0;
    Consumer<List<Map<String, Object>>> reportToInspector = new Consumer<>() {
        @Override
        public void accept(List<Map<String, Object>> data) {
            try {
                URL apiUrl = new URL("https://api.avo.app/inspector/v1/track");

                HttpsURLConnection connection = null;
                try {
                    connection = (HttpsURLConnection) apiUrl.openConnection();

                    connection.setRequestMethod("POST");
                    connection.setDoInput(true);
                    connection.setDoOutput(true);

                    writeTrackingCallHeader(connection);
                    writeTrackingCallBody(data, connection);

                    connection.connect();

                    final int responseCode = connection.getResponseCode();
                    if (responseCode != HttpsURLConnection.HTTP_OK) {
                        if (AvoInspector.isLogging()) {
                            System.err.println("AvoInspector: Failed with code " + responseCode);
                        }
                    } else {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        //noinspection TryFinallyCanBeTryWithResources
                        try {
                            StringBuilder response = new StringBuilder();
                            String inputLine = reader.readLine();
                            while (inputLine != null) {
                                response.append(inputLine);
                                inputLine = reader.readLine();
                            }
                            JSONObject json;
                            try {
                                json = new JSONObject(response.toString());
                            } catch (JSONException e) {
                                json = new JSONObject();
                            }

                            final JSONObject finalJson = json;
                            samplingRate = finalJson.getDouble("samplingRate");
                        } finally {
                            reader.close();
                        }
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            } catch (IOException e) {
                if (AvoInspector.isLogging()) {
                    System.err.println("AvoInspector: Failed to perform network call, will retry later");
                }
            } catch (Exception e) {
                Util.handleException(e, envName);
            }
        }
    };

    AvoNetworkCallsHandler(String envName) {
        this.envName = envName;
    }

    @SuppressWarnings("Convert2Lambda")
    void reportInspectorWithBatchBody(final List<Map<String, Object>> data) {
        if (Math.random() > samplingRate) {
            if (AvoInspector.isLogging()) {
                System.out.println("Avo Inspector: Last event schema dropped due to sampling rate");
            }
            return;
        }

        for (Map<String, Object> item : data) {
            item.put("samplingRate", samplingRate);
        }

        if (AvoInspector.isLogging()) {
            for (Map<String, Object> item : data) {
                Object type = item.get("type");

                if (type != null && type.equals("sessionStarted")) {
                    System.out.println("Avo Inspector: Sending session started event");
                } else if (type != null && type.equals("event")) {
                    Object eventName = item.get("eventName");
                    Object eventProps = item.get("eventProperties");

                    if (eventName != null && eventProps != null) {
                        System.out.println("Avo Inspector: Sending event " + eventName + " with schema {\n" + eventProps + "\n}");
                    }
                } else {
                    System.err.println("Avo Inspector: Error! Unknown event type.");
                }
            }
        }

        //reportToInspector =


//                new Runnable() {
//            @Override
//            @SuppressWarnings("UseSpecificCatch")
//            public void run() {
//                try {
//                    URL apiUrl = new URL("https://api.avo.app/inspector/v1/track");
//
//                    HttpsURLConnection connection = null;
//                    try {
//                        connection = (HttpsURLConnection) apiUrl.openConnection();
//
//                        connection.setRequestMethod("POST");
//                        connection.setDoInput(true);
//                        connection.setDoOutput(true);
//
//                        writeTrackingCallHeader(connection);
//                        writeTrackingCallBody(data, connection);
//
//                        connection.connect();
//
//                        final int responseCode = connection.getResponseCode();
//                        if (responseCode != HttpsURLConnection.HTTP_OK) {
//                            if (AvoInspector.isLogging()) {
//                                System.err.println("AvoInspector: Failed with code " + responseCode);
//                            }
//                        } else {
//                            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
//                            //noinspection TryFinallyCanBeTryWithResources
//                            try {
//                                StringBuilder response = new StringBuilder();
//                                String inputLine = reader.readLine();
//                                while (inputLine != null) {
//                                    response.append(inputLine);
//                                    inputLine = reader.readLine();
//                                }
//                                JSONObject json;
//                                try {
//                                    json = new JSONObject(response.toString());
//                                } catch (JSONException e) {
//                                    json = new JSONObject();
//                                }
//
//                                final JSONObject finalJson = json;
//                                samplingRate = finalJson.getDouble("samplingRate");
//                            } finally {
//                                reader.close();
//                            }
//                        }
//                    } finally {
//                        if (connection != null) {
//                            connection.disconnect();
//                        }
//                    }
//                } catch (IOException e) {
//                    if (AvoInspector.isLogging()) {
//                        System.err.println("AvoInspector: Failed to perform network call, will retry later");
//                    }
//                } catch (Exception e) {
//                    Util.handleException(e, envName);
//                }
//            }
       // };
        new Thread(new Runnable() {
            @Override
            public void run() {
                reportToInspector.accept(data);
            }
        }).start();
    }

    @SuppressWarnings("ForLoopReplaceableByForEach")
    private void writeTrackingCallBody(List<Map<String, Object>> data, HttpsURLConnection connection) throws IOException {

        JSONArray body = new JSONArray();
        for (Iterator<Map<String, Object>> iterator = data.iterator(); iterator.hasNext(); ) {
            Map<String, Object> event = iterator.next();
            JSONObject eventJson = new JSONObject(event);
            body.put(eventJson);
        }
        String bodyString = body.toString();

        @SuppressWarnings("CharsetObjectCanBeUsed")
        byte[] bodyBytes = bodyString.getBytes("UTF-8");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(bodyBytes);
        }
    }

    private void writeTrackingCallHeader(HttpsURLConnection connection) {
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json");
    }
}
