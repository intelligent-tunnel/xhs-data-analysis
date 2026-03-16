package com.xhs.controller;

import com.xhs.service.ReportSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportSyncService reportSyncService;

    /**
     * 手动触发同步，可指定日期
     * POST /report/sync?date=2026-03-16
     */
    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestParam(value = "date", required = false) String date) {
        try {
            reportSyncService.manualSync(date);
            return Map.of("success", true, "message", "同步完成");
        } catch (Exception e) {
            return Map.of("success", false, "message", "同步失败: " + e.getMessage());
        }
    }
}