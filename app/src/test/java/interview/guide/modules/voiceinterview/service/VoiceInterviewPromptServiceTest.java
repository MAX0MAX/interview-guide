package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语音面试 Prompt 服务单元测试。
 *
 * <p>当前实现下，Prompt 服务不再在内存中缓存多套 RolePrompt，角色设定由
 * SkillsTool 在运行时按 skillId 拉取。测试仅覆盖公共 API
 * {@link VoiceInterviewPromptService#generateSystemPromptWithContext(String, String)}。
 */
@DisplayName("语音面试 Prompt 服务测试")
class VoiceInterviewPromptServiceTest {

    private VoiceInterviewPromptService promptService;

    @BeforeEach
    void setUp() {
        promptService = new VoiceInterviewPromptService(new PromptSanitizer(new LlmProviderProperties()));
    }

    @Test
    @DisplayName("普通 skillId + 无简历：输出 Skill 提示 + 语音约束 + 注入防护")
    void generatesPromptForSkillOnly() {
        String prompt = promptService.generateSystemPromptWithContext("ali-p8", null);

        assertNotNull(prompt);
        assertTrue(prompt.contains("ali-p8"), "应包含 skillId");
        assertTrue(prompt.contains("Skill 工具"), "应包含 SkillsTool 使用提示");
        assertTrue(prompt.contains("语音面试输出约束"), "应包含语音面试输出约束");
        assertFalse(prompt.contains("【简历解析文本】"), "无简历时不应渲染简历块");
    }

    @Test
    @DisplayName("skillId 为 null：跳过 Skill 提示，仅输出约束与防注入")
    void skipsSkillInstructionWhenSkillIdNull() {
        String prompt = promptService.generateSystemPromptWithContext(null, null);

        assertNotNull(prompt);
        assertFalse(prompt.contains("Skill 工具"), "skillId 为空时不应注入 Skill 提示");
        assertTrue(prompt.contains("语音面试输出约束"));
    }

    @Test
    @DisplayName("skillId 为空白串：跳过 Skill 提示")
    void skipsSkillInstructionWhenSkillIdBlank() {
        String prompt = promptService.generateSystemPromptWithContext("   ", null);

        assertFalse(prompt.contains("Skill 工具"));
    }

    @Test
    @DisplayName("提供简历：输出中包含简历块与解析文本（经过 Sanitizer）")
    void includesResumeSection() {
        String resume = "后端工程师，5 年 Java 经验，熟悉 Spring Boot。";
        String prompt = promptService.generateSystemPromptWithContext("ali-p8", resume);

        assertTrue(prompt.contains("【实时语音面试 - 候选人简历内容】"));
        assertTrue(prompt.contains("【简历解析文本】"));
        assertTrue(prompt.contains("Spring Boot"), "简历正文应出现在最终 prompt 中");
    }

    @Test
    @DisplayName("简历为空字符串：不渲染简历块")
    void skipsResumeSectionWhenEmpty() {
        String prompt = promptService.generateSystemPromptWithContext("ali-p8", "");

        assertFalse(prompt.contains("【简历解析文本】"));
    }
}
