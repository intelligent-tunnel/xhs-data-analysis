package com.xhs.service;

import com.xhs.config.FeishuConfig;
import com.xhs.config.XhsConfig;
import com.xhs.model.DateValue;
import com.xhs.model.TokenInfo;
import com.xhs.util.FeishuBitableUtil;
import com.xhs.util.FeishuUtil;
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
public class AccountReportSyncService {

    private final XhsReportService xhsReportService;
    private final TokenService tokenService;
    private final XhsConfig xhsConfig;
    private final FeishuConfig feishuConfig;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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
     * 每 2 小时执行一次（与计划粒度同步错开 10 分钟）
     */
    @Scheduled(fixedRate = 2 * 60 * 60 * 1000, initialDelay = 20 * 1000)
    public void syncAllAccounts() {
        log.info("===== 开始同步账号粒度报表数据到飞书 =====");
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE_FMT);
        log.info("当前同步日期: {}, 配置的账号数量: {}", today, xhsConfig.getAccounts().size());

        for (XhsConfig.AccountConfig account : xhsConfig.getAccounts()) {
            log.debug("检查账号: name={}, appId={}", account.getName(), account.getAppId());

            if (account.getAppId() == null || account.getAppId() == 0) {
                log.warn("账号 {} appId 无效，跳过", account.getName());
                continue;
            }

            TokenInfo token = tokenService.getToken(account.getAppId());
            if (token == null) {
                log.warn("账号 {} (appId={}) 未授权，跳过", account.getName(), account.getAppId());
                continue;
            }
            log.debug("账号 {} token 存在, advertiserId={}", account.getName(), token.getAdvertiserId());

            List<Long> advertiserIds = account.getAdvertiserIds();
            if (advertiserIds == null || advertiserIds.isEmpty()) {
                log.warn("账号 {} (appId={}) 未配置 advertiser-ids，跳过", account.getName(), account.getAppId());
                continue;
            }
            log.debug("账号 {} 配置了 {} 个广告主", account.getName(), advertiserIds.size());

            try {
                syncOneAccount(account, today);
            } catch (Exception e) {
                log.error("同步账号 {} 失败", account.getName(), e);
            }
        }
        log.info("===== 账号粒度报表同步完成 =====");
    }

    /**
     * 同步单个账号（汇总该账号下所有广告主的数据）
     */
    public void syncOneAccount(XhsConfig.AccountConfig account, String date) throws Exception {
        log.info("同步账号: accountName={}, 日期={}", account.getName(), date);

        // 1. 获取该账号下所有广告主的计划数据并汇总
        Map<String, Double> aggregatedMetrics = new HashMap<>();
        List<XhsReportService.CampaignReport> allCampaigns = new ArrayList<>();

        for (Long advertiserId : account.getAdvertiserIds()) {
            try {
                List<XhsReportService.CampaignReport> campaigns =
                        xhsReportService.fetchActiveCampaigns(account.getAppId(), advertiserId, date);
                allCampaigns.addAll(campaigns);
                log.info("账号 {} 的广告主 {} 获取 {} 个计划", account.getName(), advertiserId, campaigns.size());
            } catch (Exception e) {
                log.error("获取广告主 {} 数据失败", advertiserId, e);
            }
        }

        if (allCampaigns.isEmpty()) {
            log.info("账号 {} 无有效计划数据", account.getName());
            return;
        }

        // 2. 汇总数据（累加所有计划的指标）
        for (XhsReportService.CampaignReport campaign : allCampaigns) {
            Map<String, String> metrics = campaign.getMetrics();
            for (Map.Entry<String, String> entry : metrics.entrySet()) {
                String xhsField = entry.getKey();
                String value = entry.getValue();
                if (value == null || value.isEmpty()) {
                    continue;
                }

                try {
                    // 去除百分号并解析数值
                    String numStr = value.endsWith("%") ? value.substring(0, value.length() - 1) : value;
                    double num = Double.parseDouble(numStr);
                    if (value.endsWith("%")) {
                        num = num / 100;
                    }
                    aggregatedMetrics.merge(xhsField, num, Double::sum);
                } catch (NumberFormatException e) {
                    // 非数值字段跳过
                }
            }
        }

        log.info("账号 {} 汇总完成，共 {} 个计划", account.getName(), allCampaigns.size());

        // 3. 获取飞书 token
        String tenantToken = FeishuBitableUtil.getTenantAccessToken(
                feishuConfig.getAppId(), feishuConfig.getAppSecret());

        // 4. 查询飞书中该日期该账号是否已有记录
        LocalDate localDate = LocalDate.parse(date, DATE_FMT);
        long dateTimestamp = localDate.atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();

        // 构建查询条件：日期 + 账号名称
        Map<String, String> existingRecords = searchAccountRecords(
                feishuConfig.getAppToken(), feishuConfig.getAccountTableId(),
                dateTimestamp, account.getName(), tenantToken);

        // 5. 构建字段数据
        Map<String, Object> fields = buildAccountFields(account.getName(), dateTimestamp, aggregatedMetrics);

        // 6. 写入飞书（新增或更新）
        String recordId = existingRecords.get(account.getName());
        if (recordId != null) {
            // 更新
            List<Map<String, Object>> toUpdate = new ArrayList<>();
            Map<String, Object> updateFields = new HashMap<>();
            // 只更新指标字段
            for (Map.Entry<String, String> mapping : METRIC_MAPPING.entrySet()) {
                String feishuField = mapping.getValue();
                if (fields.containsKey(feishuField)) {
                    updateFields.put(feishuField, fields.get(feishuField));
                }
            }
            updateFields.put("record_id", recordId);
            toUpdate.add(updateFields);

            int updated = FeishuBitableUtil.batchUpdateRecords(
                    feishuConfig.getAppToken(), feishuConfig.getAccountTableId(),
                    toUpdate, tenantToken);
            log.info("账号 {}: 更新 {} 条记录", account.getName(), updated);
        } else {
            // 新增
            List<Map<String, Object>> toCreate = new ArrayList<>();
            toCreate.add(fields);

            int created = FeishuBitableUtil.batchCreateRecords(
                    feishuConfig.getAppToken(), feishuConfig.getAccountTableId(),
                    toCreate, tenantToken);
            log.info("账号 {}: 新增 {} 条记录", account.getName(), created);
        }
    }

    /**
     * 搜索账号粒度的记录（按日期和账号名称）
     */
    private Map<String, String> searchAccountRecords(String appToken, String tableId,
                                                      long dateTimestamp, String accountName,
                                                      String tenantAccessToken) {
        Map<String, String> result = new HashMap<>();
        String pageToken = null;

        do {
            String url = FeishuBitableUtil.buildApiUrl(
                    String.format("/bitable/v1/apps/%s/tables/%s/records/search", appToken, tableId));

            Map<String, Object> body = new HashMap<>();
            Map<String, Object> filter = new HashMap<>();
            filter.put("conjunction", "and");

            // 日期条件
            Map<String, Object> dateCond = new HashMap<>();
            dateCond.put("field_name", "日期");
            dateCond.put("operator", "is");
            dateCond.put("value", List.of("ExactDate", String.valueOf(dateTimestamp)));

            // 账号名称条件
            Map<String, Object> accountCond = new HashMap<>();
            accountCond.put("field_name", "聚光账户名称");
            accountCond.put("operator", "is");
            accountCond.put("value", List.of("ExactText", accountName));

            filter.put("conditions", List.of(dateCond, accountCond));
            body.put("filter", filter);
            body.put("page_size", 500);
            if (pageToken != null) {
                body.put("page_token", pageToken);
            }

            try {
                cn.hutool.json.JSONObject resp = FeishuUtil.post(url, tenantAccessToken, body);
                cn.hutool.json.JSONObject data = resp.getJSONObject("data");
                if (data == null) break;

                if (data.getJSONArray("items") != null) {
                    for (Object item : data.getJSONArray("items")) {
                        cn.hutool.json.JSONObject record = (cn.hutool.json.JSONObject) item;
                        String recordId = record.getStr("record_id");
                        cn.hutool.json.JSONObject fields = record.getJSONObject("fields");
                        if (fields != null) {
                            Object accountVal = fields.get("聚光账户名称");
                            String accountStr = FeishuBitableUtil.extractFieldText(accountVal);
                            if (accountStr != null) {
                                result.put(accountStr, recordId);
                            }
                        }
                    }
                }

                boolean hasMore = data.getBool("has_more", false);
                pageToken = hasMore ? data.getStr("page_token") : null;
            } catch (Exception e) {
                log.error("搜索账号记录失败", e);
                break;
            }
        } while (pageToken != null);

        log.info("搜索到 {} 条已有账号记录 (日期={}, 账号={})", result.size(), dateTimestamp, accountName);
        return result;
    }

    private Map<String, Object> buildAccountFields(String accountName, long dateTimestamp,
                                                    Map<String, Double> aggregatedMetrics) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("日期", new DateValue(dateTimestamp));
        fields.put("聚光账户名称", accountName);

        // 汇总后的指标数据
        for (Map.Entry<String, String> mapping : METRIC_MAPPING.entrySet()) {
            String xhsField = mapping.getKey();
            String feishuField = mapping.getValue();
            Double value = aggregatedMetrics.get(xhsField);
            if (value != null) {
                fields.put(feishuField, value);
            }
        }

        return fields;
    }

    /**
     * 手动触发账号粒度同步
     */
    public void manualSync(String date) {
        log.info("手动触发账号粒度同步, 日期={}", date);
        String syncDate = date != null ? date : LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE_FMT);

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
