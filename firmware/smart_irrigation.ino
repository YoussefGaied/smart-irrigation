#include <WiFi.h>
#include "secrets.h"
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <DHT.h>
#include <PubSubClient.h>

#define WIFI_SSID      SECRET_SSID
#define WIFI_PASSWORD  SECRET_PASS
#define DATABASE_URL   SECRET_DB_URL

#define MQTT_SERVER   "broker.hivemq.com"
#define MQTT_PORT     1883
#define MQTT_TOPIC_SUB "irrigation/command"
#define MQTT_TOPIC_PUB "irrigation/status"

#define SOIL_PIN    34
#define DHT_PIN      4
#define RELAY_PIN   26
#define LDR_PIN     35
#define DHT_TYPE  DHT22

#define SOIL_DRY  2615
#define SOIL_WET   926

DHT dht(DHT_PIN, DHT_TYPE);

// Separate clients for MQTT and Firebase
WiFiClient mqttClient;
WiFiClientSecure httpsClient;

PubSubClient mqtt(mqttClient);

unsigned long lastSend = 0;
#define SEND_INTERVAL 3600000

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (int i = 0; i < length; i++) {
    message += (char)payload[i];
  }
  Serial.print("MQTT received: ");
  Serial.println(message);

  if (message == "IRRIGATE") {
    Serial.println(">> Pump ON");
    digitalWrite(RELAY_PIN, LOW);
    unsigned long pumpStart = millis();
    while (millis() - pumpStart < 30000) {
        mqtt.loop();
        delay(10);
    }
    digitalWrite(RELAY_PIN, HIGH);
    Serial.println(">> Watering done");
    mqtt.publish(MQTT_TOPIC_PUB, "DONE");
  } else if (message == "STOP") {
    Serial.println(">> Pump OFF");
    digitalWrite(RELAY_PIN, HIGH);
    mqtt.publish(MQTT_TOPIC_PUB, "STOPPED");
  }
}

void connectMQTT() {
  int attempts = 0;
  while (!mqtt.connected() && attempts < 5) {
    Serial.print("Connecting to MQTT...");
    String clientId = "ESP32-" + String(random(0xffff), HEX);
    if (mqtt.connect(clientId.c_str())) {
      Serial.println(" Connected!");
      mqtt.subscribe(MQTT_TOPIC_SUB);
      Serial.println("Subscribed to: irrigation/command");
    } else {
      Serial.print(" Failed rc=");
      Serial.println(mqtt.state());
      attempts++;
      delay(3000);
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, HIGH);
  dht.begin();

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Connecting to WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    Serial.print(".");
    delay(500);
  }
  Serial.println(" Connected!");
  Serial.println(WiFi.localIP());

  httpsClient.setInsecure();

  mqtt.setServer(MQTT_SERVER, MQTT_PORT);
  mqtt.setCallback(mqttCallback);
  connectMQTT();
}

int getMoisturePercent(int raw) {
  int pct = map(raw, SOIL_DRY, SOIL_WET, 0, 100);
  return constrain(pct, 0, 100);
}

void sendToFirebase(String path, String value) {
  HTTPClient http;
  String url = String(DATABASE_URL) + path + ".json";
  http.begin(httpsClient, url);
  http.addHeader("Content-Type", "application/json");
  int code = http.PUT(value);
  if (code > 0) {
    Serial.print("  sent "); Serial.print(path);
    Serial.print(" → HTTP "); Serial.println(code);
  } else {
    Serial.print("  error: "); Serial.println(http.errorToString(code));
  }
  http.end();
}

void loop() {
  if (!mqtt.connected()) {
    connectMQTT();
  }
  mqtt.loop();

  unsigned long now = millis();
  if (now - lastSend >= SEND_INTERVAL || lastSend == 0) {
    lastSend = now;

    int soilRaw  = analogRead(SOIL_PIN);
    int moisture = getMoisturePercent(soilRaw);
    float temp   = dht.readTemperature();
    float humi   = dht.readHumidity();
    int light    = analogRead(LDR_PIN);

    Serial.print("Moisture: "); Serial.print(moisture); Serial.println("%");
    Serial.print("Temp: ");     Serial.print(temp);     Serial.println(" C");
    Serial.print("Humidity: "); Serial.print(humi);     Serial.println(" %");
    Serial.print("Light: ");    Serial.println(light);

    Serial.println("Sending to Firebase...");
    sendToFirebase("/irrigation/moisture",    String(moisture));
    sendToFirebase("/irrigation/temperature", String(temp));
    sendToFirebase("/irrigation/humidity",    String(humi));
    sendToFirebase("/irrigation/light",       String(light));
    Serial.println("---");
  }
}
