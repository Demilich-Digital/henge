package digital.demilich.henge.spring;

import digital.demilich.henge.core.HengeAcknowledgeThisRunsOnEveryNode;
import digital.demilich.henge.core.HengeScheduled;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.ReflectionUtils;

/**
 * Finds {@link HengeScheduled} methods on the application's singleton beans and hands them to a
 * {@link ClusterScheduler} once the context has refreshed. It also refuses Spring's own {@code @Scheduled}
 * unless the author has acknowledged that it runs on every node. A method that can't be a job (it takes
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
        refuseUnacknowledgedPlainScheduling(bean, beanName, targetClass);
        Map<Method, HengeScheduled> annotated = MethodIntrospector.selectMethods(targetClass,
                (MethodIntrospector.MetadataLookup<HengeScheduled>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, HengeScheduled.class));
        annotated.forEach((method, annotation) -> register(bean, beanName, targetClass, method, annotation));
        return bean;
    }

    /**
     * Spring's own scheduling runs once per process, so in a cluster a job runs once per node, silently. It
     * is refused unless the author has acknowledged that with {@link HengeAcknowledgeThisRunsOnEveryNode}, on
     * the method or its class; a {@link SchedulingConfigurer}, which registers tasks in code where no
     * annotation shows, needs it on the configurer. Spring's own classes are left alone: they schedule what
     * they need, and the author can't annotate them.
     */
    private static void refuseUnacknowledgedPlainScheduling(Object bean, String beanName, Class<?> targetClass) {
        if (targetClass.getName().startsWith("org.springframework.")
                || AnnotatedElementUtils.hasAnnotation(targetClass, HengeAcknowledgeThisRunsOnEveryNode.class)) {
            return;
        }
        List<String> offenders = new ArrayList<>();
        MethodIntrospector.selectMethods(targetClass, (MethodIntrospector.MetadataLookup<Boolean>) method ->
                !AnnotatedElementUtils.findMergedRepeatableAnnotations(method, Scheduled.class, Schedules.class).isEmpty()
                        && !AnnotatedElementUtils.hasAnnotation(method, HengeAcknowledgeThisRunsOnEveryNode.class) ? Boolean.TRUE : null)
                .keySet().forEach(method -> offenders.add("@Scheduled method " + targetClass.getName() + "#" + method.getName()));
        if (bean instanceof SchedulingConfigurer) {
            offenders.add("SchedulingConfigurer " + targetClass.getName());
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(String.join(" and ", offenders) + " (bean '" + beanName + "') would run on every node "
                    + "that hosts it, once each: Spring's scheduling is per process, so three replicas run it three times. "
                    + "To run it once across the cluster, use @HengeScheduled(cron = \"...\") instead. If running on every "
                    + "node is what you want, say so by adding @HengeAcknowledgeThisRunsOnEveryNode to it.");
        }
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
        Duration maxRuntime = maxRuntime(where, annotation);
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        ReflectionUtils.makeAccessible(invocable);
        Runnable body = () -> ReflectionUtils.invokeMethod(invocable, bean);
        if (jobs.put(name, new ClusterScheduler.Job(name, cron, zone, body, maxRuntime, annotation.overlap())) != null) {
            throw new IllegalStateException("Two @HengeScheduled jobs are named '" + name + "' (" + where + " is one); "
                    + "give each its own name in the annotation");
        }
    }

    /** The job's {@code maxRuntime}, an hour if it sets none. It must be positive: a run that may take no time is never run. */
    private Duration maxRuntime(String where, HengeScheduled annotation) {
        String raw = environment.resolvePlaceholders(annotation.maxRuntime()).trim();
        if (raw.isEmpty()) {
            raw = HengeScheduled.DEFAULT_MAX_RUNTIME;
        }
        Duration maxRuntime;
        try {
            maxRuntime = DurationFormatterUtils.detectAndParse(raw, DurationFormat.Unit.MILLIS);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("@HengeScheduled method " + where + " has maxRuntime=" + raw + ", which is not a "
                    + "duration; use milliseconds (60000), a unit suffix (90m, 2h) or ISO-8601 (PT2H)", e);
        }
        if (maxRuntime.isZero() || maxRuntime.isNegative()) {
            throw new IllegalStateException("@HengeScheduled method " + where + " has maxRuntime=" + raw + ", which must be positive");
        }
        return maxRuntime;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext() != applicationContext || scheduler != null || jobs.isEmpty()) {
            return;
        }
        SystemMetrics metrics = applicationContext.getBeanProvider(SystemMetrics.class).getIfAvailable(() -> SystemMetrics.NONE);
        scheduler = new ClusterScheduler(metrics.measured(applicationContext.getBean(SystemEphemeralDatastore.class), "scheduler"),
                InstantSource.system(), ClusterScheduler.DEFAULT_RUN_TTL, metrics);
        Duration defaultMaxRuntime = DurationFormatterUtils.detectAndParse(HengeScheduled.DEFAULT_MAX_RUNTIME, DurationFormat.Unit.MILLIS);
        jobs.values().forEach(job -> {
            scheduler.add(job);
            log.info("Henge scheduled job '" + job.name() + "' runs once per fire across the cluster: " + job.cron()
                    + " in " + job.zone() + ", stopped after " + job.maxRuntime() + (job.overlap() ? ", overlapping allowed" : "")
                    + ". It must be safe to run twice, and a fire nobody was up for is skipped.");
            if (job.maxRuntime().compareTo(defaultMaxRuntime) > 0) {
                log.warn("Henge scheduled job '" + job.name() + "' may run for " + job.maxRuntime() + ", longer than the default "
                        + defaultMaxRuntime + ". A run lives on one node and is lost with it: if that node dies, nothing resumes "
                        + "it, and the next fire starts again from nothing. Henge has no durable store to resume from, so a long "
                        + "job has to be written as batches, recording its own progress in your database and safe to repeat.");
            }
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
