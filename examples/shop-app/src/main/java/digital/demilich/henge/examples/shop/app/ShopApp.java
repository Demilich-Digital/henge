package digital.demilich.henge.examples.shop.app;

import digital.demilich.henge.spring.EnableHengeServices;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The shop: orders, inventory and notifications, in one jar. Which of them this process hosts is
 * configuration ({@code henge.serve}); with none, it hosts all three.
 *
 * <p>{@code basePackages} covers the whole shop, since the services and their contracts live in other
 * modules' packages, not under this one.
 */
@SpringBootApplication
@EnableHengeServices(basePackages = "digital.demilich.henge.examples.shop")
public class ShopApp {

    public static void main(String[] args) {
        SpringApplication.run(ShopApp.class, args);
    }
}
