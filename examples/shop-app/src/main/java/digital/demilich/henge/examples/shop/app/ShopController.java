package digital.demilich.henge.examples.shop.app;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.ImmutableSet;
import digital.demilich.henge.core.ServiceVersion;
import digital.demilich.henge.examples.shop.inventory.InventoryService;
import digital.demilich.henge.examples.shop.inventory.LineItem;
import digital.demilich.henge.examples.shop.inventory.OutOfStockException;
import digital.demilich.henge.examples.shop.notifications.Notification;
import digital.demilich.henge.examples.shop.notifications.NotificationService;
import digital.demilich.henge.examples.shop.orders.Order;
import digital.demilich.henge.examples.shop.orders.OrderNotFoundException;
import digital.demilich.henge.examples.shop.orders.OrderService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shop's public API: plain Spring MVC, owned by the application. It holds the services' interfaces
 * and never learns where they run. An {@link OutOfStockException} from inventory in another process
 * arrives here as itself, so the handlers below work the same either way.
 */
@RestController
@RequestMapping("/api")
class ShopController {

    private final OrderService orders;
    private final InventoryService inventory;
    private final NotificationService notifications;

    /** Takes inventory version 2 for {@code availability}; orders takes the default, version 1. */
    ShopController(OrderService orders, @ServiceVersion(value = InventoryService.class, version = 2) InventoryService inventory,
            NotificationService notifications) {
        this.orders = orders;
        this.inventory = inventory;
        this.notifications = notifications;
    }

    record PlaceOrder(String customer, List<LineItem> items) {
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Order place(@RequestBody PlaceOrder request) {
        return orders.place(request.customer(), ImmutableList.copyOf(request.items()));
    }

    @GetMapping("/orders/{id}")
    Order get(@PathVariable("id") UUID id) {
        return orders.get(id);
    }

    @PostMapping("/orders/{id}/cancel")
    Order cancel(@PathVariable("id") UUID id) {
        return orders.cancel(id);
    }

    @GetMapping("/stock")
    Map<String, Integer> stock(@RequestParam("sku") List<String> skus) {
        return inventory.availability(ImmutableSet.copyOf(skus));
    }

    @PostMapping("/stock/{sku}")
    Map<String, Integer> restock(@PathVariable("sku") String sku, @RequestParam("quantity") int quantity) {
        inventory.restock(sku, quantity);
        return Map.of(sku, inventory.available(sku));
    }

    @GetMapping("/customers/{customer}/notifications")
    List<Notification> notifications(@PathVariable("customer") String customer) {
        return notifications.sentTo(customer);
    }

    @ExceptionHandler
    ProblemDetail outOfStock(OutOfStockException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail notFound(OrderNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
