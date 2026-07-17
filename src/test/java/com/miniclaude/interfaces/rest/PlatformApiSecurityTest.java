package com.miniclaude.interfaces.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证平台 API 在配置密钥时默认拒绝缺失凭证，并接受正确凭证。
 *
 * <p>测试经由完整 MockMvc 过滤链访问真实端点，避免只测试比较函数而遗漏过滤器装配。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "platform.security.api-key=test-platform-key"
})
@AutoConfigureMockMvc
class PlatformApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsMissingApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/platform/agents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsConfiguredApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/platform/agents")
                        .header("X-Platform-Api-Key", "test-platform-key"))
                .andExpect(status().isOk());
    }
}
