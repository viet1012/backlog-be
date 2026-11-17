package com.example.backlogbe.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Bật @Scheduled. Hiện dùng cho: CostOtImportService.scheduledShareImport
// (cron cost-monitoring.ot.share-cron, mặc định "-" = tắt).
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
