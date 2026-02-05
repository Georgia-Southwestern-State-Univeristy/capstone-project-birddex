package com.example.birddex;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        Button btnLogin = findViewById(R.id.btnLogin);
        TextView tvForgot = findViewById(R.id.tvForgot);
        TextView tvSignUp = findViewById(R.id.tvSignUp);

        btnLogin.setOnClickListener(v -> {
            startActivity(new android.content.Intent(LoginActivity.this, HomeActivity.class));
            finish();
        });

        tvForgot.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, ForgotPasswordActivity.class));
        });

        tvSignUp.setOnClickListener(v -> {
            startActivity(new android.content.Intent(LoginActivity.this, SignUpActivity.class));
        });
    }
}
