package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DashscopeLlmService 单元测试
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>基本 LLM 调用（委托 LlmProviderRegistry 并走 voice 专用 ChatClient）</li>
 *   <li>对话历史处理</li>
 *   <li>各类 LLM 错误映射为友好用户提示</li>
 * </ul>
 *
 * <p>由于 ChatClient 链式调用难以完整 mock，测试以"方法不抛出异常"与"正确调用下游服务"为主。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Dashscope LLM 服务测试")
class DashscopeLlmServiceTest {

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private ChatClient chatClient;

    @Mock
    private VoiceInterviewPromptService promptService;

    @Mock
    private ResumeRepository resumeRepository;

    private DashscopeLlmService dashscopeLlmService;

    private VoiceInterviewSessionEntity mockSession;

    @BeforeEach
    void setUp() {
        VoiceInterviewProperties voiceProps = new VoiceInterviewProperties();
        PromptSanitizer sanitizer = new PromptSanitizer(new LlmProviderProperties());
        dashscopeLlmService = new DashscopeLlmService(
            llmProviderRegistry,
            promptService,
            resumeRepository,
            voiceProps,
            sanitizer
        );

        mockSession = VoiceInterviewSessionEntity.builder()
                .id(1L)
                .skillId("ali-p8")
                .llmProvider("dashscope")
                .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                .build();

        lenient().when(promptService.generateSystemPromptWithContext(anyString(), any()))
            .thenReturn("System prompt");
    }

    @Nested
    @DisplayName("基本 LLM 调用测试")
    class BasicChatTests {

        @Test
        @DisplayName("调用 LLM - 验证从 Registry 获取 voice ChatClient")
        void testChat_VerifyVoiceChatClientFetched() {
            String userInput = "请介绍一下你的项目经验";
            mockSession.setSkillId("byteance-algo");
            mockSession.setLlmProvider("dashscope");

            when(promptService.generateSystemPromptWithContext(eq("byteance-algo"), any()))
                .thenReturn("你是字节跳动算法面试官");
            when(llmProviderRegistry.getVoiceChatClient(eq("dashscope"))).thenReturn(chatClient);

            assertDoesNotThrow(() -> {
                String result = dashscopeLlmService.chat(userInput, mockSession, Collections.emptyList());
                assertNotNull(result);
            });

            verify(promptService, times(1)).generateSystemPromptWithContext(eq("byteance-algo"), any());
            verify(llmProviderRegistry, times(1)).getVoiceChatClient(eq("dashscope"));
        }

        @Test
        @DisplayName("调用 LLM - 长输入")
        void testChat_LongInput() {
            StringBuilder longInput = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                longInput.append("这是第").append(i).append("句话。");
            }

            assertDoesNotThrow(() -> {
                String result = dashscopeLlmService.chat(longInput.toString(), mockSession, null);
                assertNotNull(result);
            });
        }
    }

    @Nested
    @DisplayName("对话历史测试")
    class ConversationHistoryTests {

        @Test
        @DisplayName("多轮对话 - 每轮都会构建一次 system prompt")
        void testChat_MultipleTurns() {
            String firstInput = "你好";
            String secondInput = "我有3年Java开发经验";

            assertDoesNotThrow(() -> {
                dashscopeLlmService.chat(firstInput, mockSession, null);
                dashscopeLlmService.chat(secondInput, mockSession, null);
            });

            verify(promptService, times(2)).generateSystemPromptWithContext(anyString(), any());
        }

        @Test
        @DisplayName("不同 skillId - 使用不同 system prompt")
        void testChat_DifferentSkillIds() {
            String userInput = "开始面试";

            when(promptService.generateSystemPromptWithContext(eq("ali-p8"), any()))
                .thenReturn("阿里P8面试官");
            when(promptService.generateSystemPromptWithContext(eq("tencent-backend"), any()))
                .thenReturn("腾讯后端面试官");

            VoiceInterviewSessionEntity aliSession = VoiceInterviewSessionEntity.builder()
                    .id(1L).skillId("ali-p8").build();
            VoiceInterviewSessionEntity tencentSession = VoiceInterviewSessionEntity.builder()
                    .id(2L).skillId("tencent-backend").build();

            assertDoesNotThrow(() -> {
                dashscopeLlmService.chat(userInput, aliSession, null);
                dashscopeLlmService.chat(userInput, tencentSession, null);
            });

            verify(promptService, times(1)).generateSystemPromptWithContext(eq("ali-p8"), any());
            verify(promptService, times(1)).generateSystemPromptWithContext(eq("tencent-backend"), any());
        }
    }

    @Nested
    @DisplayName("错误处理测试")
    class ErrorHandlingTests {

        @Test
        @DisplayName("提示词服务异常 - 返回友好提示")
        void testChat_PromptServiceError() {
            String userInput = "测试";
            when(promptService.generateSystemPromptWithContext(anyString(), any()))
                    .thenThrow(new RuntimeException("提示词加载失败"));

            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            assertNotNull(result);
            assertTrue(result.contains("AI 服务") || result.contains("不可用"),
                    "Error message should be user-friendly");
        }

        @Test
        @DisplayName("ChatClient 认证错误 - 返回特定错误消息")
        void testChat_ChatClientAuthenticationError() {
            String userInput = "测试";
            when(llmProviderRegistry.getVoiceChatClient(anyString()))
                    .thenThrow(new RuntimeException("403 ACCESS_DENIED: Invalid API key"));

            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            assertNotNull(result);
            assertTrue(result.contains("认证失败") || result.contains("API Key"),
                    "Should return authentication error message");
        }

        @Test
        @DisplayName("ChatClient 超时错误 - 返回超时错误消息")
        void testChat_ChatClientTimeoutError() {
            String userInput = "测试";
            when(llmProviderRegistry.getVoiceChatClient(anyString()))
                    .thenThrow(new RuntimeException("Request timeout after 30000ms"));

            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            assertNotNull(result);
            assertTrue(result.contains("超时") || result.contains("timeout"),
                    "Should return timeout error message");
        }

        @Test
        @DisplayName("ChatClient 频率限制错误 - 返回限流错误消息")
        void testChat_ChatClientRateLimitError() {
            String userInput = "测试";
            when(llmProviderRegistry.getVoiceChatClient(anyString()))
                    .thenThrow(new RuntimeException("429 rate limit exceeded"));

            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            assertNotNull(result);
            assertTrue(result.contains("频率") || result.contains("quota") || result.contains("超限"),
                    "Should return rate limit error message");
        }

        @Test
        @DisplayName("ChatClient 网络错误 - 返回网络错误消息")
        void testChat_ChatClientNetworkError() {
            String userInput = "测试";
            when(llmProviderRegistry.getVoiceChatClient(anyString()))
                    .thenThrow(new RuntimeException("connection refused: network error"));

            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            assertNotNull(result);
            assertTrue(result.contains("网络") || result.contains("connection"),
                    "Should return network error message");
        }

        @Test
        @DisplayName("ChatClient 未知错误 - 返回通用错误消息")
        void testChat_ChatClientUnknownError() {
            String userInput = "测试";
            when(llmProviderRegistry.getVoiceChatClient(anyString()))
                    .thenThrow(new RuntimeException("Unknown error occurred"));

            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            assertNotNull(result);
            assertTrue(result.contains("不可用") || result.contains("稍后"),
                    "Should return generic error message for unknown errors");
        }
    }

    @Nested
    @DisplayName("流式调用测试")
    class StreamingTests {

        @Test
        @DisplayName("chatStream - null 回调不应抛异常")
        void testChatStream_NullCallback() {
            String userInput = "测试";

            assertDoesNotThrow(() -> {
                String result = dashscopeLlmService.chatStream(userInput, null, mockSession, null);
                assertNotNull(result);
            });
        }

        @Test
        @DisplayName("chatStream - API 错误处理")
        void testChatStream_ApiError() {
            String userInput = "测试流式错误";
            Consumer<String> onToken = token -> {};
            when(llmProviderRegistry.getVoiceChatClient(anyString()))
                    .thenThrow(new RuntimeException("API 错误"));

            String result = dashscopeLlmService.chatStream(userInput, onToken, mockSession, null);

            assertNotNull(result);
            assertTrue(result.contains("不可用") || result.contains("稍后"),
                    "Should return user-friendly error message");
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("null 会话 - 抛出异常")
        void testChat_NullSession() {
            String userInput = "测试";

            assertThrows(NullPointerException.class, () -> {
                dashscopeLlmService.chat(userInput, null, null);
            });
        }

        @Test
        @DisplayName("会话 skillId 为 null - 仍能构造 prompt")
        void testChat_NullSkillId() {
            String userInput = "测试";
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .id(1L)
                    .skillId(null)
                    .build();

            when(promptService.generateSystemPromptWithContext(eq(null), any())).thenReturn("默认提示词");

            dashscopeLlmService.chat(userInput, session, null);

            verify(promptService, times(1)).generateSystemPromptWithContext(eq(null), any());
        }

        @Test
        @DisplayName("特殊字符输入 - 正常处理")
        void testChat_SpecialCharacters() {
            String specialInput = "你好！@#$%^&*()_+";

            assertDoesNotThrow(() -> {
                String result = dashscopeLlmService.chat(specialInput, mockSession, null);
                assertNotNull(result);
            });
        }
    }
}
