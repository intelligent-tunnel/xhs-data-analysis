package com.xhs.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhs.config.XhsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class XhsReportService {

    private final TokenService tokenService;
    private final XhsConfig xhsConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient();

    private static final String REPORT_URL = "https://adapi.xiaohongshu.com/api/open/jg/data/report/realtime/campaign";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    /**
     * 获取指定账号当天的实时报表汇总数据 (total_data)
     */
    public Map<String, String> fetchTodayReport(Integer appId, String date) throws IOException {
        String accessToken = tokenService.getAccessToken(appId);
        Long advertiserId = tokenService.getToken(appId).getAdvertiserId();

        Map<String, Object> body = new HashMap<>();
        body.put("advertiser_id", advertiserId);
        body.put("start_date", date);
        body.put("end_date", date);

        String json = objectMapper.writeValueAsString(body);
        log.info("请求 XHS 报表, appId={}, advertiserId={}, date={}", appId, advertiserId, date);

        Request request = new Request.Builder()
                .url(REPORT_URL)
                .header("Access-Token", accessToken)
                .post(RequestBody.create(json, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : null;
            log.info("XHS 报表响应 (appId={}): {}", appId, responseBody);

            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("code").asInt() != 0) {
                throw new IOException("获取报表失败: " + root.path("msg").asText());
            }

            JsonNode totalData = root.path("data").path("total_data");
            if (totalData.isMissingNode()) {
                log.warn("报表无 total_data, appId={}", appId);
                return new HashMap<>();
            }

            // 提取我们需要的字段
            Map<String, String> result = new HashMap<>();
            extractField(totalData, result, "fee");
            extractField(totalData, result, "impression");
            extractField(totalData, result, "click");
            extractField(totalData, result, "ctr");
            extractField(totalData, result, "acp");
            extractField(totalData, result, "cpm");
            extractField(totalData, result, "interaction");
            extractField(totalData, result, "cpi");
            extractField(totalData, result, "message_consult");
            extractField(totalData, result, "initiative_message");
            extractField(totalData, result, "msg_leads_num");
            extractField(totalData, result, "message_consult_cpl");
            extractField(totalData, result, "initiative_message_cpl");
            extractField(totalData, result, "msg_leads_cost");

            return result;
        }
    }

    private void extractField(JsonNode node, Map<String, String> result, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isMissingNode() && !value.isNull()) {
            result.put(fieldName, value.asText());
        }
    }
}