package digital.demilich.henge.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves this process's topology, when {@code henge.topology.enabled} is set:
 * {@code GET {pathPrefix}/topology} is the JSON of {@link HengeTopologyReport}, and
 * {@code GET {pathPrefix}/topology/ui} is a page that draws it.
 *
 * <p>The same trust model as the dispatcher: network isolation, plus {@code henge.transport.secret}
 * where that is set. The JSON then requires the {@code Henge-Internal-Secret} header. The page does
 * not, since it carries no data of its own: it asks for the secret and sends it with its own requests.
 */
@RestController
@RequestMapping("${henge.server.path-prefix:/_henge}")
class HengeTopologyController {

    private static final String PAGE = "/digital/demilich/henge/spring/topology.html";

    /** Everything the page needs is in the page itself; nothing is loaded from anywhere else. */
    private static final String PAGE_POLICY = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final HengeTopologyReport report;
    private final ObjectMapper objectMapper;
    private final SharedSecret secret;
    private final byte[] page;

    HengeTopologyController(HengeTopologyReport report, ObjectMapper objectMapper, HengeProperties properties) {
        this.report = report;
        this.objectMapper = objectMapper;
        this.secret = SharedSecret.from(properties);
        properties.getServerPathPrefix(); // validates it; the @RequestMapping above reads the same property
        try (InputStream in = new ClassPathResource(PAGE).getInputStream()) {
            this.page = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("The topology page is missing from the jar: " + PAGE, e);
        }
    }

    @GetMapping("/topology")
    public ResponseEntity<byte[]> topology(@RequestHeader(value = HengeDispatcherController.SECRET_HEADER, required = false) String providedSecret)
            throws IOException {
        if (!secret.accepts(providedSecret)) {
            byte[] body = objectMapper.writeValueAsBytes(java.util.Map.of("error", "Missing or invalid "
                    + HengeDispatcherController.SECRET_HEADER + " header"));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(objectMapper.writeValueAsBytes(report.report()));
    }

    @GetMapping("/topology/ui")
    public ResponseEntity<byte[]> ui() {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", PAGE_POLICY)
                .header("X-Content-Type-Options", "nosniff")
                .body(page);
    }
}
