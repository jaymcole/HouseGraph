#include "WiFiS3.h"
#include "Arduino_LED_Matrix.h"
#include <ArduinoMDNS.h>

// Animation sequences exported from https://ledmatrix-editor.arduino.cc/
// Each file defines a `const uint32_t <name>[][4]` sequence (3 words of pixel
// data + a frame duration in ms). To add/replace an animation, export it from
// the editor, save it here as <name>.h, and include it below.
#include "bird.h"
#include "squirrel.h"

// --- WiFi Credentials ---
// Defined in wifi_secrets.h (kept out of version control) as
// SECRET_SSID and SECRET_PASSWORD.
#include "wifi_secrets.h"
const char* ssid     = SECRET_SSID;
const char* password = SECRET_PASSWORD;

// --- Network Identity ---
const char* HOSTNAME = "squirrel-alarm"; // resolves as matrix-iot.local via mDNS

WiFiServer server(80);
ArduinoLEDMatrix matrix;

// mDNS responder so the device is discoverable as matrix-iot.local
WiFiUDP mdnsUdp;
MDNS mdns(mdnsUdp);

// --- State Management ---
enum State { STATE_NONE, STATE_BIRD, STATE_SQUIRREL };
State currentState = STATE_NONE;

unsigned long stateStartTime = 0;
const unsigned long DISPLAY_DURATION = 30000; // 30 seconds

// Tracks the last state we pushed to the matrix so we only reload the
// sequence on a transition (the library animates each sequence on its own).
State renderedState = STATE_NONE;

void setup() {
  Serial.begin(115200);
  matrix.begin();

  // Set the local hostname before connecting to network (used for DHCP)
  WiFi.setHostname(HOSTNAME);

  Serial.print("Connecting to Wi-Fi...");
  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  // IP Assignment Guard
  while (WiFi.localIP().toString() == "0.0.0.0") {
    delay(500);
    Serial.print(".");
  }

  Serial.println("\nConnected successfully!");
  Serial.print("IP Address: ");
  Serial.println(WiFi.localIP());

  // Start the mDNS responder so the device is discoverable by name.
  // Advertises an HTTP service on port 80 as matrix-iot.local
  mdns.begin(WiFi.localIP(), HOSTNAME);
  mdns.addServiceRecord("Matrix IoT._http", 80, MDNSServiceTCP);
  Serial.print("Accessible at: http://");
  Serial.print(HOSTNAME);
  Serial.println(".local/");

  server.begin();
}

void loop() {
  mdns.run(); // service mDNS queries so matrix-iot.local stays resolvable
  handleWebRequests();
  handleTimeout();
  renderLEDMatrix();
}

void handleWebRequests() {
  WiFiClient client = server.available();
  if (!client) return;

  String currentLine = "";
  while (client.connected()) {
    if (client.available()) {
      char c = client.read();
      if (c == '\n') {
        if (currentLine.length() == 0) {
          // End of headers, send 200 OK
          client.println("HTTP/1.1 200 OK");
          client.println("Content-type:text/plain");
          client.println("Connection: close");
          client.println();
          client.println("Status acknowledged.");
          break;
        } else {
          // Handle incoming route commands
          if (currentLine.startsWith("GET /bird")) {
            currentState = STATE_BIRD;
            stateStartTime = millis();
            Serial.println("Command: BIRD (Timer reset)");
          } else if (currentLine.startsWith("GET /squirrel")) {
            currentState = STATE_SQUIRREL;
            stateStartTime = millis();
            Serial.println("Command: SQUIRREL (Timer reset)");
          } else if (currentLine.startsWith("GET /clear")) {
            currentState = STATE_NONE;
            Serial.println("Command: CLEAR");
          }
          currentLine = "";
        }
      } else if (c != '\r') {
        currentLine += c;
      }
    }
  }
  client.stop();
}

void handleTimeout() {
  if (currentState != STATE_NONE) {
    if (millis() - stateStartTime >= DISPLAY_DURATION) {
      currentState = STATE_NONE;
      Serial.println("Timeout reached. Reverting to empty screen.");
    }
  }
}

void renderLEDMatrix() {
  // Only act when the state actually changes; matrix.play() keeps the loaded
  // sequence animating on its own until we load a different one.
  if (currentState == renderedState) return;
  renderedState = currentState;

  switch (currentState) {
    case STATE_BIRD:
      matrix.loadSequence(bird);
      matrix.play(true); // loop
      break;

    case STATE_SQUIRREL:
      matrix.loadSequence(squirrel);
      matrix.play(true); // loop
      break;

    case STATE_NONE:
    default:
      matrix.clear();
      break;
  }
}