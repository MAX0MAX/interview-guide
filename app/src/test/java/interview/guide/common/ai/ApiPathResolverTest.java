package interview.guide.common.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiPathResolverTest {

  @Nested
  @DisplayName("baseUrl 包含版本段识别")
  class ContainsVersion {

    @Test
    @DisplayName("OpenAI 原生 base-url（不含版本段）返回 false")
    void plainBaseUrl() {
      assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.openai.com")).isFalse();
      assertThat(ApiPathResolver.baseUrlContainsVersion("http://localhost:1234")).isFalse();
    }

    @Test
    @DisplayName("以 /v1 结尾（Kimi / DeepSeek / SiliconFlow / DashScope 兼容模式）返回 true")
    void trailingV1() {
      assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.moonshot.cn/v1")).isTrue();
      assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.deepseek.com/v1")).isTrue();
      assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.siliconflow.cn/v1")).isTrue();
      assertThat(ApiPathResolver.baseUrlContainsVersion(
          "https://dashscope.aliyuncs.com/compatible-mode/v1")).isTrue();
    }

    @Test
    @DisplayName("以 /v3 /v4 等非 v1 版本段结尾（豆包 Ark / 智谱 GLM）返回 true")
    void trailingOtherVersion() {
      assertThat(ApiPathResolver.baseUrlContainsVersion(
          "https://ark.cn-beijing.volces.com/api/v3")).isTrue();
      assertThat(ApiPathResolver.baseUrlContainsVersion(
          "https://open.bigmodel.cn/api/paas/v4")).isTrue();
    }

    @Test
    @DisplayName("末尾带 / 或空白不影响识别")
    void trailingSlashOrWhitespace() {
      assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.moonshot.cn/v1/")).isTrue();
      assertThat(ApiPathResolver.baseUrlContainsVersion("  https://api.moonshot.cn/v1  ")).isTrue();
    }

    @Test
    @DisplayName("null / 空串返回 false")
    void nullOrBlank() {
      assertThat(ApiPathResolver.baseUrlContainsVersion(null)).isFalse();
      assertThat(ApiPathResolver.baseUrlContainsVersion("")).isFalse();
      assertThat(ApiPathResolver.baseUrlContainsVersion("   ")).isFalse();
    }

    @Test
    @DisplayName("版本段在中段而不在末尾（后面还有其它 path）返回 false")
    void versionNotAtTail() {
      assertThat(ApiPathResolver.baseUrlContainsVersion(
          "https://api.example.com/v1/proxy")).isFalse();
    }
  }
}
