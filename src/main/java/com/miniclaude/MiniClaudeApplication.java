package com.miniclaude;

import com.miniclaude.infrastructure.engine.CliMain;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;

/**
 * Mini Claude 应用入口。
 * <p>
 * 默认启动 Spring Boot HTTP 服务；传入 {@code --cli} 时转交旧版终端 Agent 运行，
 * 便于在同一入口兼容 API 与命令行两种使用方式。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MiniClaudeApplication {

    /**
     * 根据首参数选择启动模式：{@code --cli} 走终端 Agent，否则启动 Web 服务。
     */
    public static void main(String[] args) {
        if (args.length > 0 && "--cli".equals(args[0])) {
            String[] cliArgs = Arrays.copyOfRange(args, 1, args.length);
            CliMain.main(cliArgs);
            return;
        }
        SpringApplication.run(MiniClaudeApplication.class, args);
    }
}
