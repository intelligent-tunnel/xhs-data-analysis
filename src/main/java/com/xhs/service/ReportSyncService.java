package com.xhs.service;

import com.xhs.config.FeishuConfig;
import com.xhs.config.XhsConfig;
import com.xhs.model.DateValue;
import com.xhs.model.TokenInfo;
import com.xhs.util.FeishuBitableUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

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

    /** XHS API 字段 → 飞书表格字段名 */
    private static final Map<String, String> METRIC_MAPPING = new LinkedHashMap<>();
    static {
        METRIC_MAPPING.put("fee", "消费");
        METRIC_MAPPING.put("impression", "展现量");
        METRIC_MAPPING.put("click", "点击量");
        METRIC_MAPPING.put("ctr", "点击率");
        METRIC_MAPPING.put("acp", "平均点击成本");
        METRIC_MAPPING.put("cpm", "平均千次展示费用");
        METRIC_MAPPING.put("interaction", "互动量");
        METRIC_MAPPING.put("cpi", "平均互动成本");
        METRIC_MAPPING.put("message_consult", "私信进线数");
        METRIC_MAPPING.put("initiative_message", "私信开口数");
        METRIC_MAPPING.put("msg_leads_num", "私信留资数");
        METRIC_MAPPING.put("message_consult_cpl", "私信进线成本");
        METRIC_MAPPING.put("initiative_message_cpl", "私信开口成本");
        METRIC_MAPPING.put("msg_leads_cost", "私信留资成本");
    }

    /**
     * 每 2 小时执行一次
     */
    @Scheduled(fixedRate = 2 * 60 * 60 * 1000, initialDelay = 10 * 1000)
    public void syncAllAccounts() {
        log.info("===== 开始同步计划报表数据到飞书 =====");
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE_FMT);

        for (XhsConfig.AccountConfig account : xhsConfig.getAccounts()) {
            if (account.getAppId() == null || account.getAppId() == 0) {
                continue;
            }
            if (tokenService.getToken(account.getAppId()) == null) {
                log.warn("账号 {} (appId={}) 未授权，跳过", account.getName(), account.getAppId());
                continue;
            }

            List<Long> advertiserIds = account.getAdvertiserIds();
            if (advertiserIds == null || advertiserIds.isEmpty()) {
                log.warn("账号 {} (appId={}) 未配置 advertiser-ids，跳过", account.getName(), account.getAppId());
                continue;
            }

            for (Long advertiserId : advertiserIds) {
                try {
                    syncOneAdvertiser(account.getAppId(), account.getName(), advertiserId, today);
                } catch (Exception e) {
                    log.error("同步广告主 id={} 失败", advertiserId, e);
                }
            }
        }
        log.info("===== 计划报表同步完成 =====");
    }

    /**
     * 同步单个广告主下的所有有效计划
     */
    public void syncOneAdvertiser(Integer appId, String accountName, Long advertiserId, String date) throws Exception {
        log.info("同步广告主: accountName={}, advertiserId={}, 日期={}", accountName, advertiserId, date);

        // 1. 获取有效计划数据
        List<XhsReportService.CampaignReport> campaigns =
                xhsReportService.fetchActiveCampaigns(appId, advertiserId, date);
        if (campaigns.isEmpty()) {
            log.info("广告主 {} 无有效计划数据", advertiserId);
            return;
        }

        // 2. 获取飞书 token
        String tenantToken = FeishuBitableUtil.getTenantAccessToken(
                feishuConfig.getAppId(), feishuConfig.getAppSecret());

        // 3. 查询飞书中该日期已有的记录 {计划ID: record_id}
        LocalDate localDate = LocalDate.parse(date, DATE_FMT);

        // 4. 构建日期时间戳
        long dateTimestamp = localDate.atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();

        Map<String, String> existingRecords = FeishuBitableUtil.searchRecords(
                feishuConfig.getAppToken(), feishuConfig.getTableId(),
                "日期", String.valueOf(dateTimestamp),
                "在跑计划ID",
                tenantToken);

        // 5. 分为待新增和待更新
        List<Map<String, Object>> toCreate = new ArrayList<>();
        List<Map<String, Object>> toUpdate = new ArrayList<>();

        for (XhsReportService.CampaignReport campaign : campaigns) {
            Map<String, Object> fields = buildFields(accountName, campaign, dateTimestamp);

            String campaignIdStr = String.valueOf(campaign.getCampaignId());
            String recordId = existingRecords.get(campaignIdStr);

            if (recordId != null) {
                // 已存在 → 更新（只更新指标字段）
                Map<String, Object> updateFields = new HashMap<>();
                for (String feishuField : METRIC_MAPPING.values()) {
                    if (fields.containsKey(feishuField)) {
                        updateFields.put(feishuField, fields.get(feishuField));
                    }
                }
                updateFields.put("record_id", recordId);
                toUpdate.add(updateFields);
            } else {
                toCreate.add(fields);
            }
        }

        // 6. 批量写入飞书
        if (!toCreate.isEmpty()) {
            int created = FeishuBitableUtil.batchCreateRecords(
                    feishuConfig.getAppToken(), feishuConfig.getTableId(),
                    toCreate, tenantToken);
            log.info("广告主 {}: 新增 {} 条计划记录", advertiserId, created);
        }
        if (!toUpdate.isEmpty()) {
            int updated = FeishuBitableUtil.batchUpdateRecords(
                    feishuConfig.getAppToken(), feishuConfig.getTableId(),
                    toUpdate, tenantToken);
            log.info("广告主 {}: 更新 {} 条计划记录", advertiserId, updated);
        }
        if (toCreate.isEmpty() && toUpdate.isEmpty()) {
            log.info("广告主 {}: 无需新增或更新", advertiserId);
        }
    }

    private Map<String, Object> buildFields(String accountName,
                                             XhsReportService.CampaignReport campaign,
                                             long dateTimestamp) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("日期", new DateValue(dateTimestamp));
        fields.put("账号", accountName);
        fields.put("在跑计划ID", String.valueOf(campaign.getCampaignId()));
        fields.put("在跑计划名称", campaign.getCampaignName());

        Map<String, String> metrics = campaign.getMetrics();
        for (Map.Entry<String, String> mapping : METRIC_MAPPING.entrySet()) {
            String value = metrics.get(mapping.getKey());
            if (value != null) {
                try {
                    String numStr = value.endsWith("%") ? value.substring(0, value.length() - 1) : value;
                    fields.put(mapping.getValue(), Double.parseDouble(numStr));
                } catch (NumberFormatException e) {
                    fields.put(mapping.getValue(), value);
                }
            }
        }

        return fields;
    }

    /**
     * 手动触发同步
     */
    public void manualSync(String date) {
        log.info("手动触发同步, 日期={}", date);
        String syncDate = date != null ? date : LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE_FMT);

        for (XhsConfig.AccountConfig account : xhsConfig.getAccounts()) {
            if (account.getAppId() == null || account.getAppId() == 0) {
                continue;
            }
            if (tokenService.getToken(account.getAppId()) == null) {
                continue;
            }

            List<Long> advertiserIds = account.getAdvertiserIds();
            if (advertiserIds == null || advertiserIds.isEmpty()) {
                continue;
            }

            for (Long advertiserId : advertiserIds) {
                try {
                    syncOneAdvertiser(account.getAppId(), account.getName(), advertiserId, syncDate);
                } catch (Exception e) {
                    log.error("手动同步广告主 {} 失败", advertiserId, e);
                }
            }
        }
    }
}
