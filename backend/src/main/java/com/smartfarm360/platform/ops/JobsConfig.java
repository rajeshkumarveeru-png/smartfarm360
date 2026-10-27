package com.smartfarm360.platform.ops;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the daily platform jobs: due farm business deletions and expired exports (SaaS operations design, O9, O10). */
@Configuration
@EnableScheduling
public class JobsConfig {
}
