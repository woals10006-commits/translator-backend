package com.translator;

import org.apache.poi.util.IOUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TranslatorApplication {
    public static void main(String[] args) {
        // Apache POI caps single-record array allocation at 100MB by default as a
        // zip-bomb guard. Large but legitimate manuscripts exceed this, so raise the
        // ceiling to 500MB. (Applies to all POI reads in this app.)
        IOUtils.setByteArrayMaxOverride(500_000_000);
        SpringApplication.run(TranslatorApplication.class, args);
    }
}
