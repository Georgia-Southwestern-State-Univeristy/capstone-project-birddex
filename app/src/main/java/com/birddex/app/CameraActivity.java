package com.birddex.app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class CameraActivity extends AppCompatActivity {

    private Button btnBack, btnFakeCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        btnBack = findViewById(R.id.btnBack);
        btnFakeCapture = findViewById(R.id.btnFakeCapture);

        btnBack.setOnClickListener(v -> finish());

        // Placeholder to prove this screen works (replace later with real camera intent)
        btnFakeCapture.setOnClickListener(v ->
                Toast.makeText(CameraActivity.this, "Camera placeholder working!", Toast.LENGTH_SHORT).show()
        );
    }
}