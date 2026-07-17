package com.miniclaude.domain.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 治理数据的统一摘要入口。
 *
 * <p>这里固定使用 UTF-8 与 SHA-256，目的是让资产内容、发布清单、评测结果和审计事件在不同机器上
 * 得到相同摘要。摘要用于完整性校验和防篡改证据，不等同于数字签名，也不提供来源认证。</p>
 */
public final class GovernanceHash {
    private GovernanceHash() {
    }

    public static String sha256(String value) {
        try {
            // 固定输出 64 位小写十六进制，避免同一摘要因编码形式不同而破坏清单校验或审计 hash 链。
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
