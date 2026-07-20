package com.miniclaude.domain.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 治理数据的统一摘要入口（纯函数工具类）。
 * <p>
 * <b>为何放在 domain：</b>资产、清单、评测、审计事件需跨机器确定性哈希，属于治理领域语言。
 * <p>
 * <b>不变量：</b>固定 UTF-8 + SHA-256，输出 64 位小写十六进制。
 * <p>
 * <b>边界：</b>摘要用完整性校验与防篡改证据；<b>非</b>数字签名，不提供来源认证。
 */
public final class GovernanceHash {

    /** 私有构造，禁止实例化。 */
    private GovernanceHash() {
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要。
     *
     * @param value 输入文本，null 按 NPE 或引擎约定（当前 getBytes 前不应传 null）
     * @return 64 字符小写 hex
     * @throws IllegalStateException SHA-256 算法不可用（JVM 配置异常）
     */
    public static String sha256(String value) {
        try {
            // 固定 UTF-8 编码，避免平台默认 Charset 导致跨环境哈希不一致
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                // 每位字节格式化为两位 hex，保证 manifestHash 可字符串比较
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
