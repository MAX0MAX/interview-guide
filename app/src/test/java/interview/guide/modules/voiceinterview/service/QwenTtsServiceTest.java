package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QwenTtsService Unit Tests")
class QwenTtsServiceTest {

    private QwenTtsService ttsService;

    @BeforeEach
    void setUp() {
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        VoiceInterviewProperties.QwenTtsConfig tts = properties.getQwen().getTts();
        tts.setModel("qwen3-tts-flash-realtime");
        tts.setApiKey("test-api-key");
        tts.setVoice("Cherry");
        tts.setFormat("pcm");
        tts.setSampleRate(24000);
        tts.setMode("server_commit");
        tts.setLanguageType("Chinese");
        tts.setSpeechRate(1.0f);
        tts.setVolume(60);

        ttsService = new QwenTtsService(properties);
    }

    @Test
    @DisplayName("Should initialize service successfully")
    void testInit() {
        assertDoesNotThrow(() -> ttsService.init());
    }

    @Test
    @DisplayName("Should return empty array for empty text")
    void testSynthesizeEmptyText() {
        ttsService.init();

        byte[] result = ttsService.synthesize("");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for null text")
    void testSynthesizeNullText() {
        ttsService.init();

        byte[] result = ttsService.synthesize(null);

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for whitespace text")
    void testSynthesizeWhitespaceText() {
        ttsService.init();

        byte[] result = ttsService.synthesize("   ");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should cleanup resources on destroy")
    void testDestroy() {
        ttsService.init();

        assertDoesNotThrow(() -> ttsService.destroy());
    }

    @Test
    @DisplayName("reload() updates config from new properties")
    void testReloadUpdatesConfig() {
        ttsService.init();

        VoiceInterviewProperties newProps = new VoiceInterviewProperties();
        VoiceInterviewProperties.QwenTtsConfig newTts = newProps.getQwen().getTts();
        newTts.setModel("qwen3-tts-flash-realtime");
        newTts.setApiKey("updated-key");
        newTts.setVoice("Serena");
        newTts.setFormat("pcm");
        newTts.setSampleRate(24000);
        newTts.setMode("commit");
        newTts.setLanguageType("Chinese");
        newTts.setSpeechRate(1.0f);
        newTts.setVolume(70);

        assertDoesNotThrow(() -> ttsService.reload(newProps));
    }
}
