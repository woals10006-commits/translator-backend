package com.translator.proofread;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 교정교열 작업 하나의 진행 상황. 화면이 1.5초마다 이 값을 읽어 간다. */
public class ProofreadJob {
    private final String id;
    private volatile int total;          // 검토할 화 수
    private volatile int completed;      // 끝난 화 수
    private volatile String status = ""; // "3/15화 검토 중" 같은 사람이 읽는 문구
    private volatile boolean done;
    private volatile String error;
    private volatile String savedPath;
    private volatile int errorCount;     // 끝내 실패한 화 수

    // 실패했거나 확인이 필요한 화를 화면에 그대로 보여주기 위한 기록.
    private final List<String> notes = new CopyOnWriteArrayList<>();

    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    public ProofreadJob(String id) { this.id = id; }

    public String getId() { return id; }
    public int getTotal() { return total; }
    public void setTotal(int t) { total = t; }
    public int getCompleted() { return completed; }
    public synchronized void addCompleted() { completed++; }
    public String getStatus() { return status; }
    public void setStatus(String s) { status = s; }
    public boolean isDone() { return done; }
    public void setDone(boolean d) { done = d; }
    public String getError() { return error; }
    public void setError(String e) { error = e; }
    public String getSavedPath() { return savedPath; }
    public void setSavedPath(String p) { savedPath = p; }
    public int getErrorCount() { return errorCount; }
    public synchronized void addErrorCount() { errorCount++; }
    public List<String> getNotes() { return Collections.unmodifiableList(notes); }
    public void addNote(String n) { notes.add(n); }

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
