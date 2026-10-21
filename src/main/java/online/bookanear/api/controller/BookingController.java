package online.bookanear.api.controller;

import online.bookanear.api.dto.BookingDto;
import online.bookanear.api.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "04 - Bookings", description = "Public reservation creation and token-based management.")
public class BookingController {
    private final BookingService service;

    BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a reservation", description = "Book a service at a location with a selected worker and start time. Availability and booking rules are validated.")
    public Object create(@Valid @RequestBody BookingDto.CreateBooking request) {
        return service.create(request);
    }

    @PostMapping("/bookings/universal")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a resource-based reservation",
            description = "Automatically assigns staff, a table or equipment according to configured service requirements.")
    public Object createUniversal(@Valid @RequestBody BookingDto.CreateUniversalBooking request) {
        return service.createUniversal(request);
    }

    @GetMapping("/public/reservations/{token}")
    @Operation(summary = "Get reservation by token", description = "Return reservation details using the opaque public reservation token.")
    public Object get(@Parameter(description = "Opaque public reservation management token.") @PathVariable String token) {
        return service.getPublic(token);
    }

    @PostMapping("/public/reservations/{token}/cancel")
    @Operation(summary = "Cancel reservation by token", description = "Cancel an existing reservation using its public management token, subject to cancellation rules.")
    public Object cancel(@Parameter(description = "Opaque public reservation management token.") @PathVariable String token) {
        return service.cancel(token);
    }

    @PostMapping("/public/reservations/{token}/reschedule")
    @Operation(summary = "Reschedule reservation by token", description = "Move an existing reservation to a new start time using its public management token.")
    public Object reschedule(@Parameter(description = "Opaque public reservation management token.") @PathVariable String token, @Valid @RequestBody BookingDto.Reschedule request) {
        return service.reschedule(token, request);
    }
}
