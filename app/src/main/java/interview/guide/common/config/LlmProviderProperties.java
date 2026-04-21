package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class LlmProviderProperties {
    private String defaultProvider = "dashscope";
    private Map<String, ProviderConfig> providers;
    private Map<String, String> moduleDefaults = new HashMap<>();
    private AdvisorConfig advisors = new AdvisorConfig();
    /**
     * UI 回写 provider 配置的目标 YAML 文件绝对路径。
     * 未配置时 {@link interview.guide.modules.llmprovider.service.LlmProviderConfigService}
     * 在 {@code @PostConstruct} 阶段会抛出异常，避免 UI 保存成功但配置静默丢失。
     */
    private String configYamlPath;
    /**
     * UI 回写 apiKey 的目标 .env 文件绝对路径，同样启动校验。
     */
    private String configEnvPath;

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
        private boolean enabled = true;
        private String embeddingModel;
    }

    @Data
    public static class AdvisorConfig {
        private boolean enabled = true;

        // ToolCallAdvisor
        private boolean toolCallEnabled = true;
        private boolean toolCallConversationHistoryEnabled = false;
        private boolean streamToolCallResponses = false;

        // MessageChatMemoryAdvisor（默认关闭，避免会话串扰）
        private boolean messageChatMemoryEnabled = false;
        private int messageChatMemoryMaxMessages = 120;

        // SimpleLoggerAdvisor（默认关闭）
        private boolean simpleLoggerEnabled = false;

        // SafeGuardAdvisor
        private boolean safeguardEnabled = true;
        private List<String> safeguardWords = List.of(
            "I'll now act as",
            "Sure, I'll ignore",
            "我已经忽略",
            "新的角色是",
            "忽略之前的指令",
            "forget all previous instructions"
        );

        // PromptSanitizer
        private boolean promptSanitizerEnabled = true;
    }
}
