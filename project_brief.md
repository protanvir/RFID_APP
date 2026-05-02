Product Requirements Document: TAS RACE TIMING

1. Project Overview

RACE_TIMING_OS is a high-performance Android application designed specifically for professional race timing professionals using the Chainway C72 RFID sled. The app prioritizes rugged reliability, extreme legibility in outdoor conditions, and low-latency data handling.

App UI and design.md can be found here check all sub directories  

D:\c72\docs_sdk\screen\ui\



2. Target Audience & Hardware

Primary Users: Race timers, event organizers, and athletic officials.

Hardware Platform: Chainway C72 (Android-based RFID handheld reader).

Environment: Outdoor race tracks, finish lines, and checkpoints with varying lighting conditions.

3. Design System & Visual Language

Name: Industrial Performance Interface
Theme: High-contrast Dark Mode (optimized for OLED/outdoor visibility).
Primary Color: #FFD700 (Performance Yellow) for critical data.
Background: Deep Charcoal/Black (#110e05) to reduce glare and battery consumption.
Typography: Inter (Bold/Black weights) for maximum glanceability.

4. Key Functional Requirements

4.1. Live Reads Dashboard
Session Timer: Large, high-visibility elapsed time display.
Data Counters: Real-time tracking of 'Total Reads' and 'Unique Tags'.
Live Feed: Scrollable list of recent tag reads including Bib #, Time, and Signal Strength (RSSI).
Control: Large, easy-to-hit 'Start/Stop Scanning' button (Red/Green states).

4.2. Device Configuration (Settings)
RFID Power Level: Variable control from 0 dBm to 30 dBm with presets.
Filtering: EPC Prefix filtering to ignore non-race tags.
Connectivity: Configuration for external server URL and Port for data offloading.

Diagnostics: One-touch 'Test Connection' functionality.

4.3. Hardware Monitoring
Real-time Stats: Monitoring of module temperature and battery health.
Status Indicators: RFID activity, recording state, and battery percentage.

5. User Flows

The Race Start: Official configures server connection and power levels -> Navigates to Dashboard -> Hits 'Start Scanning' as the first wave begins.
The Live Monitor: Official monitors RSSI levels and unique counts to ensure the hardware is correctly positioned and picking up all participants.
The Configuration: Official adjusts power levels on-the-fly to balance battery life vs. read distance.

6. Success Metrics
Glanceability: Information should be readable from 3 feet away in direct sunlight.
Reliability: Zero data loss during sustained scanning sessions.
Usability: Critical controls accessible within a single tap.

7. Next Development Pipeline

Add features to configure external API server connection and send Data to External API server 

WICLAX
[https://](https://wiclax.com/documents/Generic-acquisition-protocol-for-Wiclax.pdf)

RUNONRUFUS
D:\c72\docs_sdk\rufus-cloud-public-api.yaml

