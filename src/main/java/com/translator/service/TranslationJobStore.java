package com.translator.service;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TranslationJobStore {
    private final Map<String, TranslationJob> store = new ConcurrentHashMap<>();

    public void save(TranslationJob job) { store.put(job.getId(), job); }
    public TranslationJob find(String id) { return store.get(id); }
    public void delete(String id) { store.remove(id); }
}
