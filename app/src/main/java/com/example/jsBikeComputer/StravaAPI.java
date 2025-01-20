package com.example.jsBikeComputer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import okhttp3.*;

public class StravaAPI {
    private static final String BASE_URL = "https://www.strava.com/api/v3/";
    private final OkHttpClient client;
    private final String accessToken;

    public StravaAPI(String accessToken) {
        this.client = new OkHttpClient();
        this.accessToken = accessToken;
        return;
    }
    private Request.Builder getAuthenticatedBuilder(String url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken);
    }
    public void getAthleteProfile(ApiCallback<JSONObject> callback) {
        Request request = getAuthenticatedBuilder(BASE_URL + "athlete")
                .get()
                .build();

        executeRequest(request, callback);
    }
    public void getActivities(int page, int perPage, ApiCallback<JSONArray> callback) {
        String url = BASE_URL + "athlete/activities"
                + "?page=" + page
                + "&per_page=" + perPage;

        Request request = getAuthenticatedBuilder(url)
                .get()
                .build();

        executeRequest(request, callback);
    }
    public void uploadActivity(String gpxData, String name, String description,
                               String trainer, String commute, ApiCallback<JSONObject> callback) {
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data_type", "gpx")
                .addFormDataPart("file", "activity.gpx",
                        RequestBody.create(MediaType.parse("application/gpx+xml"), gpxData));

        if (name != null) bodyBuilder.addFormDataPart("name", name);
        if (description != null) bodyBuilder.addFormDataPart("description", description);
        if (trainer != null) bodyBuilder.addFormDataPart("trainer", trainer);
        if (commute != null) bodyBuilder.addFormDataPart("commute", commute);

        Request request = getAuthenticatedBuilder(BASE_URL + "uploads")
                .post(bodyBuilder.build())
                .build();

        executeRequest(request, callback);
    }

    public void checkUploadStatus(String uploadId, ApiCallback<JSONObject> callback) {
        Request request = getAuthenticatedBuilder(BASE_URL + "uploads/" + uploadId)
                .get()
                .build();

        executeRequest(request, callback);
    }

    private void executeRequest(Request request, ApiCallback<?> callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onFailure(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();

                    if (callback instanceof ApiCallback) {
                        try {
                            JSONObject jsonObject = new JSONObject(responseBody);
                            ((ApiCallback<JSONObject>) callback).onSuccess(jsonObject);
                        } catch (Exception e) {
                            try {
                                JSONArray jsonArray = new JSONArray(responseBody);
                                ((ApiCallback<JSONArray>) callback).onSuccess(jsonArray);
                            } catch (Exception e2) {
                                // Handle the case where the response is neither a JSONObject nor a JSONArray
                                callback.onFailure(e2);
                            }
                        }
                    }
                } catch (Exception e3) {
                    callback.onFailure(e3);
                }
            }
        });
    }
}
