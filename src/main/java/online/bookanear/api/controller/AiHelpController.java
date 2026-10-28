package online.bookanear.api.controller;

import jakarta.validation.Valid;
import online.bookanear.api.config.OrganizationSecurity;
import online.bookanear.api.dto.HelpChatDto;
import online.bookanear.api.service.AiHelpService;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/help")
public class AiHelpController {
    private final AiHelpService service;
    private final OrganizationSecurity security;

    public AiHelpController(AiHelpService service, OrganizationSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping("/status")
    public HelpChatDto.Status status() {
        return service.status();
    }

    @GetMapping("/history")
    public java.util.List<HelpChatDto.Message> history(Authentication authentication) {
        return service.history(userId(authentication));
    }

    @DeleteMapping("/history")
    public void clearHistory(Authentication authentication) {
        service.clearHistory(userId(authentication));
    }

    @PostMapping("/chat")
    public HelpChatDto.Reply chat(@Valid @RequestBody HelpChatDto.Request request,
                                  Authentication authentication) {
        return service.reply(request, userId(authentication));
    }

    private long userId(Authentication authentication) {
        Long userId = security.authenticatedUserId(authentication);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return userId;
    }
}
