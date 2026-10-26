🏏 IoT & Computer Vision Third Umpire Cricket System

An advanced, low-latency, semi-automated Third Umpire and Live Scoring system designed for modern cricket matches. This project bridges hardware, real-time video streaming, and IoT protocols to simulate professional broadcast-level decision-making (such as Ultra-Edge/Snickometer acoustics, run-out reviews, and live scorecard management) using accessible hardware like ESP32-C3 and mobile phone cameras.

🌟 Key Features

Acoustic Edge Detection (Ultra-Edge style): An ESP32-C3 microcontroller paired with a piezoelectric sensor mounted on the cricket bat detects bat-ball impacts and transmits real-time vibration data over TCP.

Multi-Angle Video Feeds: Integrates mobile phone cameras to serve as live side-field and on-field feeds for replay analysis.

Real-Time Communication: Utilizes WebSockets for instant synchronization between the field cameras, sensors, and the central control panel.

Third Umpire Dashboard: A dedicated control interface allowing the third umpire to review footage, analyze edge audio spikes, and manage match decisions.
Automated Match Scoring: Seamlessly updates the live scorecard based on umpire rulings and ball tracking data.

🛠️ Tech Stack

Backend / Server: Spring Boot (Java), handling REST APIs, WebSocket coordination, and core match logic.

Computer Vision & Processing: Python (for video stream handling, frame buffering, and optional computer vision modules).

Microcontroller & IoT Hardware:

ESP32-C3 Wi-Fi/BLE chip embedded on the cricket bat.

Piezoelectric sensor for impact/vibration detection.

Protocols:

TCP: For reliable, low-latency transmission of sensor vibration packets from the ESP32-C3 to the server.

WebSockets: For real-time, bi-directional communication with the third umpire web client and camera feeds.


