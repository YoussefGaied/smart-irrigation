package com.example.smartirigation;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MainActivity extends AppCompatActivity {

    TextView tvMoisture, tvTemp, tvHumidity, tvLight, tvStatus;
    Button btnIrrigate;
    Switch switchAuto;
    DatabaseReference dbRef;
    MqttClient mqttClient;

    String MQTT_BROKER = "tcp://broker.hivemq.com:1883";
    String MQTT_TOPIC  = "irrigation/command";
    void connectMQTT() {
        new Thread(() -> {
            try {
                runOnUiThread(() -> tvStatus.setText("Status: Connecting MQTT..."));
                if (mqttClient != null && mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }
                mqttClient = new MqttClient(MQTT_BROKER, MqttClient.generateClientId(), null);
                MqttConnectOptions options = new MqttConnectOptions();
                options.setCleanSession(true);
                options.setConnectionTimeout(30);
                options.setKeepAliveInterval(60);
                options.setAutomaticReconnect(true);
                mqttClient.connect(options);
                runOnUiThread(() -> tvStatus.setText("Status: MQTT Connected ✅"));
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("Status: MQTT Failed - " + e.getMessage()));
            }
        }).start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvMoisture  = findViewById(R.id.tvMoisture);
        tvTemp      = findViewById(R.id.tvTemp);
        tvHumidity  = findViewById(R.id.tvHumidity);
        tvLight     = findViewById(R.id.tvLight);
        tvStatus    = findViewById(R.id.tvStatus);
        btnIrrigate = findViewById(R.id.btnIrrigate);
        switchAuto  = findViewById(R.id.switchAuto);

        // Connect Firebase
        dbRef = FirebaseDatabase.getInstance().getReference("irrigation");
        dbRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Object moisture = snapshot.child("moisture").getValue();
                    Object temp     = snapshot.child("temperature").getValue();
                    Object humidity = snapshot.child("humidity").getValue();
                    Object light    = snapshot.child("light").getValue();

                    tvMoisture.setText("💧 Moisture: " + moisture + "%");
                    tvTemp.setText("🌡️ Temp: " + temp + " °C");
                    tvHumidity.setText("💨 Humidity: " + humidity + "%");
                    tvLight.setText("☀️ Light: " + light);
                    tvStatus.setText("Status: Data received ✅");
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                tvStatus.setText("Status: Error - " + error.getMessage());
            }
        });

        // Connect MQTT in background thread
        connectMQTT();

        // Irrigate button
        btnIrrigate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!switchAuto.isChecked()) {
                    new Thread(() -> {
                        try {
                            if (mqttClient != null && mqttClient.isConnected()) {
                                MqttMessage message = new MqttMessage("IRRIGATE".getBytes());
                                message.setQos(1);
                                mqttClient.publish(MQTT_TOPIC, message);
                                runOnUiThread(() -> tvStatus.setText("Status: Irrigating! 💧"));
                            } else {
                                runOnUiThread(() -> tvStatus.setText("Status: MQTT not connected!"));
                            }
                        } catch (Exception e) {
                            runOnUiThread(() -> tvStatus.setText("Status: Error - " + e.getMessage()));
                        }
                    }).start();
                } else {
                    tvStatus.setText("Status: Auto mode ON - AI is in control 🤖");
                }
            }
        });

        // Auto mode toggle
        switchAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                tvStatus.setText("Status: Auto mode ON 🤖");
                btnIrrigate.setAlpha(0.5f);
            } else {
                tvStatus.setText("Status: Manual mode ON 👤");
                btnIrrigate.setAlpha(1.0f);
            }
        });
        Button btnReconnect = findViewById(R.id.btnReconnect);
        btnReconnect.setOnClickListener(v -> connectMQTT());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}