package online.bookanear.api.service;

import online.bookanear.api.dto.HelpChatDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class AiHelpConversationRepository {
    private final JdbcTemplate jdbc;

    public AiHelpConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<HelpChatDto.Message> history(long userId) {
        return jdbc.query("""
                SELECT role, content FROM (
                    SELECT id, role, content FROM ai_help_messages
                    WHERE user_id = ? ORDER BY id DESC LIMIT 200
                ) recent_messages ORDER BY id
                """, (rs, rowNum) -> new HelpChatDto.Message(rs.getString("role"), rs.getString("content")), userId);
    }

    @Transactional
    public void saveExchange(long userId, String userMessage, String assistantMessage) {
        jdbc.update("INSERT INTO ai_help_messages (user_id, role, content) VALUES (?, 'user', ?)",
                userId, userMessage);
        jdbc.update("INSERT INTO ai_help_messages (user_id, role, content) VALUES (?, 'assistant', ?)",
                userId, assistantMessage);
        jdbc.update("""
                DELETE FROM ai_help_messages
                WHERE user_id = ? AND id NOT IN (
                    SELECT id FROM ai_help_messages WHERE user_id = ?
                    ORDER BY id DESC LIMIT 200
                )
                """, userId, userId);
    }

    @Transactional
    public void clearHistory(long userId) {
        jdbc.update("DELETE FROM ai_help_messages WHERE user_id = ?", userId);
    }
}
