package digital.demilich.henge.examples.plainspring;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.examples.shop.inventory.InventoryService;
import digital.demilich.henge.examples.shop.inventory.LineItem;
import digital.demilich.henge.examples.shop.notifications.NotificationService;
import digital.demilich.henge.examples.shop.orders.Order;
import digital.demilich.henge.examples.shop.orders.OrderService;
import digital.demilich.henge.spring.EnableHengeServices;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * The shop's services under plain Spring Framework: no {@code SpringApplication}, no
 * {@code henge-spring-boot-starter}. {@code @EnableHengeServices} alone wires the services, their lease
 * and their rate limit, all embedded. Configuration comes from a {@code @PropertySource}, since there's
 * no Boot to read {@code application.yml}. This serves no HTTP; for a Boot-free process that does, see
 * {@code henge-spring}'s {@code HengeDispatchPlainSpringTest}.
 */
public class ExamplePlainSpringApp {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class)) {
            OrderService orders = context.getBean(OrderService.class);
            InventoryService inventory = context.getBean(InventoryService.class);
            NotificationService notifications = context.getBean(NotificationService.class);

            System.out.println("Rope available: " + inventory.available("rope"));
            Order order = orders.place("ada", ImmutableList.of(new LineItem("rope", 3)));
            System.out.println("Placed " + order.id() + "; rope available: " + inventory.available("rope"));
            orders.cancel(order.id());
            System.out.println("Cancelled it; rope available: " + inventory.available("rope"));
            System.out.println("Sent to ada: " + notifications.sentTo("ada").size() + " messages");
        }
    }

    @Configuration
    @PropertySource("classpath:shop.properties")
    @EnableHengeServices(basePackages = "digital.demilich.henge.examples.shop")
    static class AppConfig {
    }
}
