package com.example.jsBikeComputer;

import android.content.Intent;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.MenuItem;
import com.example.jsBikeComputer.R;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.net.Uri;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import androidx.security.crypto.MasterKeys;

import org.json.JSONObject;
import java.io.IOException;
import java.security.GeneralSecurityException;

import okhttp3.*;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

public class ConnectionsActivity extends AppCompatActivity {

    private static final String CLIENT_ID = "145021";
    private static final String CLIENT_SECRET = "69958b027f4d325c7be695094c3e0f35f7f0cff2";
    private static final String REDIRECT_URI = "http://jsbikecomputer.app/callback";
    private static final String STRAVA_AUTH_URL = "https://www.strava.com/oauth/mobile/authorize";
    private static final String STRAVA_TOKEN_URL = "https://www.strava.com/oauth/token";

    private EncryptedSharedPreferences encryptedPrefs;
    private final OkHttpClient client = new OkHttpClient();
    private StravaAPI stravaApi;
    private TextView profileText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connections);

        BottomNavigationView navView = findViewById(R.id.nav_view);
        navView.setSelectedItemId(R.id.navigation_devices);

        navView.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                if(item.getItemId() == R.id.navigation_connections){
                        return true;
                }
                else if(item.getItemId() == R.id.navigation_devices)
                {
                        startActivity(new Intent(getApplicationContext(),BluetoothActivity.class));
                        overridePendingTransition(0,0);
                        return true;
                }
                return false;
            }
        });

        setupEncryptedPreferences();
        setupUI();

        if (hasValidTokens()) {
            initializeStravaApi();
            loadAthleteProfile();
        }
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

        loginButton.setOnClickListener(v -> {
            if (!hasValidTokens()) {
                initiateStravaLogin();
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

        Intent intent = new Intent(Intent.ACTION_VIEW, authUri);
        startActivity(intent);
    }

    @Override
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
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(ConnectionsActivity.this, "Authentication failed", Toast.LENGTH_SHORT).show()
                );
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
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
            stravaApi.getAthleteProfile(new ApiCallback<JSONObject>() {
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
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(ConnectionsActivity.this, "Token refresh failed", Toast.LENGTH_SHORT).show();
                    initiateStravaLogin();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
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

    private void uploadActivityExample() {
        // Example GPX data - replace with real GPX data from your app

        /* Upload whatever is synced from esp32 */
        String sampleGpx = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\">...</gpx>";

        stravaApi.uploadActivity(sampleGpx, "My Ride", "Great ride today!", "false", "false",
                new ApiCallback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject result) {
                        try {
                            String uploadId = result.getString("id");
                            checkUploadStatus(uploadId);
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
                        });
                    }
                });
    }

    private void checkUploadStatus(String uploadId) {
        stravaApi.checkUploadStatus(uploadId, new ApiCallback<JSONObject>() {
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
}