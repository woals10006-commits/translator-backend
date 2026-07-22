package com.translator.service;

import java.util.concurrent.atomic.AtomicLong;

public class TranslationJob {
    private final String id;
    private volatile int total;
    private volatile int completed;
    private volatile boolean done;
    private volatile String error;
    private volatile byte[] result;
    private volatile int errorCount;
    // Absolute path where the finished .docx was auto-saved on disk, so the
    // result survives even if the browser never downloads it.
    private volatile String savedPath;

    // Token usage accumulates across all concurrent API calls for this job, so
    // it must be thread-safe. Used to compute the exact credit cost per run.
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    public TranslationJob(String id) { this.id = id; }

    public String getId() { return id; }
    public int getTotal() { return total; }
    public void setTotal(int t) { total = t; }
    public int getCompleted() { return completed; }
    public void addCompleted(int n) { completed += n; }
    public boolean isDone() { return done; }
    public void setDone(boolean d) { done = d; }
    public String getError() { return error; }
    public void setError(String e) { error = e; }
    public byte[] getResult() { return result; }
    public void setResult(byte[] r) { result = r; }
    public int getErrorCount() { return errorCount; }
    public void addErrorCount(int n) { errorCount += n; }
    public void setErrorCount(int n) { errorCount = n; }
    public String getSavedPath() { return savedPath; }
    public void setSavedPath(String p) { savedPath = p; }

    public void addTokens(long in, long out) {
        inputTokens.addAndGet(in);
        outputTokens.addAndGet(out);
    }
    public long getInputTokens() { return inputTokens.get(); }
    public long getOutputTokens() { return outputTokens.get(); }

    public int getProgress() {
        if (total == 0) return 0;
        return Math.min(99, (int) ((completed * 100.0) / total));
    }
}
