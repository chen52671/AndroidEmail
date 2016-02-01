package com.chen.mail.ui;

public interface UpOrBackController {

    interface UpOrBackHandler {
        boolean onBackPressed();
        boolean onUpPressed();
    }

    void addUpOrBackHandler(UpOrBackHandler handler);
    void removeUpOrBackHandler(UpOrBackHandler handler);
}
