package com.xhs.util;

import cn.hutool.core.util.StrUtil;
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
     * 按多个字段条件查询记录，返回第一条记录的 record_id
     */
    public static String searchRecordId(String appToken, String tableId,
                                        Map<String, String> conditions, String tenantAccessToken) {
        String url = FeishuUtil.buildApiUrl(
                String.format("/bitable/v1/apps/%s/tables/%s/records/search", appToken, tableId));

        Map<String, Object> body = new HashMap<>();
        Map<String, Object> filter = new HashMap<>();
        filter.put("conjunction", "and");

        List<Map<String, Object>> condList = new ArrayList<>();
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            Map<String, Object> cond = new HashMap<>();
            cond.put("field_name", entry.getKey());
            cond.put("operator", "is");
            cond.put("value", List.of(entry.getValue()));
            condList.add(cond);
        }

        filter.put("conditions", condList);
        body.put("filter", filter);
        body.put("page_size", 1);

        try {
            JSONObject resp = FeishuUtil.post(url, tenantAccessToken, body);
            JSONObject data = resp.getJSONObject("data");
            if (data == null || data.getJSONArray("items") == null) {
                return null;
            }
            List<JSONObject> items = data.getJSONArray("items").toList(JSONObject.class);
            return items.isEmpty() ? null : items.getFirst().getStr("record_id");
        } catch (Exception e) {
            log.error("查询多维表格记录失败", e);
            return null;
        }
    }

    /**
     * 创建多维表格记录
     */
    public static String createRecord(String appToken, String tableId,
                                      Map<String, Object> fields, String tenantAccessToken) {
        String url = FeishuUtil.buildApiUrl(
                String.format("/bitable/v1/apps/%s/tables/%s/records", appToken, tableId));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("fields", convertFields(fields));

        try {
            JSONObject resp = FeishuUtil.post(url, tenantAccessToken, requestBody);
            JSONObject data = resp.getJSONObject("data");
            if (data != null && data.getJSONObject("record") != null) {
                String recordId = data.getJSONObject("record").getStr("record_id");
                log.info("创建多维表格记录成功: recordId={}", recordId);
                return recordId;
            }
            return null;
        } catch (Exception e) {
            log.error("创建多维表格记录失败", e);
            return null;
        }
    }

    /**
     * 更新多维表格记录
     */
    public static boolean updateRecord(String appToken, String tableId, String recordId,
                                       Map<String, Object> fields, String tenantAccessToken) {
        String url = FeishuUtil.buildApiUrl(
                String.format("/bitable/v1/apps/%s/tables/%s/records/%s", appToken, tableId, recordId));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("fields", convertFields(fields));

        try {
            FeishuUtil.put(url, tenantAccessToken, requestBody);
            log.info("更新多维表格记录成功: recordId={}", recordId);
            return true;
        } catch (Exception e) {
            log.error("更新多维表格记录失败: recordId={}", recordId, e);
            return false;
        }
    }

    private static Map<String, Object> convertFields(Map<String, Object> fields) {
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