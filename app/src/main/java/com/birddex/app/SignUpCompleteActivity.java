package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.birddex.app.databinding.ActivitySignUpCompleteBinding;

public class SignUpCompleteActivity extends AppCompatActivity {

    private ActivitySignUpCompleteBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySignUpCompleteBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnGoToLogin.setOnClickListener(v -> {
            startActivity(new Intent(SignUpCompleteActivity.this, LoginActivity.class));
            finish(); // Finish this activity so they can't go back to it with the back button
        });
    }
}