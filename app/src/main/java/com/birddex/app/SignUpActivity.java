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
 */
public class SignUpActivity extends AppCompatActivity {

    private FirebaseManager firebaseManager;
    private sign_IN_upValidator signINupValidator;
    private LoadingDialog loadingDialog;

    private EditText usernameEditText;
    private EditText emailEditText;
    private EditText passwordEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);

        firebaseManager = new FirebaseManager(this);
        signINupValidator = new sign_IN_upValidator();
        loadingDialog = new LoadingDialog(this);

        usernameEditText = findViewById(R.id.etUsername);
        emailEditText = findViewById(R.id.etEmail);
        passwordEditText = findViewById(R.id.etPassword);

        Button btnSignUp = findViewById(R.id.btnSignUp);
        TextView tvAlready = findViewById(R.id.tvAlready);

        btnSignUp.setOnClickListener(v -> {
            if (signINupValidator.validateSignUpForm(usernameEditText, emailEditText, passwordEditText)) {
                String username = usernameEditText.getText().toString();
                String email = emailEditText.getText().toString();
                String password = passwordEditText.getText().toString();
                
                loadingDialog.show();
                firebaseManager.createAccount(username, email, password, new FirebaseManager.AuthListener() {
                    @Override
                    public void onSuccess(FirebaseUser user) {
                        loadingDialog.dismiss();
                        Toast.makeText(SignUpActivity.this, "Sign up successful. Please verify your email. " + getString(R.string.email_verification_expiration_message), Toast.LENGTH_LONG).show();
                        startActivity(new Intent(SignUpActivity.this, SignUpCompleteActivity.class));
                        finish();
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        loadingDialog.dismiss();
                        Toast.makeText(SignUpActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onUsernameTaken() { 
                        loadingDialog.dismiss();
                        usernameEditText.setError("Username already taken.");
                    }

                    @Override
                    public void onEmailTaken() {
                        loadingDialog.dismiss();
                        emailEditText.setError("Email already taken.");
                        Toast.makeText(SignUpActivity.this, "Email is already taken.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        tvAlready.setOnClickListener(v -> {
            startActivity(new Intent(SignUpActivity.this, LoginActivity.class));
            finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
    }
}
