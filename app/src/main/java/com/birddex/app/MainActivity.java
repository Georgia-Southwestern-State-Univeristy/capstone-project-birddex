package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Button btnCamera, btnBirdDex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnCamera = findViewById(R.id.btnCamera);
        btnBirdDex = findViewById(R.id.btnBirdDex);

        btnCamera.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, CameraActivity.class))
        );

        btnBirdDex.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, BirdDexActivity.class))
        );
    }
}