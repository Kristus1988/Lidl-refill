package com.example.lidlrefill;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class VariableInterval {
    private static final Random random = new Random();
    
    public static int getRandomInterval(int baseMinutes) {
        double variation = 0.75 + (random.nextDouble() * 0.5);
        int seconds = (int) (baseMinutes * 60 * variation);
        seconds += random.nextInt(45) - 22;
        return Math.max(30, seconds);
    }
    
    public static int getBackoffInterval(int retryCount) {
        int baseSeconds = 60;
        int backoff = baseSeconds * (int) Math.pow(2, Math.min(retryCount, 5));
        backoff = Math.min(backoff, 1800);
        double jitter = 0.9 + (random.nextDouble() * 0.2);
        backoff = (int) (backoff * jitter);
        return Math.max(30, backoff);
    }
    
    public static int getCheckInterval(int baseMinutes) {
        int baseSeconds = baseMinutes * 60;
        double variation = 0.8 + (random.nextDouble() * 0.4);
        int seconds = (int) (baseSeconds * variation);
        seconds += ThreadLocalRandom.current().nextInt(-30, 30);
        return Math.max(45, seconds);
    }
    
    public static int getPostRefillWait(int baseMinutes) {
        int baseSeconds = baseMinutes * 60;
        double variation = 0.9 + (random.nextDouble() * 0.4);
        int seconds = (int) (baseSeconds * variation);
        seconds += random.nextInt(300);
        return Math.max(60, seconds);
    }
}
