# TimingSoft Codebase Help

## Overview

TimingSoft is a Java-based RFID race timing system. When a tag is read by an RFID reader, the tag data is simultaneously sent to **four destinations**:

1. **Local API** — HTTP POST to a local Node.js server
2. **Wiclax API** — HTTP GET to the Wiclax timing platform
3. **Rufus API** — HTTP POST to the RunOnRufus platform
4. **CSV Backup** — Appended to `data/tag_reads.csv`

---

## Tag Read Data Pipeline

Every tag read triggers `onTagRead()` in [src/TimingSystemGUI.java](src/TimingSystemGUI.java) (line 349) or `onTagReported()` in [src/TagReader.java](src/TagReader.java) (line 120). All four destinations are called in sequence:

### TimingSystemGUI.java — `onTagRead()` (lines 349–377)

```java
@Override
public void onTagRead(String epc, String timestamp, String readerIp, int antennaPort) {
    // Process Data pipeline
    sendToLocalApi(epc, timestamp);   // Line 351
    sendToWiclax(epc);                // Line 352
    rufusClient.sendPassing(epc, timestamp); // Line 353
    saveToCsv(epc, timestamp);        // Line 354

    SwingUtilities.invokeLater(() -> {
        int count = tableModel.getRowCount() + 1;
        tableModel.insertRow(0, new Object[] { count, epc, timestamp, readerIp });
        // ... update UI
    });
}
```

### TagReader.java — `onTagReported()` (lines 120–140)

```java
@Override
public void onTagReported(ImpinjReader reader, TagReport report) {
    List<Tag> tags = report.getTags();
    for (Tag t : tags) {
        String epc = t.getEpc().toHexString();
        System.out.println("EPC: " + epc);

        sendTagData(epc, reader.getAddress());  // Local API
        sendToWiclax(epc);                       // Wiclax API
        rufusClient.sendPassing(shortEpc(epc), rufusTimestamp); // Rufus API
        saveToCsv(epc);                          // CSV Backup
    }
}
```

---

## 1. Wiclax API Integration

### Fields — `TimingSystemGUI.java` (lines 56–58)

```java
private String wiclaxUrl;
private String wiclaxUser;
private String wiclaxDeviceId;
```

### Config Loading — `TimingSystemGUI.java` (lines 305–307)

```java
wiclaxUrl    = prop.getProperty("wiclax_url",       "http://wiclax.com:35014");
wiclaxUser   = prop.getProperty("wiclax_user",      "totalactivesports");
wiclaxDeviceId = prop.getProperty("wiclax_device_id", "tas246");
```

### `sendToWiclax()` — `TimingSystemGUI.java` (lines 458–479)

Runs on a **background thread** so it does not block the UI.

```java
private void sendToWiclax(String epc) {
    new Thread(() -> {
        try {
            if (wiclaxUser.isEmpty() || wiclaxDeviceId.isEmpty()) {
                return;
            }
            DateTimeFormatter wiclaxFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
            String timestamp = LocalDateTime.now().format(wiclaxFormatter);
            String passingData = String.format("@%s@%s", epc, timestamp);

            String query = String.format("user=%s&deviceId=%s&passings=%s",
                    wiclaxUser, wiclaxDeviceId, passingData);

            URL url = new URL(wiclaxUrl + "?" + query);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.getResponseCode();
        } catch (Exception e) {
            System.err.println("Wiclax Error: " + e.getMessage());
        }
    }).start();
}
```

### `sendToWiclax()` — `TagReader.java` (lines 174–197)

Synchronous version (no background thread) used in headless/CLI mode.

```java
private void sendToWiclax(String epc) {
    try {
        if (wiclaxUser == null || wiclaxUser.isEmpty() || wiclaxDeviceId == null || wiclaxDeviceId.isEmpty()) {
            return;
        }
        DateTimeFormatter wiclaxFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
        String timestamp = LocalDateTime.now().format(wiclaxFormatter);

        String passingData = String.format("@%s@%s", epc, timestamp);

        String query = String.format("user=%s&deviceId=%s&passings=%s",
                wiclaxUser, wiclaxDeviceId, passingData);

        String url = wiclaxUrl != null ? wiclaxUrl : "http://wiclax.com:35014";
        URL connUrl = new URL(url + "?" + query);
        HttpURLConnection conn = (HttpURLConnection) connUrl.openConnection();
        conn.setRequestMethod("GET");

        int responseCode = conn.getResponseCode();
        System.out.println("Wiclax Response: " + responseCode);
    } catch (Exception e) {
        System.err.println("Failed to send to Wiclax: " + e.getMessage());
    }
}
```

### Wiclax Request Format

| Field | Value |
|---|---|
| HTTP Method | `GET` |
| URL pattern | `{wiclax_url}?user={wiclaxUser}&deviceId={wiclaxDeviceId}&passings={passingData}` |
| Timestamp format | `yyyyMMddHHmmssSSS` (e.g. `20260502141002123`) |
| Passing data format | `@{epc}@{timestamp}` (e.g. `@12AB34CD@20260502141002123`) |

**Example full URL:**
```
http://wiclax.com:35014?user=totalactivesports&deviceId=tas246&passings=@12AB34CD@20260502141002123
```

---

## 2. Local API Integration

Sends tag data as a JSON POST to a local Node.js server.

### `sendToLocalApi()` — `TimingSystemGUI.java` (lines 434–456)

```java
private void sendToLocalApi(String epc, String timestamp) {
    new Thread(() -> {
        try {
            URL url = new URL(localApiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String jsonInputString = String.format(
                    "{\"epc\": \"%s\", \"timestamp\": \"%s\"}",
                    epc, timestamp);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            conn.getResponseCode(); // Trigger request
        } catch (Exception e) {
            System.err.println("Local API Error: " + e.getMessage());
        }
    }).start();
}
```

### Local API Request Format

| Field | Value |
|---|---|
| HTTP Method | `POST` |
| Content-Type | `application/json` |
| Body | `{"epc": "<epc>", "timestamp": "<yyyy-MM-dd HH:mm:ss.SSS>"}` |

---

## 3. Rufus API Integration

### `RufusApiClient.java` — Full Source ([src/RufusApiClient.java](src/RufusApiClient.java))

The Rufus integration is a dedicated client class with three operations:

#### `createSession()` (lines 26–69) — called on Connect

```java
public boolean createSession() {
    if (apiKey == null || apiKey.isEmpty() || deviceId == null || deviceId.isEmpty()) {
        System.err.println("Rufus API: Missing api_key or device_id, skipping session creation");
        return false;
    }

    URL url = new URL(baseUrl + "/sessions");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("api_key", apiKey);
    conn.setDoOutput(true);

    String alias = "TimingSoft-" + deviceId;
    String jsonBody = String.format("{\"deviceid\":\"%s\",\"alias\":\"%s\"}", deviceId, alias);
    // ... write body, read response, extract sessionToken
}
```

#### `sendPassing()` (lines 71–108) — called on every tag read

```java
public void sendPassing(String epc, String timestamp) {
    if (!sessionActive || sessionToken == null) return;

    new Thread(() -> {
        URL url = new URL(baseUrl + "/passings");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("api_key", apiKey);
        conn.setDoOutput(true);

        String isoTimestamp = convertToIso8601(timestamp);
        int num = passingCounter.incrementAndGet();

        String jsonBody = String.format(
            "{\"token_session\":\"%s\",\"passings_list\":[{\"timestamp\":\"%s\",\"num_passing\":%d,\"bib\":\"%s\",\"chip\":\"%s\"}]}",
            sessionToken, isoTimestamp, num, epc, epc);
        // ... write body, fire request
    }).start();
}
```

#### `closeSession()` (lines 110–133) — called on Disconnect / app close

```java
public void closeSession() {
    if (!sessionActive || sessionToken == null) return;

    URL url = new URL(baseUrl + "/sessions/close/" + sessionToken);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("PATCH");
    conn.setRequestProperty("api_key", apiKey);
    // ...
}
```

### Rufus Request Format

| Endpoint | Method | Purpose |
|---|---|---|
| `/sessions` | POST | Open a timing session |
| `/passings` | POST | Send a tag read |
| `/sessions/close/{token}` | PATCH | Close the session |

---

## 4. CSV Backup

Tag reads are also appended to `data/tag_reads.csv`.

### `saveToCsv()` — `TimingSystemGUI.java` (lines 481–495)

```java
private synchronized void saveToCsv(String epc, String timestamp) {
    try {
        File dataDir = new File("data");
        if (!dataDir.exists()) dataDir.mkdirs();
        File csvFile = new File(dataDir, "tag_reads.csv");

        try (FileWriter fw = new FileWriter(csvFile, true);
                PrintWriter pw = new PrintWriter(fw)) {
            pw.println(epc + "," + timestamp);
        }
    } catch (IOException e) {
        System.err.println("CSV Error: " + e.getMessage());
    }
}
```

**CSV row format:** `<epc>,<yyyy-MM-dd HH:mm:ss.SSS>`

---

## 5. Configuration

All endpoints and credentials are stored in [config.properties](config.properties) and editable via the Settings dialog.

### config.properties (current values)

```properties
ip=172.16.1.103
reader_type=Impinj
local_api_url=http://localhost:3000/api/tags
wiclax_url=http://wiclax.com:35014
wiclax_user=totalactivesports
wiclax_device_id=tas246
rufus_api_key=
rufus_base_url=https://api.runonrufus.com/v0
rufus_device_id=
```

### Settings UI — `SettingsDialog.java` (lines 90–112)

The "Wiclax" tab in the Settings dialog provides three editable fields:

```java
private JTextField wiclaxUrlField;
private JTextField wiclaxUserField;
private JTextField wiclaxDeviceIdField;
```

The dialog loads from and saves back to `config.properties`. After saving, `TimingSystemGUI` reloads these values live (lines 556–563):

```java
if (dialog.isSaved()) {
    wiclaxUrl      = dialog.getSettings().getProperty("wiclax_url");
    wiclaxUser     = dialog.getSettings().getProperty("wiclax_user");
    wiclaxDeviceId = dialog.getSettings().getProperty("wiclax_device_id");
    // ...
}
```

---

## File Map

| File | Role |
|---|---|
| [src/TimingSystemGUI.java](src/TimingSystemGUI.java) | Main GUI + data pipeline (`sendToWiclax`, `sendToLocalApi`, `saveToCsv`) |
| [src/TagReader.java](src/TagReader.java) | Headless CLI reader — own copy of `sendToWiclax`, `sendTagData`, `saveToCsv` |
| [src/RufusApiClient.java](src/RufusApiClient.java) | Dedicated Rufus API client (`createSession`, `sendPassing`, `closeSession`) |
| [src/SettingsDialog.java](src/SettingsDialog.java) | Settings UI — Wiclax/Rufus/Local tabs, loads & saves `config.properties` |
| [config.properties](config.properties) | Runtime configuration for all endpoints and credentials |
