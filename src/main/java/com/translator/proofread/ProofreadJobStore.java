package com.translator.proofread;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProofreadJobStore {
    private final Map<String, ProofreadJob> store = new ConcurrentHashMap<>();

    public void save(ProofreadJob job) { store.put(job.getId(), job); }
    public ProofreadJob find(String id) { return store.get(id); }
}
