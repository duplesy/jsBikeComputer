package com.example.jsBikeComputer;

public interface ApiCallback<T> {
    void onSuccess(T result);
    void onFailure(Exception e);
}
