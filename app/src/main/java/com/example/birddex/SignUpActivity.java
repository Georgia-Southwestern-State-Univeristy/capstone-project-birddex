package com.example.birddex;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SignUpActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);

        Button btnSignUp = findViewById(R.id.btnSignUp);
        TextView tvAlready = findViewById(R.id.tvAlready);

        btnSignUp.setOnClickListener(v -> {
            startActivity(new Intent(SignUpActivity.this, SignUpCompleteActivity.class));
            finish();
        });

        tvAlready.setOnClickListener(v -> {
            startActivity(new Intent(SignUpActivity.this, LoginActivity.class));
            finish();
        });
    }
}
