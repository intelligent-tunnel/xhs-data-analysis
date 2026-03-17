package com.xhs.util;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xhs.model.DateValue;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class FeishuBitableUtil {

    private static final Map<String, TokenCache> TOKEN_CACHE = new ConcurrentHashMap<>();

    private static class TokenCache {
        private final String token;
        private final long expireTime;

        public TokenCache(String token, long expireTime) {
            this.token = token;
            this.expireTime = expireTime;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() >= expireTime;
        }

        public String getToken() {
            return token;
        }
    }

    public static String getTenantAccessToken(String appId, String appSecret) {
        TokenCache cached = TOKEN_CACHE.get(appId);
        if (cached != null && !cached.isExpired()) {
            return cached.getToken();
        }

        String url = FeishuUtil.buildApiUrl("/auth/v3/tenant_access_token/internal");
        Map<String, String> body = new HashMap<>();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);

        HttpResponse httpResponse = HttpRequest.post(url)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(JSONUtil.toJsonStr(body))
                .execute();
        JSONObject response = JSONUtil.parseObj(httpResponse.body());

        Integer code = response.getInt("code");
        if (code == null || code != 0) {
            throw new RuntimeException("获取 tenant_access_token 失败: " + response.getStr("msg"));
        }

        String token = response.getStr("tenant_access_token");
        int expire = response.getInt("expire", 7200);
        TOKEN_CACHE.put(appId, new TokenCache(token, System.currentTimeMillis() + (expire - 300) * 1000L));

        return token;
    }

    /**
     * 搜索记录，返回所有匹配的 record_id 映射（指定字段值 → record_id）
     * 用于批量匹配：传入日期条件，返回该日期下所有记录的 {计划ID: record_id}
     */
    public static Map<String, String> searchRecords(String appToken, String tableId,
                                                     String filterFieldName, Object filterFieldValue,
                                                     String keyFieldName,
                                                     String tenantAccessToken) {
        Map<String, String> result = new HashMap<>();
        String pageToken = null;

        do {
            String url = FeishuUtil.buildApiUrl(
                    String.format("/bitable/v1/apps/%s/tables/%s/records/search", appToken, tableId));

            Map<String, Object> body = new HashMap<>();
            Map<String, Object> filter = new HashMap<>();
            filter.put("conjunction", "and");

            Map<String, Object> cond = new HashMap<>();
            cond.put("field_name", filterFieldName);
            cond.put("operator", "is");
            cond.put("value", List.of(filterFieldValue));
            filter.put("conditions", List.of(cond));

            body.put("filter", filter);
            body.put("page_size", 500);
            if (pageToken != null) {
                body.put("page_token", pageToken);
            }

            try {
                JSONObject resp = FeishuUtil.post(url, tenantAccessToken, body);
                JSONObject data = resp.getJSONObject("data");
                if (data == null) break;

                if (data.getJSONArray("items") != null) {
                    for (Object item : data.getJSONArray("items")) {
                        JSONObject record = (JSONObject) item;
                        String recordId = record.getStr("record_id");
                        JSONObject fields = record.getJSONObject("fields");
                        if (fields != null) {
                            // keyFieldName 的值可能是数字或文本
                            Object keyVal = fields.get(keyFieldName);
                            if (keyVal != null) {
                                result.put(String.valueOf(keyVal), recordId);
                            }
                        }
                    }
                }

                boolean hasMore = data.getBool("has_more", false);
                pageToken = hasMore ? data.getStr("page_token") : null;
            } catch (Exception e) {
                log.error("搜索多维表格记录失败", e);
                break;
            }
        } while (pageToken != null);

        log.info("搜索到 {} 条已有记录 ({}={})", result.size(), filterFieldName, filterFieldValue);
        return result;
    }

    /**
     * 批量创建记录（飞书限制每次最多 500 条）
     */
    public static int batchCreateRecords(String appToken, String tableId,
                                          List<Map<String, Object>> recordsList,
                                          String tenantAccessToken) {
        if (recordsList == null || recordsList.isEmpty()) {
            return 0;
        }

        String url = FeishuUtil.buildApiUrl(
                String.format("/bitable/v1/apps/%s/tables/%s/records/batch_create", appToken, tableId));

        int created = 0;
        // 每批最多 500 条
        for (int i = 0; i < recordsList.size(); i += 500) {
            List<Map<String, Object>> batch = recordsList.subList(i, Math.min(i + 500, recordsList.size()));

            List<Map<String, Object>> records = new ArrayList<>();
            for (Map<String, Object> fields : batch) {
                Map<String, Object> record = new HashMap<>();
                record.put("fields", convertFields(fields));
                records.add(record);
            }

            Map<String, Object> body = new HashMap<>();
            body.put("records", records);

            try {
                JSONObject resp = FeishuUtil.post(url, tenantAccessToken, body);
                JSONObject data = resp.getJSONObject("data");
                if (data != null && data.getJSONArray("records") != null) {
                    created += data.getJSONArray("records").size();
                }
                log.info("批量创建成功: 本批 {} 条", batch.size());
            } catch (Exception e) {
                log.error("批量创建记录失败, 本批 {} 条", batch.size(), e);
            }
        }

        return created;
    }

    /**
     * 批量更新记录（飞书限制每次最多 500 条）
     * recordsList 中每个 map 必须包含 "record_id" 键
     */
    public static int batchUpdateRecords(String appToken, String tableId,
                                          List<Map<String, Object>> recordsList,
                                          String tenantAccessToken) {
        if (recordsList == null || recordsList.isEmpty()) {
            return 0;
        }

        String url = FeishuUtil.buildApiUrl(
                String.format("/bitable/v1/apps/%s/tables/%s/records/batch_update", appToken, tableId));

        int updated = 0;
        for (int i = 0; i < recordsList.size(); i += 500) {
            List<Map<String, Object>> batch = recordsList.subList(i, Math.min(i + 500, recordsList.size()));

            List<Map<String, Object>> records = new ArrayList<>();
            for (Map<String, Object> item : batch) {
                String recordId = (String) item.get("record_id");
                Map<String, Object> fields = new HashMap<>(item);
                fields.remove("record_id");

                Map<String, Object> record = new HashMap<>();
                record.put("record_id", recordId);
                record.put("fields", convertFields(fields));
                records.add(record);
            }

            Map<String, Object> body = new HashMap<>();
            body.put("records", records);

            try {
                FeishuUtil.post(url, tenantAccessToken, body);
                updated += batch.size();
                log.info("批量更新成功: 本批 {} 条", batch.size());
            } catch (Exception e) {
                log.error("批量更新记录失败, 本批 {} 条", batch.size(), e);
            }
        }

        return updated;
    }

    static Map<String, Object> convertFields(Map<String, Object> fields) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof DateValue dateValue) {
                result.put(entry.getKey(), dateValue.getTimestamp());
            } else if (value instanceof Number) {
                result.put(entry.getKey(), value);
            } else {
                result.put(entry.getKey(), String.valueOf(value));
            }
        }
        return result;
    }
}