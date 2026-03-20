package com.xhs.controller;

import com.xhs.config.XhsConfig;
import com.xhs.service.AccountReportSyncService;
import com.xhs.service.ReportSyncService;
import com.xhs.service.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportSyncService reportSyncService;
    private final AccountReportSyncService accountReportSyncService;
    private final TokenService tokenService;
    private final XhsConfig xhsConfig;

    /**
     * 手动触发计划粒度同步，可指定日期
     * POST /report/sync?date=2026-03-16
     */
    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestParam(value = "date", required = false) String date) {
        try {
            reportSyncService.manualSync(date);
            return Map.of("success", true, "message", "计划粒度同步完成");
        } catch (Exception e) {
            return Map.of("success", false, "message", "同步失败: " + e.getMessage());
        }
    }

    /**
     * 手动触发账号粒度同步，可指定日期
     * POST /report/sync-account?date=2026-03-16
     */
    @PostMapping("/sync-account")
    public Map<String, Object> syncAccount(@RequestParam(value = "date", required = false) String date) {
        log.info("收到账号粒度同步请求, date={}", date);
        try {
            accountReportSyncService.manualSync(date);
            return Map.of("success", true, "message", "账号粒度同步完成");
        } catch (Exception e) {
            log.error("账号粒度同步失败", e);
            return Map.of("success", false, "message", "同步失败: " + e.getMessage());
        }
    }

    /**
     * 测试接口：检查账号配置和授权状态
     * GET /report/test-config
     */
    @GetMapping("/test-config")
    public Map<String, Object> testConfig() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> accounts = new ArrayList<>();

        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        for (XhsConfig.AccountConfig account : xhsConfig.getAccounts()) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", account.getName());
            info.put("appId", account.getAppId());
            info.put("advertiserIds", account.getAdvertiserIds());

            var token = tokenService.getToken(account.getAppId());
            if (token != null) {
                info.put("authorized", true);
                info.put("advertiserId", token.getAdvertiserId());
            } else {
                info.put("authorized", false);
            }
            accounts.add(info);
        }

        result.put("today", today);
        result.put("accountCount", accounts.size());
        result.put("accounts", accounts);
        return result;
    }
}