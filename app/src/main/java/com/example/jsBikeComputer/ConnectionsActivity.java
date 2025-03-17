package com.example.jsBikeComputer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.net.Uri;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.*;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class ConnectionsActivity extends AppCompatActivity {

    private static final String CLIENT_ID = "145021";
    private static final String CLIENT_SECRET = "69958b027f4d325c7be695094c3e0f35f7f0cff2";
    private static final String REDIRECT_URI = "https://jsbikecomputer.app/callback";
    private static final String STRAVA_AUTH_URL = "https://www.strava.com/oauth/mobile/authorize";
    private static final String STRAVA_TOKEN_URL = "https://www.strava.com/oauth/token";

    private static final String TAG = "BikeComputerBLE";

    private EncryptedSharedPreferences encryptedPrefs;
    private final OkHttpClient client = new OkHttpClient();
    private StravaAPI stravaApi;
    private TextView profileText;

    private TextView connStatusText;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connections);

        BottomNavigationView navView = findViewById(R.id.nav_view);
        navView.setSelectedItemId(R.id.navigation_devices);

        navView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if(itemId == R.id.navigation_connections){
                    return true;
            }
            else if(itemId == R.id.navigation_devices)
            {
                startActivity(new Intent(getApplicationContext(), MainActivity.class));
                overridePendingTransition(0,0);
                return true;
            }
            return false;
        });

        setupEncryptedPreferences();
        setupUI();

        if (hasValidTokens()) {
            initializeStravaApi();
            loadAthleteProfile();
        }

        handleIntent(getIntent());
    }

    private void setupEncryptedPreferences() {
        try {
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    "strava_master_key", // Choose a unique alias for your key
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build();

            MasterKey masterKey = new MasterKey.Builder(getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    //.setKeyGenParameterSpec(spec)
                    .build();

            encryptedPrefs = (EncryptedSharedPreferences) EncryptedSharedPreferences.create(
                    getApplicationContext(),
                    "strava_prefs",
                    masterKey, // Use the MasterKey object directly
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

        } catch (GeneralSecurityException | IOException e) {
            Log.e("Connections", "Error setting up EncryptedSharedPreferences", e);
            Toast.makeText(this, "Error setting up secure storage", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupUI() {
        Button loginButton = findViewById(R.id.loginButton);
        Button uploadButton = findViewById(R.id.uploadButton);
        profileText = findViewById(R.id.profileText);
        connStatusText = findViewById(R.id.connStatusText);

        loginButton.setOnClickListener(v -> {
            if (!hasValidTokens()) {
                initiateStravaLogin();
                loadAthleteProfile();
            } else {
                Toast.makeText(this, "Already logged in!", Toast.LENGTH_SHORT).show();
            }
        });

        uploadButton.setOnClickListener(v -> {
            if (stravaApi != null) {
                uploadActivityExample();
            } else {
                Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void initiateStravaLogin() {
        Uri authUri = Uri.parse(STRAVA_AUTH_URL)
                .buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("state", "initiate")
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("approval_prompt", "force")
                .appendQueryParameter("scope", "activity:write,activity:read")
                .build();

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, authUri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Log.e("ConnectionsActivity", "Error launching auth intent", e);
            Toast.makeText(this, "Unable to launch authentication", Toast.LENGTH_SHORT).show();
        }
    }

/*    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        Uri uri = intent.getData();
        if (uri != null) {
            Log.d("ConnectionsActivity", "Received URI: " + uri.toString());
            if (uri.toString().startsWith(REDIRECT_URI)) {
                String code = uri.getQueryParameter("code");
                if (code != null) {
                    Log.d("ConnectionsActivity", "Authorization code: " + code);
                    exchangeCodeForToken(code);
                } else {
                    Log.e("ConnectionsActivity", "Authorization failed: No code");
                }
            }
        }
    }*/

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);

    }

    private void handleIntent(Intent intent) {
        Log.d("ConnectionsActivity", "onNewIntent called");

        if (intent == null) {
            Log.e("ConnectionsActivity", "Received null intent");
            return;
        }

        String action = intent.getAction();
        Uri uri = intent.getData();

        Log.d("ConnectionsActivity", "Intent Action: " + action);
        Log.d("ConnectionsActivity", "Intent Data: " + (uri != null ? uri.toString() : "null"));

        if (uri != null) {
            runOnUiThread(() ->
                    Toast.makeText(this, "Received URI: " + uri.toString(), Toast.LENGTH_LONG).show()
            );

            Log.d("ConnectionsActivity", "Full URI Details:");
            Log.d("ConnectionsActivity", "Scheme: " + uri.getScheme());
            Log.d("ConnectionsActivity", "Host: " + uri.getHost());
            Log.d("ConnectionsActivity", "Path: " + uri.getPath());

            // Log all query parameters
            for (String key : uri.getQueryParameterNames()) {
                Log.d("ConnectionsActivity", "Query Param - " + key + ": " + uri.getQueryParameter(key));
            }

            if (uri.toString().startsWith(REDIRECT_URI)) {
                String code = uri.getQueryParameter("code");

                Log.d("ConnectionsActivity", "Matching Redirect URI");
                Log.d("ConnectionsActivity", "Code: " + code);

                if (code != null) {
                    Log.d("ConnectionsActivity", "Exchanging code for token");
                    exchangeCodeForToken(code);
                } else {
                    Log.e("ConnectionsActivity", "Authorization failed: No code");
                }
            }
        }
    }

    private void exchangeCodeForToken(String code) {
        RequestBody requestBody = new FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .build();

        Request request = new Request.Builder()
                .url(STRAVA_TOKEN_URL)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("ConnectionsActivity", "Token Exchange Failure", e);

                runOnUiThread(() ->
                        Toast.makeText(ConnectionsActivity.this, "Authentication failed", Toast.LENGTH_SHORT).show()
                );
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    assert response.body() != null;
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);

                    encryptedPrefs.edit()
                            .putString("access_token", json.getString("access_token"))
                            .putString("refresh_token", json.getString("refresh_token"))
                            .putLong("expires_at", json.getLong("expires_at"))
                            .apply();

                    runOnUiThread(() -> {
                        Toast.makeText(ConnectionsActivity.this, "Successfully authenticated!", Toast.LENGTH_SHORT).show();
                        initializeStravaApi();
                        loadAthleteProfile();
                    });
                } catch (Exception e) {
                    Log.e("Connections", "Error parsing JSON response", e);
                    runOnUiThread(() ->
                            Toast.makeText(ConnectionsActivity.this, "Error processing response", Toast.LENGTH_SHORT).show()
                    );
                }
            }
        });
    }

    private boolean hasValidTokens() {
        String accessToken = encryptedPrefs.getString("access_token", null);
        long expiresAt = encryptedPrefs.getLong("expires_at", 0);
        return accessToken != null && System.currentTimeMillis() / 1000 < expiresAt;
    }

    private void initializeStravaApi() {
        String accessToken = encryptedPrefs.getString("access_token", null);
        if (accessToken != null) {
            stravaApi = new StravaAPI(accessToken);
        }
    }

    private void loadAthleteProfile() {
        if (stravaApi != null) {
            stravaApi.getAthleteProfile(new ApiCallback<>() {
                @Override
                public void onSuccess(JSONObject result) {
                    runOnUiThread(() -> {
                        try {
                            String firstname = result.getString("firstname");
                            String lastname = result.getString("lastname");
                            profileText.setText(String.format("Welcome %s %s", firstname, lastname));
                        } catch (Exception e) {
                            Log.e("Connections", "Error parsing athlete profile", e);
                        }
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(ConnectionsActivity.this, "Failed to load profile", Toast.LENGTH_SHORT).show();
                        if (e.getMessage() != null && e.getMessage().contains("401")) {
                            refreshToken();
                        }
                    });
                }
            });
        }
    }

    private void refreshToken() {
        String refreshToken = encryptedPrefs.getString("refresh_token", null);
        if (refreshToken == null) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Please login again", Toast.LENGTH_SHORT).show();
                initiateStravaLogin();
            });
            return;
        }

        RequestBody requestBody = new FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build();

        Request request = new Request.Builder()
                .url(STRAVA_TOKEN_URL)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(ConnectionsActivity.this, "Token refresh failed", Toast.LENGTH_SHORT).show();
                    initiateStravaLogin();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    assert response.body() != null;
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);

                    encryptedPrefs.edit()
                            .putString("access_token", json.getString("access_token"))
                            .putString("refresh_token", json.getString("refresh_token"))
                            .putLong("expires_at", json.getLong("expires_at"))
                            .apply();

                    runOnUiThread(() -> {
                        initializeStravaApi();
                        loadAthleteProfile();
                    });
                } catch (Exception e) {
                    Log.e("Connections", "Error parsing refresh token response", e);
                    runOnUiThread(() -> {
                        Toast.makeText(ConnectionsActivity.this, "Error refreshing token", Toast.LENGTH_SHORT).show();
                        initiateStravaLogin();
                    });
                }
            }
        });
    }

    private void uploadActivityExample()  {
        // Get all saved FIT files from app's internal storage
        List<File> fitFiles = getAllSavedFitFiles();

        if (fitFiles.isEmpty()) {
            Toast.makeText(this, "No FIT files found to upload", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show progress to user
        updateStatus("Found " + fitFiles.size() + " files to upload to Strava");

        // Process files one by one
        uploadNextFileToStrava(fitFiles, 0);
    }

    private List<File> getAllSavedFitFiles() {
        List<File> fitFiles = new ArrayList<>();
        File internalDir = getFilesDir();
        File[] files = internalDir.listFiles();

        if (files != null) {
            for (File file : files) {
                // Check if file is a FIT file
                if (file.isFile() && file.getName().endsWith(".tcx")) {
                    fitFiles.add(file);
                }
            }
        }

        return fitFiles;
    }

    private void uploadNextFileToStrava(List<File> files, int index) {
        try {
            if (index >= files.size()) {
                // All files uploaded
                updateStatus("All files uploaded to Strava");
                return;
            }

            File fileToUpload = files.get(index);
            updateStatus("Uploading " + fileToUpload.getName() + " (" + (index + 1) + "/" + files.size() + ")");

            // Start the upload process for this file
            uploadFileToStrava(files, index);//
            deleteFile(fileToUpload.getName());
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void uploadFileToStrava(List<File> files, int index) {
        try {
            // Read file content
            byte[] fileContent = readFileToByteArray(files.get(index));

            // Convert from FIT to GPX if needed
            // Note: Since your uploadActivity method expects GPX,
            // you might need to convert FIT to GPX or modify the uploadActivity
            // method to support FIT files directly.

            String activityName = "Bike Ride " + new SimpleDateFormat("yyyy-MM-dd HH:mm",
                    Locale.US).format(new Date());
            String description = "Uploaded from JSBikeComputer";

            stravaApi.uploadActivity(
                    new String(fileContent), // If this really needs GPX, you would need to convert from FIT
                    activityName,
                    description,
                    "false", // trainer
                    "false", // commute
                    new ApiCallback<>() {
                        @Override
                        public void onSuccess(JSONObject result) {
                            try {
                                String uploadId = result.getString("id");
                                checkUploadStatus(uploadId);

                                runOnUiThread(() -> {
                                    updateStatus("File uploaded successfully: " + files.get(index).getName());

                                    // Move to the next file
                                    uploadNextFileToStrava(files, index + 1);
                                });
                            } catch (Exception e) {
                                Log.e("Connections", "Error parsing upload response", e);
                                runOnUiThread(() ->
                                        Toast.makeText(ConnectionsActivity.this, "Error processing upload", Toast.LENGTH_SHORT).show()
                                );
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            runOnUiThread(() -> {
                                Toast.makeText(ConnectionsActivity.this, "Upload failed", Toast.LENGTH_SHORT).show();
                                if (e.getMessage() != null && e.getMessage().contains("401")) {
                                    refreshToken();
                                }
                                uploadNextFileToStrava(files, index+1);
                            });
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] readFileToByteArray(File file)  {
        byte[] buffer = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int bytesRead = fis.read(buffer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return buffer;
    }

    private void checkUploadStatus(String uploadId) {
        stravaApi.checkUploadStatus(uploadId, new ApiCallback<>() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    String status = result.getString("status");
                    runOnUiThread(() ->
                            Toast.makeText(ConnectionsActivity.this,
                                    "Upload status: " + status, Toast.LENGTH_SHORT).show()
                    );
                } catch (Exception e) {
                    Log.e("Connections", "Error parsing status response", e);
                }
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("Connections", "Error checking upload status", e);
                runOnUiThread(() ->
                        Toast.makeText(ConnectionsActivity.this, "Error checking upload status", Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void updateStatus(String message) {
        mainHandler.post(() -> {
            Log.d(TAG, message);
            connStatusText.setText(message);
        });
    }
}