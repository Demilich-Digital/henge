package digital.demilich.henge.spring;

/**
 * Watches calls arriving over {@code /_henge}, from start to answer, rejected ones included: the
 * serving side of what a {@link ServiceCallInterceptor} sees on the calling side. The dispatcher knows
 * only this interface, so it loads without Micrometer on the classpath.
 */
interface ServiceDispatchObserver {

    /** Observes nothing. */
    ServiceDispatchObserver NONE = () -> new Scope() {
        @Override
        public void resolved(String service, int version, String method) {
        }

        @Override
        public void stop(int status, String exceptionType) {
        }
    };

    /** A request has arrived. */
    Scope start();

    /** One request. */
    interface Scope {

        /**
         * The request names a service version and method this process serves. Not called for one that
         * doesn't, since those names are the caller's, and unbounded.
         */
        void resolved(String service, int version, String method);

        /**
         * The request is answered with {@code status}; {@code exceptionType} is the class of the business
         * exception the method threw, or {@code null} if none did.
         */
        void stop(int status, String exceptionType);
    }
}
