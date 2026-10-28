package online.bookanear.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.bookanear.api.dto.HelpChatDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiHelpServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reportsAssistantDisabledWhenNoApiKeyIsConfigured() {
        var service = new AiHelpService("", "gpt-4o-mini", "https://api.openai.com/v1", objectMapper,
                mock(AiHelpConversationRepository.class));

        assertThat(service.status().enabled()).isFalse();
    }

    @Test
    void rejectsChatWhenNoApiKeyIsConfigured() {
        var service = new AiHelpService("", "gpt-4o-mini", "https://api.openai.com/v1", objectMapper,
                mock(AiHelpConversationRepository.class));

        assertThatThrownBy(() -> service.reply(new HelpChatDto.Request(List.of(
                new HelpChatDto.Message("user", "How do I add a service?")
        )), 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void requiresTheLatestChatMessageToComeFromTheUser() {
        var service = new AiHelpService("configured-key", "gpt-4o-mini", "https://api.openai.com/v1", objectMapper,
                mock(AiHelpConversationRepository.class));

        assertThatThrownBy(() -> service.reply(new HelpChatDto.Request(List.of(
                new HelpChatDto.Message("assistant", "Hello")
        )), 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void loadsVerifiedProductGuideFromBuildResources() {
        assertThat(AiHelpService.loadProductGuide())
                .contains("Uložit všechny")
                .contains("Asistent neumí zjistit")
                .contains("Asistent poskytuje pouze návod");
    }
}
