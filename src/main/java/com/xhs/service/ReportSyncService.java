package com.xhs.service;

import com.xhs.config.FeishuConfig;
import com.xhs.config.XhsConfig;
import com.xhs.model.DateValue;
import com.xhs.util.FeishuBitableUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportSyncService {

    private final XhsReportService xhsReportService;
    private final TokenService tokenService;
    private final XhsConfig xhsConfig;
    private final FeishuConfig feishuConfig;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FEISHU_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * XHS API 字段 → 飞书表格字段名 映射
     */
    private static final Map<String, String> FIELD_MAPPING = new LinkedHashMap<>();
    static {
        FIELD_MAPPING.put("fee", "消费");
        FIELD_MAPPING.put("impression", "展现量");
        FIELD_MAPPING.put("click", "点击量");
        FIELD_MAPPING.put("ctr", "点击率");
        FIELD_MAPPING.put("acp", "平均点击成本");
        FIELD_MAPPING.put("cpm", "平均千次展示费用");
        FIELD_MAPPING.put("interaction", "互动量");
        FIELD_MAPPING.put("cpi", "平均互动成本");
        FIELD_MAPPING.put("message_consult", "私信进线数");
        FIELD_MAPPING.put("initiative_message", "私信开口数");
        FIELD_MAPPING.put("msg_leads_num", "私信留资数");
        FIELD_MAPPING.put("message_consult_cpl", "私信进线成本");
        FIELD_MAPPING.put("initiative_message_cpl", "私信开口成本");
        FIELD_MAPPING.put("msg_leads_cost", "私信留资成本");
    }

    /**
     * 每 2 小时执行一次，同步所有账号当天的报表数据到飞书
     */
    @Scheduled(fixedRate = 2 * 60 * 60 * 1000, initialDelay = 10 * 1000)
    public void syncAllAccounts() {
        log.info("===== 开始同步报表数据到飞书 =====");
        String today = LocalDate.now().format(DATE_FMT);

        for (XhsConfig.AccountConfig account : xhsConfig.getAccounts()) {
            // 跳过未配置的账号
            if (account.getAppId() == null || account.getAppId() == 0) {
                continue;
            }
            // 跳过未授权的账号
            if (tokenService.getToken(account.getAppId()) == null) {
                log.warn("账号 {} (appId={}) 未授权，跳过", account.getName(), account.getAppId());
                continue;
            }

            try {
                syncOneAccount(account, today);
            } catch (Exception e) {
                log.error("同步账号 {} 失败", account.getName(), e);
            }
        }
        log.info("===== 报表同步完成 =====");
    }

    /**
     * 同步单个账号的数据
     */
    public void syncOneAccount(XhsConfig.AccountConfig account, String date) throws Exception {
        log.info("同步账号: {}, 日期: {}", account.getName(), date);

        // 1. 获取 XHS 报表数据
        Map<String, String> reportData = xhsReportService.fetchTodayReport(account.getAppId(), date);
        if (reportData.isEmpty()) {
            log.warn("账号 {} 无报表数据", account.getName());
            return;
        }

        // 2. 获取飞书 token
        String tenantToken = FeishuBitableUtil.getTenantAccessToken(
                feishuConfig.getAppId(), feishuConfig.getAppSecret());

        // 3. 构建飞书字段数据
        Map<String, Object> fields = new HashMap<>();
        fields.put("聚光账户名称", account.getName());

        // 日期字段：转为 Unix 时间戳（毫秒）
        LocalDate localDate = LocalDate.parse(date, DATE_FMT);
        long dateTimestamp = localDate.atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();
        fields.put("日期", new DateValue(dateTimestamp));

        // 映射报表数据字段
        for (Map.Entry<String, String> mapping : FIELD_MAPPING.entrySet()) {
            String xhsField = mapping.getKey();
            String feishuField = mapping.getValue();
            String value = reportData.get(xhsField);
            if (value != null) {
                try {
                    fields.put(feishuField, Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    fields.put(feishuField, value);
                }
            }
        }

        // 4. 查询是否已存在该账号+日期的记录
        String feishuDateStr = localDate.format(FEISHU_DATE_FMT);
        Map<String, String> searchConditions = new HashMap<>();
        searchConditions.put("聚光账户名称", account.getName());
        searchConditions.put("日期", feishuDateStr);

        String recordId = FeishuBitableUtil.searchRecordId(
                feishuConfig.getAppToken(), feishuConfig.getTableId(),
                searchConditions, tenantToken);

        // 5. 存在则更新，不存在则新增
        if (recordId != null) {
            log.info("更新已有记录, 账号={}, 日期={}, recordId={}", account.getName(), date, recordId);
            // 更新时不需要再传聚光账户名称和日期
            fields.remove("聚光账户名称");
            fields.remove("日期");
            FeishuBitableUtil.updateRecord(
                    feishuConfig.getAppToken(), feishuConfig.getTableId(),
                    recordId, fields, tenantToken);
        } else {
            log.info("创建新记录, 账号={}, 日期={}", account.getName(), date);
            FeishuBitableUtil.createRecord(
                    feishuConfig.getAppToken(), feishuConfig.getTableId(),
                    fields, tenantToken);
        }
    }

    /**
     * 手动触发同步
     */
    public void manualSync(String date) {
        log.info("手动触发同步, 日期={}", date);
        String syncDate = date != null ? date : LocalDate.now().format(DATE_FMT);

        for (XhsConfig.AccountConfig account : xhsConfig.getAccounts()) {
            if (account.getAppId() == null || account.getAppId() == 0) {
                continue;
            }
            if (tokenService.getToken(account.getAppId()) == null) {
                continue;
            }
            try {
                syncOneAccount(account, syncDate);
            } catch (Exception e) {
                log.error("手动同步账号 {} 失败", account.getName(), e);
            }
        }
    }
}