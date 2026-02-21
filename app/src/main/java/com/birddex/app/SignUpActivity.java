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
 * SignUpActivity handles the user registration process.
 * Users can create a new account by providing a username, email, and password.
 */
public class SignUpActivity extends AppCompatActivity {

    private FirebaseManager firebaseManager;
    private sign_IN_upValidator signINupValidator;

    private EditText usernameEditText;
    private EditText emailEditText;
    private EditText passwordEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);

        // Initialize helper classes for Firebase operations and input validation.
        firebaseManager = new FirebaseManager();
        signINupValidator = new sign_IN_upValidator();

        // Bind UI components to variables.
        usernameEditText = findViewById(R.id.etUsername);
        emailEditText = findViewById(R.id.etEmail);
        passwordEditText = findViewById(R.id.etPassword);

        Button btnSignUp = findViewById(R.id.btnSignUp);
        TextView tvAlready = findViewById(R.id.tvAlready);

        // Handle the sign-up button click.
        btnSignUp.setOnClickListener(v -> {
            // Validate the user input using the signINupValidator helper.
            if (signINupValidator.validateSignUpForm(usernameEditText, emailEditText, passwordEditText)) {
                String username = usernameEditText.getText().toString();
                String email = emailEditText.getText().toString();
                String password = passwordEditText.getText().toString();
                
                // Attempt to create a new account with Firebase.
                firebaseManager.createAccount(username, email, password, new FirebaseManager.AuthListener() {
                    @Override
                    public void onSuccess(FirebaseUser user) {
                        // On successful account creation, navigate to the HomeActivity and save the user's username in Firestore.
                        Toast.makeText(SignUpActivity.this, "Sign up successful.", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(SignUpActivity.this, HomeActivity.class));
                        finish(); // Finish current activity to prevent returning on back press.
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        // Display error message if account creation fails.
                        Toast.makeText(SignUpActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onUsernameTaken() { // Changed from onFullNameTaken()
                        // Inform the user if the username is already in use.
                        usernameEditText.setError("Username already taken."); // Updated error message
                    }
                });
            }
        });

        // Navigate back to LoginActivity if the user already has an account.
        tvAlready.setOnClickListener(v -> {
            startActivity(new Intent(SignUpActivity.this, LoginActivity.class));
            finish();
        });
    }
}