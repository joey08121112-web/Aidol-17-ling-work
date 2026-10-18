package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class ApiClientErrorMessageTest {
    @Test public void chineseServerDetailIsShownAsIs() {
        assertEquals("登录已失效，请重新登录", ApiClient.errorMessage(401, "{\"detail\":\"登录已失效，请重新登录\"}"));
        assertEquals("请输入内容", ApiClient.errorMessage(422, "{\"detail\":\" 请输入内容 \"}"));
    }

    @Test public void frameworkRouteErrorsBecomeVersionMismatch() {
        assertEquals("服务版本暂不匹配，请更新 App 或稍后再试（错误 404）", ApiClient.errorMessage(404, "{\"detail\":\"Not Found\"}"));
        assertEquals("服务版本暂不匹配，请更新 App 或稍后再试（错误 405）", ApiClient.errorMessage(405, "{\"detail\":\"Method Not Allowed\"}"));
    }

    @Test public void untranslatedOrMissingDetailsNeverReachTheUser() {
        String[] bodies = {"{\"detail\":\"Internal Server Error\"}", "{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"field required\"}]}",
            "<html>Bad Gateway</html>", "", null, "{}"};
        for (String body : bodies) {
            String message = ApiClient.errorMessage(502, body);
            assertEquals(String.valueOf(body), "服务暂不可用，请稍后再试（错误 502）", message);
        }
    }
}
