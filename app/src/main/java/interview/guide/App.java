package interview.guide;

import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Interview Platform - Main Application
 * 智能AI面试官平台 - 主启动类
 */
@EnableScheduling
@SpringBootApplication(exclude = {
    OpenAiChatAutoConfiguration.class,
    OpenAiEmbeddingAutoConfiguration.class
})
public class App {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
