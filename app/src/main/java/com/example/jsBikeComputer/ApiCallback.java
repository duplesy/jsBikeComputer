package com.example.bikecomputer;

public interface ApiCallback<T> {
    void onSuccess(T result);
    void onFailure(Exception e);
}
