package digital.demilich.henge.spring;

import digital.demilich.henge.core.HengeScheduled;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.lang.reflect.Method;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.ReflectionUtils;

/**
 * Finds {@link HengeScheduled} methods on the application's singleton beans and hands them to a
 * {@link ClusterScheduler} once the context has refreshed. A method that can't be a job (it takes
 * arguments, its cron expression doesn't parse, two jobs share a name, its bean isn't a singleton) fails
 * startup, naming it.
 *
 * <p>Like Spring's own scheduling post-processor it needs nothing when it is created, and looks up the
 * datastore only at the refresh: asking for it as a constructor argument would build the datastore
 * before {@link HengeDatastoreInstaller} has had the chance to wrap it.
 */
class HengeScheduledJobs implements BeanPostProcessor, BeanFactoryAware, EnvironmentAware, ApplicationContextAware,
        ApplicationListener<ContextRefreshedEvent>, DisposableBean {

    private static final Log log = LogFactory.getLog(HengeScheduledJobs.class);

    private final Map<String, ClusterScheduler.Job> jobs = new LinkedHashMap<>();
    private BeanFactory beanFactory;
    private Environment environment;
    private ApplicationContext applicationContext;
    private ClusterScheduler scheduler;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        Map<Method, HengeScheduled> annotated = MethodIntrospector.selectMethods(targetClass,
                (MethodIntrospector.MetadataLookup<HengeScheduled>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, HengeScheduled.class));
        annotated.forEach((method, annotation) -> register(bean, beanName, targetClass, method, annotation));
        return bean;
    }

    private void register(Object bean, String beanName, Class<?> targetClass, Method method, HengeScheduled annotation) {
        String where = targetClass.getName() + "#" + method.getName();
        if (method.getParameterCount() != 0) {
            throw new IllegalStateException("@HengeScheduled method " + where + " takes arguments; a job is called with none");
        }
        if (beanFactory instanceof ConfigurableListableBeanFactory configurable && !configurable.isSingleton(beanName)) {
            throw new IllegalStateException("@HengeScheduled method " + where + " is on bean '" + beanName
                    + "', which is not a singleton; a job runs on one instance of its bean");
        }
        String configuredName = environment.resolvePlaceholders(annotation.name());
        String name = configuredName.isBlank() ? where : configuredName.trim();
        CronExpression cron;
        ZoneId zone;
        try {
            cron = CronExpression.parse(environment.resolveRequiredPlaceholders(annotation.cron()));
            String configuredZone = environment.resolveRequiredPlaceholders(annotation.zone());
            zone = configuredZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(configuredZone.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("@HengeScheduled method " + where + " has a cron or zone that can't be read: "
                    + e.getMessage(), e);
        }
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        ReflectionUtils.makeAccessible(invocable);
        Runnable body = () -> ReflectionUtils.invokeMethod(invocable, bean);
        if (jobs.put(name, new ClusterScheduler.Job(name, cron, zone, body)) != null) {
            throw new IllegalStateException("Two @HengeScheduled jobs are named '" + name + "' (" + where + " is one); "
                    + "give each its own name in the annotation");
        }
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext() != applicationContext || scheduler != null || jobs.isEmpty()) {
            return;
        }
        scheduler = new ClusterScheduler(applicationContext.getBean(SystemEphemeralDatastore.class), InstantSource.system());
        jobs.values().forEach(job -> {
            scheduler.add(job);
            log.info("Henge scheduled job '" + job.name() + "' runs once per fire across the cluster: " + job.cron()
                    + " in " + job.zone() + ". It must be safe to run twice, and a fire nobody was up for is skipped.");
        });
        scheduler.start();
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.close();
        }
    }
}
