package digital.demilich.henge.spring;

import digital.demilich.henge.core.HengeAcknowledgeThisOpensAPoolOnEveryNode;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Refuses a {@link DataSource} that Spring manages, which is what Spring Boot's {@code spring.datasource.*}
 * auto-configuration builds. A bean's pool opens on every process that runs the application, and nothing
 * Henge does can stop that: a lease keeps a pool from being built on a process that isn't granted it, but
 * a pool that is an ordinary bean already exists, and JPA, Flyway and schema initialization connect to it
 * as the context starts, before any lease could be asked for. So the database sees pools times processes
 * connections, which is the very thing a lease is there to cap.
 *
 * <p>It runs as a {@link BeanFactoryPostProcessor}, once every bean definition exists, and looks at their
 * types without creating any of them. Saying that every process opening its own pool is what is wanted,
 * with {@link HengeAcknowledgeThisOpensAPoolOnEveryNode}, is the way past it, as
 * {@link digital.demilich.henge.core.HengeAcknowledgeThisRunsOnEveryNode} is for {@code @Scheduled}.
 */
class HengeDataSourceGuard implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // Not eager: this must not create a FactoryBean, let alone a pool, to learn what it is.
        String[] names = beanFactory.getBeanNamesForType(DataSource.class, true, false);
        if (names.length == 0) {
            return;
        }
        // On the application's class, it covers the pool Boot builds, which has no method of the author's to carry it.
        if (beanFactory.getBeanNamesForAnnotation(HengeAcknowledgeThisOpensAPoolOnEveryNode.class).length > 0) {
            return;
        }
        List<String> found = new ArrayList<>();
        for (String name : names) {
            found.add("'" + name + "'" + origin(beanFactory.getMergedBeanDefinition(name)));
        }
        throw new IllegalStateException("This application has a DataSource bean, " + String.join(", ", found)
                + " (Spring Boot builds one from spring.datasource.*), and a bean's pool is opened on every process "
                + "that runs the application, before a lease could be asked for: JPA, Flyway and schema "
                + "initialization connect as the context starts. The database would see pools times processes "
                + "connections, which is what a lease exists to cap. Take the DataSource out of Spring's hands: "
                + "exclude DataSourceAutoConfiguration (spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration), build the pool in a "
                + "@LeasedResource ResourceProvider<DataSource> sized from lease.amount(), and take it with "
                + "@RequiresLease(\"name\") in the service's constructor. If every process opening its own pool is "
                + "what you want (an embedded database, or one that takes any number of connections), say so with "
                + "@HengeAcknowledgeThisOpensAPoolOnEveryNode on your @Bean method, or on your application class "
                + "for the one Boot builds.");
    }

    private static String origin(BeanDefinition definition) {
        String where = definition.getFactoryBeanName() != null ? definition.getFactoryBeanName()
                : definition.getBeanClassName() != null ? definition.getBeanClassName()
                : definition.getResourceDescription();
        return where == null ? "" : " (from " + where + ")";
    }
}
