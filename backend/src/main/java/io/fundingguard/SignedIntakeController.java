package io.fundingguard;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SignedIntakeController {
    private final SignedIntake intake;
    public SignedIntakeController(SignedIntake intake) { this.intake = intake; }

    @PostMapping(SignedIntake.PATH)
    public ResponseEntity<?> ingest(HttpServletRequest request) throws IOException {
        try {
            if (request.getQueryString() != null) throw new ApiException(400, "Query parameters are not supported.");
            var result = intake.accept(request.getHeader("X-FG-Key-Id"), request.getHeader("X-FG-Timestamp"),
                request.getHeader("X-FG-Nonce"), request.getHeader("X-FG-Signature"), request.getInputStream().readNBytes(8193));
            org.slf4j.LoggerFactory.getLogger(SignedIntakeController.class).info("signed_intake_committed replayed={}", result.get("replayed"));
            return ResponseEntity.ok(result);
        } catch (ApiException e) { return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage())); }
    }
}
