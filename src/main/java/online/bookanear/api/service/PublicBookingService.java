package online.bookanear.api.service;

import online.bookanear.api.dto.PublicBookingDto;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class PublicBookingService {
    private final NamedParameterJdbcTemplate db;
    public PublicBookingService(NamedParameterJdbcTemplate db) { this.db=db; }


    public PublicBookingDto.PublicOrganization getOrganization(
            UUID publicId) {

        List<PublicBookingDto.PublicOrganization> result = db.query("""
                        SELECT public_id, name
                        FROM organizations
                        WHERE public_id = :publicId
                          AND registration_status = 'active'
                        """,
                Map.of("publicId", publicId),
                (rs, rowNum) -> new PublicBookingDto.PublicOrganization(
                        rs.getObject("public_id", UUID.class),
                        rs.getString("name")
                )
        );

        if (result.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Organization not found"
            );
        }

        return result.get(0);
    }
}
