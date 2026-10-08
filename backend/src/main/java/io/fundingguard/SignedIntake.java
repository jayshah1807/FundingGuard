package io.fundingguard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Machine credentials are scoped to an existing active Security user, never a client-supplied tenant. */
@Service
public class SignedIntake {
    public static final String PATH = "/api/integrations/simulator/events";
    public record Key(String secret, String email) {}
    private final Map<String, Key> keys;
    private final WorkflowService workflow;
    private final AutomationEngine engine;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public SignedIntake(@Value("${fundingguard.ingest.keys:{}}") String configuration,
            WorkflowService workflow, AutomationEngine engine, JdbcTemplate db, ObjectMapper json) {
        this.workflow = workflow; this.engine = engine; this.db = db; this.json = json;
        try {
            keys = Map.copyOf(json.readValue(configuration, new TypeReference<Map<String, Key>>() {}));
            for (var entry : keys.entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}") || entry.getValue().secret() == null
                    || entry.getValue().secret().getBytes(StandardCharsets.UTF_8).length < 32
                    || entry.getValue().email() == null) throw new IllegalArgumentException();
            }
        } catch (Exception e) { throw new IllegalArgumentException("Invalid intake key configuration; use key IDs with secrets of at least 32 bytes and user emails."); }
    }

    static byte[] signature(String secret, String keyId, String timestamp, String nonce, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v1\nPOST\n" + PATH + "\n" + keyId + "\n" + timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (Exception e) { throw new IllegalStateException("HMAC unavailable", e); }
    }

    private ApiException reject(int status, String code) {
        // Fixed codes only: no request body, signatures, keys or attacker-supplied strings in logs.
        LoggerFactory.getLogger(SignedIntake.class).warn("signed_intake_rejected code={}", code);
        return new ApiException(status, "Signed intake rejected: " + code);
    }

    @Transactional
    public Map<String, Object> accept(String keyId, String timestamp, String nonce, String supplied, byte[] body) {
        if (body.length > 8192) throw reject(413, "BODY_TOO_LARGE");
        if (keys.isEmpty()) throw reject(503, "DISABLED");
        if (keyId == null || timestamp == null || nonce == null || supplied == null
            || !timestamp.matches("[0-9]{10}") || !nonce.matches("[A-Za-z0-9_-]{16,100}")
            || !supplied.matches("[a-fA-F0-9]{64}")) throw reject(401, "AUTHENTICATION");
        var key = keys.get(keyId);
        long now = Instant.now().getEpochSecond(), at = Long.parseLong(timestamp);
        if (key == null || at < now - 300 || at > now + 30
            || !MessageDigest.isEqual(signature(key.secret(), keyId, timestamp, nonce, body), HexFormat.of().parseHex(supplied)))
            throw reject(401, "AUTHENTICATION");
        WorkflowService.Actor actor;
        try { actor = workflow.actor(key.email()); }
        catch (ApiException e) { throw reject(401, "AUTHENTICATION"); }
        if (!"SECURITY".equals(actor.role())) throw reject(403, "ROLE");
        AutomationEngine.Signal signal;
        try { signal = json.readValue(body, AutomationEngine.Signal.class); }
        catch (Exception e) { throw reject(400, "INVALID_JSON"); }
        if (signal == null) throw reject(400, "INVALID_JSON");
        // Serializes accepted requests with payout mutations. Receipt and response commit atomically.
        db.queryForObject("select id from fg_tenants where id=? for update", String.class, actor.tenant());
        db.update("delete from fg_ingest_receipts where received_at < now() - interval '1 day'");
        if (db.update("insert into fg_ingest_receipts(key_id,nonce) values(?,?) on conflict do nothing", keyId, nonce) == 0)
            throw reject(409, "REPLAY");
        var result = engine.ingestSigned(actor, signal);
        return result;
    }
}
