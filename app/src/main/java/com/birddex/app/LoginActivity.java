package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseUser;

/**
 * LoginActivity handles the user authentication process.
 * Users can log in with their email and password, navigate to sign up,
 * or initiate a password reset.
 */
public class LoginActivity extends AppCompatActivity {

    private FirebaseManager firebaseManager;
    private sign_IN_upValidator signINupValidator;

    private EditText emailEditText;
    private EditText passwordEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // Initialize helper classes for Firebase operations and input validation.
        firebaseManager = new FirebaseManager();
        signINupValidator = new sign_IN_upValidator();

        // Bind UI components to variables.
        emailEditText = findViewById(R.id.etEmail);
        passwordEditText = findViewById(R.id.etPassword);

        Button btnLogin = findViewById(R.id.btnLogin);
        TextView tvForgot = findViewById(R.id.tvForgot);
        TextView tvSignUp = findViewById(R.id.tvSignUp);

        // Handle the login button click.
        btnLogin.setOnClickListener(v -> {
            // Validate the user input using the signINupValidator helper.
            if (signINupValidator.validateSignInForm(emailEditText, passwordEditText)) {
                String email = emailEditText.getText().toString();
                String password = passwordEditText.getText().toString();
                
                // Attempt to sign in with Firebase.
                firebaseManager.signIn(email, password, new FirebaseManager.AuthListener() {
                    @Override
                    public void onSuccess(FirebaseUser user) {
                        // On successful login, navigate to the HomeActivity.
                        Toast.makeText(LoginActivity.this, "Login successful.", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                        finish(); // Finish current activity to prevent returning on back press.
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        // Display error message if login fails.
                        Toast.makeText(LoginActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onUsernameTaken() {
                        // This callback is not used during sign-in.
                    }
                });
            }
        });

        // Navigate to ForgotPasswordActivity.
        tvForgot.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, ForgotPasswordActivity.class));
        });

        // Navigate to SignUpActivity.
        tvSignUp.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, SignUpActivity.class));
        });
    }
}
