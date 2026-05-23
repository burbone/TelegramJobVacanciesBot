package com.botTelegram.botTelegram.domain;

public enum SearchMode {
    PRECISE(0.65),
    NORMAL(0.60),
    WIDE(0.50);

    public final double threshold;

    SearchMode(double threshold) {
        this.threshold = threshold;
    }
}