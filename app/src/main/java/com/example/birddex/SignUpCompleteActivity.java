package com.example.birddex;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class SignUpCompleteActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup_complete);

        Button btnGoLogin = findViewById(R.id.btnGoLogin);

        btnGoLogin.setOnClickListener(v -> {
            startActivity(new Intent(SignUpCompleteActivity.this, LoginActivity.class));
            finish(); // prevents going back to the "complete" screen
        });
    }
}
