package digital.demilich.henge.spring.fixture.echo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** An ordinary application endpoint, used to prove Spring Security still protects everything but /_modular. */
@RestController
public class ProtectedController {

    @GetMapping("/open")
    public String open() {
        return "anyone";
    }

    @GetMapping("/protected")
    public String hello() {
        return "secret stuff";
    }
}
