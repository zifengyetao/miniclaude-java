package com.miniclaude;

import com.miniclaude.infrastructure.engine.CliMain;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;

/**
 * Mini Claude 平台 Spring Boot 主入口。
 * <p>
 * <b>职责</b>：根据启动参数在 HTTP 服务模式与 CLI 终端模式之间二选一，是整个 JVM 进程的唯一
 * {@code main} 入口。
 * <p>
 * <b>上游</b>：运维/开发者通过 {@code java -jar} 或 IDE 启动；CLI 模式由 {@link CliMain} 承接。
 * <b>下游</b>：Web 模式加载 Spring 容器，暴露 {@code interfaces.rest} 层 REST API 及全部
 * {@code application} 服务；CLI 模式绕过 HTTP，直接驱动旧版终端 Agent 引擎。
 * <p>
 * <b>安全/约束</b>：本类不做鉴权；HTTP 安全边界由 Spring Security 过滤器链承担。两种模式共享
 * 同一 classpath，但 CLI 分支不启动 Web 容器，避免在无 HTTP 场景下暴露管理端口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MiniClaudeApplication {

    /**
     * 进程入口：解析首参数决定启动路径。
     *
     * @param args 命令行参数；首参为 {@code --cli} 时剥离后转交 {@link CliMain#main}，
     *             否则原样交给 {@link SpringApplication#run}
     * @throws 无显式抛出；底层 Spring/CLI 初始化失败会以非零退出码终止进程
     * @implNote 使用 {@code --cli} 前缀而非子命令，是为与 Spring Boot 标准参数（如 {@code --server.port}）
     *           区分，防止误将 Spring 配置当作 CLI 标志
     */
    public static void main(String[] args) {
        // 分支一：CLI 模式 —— 跳过 Spring Boot 容器，降低本地调试时的启动开销
        if (args.length > 0 && "--cli".equals(args[0])) {
            String[] cliArgs = Arrays.copyOfRange(args, 1, args.length);
            CliMain.main(cliArgs);
            return;
        }
        // 分支二：默认 Web 模式 —— 启动完整 Spring Boot 应用栈
        SpringApplication.run(MiniClaudeApplication.class, args);
    }
}
