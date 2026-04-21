package interview.guide.common.ai;

import java.util.regex.Pattern;

/**
 * 判断 OpenAI-兼容 provider 的 base-url 是否自带版本段（如 /v1、/v3、/api/paas/v4）。
 *
 * <p>背景：Spring AI {@code OpenAiApi} 默认 {@code completionsPath=/v1/chat/completions}、
 * {@code embeddingsPath=/v1/embeddings}，它假设 base-url 形如 {@code https://api.openai.com}。
 * 但国内常用的 OpenAI-兼容端点（阿里 DashScope {@code /compatible-mode/v1}、Kimi {@code /v1}、
 * 智谱 {@code /api/paas/v4}、豆包 Ark {@code /api/v3}、DeepSeek/SiliconFlow {@code /v1} ...）
 * 都把版本段放在 base-url 里，按默认路径拼接会得到 {@code .../v1/v1/chat/completions}（404）。
 *
 * <p>本方法识别 base-url 末尾的 {@code /v\d+} 或 {@code /api/.*\/v\d+} 模式；匹配到则调用方
 * 应显式将 completionsPath/embeddingsPath 改为不带 {@code /v1} 前缀的相对路径。
 */
public final class ApiPathResolver {

    /** 以 /vN 结尾的版本段（允许后缀字母，如 v1beta）。*/
    private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

    private ApiPathResolver() {}

    public static boolean baseUrlContainsVersion(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String stripped = baseUrl.trim();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return TRAILING_VERSION.matcher(stripped).find();
    }
}
